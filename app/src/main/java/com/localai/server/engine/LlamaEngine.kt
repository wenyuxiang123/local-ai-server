package com.localai.server.engine

import android.content.Context
import android.util.Log
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.arm.aichat.OptimizationConfig
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import com.localai.server.util.FileLog

/**
 * LlamaEngine 基于 llama.cpp 官方 Android 绑定实现
 * 使用 InferenceEngine 和 AiChat 类进行模型推理
 * 支持 llama.cpp 全部优化参数：Vulkan GPU offload、Flash Attention、KV cache 量化等
 */
@Singleton
class LlamaEngine @Inject constructor(
    private val context: Context
) {
    companion object {
        private const val TAG = "LlamaEngine"
        private const val PREFS_NAME = "llama_engine_prefs"
        private const val KEY_VULKAN_STATUS = "vulkan_status"  // "ok", "fail", "testing", "unknown"
        private const val KEY_VULKAN_TESTED = "vulkan_tested"
        
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
    private var _currentOptimization: OptimizationConfig = OptimizationConfig()
    
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
     * 加载模型 - 支持全部优化参数
     * 
     * @param path 模型文件路径
     * @param nCtx 上下文大小 (默认: 2048)
     * @param nThreads CPU 线程数 (默认: 4)
     * @param nBatch 批处理大小 (默认: 512)
     * @param flashAttn 启用 Flash Attention (默认: true)
     * @param cacheType KV cache 量化类型: "f16", "q8_0", "q4_0", "q5_0", "q5_1" (默认: "f16")
     * @param nGpuLayers GPU 卸载层数 (0=纯CPU, -1=全部, >0=指定层数) (默认: 0)
     */
    /**
     * Test Vulkan support safely with crash recovery
     * Returns true if Vulkan is available and functional
     */
    fun testVulkanSupport(): Boolean {
        // Check crash recovery: if last test left status as "testing", it crashed
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastStatus = prefs.getString(KEY_VULKAN_STATUS, "unknown")
        
        if (lastStatus == "testing") {
            // Last Vulkan test caused a crash
            Log.w(TAG, "Vulkan test crashed last time, marking as unsupported")
            prefs.edit().putString(KEY_VULKAN_STATUS, "fail").apply()
            return false
        }
        
        if (lastStatus == "fail") {
            Log.i(TAG, "Vulkan previously marked as unsupported")
            return false
        }
        
        if (lastStatus == "ok") {
            Log.i(TAG, "Vulkan previously tested OK")
            return _engine?.testVulkanSupport() ?: false
        }
        
        // First time: test with crash recovery
        return try {
            prefs.edit().putString(KEY_VULKAN_STATUS, "testing").apply()
            val result = _engine?.testVulkanSupport() ?: false
            prefs.edit().putString(KEY_VULKAN_STATUS, if (result) "ok" else "fail").apply()
            Log.i(TAG, "Vulkan test result: $result")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Vulkan test exception", e)
            prefs.edit().putString(KEY_VULKAN_STATUS, "fail").apply()
            false
        }
    }
    
    /**
     * Reset Vulkan status (allow re-testing)
     */
    fun resetVulkanStatus() {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_VULKAN_STATUS, "unknown").apply()
    }
    
    /**
     * Check if GPU offload is safe to enable
     * Automatically downgrades to CPU if Vulkan is not available
     */
    fun safeGpuLayers(requestedLayers: Int): Int {
        if (requestedLayers == 0) return 0  // CPU mode, no check needed
        
        val vulkanOk = testVulkanSupport()
        if (!vulkanOk) {
            Log.w(TAG, "Vulkan not available, GPU offload disabled (requested: $requestedLayers)")
            return 0
        }
        
        Log.i(TAG, "Vulkan available, GPU offload enabled: $requestedLayers layers")
        return requestedLayers
    }

    suspend fun loadModel(
        path: String,
        nCtx: Int = 2048,
        nThreads: Int = 4,
        nBatch: Int = 512,
        flashAttn: Boolean = true,
        cacheType: String = "f16",
        nGpuLayers: Int = 0
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
            
            Log.i(TAG, "Loading model with llama.cpp optimizations:")
            Log.i(TAG, "  Model: ${file.name}, size=${file.length() / 1024 / 1024}MB")
            Log.i(TAG, "  nCtx: $nCtx, nThreads: $nThreads, nBatch: $nBatch")
            // 安全检测GPU：如果Vulkan不可用则自动降级为CPU
            val actualGpuLayers = safeGpuLayers(nGpuLayers)
            if (actualGpuLayers != nGpuLayers) {
                Log.w(TAG, "GPU offload downgraded: $nGpuLayers -> $actualGpuLayers (Vulkan not available)")
                FileLog.log(TAG, "WARNING: GPU offload downgraded: $nGpuLayers -> $actualGpuLayers")
            }
            
            Log.i(TAG, "  flashAttn: $flashAttn, cacheType: $cacheType, nGpuLayers: $actualGpuLayers")
            FileLog.log(TAG, "Loading model: ${file.name}")
            FileLog.log(TAG, "Optimizations: nCtx=$nCtx, nThreads=$nThreads, nBatch=$nBatch")
            FileLog.log(TAG, "flashAttn=$flashAttn, cacheType=$cacheType, nGpuLayers=$actualGpuLayers")
            
            // 使用官方 API 加载模型，传入全部优化参数
            engine.loadModel(
                pathToModel = path,
                nCtx = nCtx,
                nThreads = nThreads,
                nBatch = nBatch,
                flashAttn = flashAttn,
                cacheType = cacheType,
                nGpuLayers = actualGpuLayers
            )
            
            // 更新当前优化配置（使用实际GPU层数）
            _currentOptimization = OptimizationConfig(
                nCtx = nCtx,
                nThreads = nThreads,
                nBatch = nBatch,
                flashAttn = flashAttn,
                cacheType = cacheType,
                nGpuLayers = actualGpuLayers
            )
            
            // 如果之前有设置过 system prompt，重新设置
            if (_systemPrompt.isNotEmpty()) {
                engine.setSystemPrompt(_systemPrompt)
            }
            
            isModelLoaded = true
            loadedModelPath = path
            loadedModelName = file.name
            
            Log.i(TAG, "Model loaded successfully: ${file.name}")
            FileLog.log(TAG, "Model loaded successfully: ${file.name}")
            
            // 获取并打印优化信息
            try {
                val optInfo = engine.getOptimizationInfo()
                Log.i(TAG, "Optimization info: $optInfo")
                FileLog.log(TAG, "Active optimizations: $optInfo")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to get optimization info", e)
            }
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model", e)
            FileLog.log(TAG, "Model load failed: ${e.message}")
            isModelLoaded = false
            loadedModelPath = null
            loadedModelName = null
            false
        }
    }
    
    /**
     * 获取当前优化配置
     */
    fun getOptimizationConfig(): OptimizationConfig = _currentOptimization
    
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
            Log.i(TAG, "Unloading model...")
            engine.cleanUp()
            isModelLoaded = false
            Log.i(TAG, "Model unloaded")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unload model", e)
        }
    }
    
    /**
     * 生成文本
     */
    fun generate(prompt: String, maxTokens: Int = 1024, temperature: Float = 0.7f): String {
        val startTime = System.currentTimeMillis()
        val buffer = StringBuilder()
        
        runBlocking(Dispatchers.IO) {
            engine.sendUserPrompt(prompt, maxTokens)
                .catch { e ->
                    Log.e(TAG, "Generation error", e)
                }
                .collect { token ->
                    buffer.append(token)
                }
        }
        
        val elapsed = System.currentTimeMillis() - startTime
        Log.i(TAG, "Generated ${buffer.length} chars in ${elapsed}ms (${buffer.length * 1000 / elapsed.coerceAtLeast(1)} chars/s)")
        
        return buffer.toString()
    }
    
    /**
     * 检查模型是否已加载
     */
    fun isModelLoaded(): Boolean = isModelLoaded
    
    /**
     * 获取已加载模型信息
     */
    fun getLoadedModelInfo(): Map<String, Any?> {
        return mapOf(
            "name" to loadedModelName,
            "path" to loadedModelPath,
            "loaded" to isModelLoaded
        ) + _currentOptimization.let {
            mapOf(
                "nCtx" to it.nCtx,
                "nThreads" to it.nThreads,
                "nBatch" to it.nBatch,
                "flashAttn" to it.flashAttn,
                "cacheType" to it.cacheType,
                "nGpuLayers" to it.nGpuLayers
            )
        }
    }
    
    /**
     * 获取已加载模型名称
     */
    fun getLoadedModelName(): String? = loadedModelName
}
