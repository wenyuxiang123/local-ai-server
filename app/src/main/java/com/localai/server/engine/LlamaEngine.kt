package com.localai.server.engine

import android.content.Context
import android.util.Log
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.arm.aichat.isModelLoaded
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import com.localai.server.util.FileLog

/**
 * LlamaEngine 基于 llama.cpp 官方 Android 绑定实现
 * 使用 InferenceEngine 和 AiChat 类进行模型推理
 */
@Singleton
class LlamaEngine @Inject constructor(
    private val context: Context
) {
    companion object {
        private const val TAG = "LlamaEngine"
        
        private var _instance: LlamaEngine? = null
        private var _engine: InferenceEngine? = null
        
        fun initialize(context: Context): Boolean {
            return try {
                Log.i(TAG, "Initializing LlamaEngine with official llama.cpp binding...")
                _instance = LlamaEngine(context)
                _engine = AiChat.getInferenceEngine(context)
                _instance?.startStateCollection()
                Log.i(TAG, "LlamaEngine initialized successfully")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize LlamaEngine", e)
                false
            }
        }
        
        fun loadLibraries(): Boolean {
            return try {
                Log.i(TAG, "Checking native library status...")
                _engine?.let {
                    Log.i(TAG, "Native library already loaded")
                    true
                } ?: run {
                    Log.e(TAG, "Engine not initialized")
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load libraries", e)
                false
            }
        }
        
        fun getLoadError(): String? {
            return _engine?.state?.value?.let { state ->
                when (state) {
                    is InferenceEngine.State.Error -> state.exception.message
                    is InferenceEngine.State.Uninitialized -> "Engine not initialized"
                    else -> null
                }
            }
        }
    }
    
    private val engine: InferenceEngine
        get() = _engine ?: throw IllegalStateException("Engine not initialized. Call initialize() first.")
    
    private val _state = MutableStateFlow<InferenceEngine.State>(InferenceEngine.State.Uninitialized)
    val state: StateFlow<InferenceEngine.State> = _state.asStateFlow()
    
    private var isModelLoaded = false
    private var loadedModelPath: String? = null
    private var loadedModelName: String? = null
    private var _systemPrompt: String = ""
    
    init {
        Log.i(TAG, "LlamaEngine instance created")
    }
    
    // 启动状态收集（在initialize后调用）
    private fun startStateCollection() {
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.Main) {
            engine.state.collect { state ->
                _state.value = state
                isModelLoaded = state.isModelLoaded
                Log.d(TAG, "Engine state changed: ${state.javaClass.simpleName}")
            }
        }
    }
    
    /**
     * 加载模型
     * @param path 模型文件路径
     * @param nCtx 上下文大小
     * @param nThreads 线程数
     * @param nBatch 批处理大小 (默认: 512)
     * @param flashAttn 是否启用 Flash Attention (默认: true)
     * @param cacheType KV cache 量化类型: f16/q4_0/q5_0/q8_0 (默认: q4_0)
     */
    suspend fun loadModel(
        path: String, 
        nCtx: Int = 2048, 
        nThreads: Int = 4,
        nBatch: Int = 512,
        flashAttn: Boolean = true,
        cacheType: String = "q4_0"
    ): Boolean = withContext(Dispatchers.IO) {
        val file = File(path)
        if (!file.exists()) {
            Log.e(TAG, "Model file not found: $path")
            return@withContext false
        }
        
        try {
            // 等待引擎初始化完成（最多等待10秒）
            Log.i(TAG, "Waiting for engine initialization...")
            try {
                withTimeout(10000) {
                    engine.state.first { state ->
                        state is InferenceEngine.State.Initialized || 
                        state is InferenceEngine.State.ModelReady ||
                        state is InferenceEngine.State.Error
                    }
                }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Engine initialization timeout")
                return@withContext false
            }
            
            // 检查是否有错误
            val currentState = engine.state.value
            if (currentState is InferenceEngine.State.Error) {
                Log.e(TAG, "Engine in error state: ${(currentState as InferenceEngine.State.Error).exception.message}")
                return@withContext false
            }
            
            // 卸载旧模型
            if (isModelLoaded) {
                unloadModel()
            }
            
            Log.i(TAG, "Loading model: ${file.name}, size=${file.length() / 1024 / 1024}MB")
            FileLog.log(TAG, "Loading model: ${file.name}, size=${file.length() / 1024 / 1024}MB, nCtx=$nCtx, nThreads=$nThreads, nBatch=$nBatch, flashAttn=$flashAttn, cacheType=$cacheType")
            Log.i(TAG, "Context size: $nCtx, Threads: $nThreads, nBatch: $nBatch, flashAttn: $flashAttn, cacheType: $cacheType")
            
            // 使用官方 API 加载模型，传入上下文大小和优化参数
            engine.loadModel(path, nCtx, nBatch, flashAttn, cacheType)
            
            // 如果之前有设置过 system prompt，重新设置
            if (_systemPrompt.isNotEmpty()) {
                engine.setSystemPrompt(_systemPrompt)
            }
            
            isModelLoaded = true
            loadedModelPath = path
            loadedModelName = file.name
            
            Log.i(TAG, "Model loaded successfully: ${file.name}")
            FileLog.log(TAG, "Model loaded successfully: ${file.name}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model", e)
            isModelLoaded = false
            loadedModelPath = null
            loadedModelName = null
            false
        }
    }
    
    /**
     * 设置系统提示词
     */
    suspend fun setSystemPrompt(systemPrompt: String): Boolean = withContext(Dispatchers.IO) {
        if (!isModelLoaded) {
            _systemPrompt = systemPrompt // 保存以便模型加载后使用
            Log.i(TAG, "System prompt saved for later use")
            return@withContext true
        }
        
        try {
            _systemPrompt = systemPrompt
            engine.setSystemPrompt(systemPrompt)
            Log.i(TAG, "System prompt set successfully")
            true
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
            engine.cleanUp()
            isModelLoaded = false
            loadedModelPath = null
            loadedModelName = null
            Log.i(TAG, "Model unloaded")
        } catch (e: Exception) {
            Log.e(TAG, "Error unloading model", e)
        }
    }
    
    fun isModelLoaded(): Boolean {
        // 同时检查本地标志和engine状态，防止竞态导致的状态不一致
        if (!isModelLoaded) return false
        
        val engineState = engine.state.value
        val engineReady = engineState is InferenceEngine.State.ModelReady ||
                          engineState is InferenceEngine.State.Generating ||
                          engineState is InferenceEngine.State.ProcessingUserPrompt ||
                          engineState is InferenceEngine.State.ProcessingSystemPrompt
        
        Log.d(TAG, "isModelLoaded() check: localFlag=$isModelLoaded, engineState=${engineState.javaClass.simpleName}, engineReady=$engineReady")
        
        // 如果引擎状态异常但本地标志为true，重置本地标志
        if (!engineReady && isModelLoaded) {
            Log.w(TAG, "Engine state mismatch! Resetting isModelLoaded flag. Engine was: ${engineState.javaClass.simpleName}")
            isModelLoaded = false
            return false
        }
        
        return engineReady
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
        // 重新检查模型状态，确保同步检查
        if (!isModelLoaded()) throw IllegalStateException("模型未加载或引擎状态异常")
        
        try {
            Log.d(TAG, "Generating response for prompt: ${prompt.take(50)}...")
            val genStartTime = System.currentTimeMillis()
            FileLog.log(TAG, "Starting generation, prompt=${prompt.take(50)}..., maxTokens=$maxTokens")
            
            val result = StringBuilder()
            engine.sendUserPrompt(prompt, maxTokens)
                .map { token ->
                    result.append(token)
                    token
                }
                .catch { e ->
                    Log.e(TAG, "Generation error", e)
                    throw e
                }
                .collect { }
            
            val genDuration = System.currentTimeMillis() - genStartTime
            Log.d(TAG, "Generated ${result.length} characters in ${genDuration}ms")
            FileLog.log(TAG, "Generation complete: ${result.length} chars in ${genDuration}ms")
            result.toString()
        } catch (e: IllegalStateException) {
            // Engine state mismatch - reset local flag
            Log.e(TAG, "Engine state mismatch during generation", e)
            isModelLoaded = false
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Generation failed", e)
            FileLog.log(TAG, "Generation failed: ${e.message}")
            // 检查engine状态是否异常，如果是则重置标志
            val state = engine.state.value
            if (state !is InferenceEngine.State.ModelReady && 
                state !is InferenceEngine.State.Generating) {
                Log.w(TAG, "Engine state abnormal, resetting isModelLoaded flag. State: ${state.javaClass.simpleName}")
                isModelLoaded = false
            }
            throw e
        }
    }
    
    /**
     * 流式生成（返回 Flow）
     */
    fun generateStream(
        prompt: String, 
        maxTokens: Int = 512, 
        temperature: Float = 0.7f, 
        topK: Int = 40, 
        topP: Float = 0.9f
    ): Flow<String> {
        if (!isModelLoaded()) throw IllegalStateException("模型未加载或引擎状态异常")
        
        Log.d(TAG, "Streaming generation for prompt: ${prompt.take(50)}...")
        return engine.sendUserPrompt(prompt, maxTokens)
    }
    
    fun getLoadedModelName(): String? = loadedModelName
    
    /**
     * 获取当前优化配置
     * @return OptimizationParams 当前使用的优化参数
     */
    fun getOptimizationConfig(): com.localai.server.domain.model.OptimizationParams? {
        if (!isModelLoaded) return null
        // 从内存信息动态计算当前配置
        val memInfo = android.app.ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(memInfo)
        val availMemMB = memInfo.availMem / 1024 / 1024
        
        val nBatch = when {
            availMemMB > 8000 -> 1024
            availMemMB > 4000 -> 512
            else -> 256
        }
        
        return com.localai.server.domain.model.OptimizationParams(
            nBatch = nBatch,
            flashAttn = true,
            cacheType = "q4_0"
        )
    }
    
    fun getMemoryUsage(): Long {
        if (!isModelLoaded) return 0
        val file = loadedModelPath?.let { File(it) }
        return file?.length() ?: 0
    }
    
    fun getModelInfo(): Map<String, Any> {
        return mapOf(
            "loaded" to isModelLoaded,
            "name" to (loadedModelName ?: "未加载"),
            "path" to (loadedModelPath ?: ""),
            "memoryUsage" to getMemoryUsage(),
            "engine" to "llama.cpp official Android binding",
            "state" to (_state.value.javaClass.simpleName),
            "systemPromptSet" to (_systemPrompt.isNotEmpty())
        )
    }
    
    fun getLoadedModelInfo(): Map<String, Any> = getModelInfo()
    
    /**
     * 销毁引擎
     */
    fun destroy() {
        try {
            engine.destroy()
            _instance = null
            _engine = null
            Log.i(TAG, "Engine destroyed")
        } catch (e: Exception) {
            Log.e(TAG, "Error destroying engine", e)
        }
    }
}
