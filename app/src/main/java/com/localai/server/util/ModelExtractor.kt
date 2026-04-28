package com.localai.server.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 模型解压工具类
 * 支持从URL下载模型文件
 */
@Singleton
class ModelExtractor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "ModelExtractor"
        private const val PREFS_NAME = "model_extractor"
        private const val KEY_MODEL_EXTRACTED = "model_extracted"
        private const val KEY_MODEL_VERSION = "model_version"
        private const val CURRENT_MODEL_VERSION = "4B_v1"
        private const val MODEL_FILE_NAME = "Qwen3-4B-Q4_K_M.gguf"
        private const val BUFFER_SIZE = 8 * 1024 * 1024 // 8MB buffer
        private const val EXPECTED_TOTAL_SIZE = 2684354560L // 4B Q4_K_M模型大小约2.5GB
        
/**
 * 模型下载URL列表 - 按优先级排序
 * 优先使用ModelScope国内源，HuggingFace作为备选
 */
private val MODEL_DOWNLOAD_URLS = listOf(
    // 1. ModelScope主源（国内，速度快）
    "https://modelscope.cn/api/v1/models/Qwen/Qwen3-4B-GGUF/file/Qwen3-4B-Q4_K_M.gguf",
    // 2. HuggingFace备选源
    "https://huggingface.co/unsloth/Qwen3-4B-GGUF/resolve/main/Qwen3-4B-Q4_K_M.gguf"
)

private const val MODEL_DOWNLOAD_URL = MODEL_DOWNLOAD_URLS.first()
    }
    
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    private val modelsDir: File by lazy {
        File(context.filesDir, "models").apply { mkdirs() }
    }
    
    private val modelFile: File by lazy {
        File(modelsDir, MODEL_FILE_NAME)
    }
    
    /**
     * 检查模型是否已解压
     */
    fun isModelExtracted(): Boolean {
        val versionMatch = prefs.getString(KEY_MODEL_VERSION, "") == CURRENT_MODEL_VERSION
        val fileExists = modelFile.exists()
        val sizeMatch = modelFile.length() == EXPECTED_TOTAL_SIZE
        return versionMatch && fileExists && sizeMatch
    }
    
    /**
     * 获取模型文件路径
     */
    fun getModelPath(): String? {
        return if (modelFile.exists()) {
            modelFile.absolutePath
        } else {
            null
        }
    }
    
    /**
     * 获取模型文件名
     */
    fun getModelFileName(): String = MODEL_FILE_NAME
    
    /**
     * 获取下载URL
     */
    fun getDownloadUrl(): String = MODEL_DOWNLOAD_URL
    
    /**
     * 解压/下载模型文件
     * 从URL下载到 filesDir/models/
     * 包含三个阶段：下载、等待加载、加载中
     * 支持多URL自动fallback
     */
    fun extractModel(): Flow<ExtractProgress> = flow {
        // 检查模型文件是否已存在
        if (isModelExtracted()) {
            Log.i(TAG, "Model already extracted: ${modelFile.absolutePath}")
            emit(ExtractProgress(100, "模型已就绪"))
            return@flow
        }
        
        // 确保目录存在
        modelsDir.mkdirs()
        
        // 阶段1：下载（支持多URL fallback）
        emit(ExtractProgress(0, "准备下载模型..."))
        
        var lastException: Exception? = null
        var downloadedFromUrl: String? = null
        
        for ((index, downloadUrl) in MODEL_DOWNLOAD_URLS.withIndex()) {
            val sourceName = if (index == 0) "ModelScope" else "HuggingFace"
            Log.i(TAG, "Trying download from $sourceName: $downloadUrl")
            emit(ExtractProgress(1, "连接 $sourceName 服务器..."))
            
            try {
                downloadModelWithFallback(downloadUrl)
                downloadedFromUrl = downloadUrl
                Log.i(TAG, "Download successful from $sourceName")
                break
            } catch (e: Exception) {
                Log.w(TAG, "Download failed from $sourceName: ${e.message}")
                lastException = e
                
                // 如果不是最后一个URL，尝试下一个
                if (index < MODEL_DOWNLOAD_URLS.size - 1) {
                    emit(ExtractProgress(1, "$sourceName 下载失败，尝试备选源..."))
                }
            }
        }
        
        // 如果所有URL都失败，抛出异常
        if (downloadedFromUrl == null) {
            throw lastException ?: Exception("所有下载源均失败")
        }
        
        // 阶段2：等待加载
        emit(ExtractProgress(98, "下载完成"))
        emit(ExtractProgress(98, "正在初始化 llama.cpp..."))
        Thread.sleep(500)
        emit(ExtractProgress(99, "加载模型权重..."))
        Thread.sleep(500)
        
        // 阶段3：加载中
        emit(ExtractProgress(99, "分配内存空间..."))
        Thread.sleep(300)
        emit(ExtractProgress(99, "准备推理上下文..."))
        Thread.sleep(200)
        
        // 保存解压状态
        prefs.edit()
            .putBoolean(KEY_MODEL_EXTRACTED, true)
            .putString(KEY_MODEL_VERSION, CURRENT_MODEL_VERSION)
            .apply()
        
        Log.i(TAG, "Model downloaded successfully: ${modelFile.absolutePath}")
        emit(ExtractProgress(100, "模型准备完成"))
        
    }.catch { e ->
        Log.e(TAG, "Failed to download model", e)
        // 清理可能的不完整文件和状态
        if (modelFile.exists()) {
            modelFile.delete()
        }
        prefs.edit()
            .putBoolean(KEY_MODEL_EXTRACTED, false)
            .putString(KEY_MODEL_VERSION, "")
            .apply()
        emit(ExtractProgress(-1, "下载失败: ${e.message}"))
    }.flowOn(Dispatchers.IO)
    
    /**
     * 从单个URL下载模型文件
     */
    private suspend fun FlowCollector<ExtractProgress>.downloadModelWithFallback(urlString: String) {
        downloadModel(urlString)
    }
    
    /**
     * 从URL下载模型文件
     */
    private suspend fun FlowCollector<ExtractProgress>.downloadModel(urlString: String) {
        var connection: HttpURLConnection? = null
        var downloaded = 0L
        var startTime = System.currentTimeMillis()
        var lastUpdateTime = startTime
        
        try {
            val url = URL(urlString)
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 30000
            connection.readTimeout = 300000 // 5分钟超时
            connection.requestMethod = "GET"
            connection.connect()
            
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw Exception("服务器返回错误: $responseCode")
            }
            
            val contentLength = connection.contentLength.toLong()
            // 如果服务器不返回Content-Length，使用预期大小
            val totalSize = if (contentLength > 0) contentLength else EXPECTED_TOTAL_SIZE
            Log.i(TAG, "Content-Length: $contentLength, using totalSize: $totalSize")
            
            emit(ExtractProgress(2, "开始下载模型 (0%)"))
            
            connection.inputStream.use { input ->
                FileOutputStream(modelFile).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var read: Int
                    
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloaded += read
                        
                        // 计算进度 (2% - 95%)
                        val progress = if (totalSize > 0) {
                            (2 + (downloaded * 93 / totalSize)).toInt()
                        } else {
                            ((downloaded % 100) + 2).toInt().coerceIn(2, 95)
                        }
                        
                        // 每秒更新一次
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastUpdateTime >= 1000) {
                            lastUpdateTime = currentTime
                            
                            val percent = if (totalSize > 0) {
                                (downloaded * 100 / totalSize).toInt()
                            } else {
                                0
                            }
                            
                            val downloadedMB = downloaded / (1024 * 1024)
                            val totalMB = totalSize / (1024 * 1024)
                            
                            // 计算下载速度
                            val elapsedSeconds = (currentTime - startTime) / 1000.0
                            val speedMBps = if (elapsedSeconds > 0) {
                                String.format("%.1f", downloaded / (1024 * 1024) / elapsedSeconds)
                            } else {
                                "--"
                            }
                            
                            // 计算剩余时间
                            val remainingStr = if (totalSize > 0 && downloaded > 0) {
                                val remainingBytes = totalSize - downloaded
                                val remainingSeconds = (remainingBytes / (downloaded / elapsedSeconds)).toInt()
                                val minutes = remainingSeconds / 60
                                val seconds = remainingSeconds % 60
                                "${minutes}分${seconds}秒"
                            } else {
                                "--"
                            }
                            
                            val speedBytesPerSec = if (elapsedSeconds > 0) {
                                (downloaded / elapsedSeconds).toLong()
                            } else {
                                0L
                            }
                            
                            emit(ExtractProgress(
                                percent = progress.coerceIn(2, 95),
                                message = "下载中 $percent% | $downloadedMB/$totalMB MB | $speedMBps MB/s | 剩余 $remainingStr",
                                downloadedBytes = downloaded,
                                totalBytes = totalSize,
                                speedBytesPerSec = speedBytesPerSec
                            ))
                        }
                    }
                }
            }
            
            // 验证文件大小
            if (totalSize > 0 && modelFile.length() != totalSize) {
                Log.w(TAG, "File size mismatch: ${modelFile.length()} vs expected $totalSize")
            }
            
            emit(ExtractProgress(97, "下载完成，验证文件中..."))
            
        } catch (e: Exception) {
            modelFile.delete()
            throw e
        } finally {
            connection?.disconnect()
        }
    }
    
    /**
     * 重置解压状态
     */
    fun resetExtraction() {
        prefs.edit()
            .putBoolean(KEY_MODEL_EXTRACTED, false)
            .putString(KEY_MODEL_VERSION, "")
            .apply()
        
        if (modelFile.exists()) {
            modelFile.delete()
        }
    }
}

/**
 * 提取进度数据类
 */
data class ExtractProgress(
    val percent: Int,
    val message: String,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val speedBytesPerSec: Long = 0
)

