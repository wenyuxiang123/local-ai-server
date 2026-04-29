package com.localai.server.domain.model

/**
 * 优化参数配置
 * @param nBatch 批处理大小，默认 512
 * @param flashAttn 是否启用 Flash Attention，默认 true
 * @param cacheType KV cache 量化类型: f16/q4_0/q5_0/q8_0，默认 q4_0
 */
data class OptimizationParams(
    val nBatch: Int = 512,
    val flashAttn: Boolean = true,
    val cacheType: String = "q4_0"
)

data class ModelConfig(
    val name: String,
    val path: String,
    val contextSize: Int = 2048,
    val threads: Int = 4,
    val sizeBytes: Long = 0,
    val optimizationParams: OptimizationParams? = null
)

data class GenerateConfig(
    val maxTokens: Int = 512,
    val temperature: Float = 0.7f,
    val topK: Int = 40,
    val topP: Float = 0.9f
)

data class ServerStatus(
    val isRunning: Boolean,
    val modelLoaded: Boolean,
    val loadedModel: String?,
    val address: String?,
    val uptime: Long
)

data class ModelInfo(
    val name: String,
    val url: String,
    val size: String,
    val description: String
)

// ModelScope mirror for China - verified URLs
val AVAILABLE_MODELS = listOf(
    ModelInfo(
        name = "Qwen3-4B-Q3_K_M",
        url = "https://modelscope.cn/models/unsloth/Qwen3-4B-GGUF/resolve/master/Qwen3-4B-Q3_K_M.gguf",
        size = "~1.8GB",
        description = "通用对话模型，更小体积，速度更快"
    ),
    ModelInfo(
        name = "Qwen3-4B-Q4_K_M",
        url = "https://huggingface.co/unsloth/Qwen3-4B-GGUF/resolve/main/Qwen3-4B-Q4_K_M.gguf",
        size = "~2.5GB",
        description = "通用对话模型，支持思考模式，性能更强"
    ),
    ModelInfo(
        name = "Qwen3-1.7B-Q4_K_M",
        url = "https://modelscope.cn/api/v1/models/unsloth/Qwen3-1.7B-GGUF/resolve/master/Qwen3-1.7B-Q4_K_M.gguf",
        size = "~1.1GB",
        description = "轻量级模型，速度快，适合对话"
    ),
    ModelInfo(
        name = "Qwen2.5-3B-Instruct-Q4_K_M",
        url = "https://modelscope.cn/api/v1/models/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/master/qwen2.5-3b-instruct-q4_k_m.gguf",
        size = "~1.9GB",
        description = "中等规模，效果更好"
    ),
    ModelInfo(
        name = "Qwen3-0.6B-Q4_K_M",
        url = "https://modelscope.cn/api/v1/models/unsloth/Qwen3-0.6B-GGUF/resolve/master/Qwen3-0.6B-Q4_K_M.gguf",
        size = "~400MB",
        description = "超轻量级，极速响应"
    )
)
