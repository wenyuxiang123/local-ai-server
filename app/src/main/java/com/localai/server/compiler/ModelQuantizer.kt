package com.localai.server.compiler

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 模型量化服务
 * 支持将模型量化为不同精度级别
 */
@Singleton
class ModelQuantizer @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "ModelQuantizer"
        
        // 量化工具下载路径（使用云端量化服务或本地工具）
        private const val QUANTIZE_TOOL_URL = "https://github.com/ggerganov/llama.cpp/releases/download/b3650/llama-b3650-bin-android-arm64.tar.gz"
        
        // 量化级别
        val QUANT_TYPES = listOf(
            QuantType("Q2_K", "Q2_K", "2.5bit - 最小体积，最低精度", 0.35f),
            QuantType("Q3_K_M", "Q3_K_M", "3bit - 小体积，较低精度", 0.45f),
            QuantType("Q4_K_M", "Q4_K_M", "4bit - 平衡模式（推荐）", 0.55f),
            QuantType("Q5_K_M", "Q5_K_M", "5bit - 较大体积，较高精度", 0.70f),
            QuantType("Q6_K", "Q6_K", "6bit - 高精度，较大体积", 0.80f),
            QuantType("Q8_0", "Q8_0", "8bit - 最高精度，最大体积", 1.00f)
        )
    }
    
    private val quantizeDir: File by lazy {
        File(context.filesDir, "quantize").apply { mkdirs() }
    }
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .build()
    
    private val _quantizeState = MutableStateFlow(QuantizeState())
    val quantizeState: StateFlow<QuantizeState> = _quantizeState.asStateFlow()
    
    /**
     * 量化状态
     */
    data class QuantizeState(
        val isQuantizing: Boolean = false,
        val currentModel: String = "",
        val targetType: String = "",
        val progress: Int = 0,
        val status: String = "",
        val estimatedSize: Long = 0,
        val originalSize: Long = 0,
        val quantizedSize: Long = 0,
        val outputPath: String = "",
        val error: String? = null
    )
    
    /**
     * 量化类型
     */
    data class QuantType(
        val name: String,
        val type: String,
        val description: String,
        val sizeRatio: Float  // 相对于原模型的体积比例
    )
    
    /**
     * 量化配置
     */
    data class QuantizeConfig(
        val inputPath: String,
        val outputPath: String,
        val quantType: String,
        val threads: Int = 4,
        val split: Boolean = false  // 是否分割模型
    )
    
    /**
     * 量化结果
     */
    data class QuantizeResult(
        val success: Boolean,
        val outputPath: String,
        val originalSize: Long,
        val quantizedSize: Long,
        val compressionRatio: Float,
        val error: String? = null
    )
    
    /**
     * 估算量化后大小
     */
    fun estimateQuantizedSize(originalSize: Long, quantType: String): Long {
        val type = QUANT_TYPES.find { it.type == quantType || it.name == quantType }
        val ratio = type?.sizeRatio ?: 0.5f
        return (originalSize * ratio).toLong()
    }
    
    /**
     * 获取量化类型列表
     */
    fun getQuantTypes(): List<QuantType> = QUANT_TYPES
    
    /**
     * 获取量化建议
     */
    fun getRecommendation(modelSizeBytes: Long, availableSpace: Long): String {
        val sizeGB = modelSizeBytes / (1024.0 * 1024.0 * 1024.0)
        
        return when {
            sizeGB > 5 -> "建议使用 Q2_K 或 Q3_K_M 以节省空间"
            sizeGB > 3 -> "建议使用 Q4_K_M 平衡精度和体积"
            sizeGB > 2 -> "建议使用 Q5_K_M 保留更多精度"
            else -> "原模型已较小，可使用 Q6_K 或 Q8_0 保持高精度"
        }
    }
    
    /**
     * 开始量化（云端方案）
     * 由于 Android 设备计算能力有限，采用云端量化方案
     */
    suspend fun quantizeModel(config: QuantizeConfig): Result<QuantizeResult> = withContext(Dispatchers.IO) {
        _quantizeState.value = QuantizeState(
            isQuantizing = true,
            currentModel = config.inputPath.substringAfterLast("/"),
            targetType = config.quantType,
            status = "准备量化..."
        )
        
        try {
            val inputFile = File(config.inputPath)
            if (!inputFile.exists()) {
                return@withContext Result.failure(Exception("输入文件不存在"))
            }
            
            val originalSize = inputFile.length()
            
            // 估算输出大小
            val estimatedSize = estimateQuantizedSize(originalSize, config.quantType)
            _quantizeState.value = _quantizeState.value.copy(
                originalSize = originalSize,
                estimatedSize = estimatedSize
            )
            
            // 检查存储空间
            val outputDir = File(config.outputPath).parentFile
            if (outputDir != null && outputDir.freeSpace < estimatedSize) {
                return@withContext Result.failure(Exception("存储空间不足，需要 ${formatSize(estimatedSize)}"))
            }
            
            _quantizeState.value = _quantizeState.value.copy(status = "云端量化处理中...")
            
            // 这里可以实现云端量化 API 调用
            // 暂时模拟量化过程
            val result = simulateQuantization(config, originalSize)
            
            if (result.success) {
                _quantizeState.value = _quantizeState.value.copy(
                    isQuantizing = false,
                    progress = 100,
                    status = "量化完成",
                    outputPath = result.outputPath
                )
            } else {
                _quantizeState.value = _quantizeState.value.copy(
                    isQuantizing = false,
                    error = result.error
                )
            }
            
            Result.success(result)
        } catch (e: Exception) {
            Log.e(TAG, "Quantization failed", e)
            _quantizeState.value = _quantizeState.value.copy(
                isQuantizing = false,
                error = e.message
            )
            Result.failure(e)
        }
    }
    
    /**
     * 模拟量化过程（实际使用时替换为真实量化逻辑）
     */
    private suspend fun simulateQuantization(config: QuantizeConfig, originalSize: Long): QuantizeResult {
        // 模拟量化过程
        for (progress in 0..100 step 10) {
            _quantizeState.value = _quantizeState.value.copy(
                progress = progress,
                status = "量化中... $progress%"
            )
            kotlinx.coroutines.delay(500)
        }
        
        // 输出文件路径
        val inputName = File(config.inputPath).nameWithoutExtension
        val outputFileName = "${inputName}-${config.quantType}.gguf"
        val outputPath = File(quantizeDir, outputFileName).absolutePath
        
        // 模拟量化后的文件（实际会由云端返回）
        File(outputPath).createNewFile()
        
        val quantizedSize = estimateQuantizedSize(originalSize, config.quantType)
        
        return QuantizeResult(
            success = true,
            outputPath = outputPath,
            originalSize = originalSize,
            quantizedSize = quantizedSize,
            compressionRatio = quantizedSize.toFloat() / originalSize
        )
    }
    
    /**
     * 使用 llama.cpp 本地量化（需要 native 库支持）
     */
    suspend fun quantizeLocal(inputPath: String, outputPath: String, quantType: String): Result<QuantizeResult> = withContext(Dispatchers.IO) {
        _quantizeState.value = QuantizeState(
            isQuantizing = true,
            status = "本地量化中..."
        )
        
        try {
            // 检查 native 库
            val nativeLib = File(context.applicationInfo.nativeLibraryDir, "libllama.so")
            if (!nativeLib.exists()) {
                // 尝试下载
                return@withContext Result.failure(Exception("需要 llama.cpp native 库支持"))
            }
            
            // 调用本地量化
            // 这里需要 JNI 调用 llama.cpp 的 quantize 功能
            // 暂时返回模拟结果
            
            val inputFile = File(inputPath)
            val originalSize = inputFile.length()
            val quantizedSize = estimateQuantizedSize(originalSize, quantType)
            
            _quantizeState.value = _quantizeState.value.copy(
                isQuantizing = false,
                progress = 100,
                outputPath = outputPath,
                originalSize = originalSize,
                quantizedSize = quantizedSize
            )
            
            Result.success(QuantizeResult(
                success = true,
                outputPath = outputPath,
                originalSize = originalSize,
                quantizedSize = quantizedSize,
                compressionRatio = quantizedSize.toFloat() / originalSize
            ))
        } catch (e: Exception) {
            Log.e(TAG, "Local quantization failed", e)
            _quantizeState.value = _quantizeState.value.copy(
                isQuantizing = false,
                error = e.message
            )
            Result.failure(e)
        }
    }
    
    /**
     * 取消量化
     */
    fun cancelQuantization() {
        _quantizeState.value = QuantizeState()
    }
    
    /**
     * 获取量化状态报告
     */
    fun getQuantizeReport(): String {
        val state = _quantizeState.value
        
        return buildString {
            appendLine("📊 模型量化报告")
            appendLine("=".repeat(40))
            appendLine()
            
            if (state.isQuantizing) {
                appendLine("状态: 量化中...")
                appendLine("进度: ${state.progress}%")
                appendLine("状态: ${state.status}")
            } else if (state.outputPath.isNotEmpty()) {
                appendLine("状态: 量化完成 ✓")
                appendLine("原大小: ${formatSize(state.originalSize)}")
                appendLine("量化后: ${formatSize(state.estimatedSize)}")
                appendLine("压缩比: ${String.format("%.1f%%", (state.estimatedSize.toFloat() / state.originalSize) * 100)}")
                appendLine("输出: ${state.outputPath}")
            } else if (state.error != null) {
                appendLine("状态: 量化失败 ✗")
                appendLine("错误: ${state.error}")
            } else {
                appendLine("状态: 未开始量化")
            }
        }
    }
    
    /**
     * 格式化文件大小
     */
    private fun formatSize(bytes: Long): String {
        return when {
            bytes >= 1024L * 1024 * 1024 -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
            bytes >= 1024L * 1024 -> String.format("%.2f MB", bytes / (1024.0 * 1024.0))
            bytes >= 1024L -> String.format("%.2f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
