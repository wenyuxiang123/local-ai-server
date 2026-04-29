#include <android/log.h>
#include <jni.h>
#include <iomanip>
#include <cmath>
#include <string>
#include <unistd.h>
#include <sampling.h>

#include "logging.h"
#include "chat.h"
#include "common.h"
#include "llama.h"

template<class T>
static std::string join(const std::vector<T> &values, const std::string &delim) {
    std::ostringstream str;
    for (size_t i = 0; i < values.size(); i++) {
        str << values[i];
        if (i < values.size() - 1) { str << delim; }
    }
    return str.str();
}

/**
 * LLama resources: context, model, batch and sampler
 * Optimized configuration with all llama.cpp advanced features
 */
constexpr int   N_THREADS_MIN           = 2;
constexpr int   N_THREADS_MAX           = 8;  // Increased from 4 to 8 for better multi-core utilization
constexpr int   N_THREADS_HEADROOM      = 2;

constexpr int   DEFAULT_CONTEXT_SIZE    = 2048;
constexpr int   DEFAULT_BATCH_SIZE       = 512;  // Configurable batch size
constexpr int   OVERFLOW_HEADROOM       = 4;
constexpr float DEFAULT_SAMPLER_TEMP    = 0.3f;

// Default optimization parameters
constexpr bool  DEFAULT_FLASH_ATTN      = true;
constexpr int   DEFAULT_N_GPU_LAYERS    = 0;     // 0 = CPU only by default
constexpr ggml_type DEFAULT_CACHE_TYPE  = GGML_TYPE_F16;  // f16 by default, use Q4_0 for quantization

static llama_model                      * g_model;
static llama_context                    * g_context;
static llama_batch                        g_batch;
static common_chat_templates_ptr          g_chat_templates;
static common_sampler                   * g_sampler;
static int                                 g_n_ctx = DEFAULT_CONTEXT_SIZE;

// Global parameters for optimization features
static int         g_n_threads = 4;
static int         g_n_batch = DEFAULT_BATCH_SIZE;
static bool        g_flash_attn = DEFAULT_FLASH_ATTN;
static int         g_n_gpu_layers = DEFAULT_N_GPU_LAYERS;
static ggml_type   g_cache_type_k = DEFAULT_CACHE_TYPE;
static ggml_type   g_cache_type_v = DEFAULT_CACHE_TYPE;

/**
 * Convert cache type string to ggml_type
 */
static ggml_type parse_cache_type(const std::string &cache_type_str) {
    if (cache_type_str == "q4_0") {
        return GGML_TYPE_Q4_0;
    } else if (cache_type_str == "q8_0") {
        return GGML_TYPE_Q8_0;
    } else if (cache_type_str == "q5_0") {
        return GGML_TYPE_Q5_0;
    } else if (cache_type_str == "q5_1") {
        return GGML_TYPE_Q5_1;
    } else {
        return GGML_TYPE_F16;  // Default to f16
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_init(JNIEnv *env, jobject /*unused*/, jstring nativeLibDir) {
    // Set llama log handler to Android
    llama_log_set(aichat_android_log_callback, nullptr);

    // Loading all CPU backend variants (CPU, Vulkan, etc.)
    const auto *path_to_backend = env->GetStringUTFChars(nativeLibDir, 0);
    LOGi("Loading backends from %s", path_to_backend);
    ggml_backend_load_all_from_path(path_to_backend);
    env->ReleaseStringUTFChars(nativeLibDir, path_to_backend);

    // Initialize backends
    llama_backend_init();
    LOGi("Backend initiated; Log handler set.");
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_load(JNIEnv *env, jobject, 
    jstring jmodel_path, jint jn_ctx, jint jn_threads, jint jn_batch, 
    jboolean jflash_attn, jstring jcache_type, jint jn_gpu_layers) {
    
    // Parse optimization parameters
    const int n_ctx = (jn_ctx > 0) ? jn_ctx : DEFAULT_CONTEXT_SIZE;
    g_n_ctx = n_ctx;
    
    // Thread configuration
    g_n_threads = (jn_threads > 0) ? jn_threads : 4;
    g_n_threads = std::max(N_THREADS_MIN, std::min(g_n_threads, N_THREADS_MAX));
    
    // Batch configuration
    g_n_batch = (jn_batch > 0) ? jn_batch : DEFAULT_BATCH_SIZE;
    
    // Flash attention
    g_flash_attn = jflash_attn;
    
    // Cache type
    if (jcache_type != nullptr) {
        const auto *cache_type_str = env->GetStringUTFChars(jcache_type, 0);
        g_cache_type_k = parse_cache_type(std::string(cache_type_str));
        g_cache_type_v = parse_cache_type(std::string(cache_type_str));
        env->ReleaseStringUTFChars(jcache_type, cache_type_str);
    }
    
    // GPU layers (0 = CPU only, -1 = all layers, >0 = specific number)
    g_n_gpu_layers = jn_gpu_layers;
    
    LOGi("=== Llama.cpp Optimization Parameters ===");
    LOGi("n_ctx: %d, n_threads: %d, n_batch: %d", n_ctx, g_n_threads, g_n_batch);
    LOGi("flash_attn: %s, cache_type: %d, n_gpu_layers: %d", 
         g_flash_attn ? "true" : "false", g_cache_type_k, g_n_gpu_layers);
    
    // Model loading with GPU offload support
    llama_model_params model_params = llama_model_default_params();
    
    // GPU offload configuration
    if (g_n_gpu_layers != 0) {
        model_params.n_gpu_layers = g_n_gpu_layers;
        LOGi("GPU offload enabled: n_gpu_layers=%d", g_n_gpu_layers);
    }
    
    // Memory management - keep model in memory for performance
    model_params.use_mmap = true;
    model_params.use_mlock = false;  // Android doesn't support mlock without root

    const auto *model_path = env->GetStringUTFChars(jmodel_path, 0);
    LOGd("%s: Loading model from: \n%s\n", __func__, model_path);
    LOGi("%s: Optimized load (n_ctx=%d, n_gpu_layers=%d)", __func__, n_ctx, g_n_gpu_layers);

    auto *model = llama_model_load_from_file(model_path, model_params);
    env->ReleaseStringUTFChars(jmodel_path, model_path);
    if (!model) {
        LOGe("Failed to load model!");
        return 1;
    }
    g_model = model;
    
    // Log model info
    char model_desc[128];
    llama_model_desc(g_model, model_desc, sizeof(model_desc));
    const auto model_size = double(llama_model_size(g_model)) / 1024.0 / 1024.0 / 1024.0;
    const auto model_n_params = double(llama_model_n_params(g_model)) / 1e9;
    LOGi("Model loaded: %s, size=%.2fGB, params=%.2fB", model_desc, model_size, model_n_params);
    
    return 0;
}

static llama_context *init_context(llama_model *model, const int n_ctx = DEFAULT_CONTEXT_SIZE) {
    if (!model) {
        LOGe("%s: model cannot be null", __func__);
        return nullptr;
    }

    LOGi("%s: Creating context with optimizations:", __func__);
    LOGi("  - n_ctx: %d", n_ctx);
    LOGi("  - n_batch: %d", g_n_batch);
    LOGi("  - n_threads: %d", g_n_threads);
    LOGi("  - flash_attn: %s", g_flash_attn ? "enabled" : "disabled");
    LOGi("  - cache_type: %d", g_cache_type_k);
    LOGi("  - n_gpu_layers: %d", g_n_gpu_layers);

    // Context parameters with all optimizations
    llama_context_params ctx_params = llama_context_default_params();
    const int trained_context_size = llama_model_n_ctx_train(model);
    if (n_ctx > trained_context_size) {
        LOGw("%s: Model was trained with only %d context size! Enforcing %d context size...",
             __func__, trained_context_size, n_ctx);
    }
    
    // Core parameters
    ctx_params.n_ctx = n_ctx;
    ctx_params.n_batch = g_n_batch;
    ctx_params.n_ubatch = g_n_batch;
    ctx_params.n_threads = g_n_threads;
    ctx_params.n_threads_batch = g_n_threads;
    
    // Flash Attention - significantly speeds up context processing
    ctx_params.flash_attn = g_flash_attn;
    
    // KV Cache quantization - saves ~60% memory with minimal quality loss
    // Note: This is for the KV cache, not the model weights
    ctx_params.cache_type_k = g_cache_type_k;
    ctx_params.cache_type_v = g_cache_type_v;
    
    // Ragged batching support
    ctx_params.no_perf = false;

    auto *context = llama_init_from_model(g_model, ctx_params);
    if (context == nullptr) {
        LOGe("%s: llama_new_context_with_model() returned null)", __func__);
        return nullptr;
    }
    
    LOGi("Context initialized successfully!");
    return context;
}

static common_sampler *new_sampler(float temp) {
    common_params_sampling sparams;
    sparams.temp = temp;
    return common_sampler_init(g_model, sparams);
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_prepare(JNIEnv * /*env*/, jobject /*unused*/) {
    auto *context = init_context(g_model, g_n_ctx);
    if (!context) { return 1; }
    g_context = context;
    g_batch = llama_batch_init(g_n_batch, 0, 1);
    g_chat_templates = common_chat_templates_init(g_model, "");
    g_sampler = new_sampler(DEFAULT_SAMPLER_TEMP);
    return 0;
}

static std::string get_backend() {
    std::vector<std::string> backends;
    for (size_t i = 0; i < ggml_backend_reg_count(); i++) {
        auto *reg = ggml_backend_reg_get(i);
        std::string name = ggml_backend_reg_name(reg);
        if (name != "CPU") {
            backends.push_back(ggml_backend_reg_name(reg));
        }
    }
    return backends.empty() ? "CPU" : join(backends, ",");
}

/**
 * Get optimization info as JSON string
 */
extern "C"
JNIEXPORT jstring JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_nativeGetOptimizationInfo(JNIEnv *env, jobject /*unused*/) {
    std::stringstream info;
    info << "{";
    info << "\"n_ctx\":" << g_n_ctx << ",";
    info << "\"n_threads\":" << g_n_threads << ",";
    info << "\"n_batch\":" << g_n_batch << ",";
    info << "\"flash_attn\":" << (g_flash_attn ? "true" : "false") << ",";
    info << "\"cache_type\":\"" << ggml_type_name(g_cache_type_k) << "\",";
    info << "\"n_gpu_layers\":" << g_n_gpu_layers << ",";
    info << "\"backend\":\"" << get_backend() << "\"";
    info << "}";
    return env->NewStringUTF(info.str().c_str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_systemInfo(JNIEnv *env, jobject /*unused*/) {
    std::stringstream info;
    info << llama_print_system_info() << "\n";
    info << "=== Optimization Info ===\n";
    info << "n_ctx: " << g_n_ctx << "\n";
    info << "n_threads: " << g_n_threads << "\n";
    info << "n_batch: " << g_n_batch << "\n";
    info << "flash_attn: " << (g_flash_attn ? "enabled" : "disabled") << "\n";
    info << "cache_type: " << ggml_type_name(g_cache_type_k) << "\n";
    info << "n_gpu_layers: " << g_n_gpu_layers << "\n";
    info << "backend: " << get_backend() << "\n";
    return env->NewStringUTF(info.str().c_str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_benchModel(JNIEnv *env, jobject /*unused*/, jint pp, jint tg,
                                                      jint pl, jint nr) {
    auto *context = init_context(g_model, pp);
    if (!context) {
        const auto *const err_msg = "Fail to init_context! Bench aborted.";
        LOGe(err_msg);
        return env->NewStringUTF(err_msg);
    }

    auto pp_avg = 0.0;
    auto tg_avg = 0.0;
    auto pp_std = 0.0;
    auto tg_std = 0.0;

    const uint32_t n_ctx = llama_n_ctx(context);
    LOGi("n_ctx = %d", n_ctx);

    int i, j;
    int nri;
    for (nri = 0; nri < nr; nri++) {
        LOGi("Benchmark prompt processing (pp = %d)", pp);

        common_batch_clear(g_batch);

        const int n_tokens = pp;
        for (i = 0; i < n_tokens; i++) {
            common_batch_add(g_batch, 0, i, {0}, false);
        }

        g_batch.logits[g_batch.n_tokens - 1] = true;
        llama_memory_clear(llama_get_memory(context), false);

        const auto t_pp_start = ggml_time_us();
        if (llama_decode(context, g_batch) != 0) {
            LOGe("llama_decode() failed during prompt processing");
        }
        const auto t_pp_end = ggml_time_us();

        // bench text generation

        LOGi("Benchmark text generation (tg = %d)", tg);

        llama_memory_clear(llama_get_memory(context), false);
        const auto t_tg_start = ggml_time_us();
        for (i = 0; i < tg; i++) {
            common_batch_clear(g_batch);
            for (j = 0; j < pl; j++) {
                common_batch_add(g_batch, 0, i, {j}, true);
            }

            if (llama_decode(context, g_batch) != 0) {
                LOGe("llama_decode() failed during text generation");
            }
        }
        const auto t_tg_end = ggml_time_us();

        llama_memory_clear(llama_get_memory(context), false);

        const auto t_pp = double(t_pp_end - t_pp_start) / 1000000.0;
        const auto t_tg = double(t_tg_end - t_tg_start) / 1000000.0;

        const auto speed_pp = double(pp) / t_pp;
        const auto speed_tg = double(pl * tg) / t_tg;

        pp_avg += speed_pp;
        tg_avg += speed_tg;

        pp_std += speed_pp * speed_pp;
        tg_std += speed_tg * speed_tg;

        LOGi("pp %f t/s, tg %f t/s", speed_pp, speed_tg);
    }

    llama_free(context);

    pp_avg /= double(nr);
    tg_avg /= double(nr);

    if (nr > 1) {
        pp_std = sqrt(pp_std / double(nr - 1) - pp_avg * pp_avg * double(nr) / double(nr - 1));
        tg_std = sqrt(tg_std / double(nr - 1) - tg_avg * tg_avg * double(nr) / double(nr - 1));
    } else {
        pp_std = 0;
        tg_std = 0;
    }

    char model_desc[128];
    llama_model_desc(g_model, model_desc, sizeof(model_desc));

    const auto model_size = double(llama_model_size(g_model)) / 1024.0 / 1024.0 / 1024.0;
    const auto model_n_params = double(llama_model_n_params(g_model)) / 1e9;

    const auto backend = get_backend();
    std::stringstream result;
    result << std::setprecision(3);
    result << "| model | size | params | backend | test | t/s |\n";
    result << "| --- | --- | --- | --- | --- | --- |\n";
    result << "| " << model_desc << " | " << model_size << "GiB | " << model_n_params << "B | "
           << backend << " | pp " << pp << " | " << pp_avg << " ± " << pp_std << " |\n";
    result << "| " << model_desc << " | " << model_size << "GiB | " << model_n_params << "B | "
           << backend << " | tg " << tg << " | " << tg_avg << " ± " << tg_std << " |\n";
    return env->NewStringUTF(result.str().c_str());
}


/**
 * Completion loop's long-term states:
 * - chat management
 * - position tracking
 */
constexpr const char *ROLE_SYSTEM       = "system";
constexpr const char *ROLE_USER         = "user";
constexpr const char *ROLE_ASSISTANT    = "assistant";

static std::vector<common_chat_msg> chat_msgs;
static llama_pos system_prompt_position;
static llama_pos current_position;

static void reset_long_term_states(const bool clear_kv_cache = true) {
    chat_msgs.clear();
    system_prompt_position = 0;
    current_position = 0;

    if (clear_kv_cache && g_context)
        llama_memory_clear(llama_get_memory(g_context), false);
}

/**
 * TODO-hyin: implement sliding-window version as a better alternative
 *
 * Context shifting by discarding the older half of the tokens appended after system prompt:
 * - take the [system_prompt_position] first tokens from the original prompt
 * - take half of the last (system_prompt_position - system_prompt_position) tokens
 * - recompute the logits in batches
 */
static void shift_context() {
    const int n_discard = (current_position - system_prompt_position) / 2;
    LOGi("%s: Discarding %d tokens", __func__, n_discard);
    llama_memory_seq_rm(llama_get_memory(g_context), 0, system_prompt_position, system_prompt_position + n_discard);
    llama_memory_seq_add(llama_get_memory(g_context), 0, system_prompt_position + n_discard, current_position, -n_discard);
    current_position -= n_discard;
    LOGi("%s: Context shifting done! Current position: %d", __func__, current_position);
}

static std::string chat_add_and_format(const std::string &role, const std::string &content) {
    common_chat_msg new_msg;
    new_msg.role = role;
    new_msg.content = content;
    auto formatted = common_chat_format_single(
            g_chat_templates.get(), chat_msgs, new_msg, role == ROLE_USER, /* use_jinja */ false);
    chat_msgs.push_back(new_msg);
    LOGi("%s: Formatted and added %s message: \n%s\n", __func__, role.c_str(), formatted.c_str());
    return formatted;
}

/**
 * Completion loop's short-term states:
 * - stop generation position
 * - token chars caching
 * - current assistant message being generated
 */
static llama_pos stop_generation_position;
static std::string cached_token_chars;
static std::ostringstream assistant_ss;

static void reset_short_term_states() {
    stop_generation_position = 0;
    cached_token_chars.clear();
    assistant_ss.str("");
}

static int decode_tokens_in_batches(
        llama_context *context,
        llama_batch &batch,
        const llama_tokens &tokens,
        const llama_pos start_pos,
        const bool compute_last_logit = false) {
    // Process tokens in batches using the configured batch size
    LOGd("%s: Decode %d tokens starting at position %d (batch_size=%d)", __func__, 
         (int) tokens.size(), start_pos, g_n_batch);
    for (int i = 0; i < (int) tokens.size(); i += g_n_batch) {
        const int cur_batch_size = std::min((int) tokens.size() - i, g_n_batch);
        common_batch_clear(batch);
        LOGv("%s: Preparing a batch size of %d starting at: %d", __func__, cur_batch_size, i);

        // Shift context if current batch cannot fit into the context
        if (start_pos + i + cur_batch_size >= g_n_ctx - OVERFLOW_HEADROOM) {
            LOGw("%s: Current batch won't fit into context! Shifting...", __func__);
            shift_context();
        }

        // Add tokens to the batch with proper positions
        for (int j = 0; j < cur_batch_size; j++) {
            const llama_token token_id = tokens[i + j];
            const llama_pos position = start_pos + i + j;
            const bool want_logit = compute_last_logit && (i + j == tokens.size() - 1);
            common_batch_add(batch, token_id, position, {0}, want_logit);
        }

        // Decode this batch
        const int decode_result = llama_decode(context, batch);
        if (decode_result) {
            LOGe("%s: llama_decode failed w/ %d", __func__, decode_result);
            return 1;
        }
    }
    return 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_processSystemPrompt(
        JNIEnv *env,
        jobject /*unused*/,
        jstring jsystem_prompt
) {
    // Safety check: ensure engine is properly initialized
    if (!g_context || !g_model) {
        LOGe("%s: g_context or g_model is null! Engine not properly initialized.", __func__);
        return -1;
    }

    // Reset long-term & short-term states
    reset_long_term_states();
    reset_short_term_states();

    // Obtain system prompt from JEnv
    const auto *system_prompt = env->GetStringUTFChars(jsystem_prompt, nullptr);
    LOGd("%s: System prompt received: \n%s", __func__, system_prompt);
    std::string formatted_system_prompt(system_prompt);

    // Format system prompt if applicable
    const bool has_chat_template = common_chat_templates_was_explicit(g_chat_templates.get());
    if (has_chat_template) {
        formatted_system_prompt = chat_add_and_format(ROLE_SYSTEM, system_prompt);
    }
    env->ReleaseStringUTFChars(jsystem_prompt, system_prompt);

    // Tokenize system prompt
    const auto system_tokens = common_tokenize(g_context, formatted_system_prompt,
                                               has_chat_template, has_chat_template);
    for (auto id: system_tokens) {
        LOGv("token: `%s`\t -> `%d`", common_token_to_piece(g_context, id).c_str(), id);
    }

    // Handle context overflow
    const int max_batch_size = g_n_ctx - OVERFLOW_HEADROOM;
    if ((int) system_tokens.size() > max_batch_size) {
        LOGe("%s: System prompt too long for context! %d tokens, max: %d",
             __func__, (int) system_tokens.size(), max_batch_size);
        return 1;
    }

    // Decode system tokens in batches
    if (decode_tokens_in_batches(g_context, g_batch, system_tokens, current_position)) {
        LOGe("%s: llama_decode() failed!", __func__);
        return 2;
    }

    // Update position
    system_prompt_position = current_position = (int) system_tokens.size();
    return 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_processUserPrompt(
        JNIEnv *env,
        jobject /*unused*/,
        jstring juser_prompt,
        jint n_predict
) {
    // Safety check: ensure engine is properly initialized
    if (!g_context || !g_model) {
        LOGe("%s: g_context or g_model is null! Engine not properly initialized.", __func__);
        return -1;
    }

    // Reset short-term states
    reset_short_term_states();

    // Obtain and tokenize user prompt
    const auto *const user_prompt = env->GetStringUTFChars(juser_prompt, nullptr);
    LOGd("%s: User prompt received: \n%s", __func__, user_prompt);
    std::string formatted_user_prompt(user_prompt);

    // Format user prompt if applicable
    const bool has_chat_template = common_chat_templates_was_explicit(g_chat_templates.get());
    if (has_chat_template) {
        formatted_user_prompt = chat_add_and_format(ROLE_USER, user_prompt);
    }
    env->ReleaseStringUTFChars(juser_prompt, user_prompt);

    // Decode formatted user prompts
    auto user_tokens = common_tokenize(g_context, formatted_user_prompt, has_chat_template, has_chat_template);
    for (auto id: user_tokens) {
        LOGv("token: `%s`\t -> `%d`", common_token_to_piece(g_context, id).c_str(), id);
    }

    // Ensure user prompt doesn't exceed the context size by truncating if necessary.
    const int user_prompt_size = (int) user_tokens.size();
    const int max_batch_size = g_n_ctx - OVERFLOW_HEADROOM;
    if (user_prompt_size > max_batch_size) {
        const int skipped_tokens = user_prompt_size - max_batch_size;
        user_tokens.resize(max_batch_size);
        LOGw("%s: User prompt too long! Skipped %d tokens!", __func__, skipped_tokens);
    }

    // Decode user tokens in batches
    if (decode_tokens_in_batches(g_context, g_batch, user_tokens, current_position, true)) {
        LOGe("%s: llama_decode() failed!", __func__);
        return 2;
    }

    // Update position
    current_position += user_prompt_size;
    stop_generation_position = current_position + user_prompt_size + n_predict;
    return 0;
}

static bool is_valid_utf8(const char *string) {
    if (!string) { return true; }

    const auto *bytes = (const unsigned char *) string;
    int num;

    while (*bytes != 0x00) {
        if ((*bytes & 0x80) == 0x00) {
            // U+0000 to U+007F
            num = 1;
        } else if ((*bytes & 0xE0) == 0xC0) {
            // U+0080 to U+07FF
            num = 2;
        } else if ((*bytes & 0xF0) == 0xE0) {
            // U+0800 to U+FFFF
            num = 3;
        } else if ((*bytes & 0xF8) == 0xF0) {
            // U+10000 to U+10FFFF
            num = 4;
        } else {
            return false;
        }

        bytes += 1;
        for (int i = 1; i < num; ++i) {
            if ((*bytes & 0xC0) != 0x80) {
                return false;
            }
            bytes += 1;
        }
    }
    return true;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_generateNextToken(
        JNIEnv *env,
        jobject /*unused*/
) {
    // Safety check: ensure engine is properly initialized
    if (!g_context || !g_model || !g_sampler) {
        LOGe("%s: g_context=%p, g_model=%p, g_sampler=%p - Engine not properly initialized!",
             __func__, (void*)g_context, (void*)g_model, (void*)g_sampler);
        return nullptr;
    }

    // Infinite text generation via context shifting
    if (current_position >= g_n_ctx - OVERFLOW_HEADROOM) {
        LOGw("%s: Context full! Shifting...", __func__);
        shift_context();
    }

    // Stop if reaching the marked position
    if (current_position >= stop_generation_position) {
        LOGw("%s: STOP: hitting stop position: %d", __func__, stop_generation_position);
        return nullptr;
    }

    // Sample next token
    const auto new_token_id = common_sampler_sample(g_sampler, g_context, -1);
    common_sampler_accept(g_sampler, new_token_id, true);

    // Populate the batch with new token, then decode
    common_batch_clear(g_batch);
    common_batch_add(g_batch, new_token_id, current_position, {0}, true);
    if (llama_decode(g_context, g_batch) != 0) {
        LOGe("%s: llama_decode() failed for generated token", __func__);
        return nullptr;
    }

    // Update position
    current_position++;

    // Stop if next token is EOG
    if (llama_vocab_is_eog(llama_model_get_vocab(g_model), new_token_id)) {
        LOGd("id: %d,\tIS EOG!\nSTOP.", new_token_id);
        chat_add_and_format(ROLE_ASSISTANT, assistant_ss.str());
        return nullptr;
    }

    // If not EOG, convert to text
    auto new_token_chars = common_token_to_piece(g_context, new_token_id);
    cached_token_chars += new_token_chars;

    // Create and return a valid UTF-8 Java string
    jstring result = nullptr;
    if (is_valid_utf8(cached_token_chars.c_str())) {
        result = env->NewStringUTF(cached_token_chars.c_str());
        LOGv("id: %d,\tcached: `%s`,\tnew: `%s`", new_token_id, cached_token_chars.c_str(), new_token_chars.c_str());

        assistant_ss << cached_token_chars;
        cached_token_chars.clear();
    } else {
        LOGv("id: %d,\tappend to cache", new_token_id);
        result = env->NewStringUTF("");
    }
    return result;
}


extern "C"
JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_unload(JNIEnv * /*unused*/, jobject /*unused*/) {
    // Reset long-term & short-term states
    reset_long_term_states();
    reset_short_term_states();

    // Free up resources
    common_sampler_free(g_sampler);
    g_chat_templates.reset();
    llama_batch_free(g_batch);
    llama_free(g_context);
    llama_model_free(g_model);
    
    // Reset global parameters
    g_n_ctx = DEFAULT_CONTEXT_SIZE;
    g_n_threads = 4;
    g_n_batch = DEFAULT_BATCH_SIZE;
    g_flash_attn = DEFAULT_FLASH_ATTN;
    g_n_gpu_layers = DEFAULT_N_GPU_LAYERS;
    g_cache_type_k = DEFAULT_CACHE_TYPE;
    g_cache_type_v = DEFAULT_CACHE_TYPE;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_shutdown(JNIEnv *, jobject /*unused*/) {
    llama_backend_free();
}
