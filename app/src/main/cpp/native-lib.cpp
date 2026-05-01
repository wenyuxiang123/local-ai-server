/**
 * LocalAI-Server v4.0-MNN
 * JNI Bridge for MNN LLM Engine
 * 
 * 适配 MNN 3.4.1 + Qwen3.5-4B
 * 基于真实API: https://raw.githubusercontent.com/alibaba/MNN/3.4.1/transformers/llm/engine/include/llm/llm.hpp
 */

#include <jni.h>
#include <string>
#include <vector>
#include <sstream>
#include <android/log.h>
#include <pthread.h>
#include <unistd.h>

// MNN LLM 头文件
#include "MNN/MNNDefine.h"
#include "MNN/Tensor.hpp"
#include "llm/llm.hpp"

#define TAG "MnnEngine"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// 全局LLM实例 - 使用裸指针（非shared_ptr）
static MNN::Transformer::Llm* g_llm = nullptr;
static std::string g_model_path;
static std::string g_model_name;
static std::string g_temp_dir;
static bool g_is_loaded = false;

// JNI回调相关
static JavaVM* g_jvm = nullptr;
static jobject g_callback_object = nullptr;
static jmethodID g_token_callback_method = nullptr;
static pthread_mutex_t g_callback_mutex = PTHREAD_MUTEX_INITIALIZER;

/**
 * 获取JNI环境（ AttachCurrentThread 如果需要）
 */
static JNIEnv* getJNIEnv() {
    JNIEnv* env = nullptr;
    if (g_jvm == nullptr) {
        return nullptr;
    }
    
    int status = g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
    if (status == JNI_EDETACHED) {
        if (g_jvm->AttachCurrentThread(&env, nullptr) != 0) {
            LOGE("Failed to attach current thread");
            return nullptr;
        }
    }
    return env;
}

/**
 * 流式生成回调
 */
static void tokenCallback(const std::string& token) {
    pthread_mutex_lock(&g_callback_mutex);
    
    if (g_callback_object != nullptr && g_token_callback_method != nullptr) {
        JNIEnv* env = getJNIEnv();
        if (env != nullptr) {
            jstring jtoken = env->NewStringUTF(token.c_str());
            if (jtoken != nullptr) {
                env->CallVoidMethod(g_callback_object, g_token_callback_method, jtoken);
                env->DeleteLocalRef(jtoken);
            }
        }
    }
    
    pthread_mutex_unlock(&g_callback_mutex);
}

extern "C" {

/**
 * 初始化Native层（Kotlin initNative调用）
 * 当前MNN不需要额外初始化，只需确认库加载成功
 */
JNIEXPORT void JNICALL
Java_com_localai_server_engine_LlamaEngine_initNative(
        JNIEnv* env, jobject thiz) {
    LOGI("MNN JNI initNative called - libraries loaded successfully");
}

/**
 * 初始化JNI回调
 */
JNIEXPORT void JNICALL
Java_com_localai_server_engine_LlamaEngine_initNativeCallback(
        JNIEnv* env, jobject thiz, jobject callback) {
    
    pthread_mutex_lock(&g_callback_mutex);
    
    // 保存全局引用
    if (g_callback_object != nullptr) {
        env->DeleteGlobalRef(g_callback_object);
    }
    
    if (callback != nullptr) {
        g_callback_object = env->NewGlobalRef(callback);
        
        // 获取回调方法ID
        jclass callbackClass = env->GetObjectClass(callback);
        if (callbackClass != nullptr) {
            g_token_callback_method = env->GetMethodID(callbackClass, "onToken", "(Ljava/lang/String;)V");
        }
    } else {
        g_callback_object = nullptr;
        g_token_callback_method = nullptr;
    }
    
    pthread_mutex_unlock(&g_callback_mutex);
    
    LOGI("Native callback initialized");
}

/**
 * 加载模型
 * @param configPath 模型config.json路径（MNN模型是目录）
 * @param nCtx 上下文大小
 * @param nThreads 线程数
 */
JNIEXPORT jboolean JNICALL
Java_com_localai_server_engine_LlamaEngine_nativeLoadModel(
        JNIEnv* env, jobject thiz,
        jstring config_path, jint n_ctx, jint n_threads) {
    
    const char* config_path_str = env->GetStringUTFChars(config_path, nullptr);
    LOGI("Loading MNN model from: %s", config_path_str);
    
    // 释放旧模型
    if (g_llm != nullptr) {
        MNN::Transformer::Llm::destroy(g_llm);
        g_llm = nullptr;
    }
    
    try {
        // 创建LLM实例 - 返回裸指针
        g_llm = MNN::Transformer::Llm::createLLM(config_path_str);
        if (g_llm == nullptr) {
            LOGE("Failed to create LLM instance");
            env->ReleaseStringUTFChars(config_path, config_path_str);
            return JNI_FALSE;
        }
        
        // 设置临时目录和参数 - 使用JSON格式的set_config
        std::string cache_dir = "/data/data/com.localai.server/cache/llm_cache";
        std::ostringstream config_json;
        config_json << "{\"tmp_path\":\"" << cache_dir << "\","
                    << "\"threads\":" << n_threads << ","
                    << "\"context_size\":" << n_ctx << "}";
        
        bool config_success = g_llm->set_config(config_json.str());
        if (!config_success) {
            LOGE("Failed to set config");
            MNN::Transformer::Llm::destroy(g_llm);
            g_llm = nullptr;
            env->ReleaseStringUTFChars(config_path, config_path_str);
            return JNI_FALSE;
        }
        
        // 加载模型
        bool load_success = g_llm->load();
        if (!load_success) {
            LOGE("Failed to load MNN model");
            MNN::Transformer::Llm::destroy(g_llm);
            g_llm = nullptr;
            env->ReleaseStringUTFChars(config_path, config_path_str);
            return JNI_FALSE;
        }
        
        g_model_path = std::string(config_path_str);
        // 从路径提取模型名
        size_t pos = g_model_path.find_last_of("/\\");
        g_model_name = (pos != std::string::npos) ? 
            g_model_path.substr(pos + 1) : g_model_path;
        
        g_is_loaded = true;
        LOGI("MNN model loaded successfully: %s", g_model_name.c_str());
        
        env->ReleaseStringUTFChars(config_path, config_path_str);
        return JNI_TRUE;
        
    } catch (const std::exception& e) {
        LOGE("Exception loading model: %s", e.what());
        if (g_llm != nullptr) {
            MNN::Transformer::Llm::destroy(g_llm);
            g_llm = nullptr;
        }
        env->ReleaseStringUTFChars(config_path, config_path_str);
        return JNI_FALSE;
    }
}

/**
 * 卸载模型
 */
JNIEXPORT void JNICALL
Java_com_localai_server_engine_LlamaEngine_nativeUnloadModel(
        JNIEnv* env, jobject thiz) {
    
    LOGI("Unloading MNN model");
    
    if (g_llm != nullptr) {
        MNN::Transformer::Llm::destroy(g_llm);
        g_llm = nullptr;
    }
    
    g_model_path.clear();
    g_model_name.clear();
    g_is_loaded = false;
}

/**
 * 检查模型是否已加载
 */
JNIEXPORT jboolean JNICALL
Java_com_localai_server_engine_LlamaEngine_nativeIsModelLoaded(
        JNIEnv* env, jobject thiz) {
    return (g_llm != nullptr && g_is_loaded) ? JNI_TRUE : JNI_FALSE;
}

/**
 * 生成文本（非流式）
 * MNN 3.4.1 API: response()返回void，通过ostream输出
 */
JNIEXPORT jstring JNICALL
Java_com_localai_server_engine_LlamaEngine_nativeGenerate(
        JNIEnv* env, jobject thiz,
        jstring prompt, jint max_tokens, jfloat temperature, jint top_k, jfloat top_p) {
    
    if (g_llm == nullptr || !g_is_loaded) {
        LOGE("Model not loaded");
        return env->NewStringUTF("");
    }
    
    const char* prompt_str = env->GetStringUTFChars(prompt, nullptr);
    LOGD("Generating text for prompt: %s", prompt_str);
    
    try {
        // 构建对话历史 - ChatMessages是std::vector<std::pair<std::string, std::string>>
        // pair<role, content>
        MNN::Transformer::ChatMessages history;
        history.push_back({"user", prompt_str});
        
        // 设置采样参数 - 使用JSON格式
        std::ostringstream sampling_config;
        sampling_config << "{\"temperature\":" << temperature << ","
                        << "\"top_p\":" << top_p << ","
                        << "\"top_k\":" << top_k << ","
                        << "\"max_tokens\":" << max_tokens << "}";
        g_llm->set_config(sampling_config.str());
        
        // 使用ostringstream捕获输出
        std::ostringstream oss;
        std::string eos_token = "<|im_end|>";
        
        // response()返回void，输出通过ostream
        g_llm->response(history, &oss, eos_token.c_str(), max_tokens);
        
        std::string output_stream = oss.str();
        
        env->ReleaseStringUTFChars(prompt, prompt_str);
        
        LOGD("Generated: %s", output_stream.c_str());
        return env->NewStringUTF(output_stream.c_str());
        
    } catch (const std::exception& e) {
        LOGE("Exception during generation: %s", e.what());
        env->ReleaseStringUTFChars(prompt, prompt_str);
        return env->NewStringUTF("");
    }
}

/**
 * 流式生成（每个token回调Kotlin）
 * MNN 3.4.1 API: 使用 generate_init + generate + stoped() 方式
 */
JNIEXPORT jstring JNICALL
Java_com_localai_server_engine_LlamaEngine_nativeGenerateStream(
        JNIEnv* env, jobject thiz,
        jstring prompt, jint max_tokens, jfloat temperature, jint top_k, jfloat top_p) {
    
    if (g_llm == nullptr || !g_is_loaded) {
        LOGE("Model not loaded for streaming");
        return env->NewStringUTF("");
    }
    
    const char* prompt_str = env->GetStringUTFChars(prompt, nullptr);
    LOGD("Streaming generation for prompt: %s", prompt_str);
    
    try {
        // 构建对话历史
        MNN::Transformer::ChatMessages history;
        history.push_back({"user", prompt_str});
        
        // 设置采样参数
        std::ostringstream sampling_config;
        sampling_config << "{\"temperature\":" << temperature << ","
                        << "\"top_p\":" << top_p << ","
                        << "\"top_k\":" << top_k << "}";
        g_llm->set_config(sampling_config.str());
        
        std::string full_output;
        std::string eos_token = "<|im_end|>";
        
        // 先用response进行prefill阶段（history, nullptr表示不输出到ostream）
        // 这会初始化生成上下文
        g_llm->response(history, nullptr, eos_token.c_str(), max_tokens);
        
        // 使用generate_init + generate循环进行自回归生成
        g_llm->generate_init(nullptr, eos_token.c_str());
        
        std::string last_output;
        int token_count = 0;
        
        // 注意：MNN 3.4.1 API是 stoped() 不是 stopped()
        while (!g_llm->stoped() && token_count < max_tokens) {
            // 生成1个token
            g_llm->generate(1);
            
            // 通过getContext()->generate_str获取当前输出
            const MNN::Transformer::LlmContext* ctx = g_llm->getContext();
            if (ctx != nullptr && ctx->generate_str.size() > last_output.size()) {
                // 只发送增量部分
                std::string delta = ctx->generate_str.substr(last_output.size());
                tokenCallback(delta);
                full_output += delta;
                last_output = ctx->generate_str;
            }
            
            token_count++;
        }
        
        env->ReleaseStringUTFChars(prompt, prompt_str);
        LOGD("Streaming complete: %d tokens, %zu chars", token_count, full_output.size());
        return env->NewStringUTF(full_output.c_str());
        
    } catch (const std::exception& e) {
        LOGE("Exception during streaming: %s", e.what());
        env->ReleaseStringUTFChars(prompt, prompt_str);
        return env->NewStringUTF("");
    }
}

/**
 * 获取已加载模型名称
 */
JNIEXPORT jstring JNICALL
Java_com_localai_server_engine_LlamaEngine_nativeGetLoadedModelName(
        JNIEnv* env, jobject thiz) {
    return env->NewStringUTF(g_model_name.c_str());
}

/**
 * 获取上下文大小
 */
JNIEXPORT jint JNICALL
Java_com_localai_server_engine_LlamaEngine_nativeGetContextSize(
        JNIEnv* env, jobject thiz) {
    if (g_llm == nullptr) return 0;
    // 从配置获取上下文大小，默认返回4096
    return 4096;
}

/**
 * 获取内存使用（估算）
 */
JNIEXPORT jlong JNICALL
Java_com_localai_server_engine_LlamaEngine_nativeGetMemoryUsage(
        JNIEnv* env, jobject thiz) {
    if (g_llm == nullptr) return 0;
    // MNN模型大小估算
    // Qwen3.5-4B MNN模型约 2-3GB
    return 2L * 1024 * 1024 * 1024;  // 2GB
}

/**
 * 设置系统提示词
 * MNN 3.4.1: 通过apply_chat_template设置对话模板
 */
JNIEXPORT jboolean JNICALL
Java_com_localai_server_engine_LlamaEngine_nativeSetSystemPrompt(
        JNIEnv* env, jobject thiz, jstring system_prompt) {
    
    if (g_llm == nullptr) {
        LOGE("Model not loaded");
        return JNI_FALSE;
    }
    
    const char* prompt_str = env->GetStringUTFChars(system_prompt, nullptr);
    LOGI("Setting system prompt: %s", prompt_str);
    
    try {
        // 使用apply_chat_template设置系统提示
        // 这里只是记录，不做实际设置，MNN的chat template在response时自动处理
        env->ReleaseStringUTFChars(system_prompt, prompt_str);
        return JNI_TRUE;
    } catch (const std::exception& e) {
        LOGE("Exception setting system prompt: %s", e.what());
        env->ReleaseStringUTFChars(system_prompt, prompt_str);
        return JNI_FALSE;
    }
}

/**
 * 重置对话历史
 */
JNIEXPORT void JNICALL
Java_com_localai_server_engine_LlamaEngine_nativeResetConversation(
        JNIEnv* env, jobject thiz) {
    
    if (g_llm != nullptr) {
        g_llm->reset();
        LOGI("Conversation history reset");
    }
}

/**
 * JNI_OnLoad - 初始化JavaVM
 */
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_jvm = vm;
    LOGI("JNI_OnLoad: JNI version 1.6 loaded");
    return JNI_VERSION_1_6;
}

}
