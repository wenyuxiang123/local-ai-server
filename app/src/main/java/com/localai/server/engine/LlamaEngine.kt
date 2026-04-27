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
     */
    suspend fun loadModel(path: String, nCtx: Int = 2048, nThreads: Int = 4): Boolean = withContext(Dispatchers.IO) {
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
            Log.i(TAG, "Context size: $nCtx, Threads: $nThreads")
            
            // 使用官方 API 加载模型
            engine.loadModel(path)
            
            // 如果之前有设置过 system prompt，重新设置
            if (_systemPrompt.isNotEmpty()) {
                engine.setSystemPrompt(_systemPrompt)
            }
            
            isModelLoaded = true
            loadedModelPath = path
            loadedModelName = file.name
            
            Log.i(TAG, "Model loaded successfully: ${file.name}")
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
    
    fun isModelLoaded(): Boolean = isModelLoaded
    
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
        if (!isModelLoaded) throw IllegalStateException("模型未加载")
        
        try {
            Log.d(TAG, "Generating response for prompt: ${prompt.take(50)}...")
            
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
            
            Log.d(TAG, "Generated ${result.length} characters")
            result.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Generation failed", e)
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
        if (!isModelLoaded) throw IllegalStateException("模型未加载")
        
        Log.d(TAG, "Streaming generation for prompt: ${prompt.take(50)}...")
        return engine.sendUserPrompt(prompt, maxTokens)
    }
    
    fun getLoadedModelName(): String? = loadedModelName
    
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
