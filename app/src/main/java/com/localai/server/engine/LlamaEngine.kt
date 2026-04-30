package com.localai.server.engine

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import com.localai.server.util.FileLog

/**
 * MnnEngine - 基于 MNN LLM 框架的推理引擎
 * 
 * LocalAI-Server v4.0-MNN
 * 使用 MNN 3.4.1 + Qwen3.5-4B-Claude蒸馏版
 * 
 * 保持与之前 LlamaEngine 接口兼容，底层实现从 llama.cpp 迁移到 MNN
 */
@Singleton
class LlamaEngine @Inject constructor(
    private val context: Context
) {
    companion object {
        private const val TAG = "MnnEngine"
        
        private var _instance: LlamaEngine? = null
        
        // JNI方法名
        private const val JNI_CLASS = "com/localai/server/engine/LlamaEngine"
        
        fun initialize(context: Context): Boolean {
            return try {
                Log.i(TAG, "Initializing MnnEngine with MNN LLM...")
                _instance = LlamaEngine(context)
                _instance?.initNative()
                _instance?.startStateCollection()
                Log.i(TAG, "MnnEngine initialized successfully")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize MnnEngine", e)
                false
            }
        }
        
        fun loadLibraries(): Boolean {
            return try {
                Log.i(TAG, "Checking native library status...")
                System.loadLibrary("localai-jni")
                System.loadLibrary("llm")
                System.loadLibrary("MNN_Express")
                System.loadLibrary("MNN")
                Log.i(TAG, "MNN native libraries loaded")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load MNN native libraries", e)
                // 如果是找不到文件，说明MNN库尚未编译，这是预期的
                if (e.message?.contains("undefined") == false) {
                    Log.w(TAG, "MNN libraries not found yet - expected before first build")
                }
                false
            }
        }
        
        fun getLoadError(): String? {
            return if (_instance == null) {
                "Engine not initialized"
            } else if (!_instance!!.isModelLoaded()) {
                "Model not loaded"
            } else {
                null
            }
        }
    }
    
    // 引擎状态
    sealed class State {
        object Uninitialized : State()
        object Initialized : State()
        object ModelReady : State()
        object Generating : State()
        data class Error(val message: String) : State()
    }
    
    private val _state = MutableStateFlow<State>(State.Uninitialized)
    val state: StateFlow<State> = _state.asStateFlow()
    
    private var isModelLoaded = false
    private var loadedModelPath: String? = null
    private var loadedModelName: String? = null
    private var _systemPrompt: String = ""
    
    init {
        Log.i(TAG, "MnnEngine instance created")
    }
    
    // ==================== JNI方法声明 ====================
    
    private external fun initNative()
    private external fun nativeLoadModel(configPath: String, nCtx: Int, nThreads: Int): Boolean
    private external fun nativeUnloadModel()
    private external fun nativeIsModelLoaded(): Boolean
    private external fun nativeGenerate(prompt: String, maxTokens: Int, temperature: Float, topK: Int, topP: Float): String
    private external fun nativeGenerateStream(prompt: String, maxTokens: Int, temperature: Float, topK: Int, topP: Float): String
    private external fun nativeGetLoadedModelName(): String
    private external fun nativeGetContextSize(): Int
    private external fun nativeGetMemoryUsage(): Long
    private external fun nativeSetSystemPrompt(systemPrompt: String): Boolean
    private external fun nativeResetConversation()
    private external fun initNativeCallback(callback: TokenCallback?)
    
    /**
     * Token回调接口（用于流式生成）
     */
    interface TokenCallback {
        fun onToken(token: String)
    }
    
    /**
     * 初始化Native层
     */
    private fun initNative() {
        try {
            System.loadLibrary("localai-jni")
            System.loadLibrary("llm")
            System.loadLibrary("MNN_Express")
            System.loadLibrary("MNN")
            _state.value = State.Initialized
            Log.i(TAG, "MNN native libraries loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load MNN libraries: ${e.message}")
            _state.value = State.Error("Native library load failed: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize native", e)
            _state.value = State.Error(e.message ?: "Unknown error")
        }
    }
    
    /**
     * 启动状态收集
     */
    private fun startStateCollection() {
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.Main) {
            _state.collect { state ->
                Log.d(TAG, "Engine state changed: $state")
            }
        }
    }
    
    /**
     * 加载模型
     * @param path 模型目录路径（包含config.json）
     * @param nCtx 上下文大小
     * @param nThreads 线程数
     * @param nBatch [Deprecated] MNN不使用此参数
     * @param flashAttn [Deprecated] MNN不使用此参数
     * @param cacheType [Deprecated] MNN不使用此参数
     * @param nGpuLayers [Deprecated] MNN不使用此参数
     */
    suspend fun loadModel(
        path: String, 
        nCtx: Int = 4096, 
        nThreads: Int = 4,
        nBatch: Int = 512,
        flashAttn: Boolean = true,
        cacheType: String = "f16",
        nGpuLayers: Int = 0
    ): Boolean = withContext(Dispatchers.IO) {
        // MNN不使用这些llama.cpp参数，仅记录日志
        Log.w(TAG, "loadModel: nBatch=$nBatch, flashAttn=$flashAttn, cacheType=$cacheType, nGpuLayers=$nGpuLayers - [Deprecated] MNN不使用这些参数")
        
        val configFile = File(path)
        
        // MNN模型是一个目录，需要找到config.json
        val actualPath = if (configFile.isDirectory) {
            val configJson = File(configFile, "config.json")
            if (configJson.exists()) {
                configJson.absolutePath
            } else {
                // 尝试查找.mnn文件
                val mnnFiles = configFile.listFiles { _, name -> name.endsWith(".mnn") }
                if (!mnnFiles.isNullOrEmpty()) {
                    // MNN模型目录，返回目录路径
                    configFile.absolutePath
                } else {
                    path
                }
            }
        } else {
            path
        }
        
        val file = File(actualPath)
        if (!file.exists()) {
            Log.e(TAG, "Model config not found: $actualPath")
            return@withContext false
        }
        
        try {
            // 卸载旧模型
            if (isModelLoaded) {
                unloadModel()
            }
            
            Log.i(TAG, "Loading MNN model from: $actualPath")
            FileLog.log(TAG, "Loading MNN model: $actualPath, nCtx=$nCtx, nThreads=$nThreads")
            
            _state.value = State.Initialized
            
            val success = nativeLoadModel(actualPath, nCtx, nThreads)
            
            if (success) {
                isModelLoaded = true
                loadedModelPath = actualPath
                loadedModelName = nativeGetLoadedModelName()
                
                // 设置系统提示词（如果有）
                if (_systemPrompt.isNotEmpty()) {
                    nativeSetSystemPrompt(_systemPrompt)
                }
                
                _state.value = State.ModelReady
                Log.i(TAG, "MNN model loaded: $loadedModelName")
                FileLog.log(TAG, "MNN model loaded successfully: $loadedModelName")
                true
            } else {
                isModelLoaded = false
                loadedModelPath = null
                loadedModelName = null
                _state.value = State.Error("Failed to load model")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load MNN model", e)
            isModelLoaded = false
            loadedModelPath = null
            loadedModelName = null
            _state.value = State.Error(e.message ?: "Unknown error")
            false
        }
    }
    
    /**
     * 设置系统提示词
     */
    suspend fun setSystemPrompt(systemPrompt: String): Boolean = withContext(Dispatchers.IO) {
        _systemPrompt = systemPrompt
        
        if (!isModelLoaded) {
            Log.i(TAG, "System prompt saved for later use")
            return@withContext true
        }
        
        try {
            val success = nativeSetSystemPrompt(systemPrompt)
            if (success) {
                Log.i(TAG, "System prompt set successfully")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set system prompt", e)
            false
        }
    }
    
    /**
     * 卸载模型
     */
    fun unloadModel() {
        try {
            nativeUnloadModel()
            isModelLoaded = false
            loadedModelPath = null
            loadedModelName = null
            _state.value = State.Initialized
            Log.i(TAG, "MNN model unloaded")
        } catch (e: Exception) {
            Log.e(TAG, "Error unloading model", e)
        }
    }
    
    /**
     * 检查模型是否已加载
     */
    fun isModelLoaded(): Boolean {
        return try {
            isModelLoaded && nativeIsModelLoaded()
        } catch (e: Exception) {
            Log.e(TAG, "Error checking model status", e)
            false
        }
    }
    
    /**
     * 同步生成（等待完整结果）
     */
    suspend fun generate(
        prompt: String, 
        maxTokens: Int = 512, 
        temperature: Float = 0.7f, 
        topK: Int = 40, 
        topP: Float = 0.9f
    ): String = withContext(Dispatchers.IO) {
        if (!isModelLoaded()) {
            throw IllegalStateException("模型未加载")
        }
        
        try {
            Log.d(TAG, "Generating response for prompt: ${prompt.take(50)}...")
            val genStartTime = System.currentTimeMillis()
            FileLog.log(TAG, "Starting generation, prompt=${prompt.take(50)}..., maxTokens=$maxTokens")
            
            _state.value = State.Generating
            
            val result = nativeGenerate(prompt, maxTokens, temperature, topK, topP)
            
            val genDuration = System.currentTimeMillis() - genStartTime
            Log.d(TAG, "Generated ${result.length} characters in ${genDuration}ms")
            FileLog.log(TAG, "Generation complete: ${result.length} chars in ${genDuration}ms")
            
            _state.value = State.ModelReady
            result
            
        } catch (e: Exception) {
            Log.e(TAG, "Generation failed", e)
            _state.value = State.Error(e.message ?: "Generation failed")
            throw e
        }
    }
    
    /**
     * 流式生成（返回 Flow）
     * 注意：MNN的流式生成通过JNI回调实现
     */
    fun generateStream(
        prompt: String, 
        maxTokens: Int = 512, 
        temperature: Float = 0.7f, 
        topK: Int = 40, 
        topP: Float = 0.9f
    ): Flow<String> {
        if (!isModelLoaded()) {
            throw IllegalStateException("模型未加载")
        }
        
        Log.d(TAG, "Streaming generation for prompt: ${prompt.take(50)}...")
        
        val flow = MutableStateFlow("")
        
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            try {
                _state.value = State.Generating
                
                // 创建回调
                val callback = object : TokenCallback {
                    override fun onToken(token: String) {
                        // 每个token都发布到flow
                        flow.value += token
                    }
                }
                
                // 初始化回调
                initNativeCallback(callback)
                
                // 执行流式生成
                nativeGenerateStream(prompt, maxTokens, temperature, topK, topP)
                
                _state.value = State.ModelReady
            } catch (e: Exception) {
                Log.e(TAG, "Streaming generation failed", e)
                _state.value = State.Error(e.message ?: "Streaming failed")
            }
        }
        
        return flow
    }
    
    /**
     * 获取已加载模型名称
     */
    fun getLoadedModelName(): String? = loadedModelName
    
    /**
     * 获取优化配置
     */
    fun getOptimizationConfig(): com.localai.server.domain.model.OptimizationParams? {
        if (!isModelLoaded) return null
        
        val memInfo = android.app.ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager)
            .getMemoryInfo(memInfo)
        val availMemMB = memInfo.availMem / 1024 / 1024
        
        val nCtx = when {
            availMemMB > 8000 -> 8192
            availMemMB > 4000 -> 4096
            else -> 2048
        }
        
        return com.localai.server.domain.model.OptimizationParams(
            nBatch = nCtx / 4,
            flashAttn = true,
            cacheType = "fp16"
        )
    }
    
    /**
     * 获取内存使用
     */
    fun getMemoryUsage(): Long {
        if (!isModelLoaded) return 0
        return try {
            nativeGetMemoryUsage()
        } catch (e: Exception) {
            0L
        }
    }
    
    /**
     * 获取模型信息
     */
    fun getModelInfo(): Map<String, Any> {
        return mapOf(
            "loaded" to isModelLoaded,
            "name" to (loadedModelName ?: "未加载"),
            "path" to (loadedModelPath ?: ""),
            "memoryUsage" to getMemoryUsage(),
            "engine" to "MNN LLM 3.4.1",
            "state" to (_state.value.toString()),
            "systemPromptSet" to (_systemPrompt.isNotEmpty())
        )
    }
    
    fun getLoadedModelInfo(): Map<String, Any> = getModelInfo()
    
    /**
     * 重置对话历史
     */
    fun resetConversation() {
        try {
            nativeResetConversation()
            Log.i(TAG, "Conversation history reset")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reset conversation", e)
        }
    }
    
    /**
     * 销毁引擎
     */
    fun destroy() {
        try {
            nativeUnloadModel()
            _instance = null
            Log.i(TAG, "MnnEngine destroyed")
        } catch (e: Exception) {
            Log.e(TAG, "Error destroying engine", e)
        }
    }
}
