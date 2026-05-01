package com.localai.server.domain.model

/**
 * 优化参数配置 - MNN版本
 * 
 * 注意：MNN不使用 llama.cpp 的参数如 nBatch、flashAttn、cacheType、nGpuLayers
 * 这些参数保留用于向后兼容，但实际引擎会忽略
 * 
 * @param backend MNN后端类型: arm82(CPU), vulkan(GPU), 默认 "arm82"
 * @param nBatch 保留参数，MNN不使用
 * @param flashAttn 保留参数，MNN不使用
 * @param cacheType 保留参数，MNN不使用
 */
data class OptimizationParams(
    val backend: String = "arm82",  // MNN后端: arm82(CPU), vulkan(GPU)
    val nBatch: Int = 512,         // [Deprecated] MNN不使用
    val flashAttn: Boolean = true,   // [Deprecated] MNN不使用
    val cacheType: String = "f16"    // [Deprecated] MNN不使用
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

// MNN预置模型列表
val AVAILABLE_MODELS = listOf(
    ModelInfo(
        name = "Qwen3.5-4B-MNN",
        url = "https://modelscope.cn/api/v1/models/MNN/Qwen3.5-4B-MNN/repo?Revision=master&FilePath={filename}",
        size = "~2.65GB",
        description = "MNN官方Qwen3.5-4B，支持思考模式，MNN标准格式（修复magic bytes兼容问题）"
    ),
    ModelInfo(
        name = "Qwen3-1.8B-Claude-Distilled",
        url = "https://modelscope.cn/taobao-mnn/Qwen3-1.8B-Claude-Reasoning-Distilled-MNN",
        size = "~1.2GB",
        description = "轻量Claude蒸馏版，速度更快，MNN格式"
    )
)
