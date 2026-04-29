package com.arm.aichat

import com.arm.aichat.InferenceEngine.State
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Interface defining the core LLM inference operations.
 * Supports llama.cpp advanced optimization parameters.
 */
interface InferenceEngine {
    /**
     * Current state of the inference engine
     */
    val state: StateFlow<State>

    /**
     * Load a model from the given path with full optimization parameters.
     *
     * @param pathToModel Path to the GGUF model file
     * @param nCtx Context size (default: 2048)
     * @param nThreads Number of CPU threads (default: 4)
     * @param nBatch Batch size for prompt processing (default: 512)
     * @param flashAttn Enable Flash Attention for faster inference (default: true)
     * @param cacheType KV cache quantization type: "f16", "q8_0", "q4_0", "q5_0", "q5_1" (default: "f16")
     * @param nGpuLayers Number of layers to offload to GPU (0=CPU only, -1=all layers, >0=specific count) (default: 0)
     * @throws UnsupportedArchitectureException if model architecture not supported
     */
    suspend fun loadModel(
        pathToModel: String,
        nCtx: Int = 2048,
        nThreads: Int = 4,
        nBatch: Int = 512,
        flashAttn: Boolean = true,
        cacheType: String = "f16",
        nGpuLayers: Int = 0
    )

    /**
     * Sends a system prompt to the loaded model
     */
    suspend fun setSystemPrompt(systemPrompt: String)

    /**
     * Sends a user prompt to the loaded model and returns a Flow of generated tokens.
     */
    fun sendUserPrompt(message: String, predictLength: Int = DEFAULT_PREDICT_LENGTH): Flow<String>

    /**
     * Runs a benchmark with the specified parameters.
     */
    suspend fun bench(pp: Int, tg: Int, pl: Int, nr: Int = 1): String

    /**
     * Unloads the currently loaded model.
     */
    fun cleanUp()

    /**
     * Cleans up resources when the engine is no longer needed.
     */
    fun destroy()

    /**
     * Get current optimization info as JSON string
     */
    fun getOptimizationInfo(): String

    /**
     * States of the inference engine
     */
    sealed class State {
        object Uninitialized : State()
        object Initializing : State()
        object Initialized : State()

        object LoadingModel : State()
        object UnloadingModel : State()
        object ModelReady : State()

        object Benchmarking : State()
        object ProcessingSystemPrompt : State()
        object ProcessingUserPrompt : State()

        object Generating : State()

        data class Error(val exception: Exception) : State()
    }

    companion object {
        const val DEFAULT_PREDICT_LENGTH = 1024
    }
}

val State.isUninterruptible
    get() = this is State.Initializing ||
        this is State.LoadingModel ||
        this is State.UnloadingModel ||
        this is State.Benchmarking ||
        this is State.ProcessingSystemPrompt ||
        this is State.ProcessingUserPrompt

val State.isModelLoaded: Boolean
    get() = this is State.ModelReady ||
        this is State.Benchmarking ||
        this is State.ProcessingSystemPrompt ||
        this is State.ProcessingUserPrompt ||
        this is State.Generating

class UnsupportedArchitectureException : Exception()

/**
 * Optimization configuration data class
 */
data class OptimizationConfig(
    val nCtx: Int = 2048,
    val nThreads: Int = 4,
    val nBatch: Int = 512,
    val flashAttn: Boolean = true,
    val cacheType: String = "f16",
    val nGpuLayers: Int = 0,
    val backend: String = "CPU"
) {
    companion object {
        fun fromJson(json: String): OptimizationConfig {
            return try {
                val map = json
                    .trim('{', '}')
                    .split(",")
                    .associate { entry ->
                        val (key, value) = entry.split(":").let { it[0].trim().removeSurrounding("\"") to it.getOrElse(1) { "" }.trim() }
                        key to value.removeSurrounding("\"")
                    }
                OptimizationConfig(
                    nCtx = map["n_ctx"]?.toIntOrNull() ?: 2048,
                    nThreads = map["n_threads"]?.toIntOrNull() ?: 4,
                    nBatch = map["n_batch"]?.toIntOrNull() ?: 512,
                    flashAttn = map["flash_attn"]?.toBoolean() ?: true,
                    cacheType = map["cache_type"] ?: "f16",
                    nGpuLayers = map["n_gpu_layers"]?.toIntOrNull() ?: 0,
                    backend = map["backend"] ?: "CPU"
                )
            } catch (e: Exception) {
                OptimizationConfig()
            }
        }
    }
}
