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
 * MNN模型提取器
 * 支持从ModelScope/HuggingFace下载MNN模型到目录
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
        
        // MNN模型版本和配置
        private const val CURRENT_MODEL_VERSION = "4B_Claude_MNN_v1"
        private const val MODEL_FILE_NAME = "config.json"  // MNN模型目录以config.json为标识
        private const val BUFFER_SIZE = 8 * 1024 * 1024 // 8MB buffer
        
        // MNN模型大小校验 - MNN模型是多文件，单文件不校验总大小
        private const val EXPECTED_TOTAL_SIZE = 0L
        
        // MNN模型目录名
        const val MNN_MODEL_DIR = "Qwen3.5-4B-Claude-Distilled"
        
        // MNN模型文件列表
        private val MNN_MODEL_FILES = listOf(
            "config.json",
            "llm_config.json", 
            "llm.mnn",
            "llm.mnn.weight",
            "tokenizer.txt"
        )
        
        // 主下载URL - ModelScope
        private const val MODEL_DOWNLOAD_URL = "https://modelscope.cn/taobao-mnn/Qwen3.5-4B-Claude-4.6-Opus-Reasoning-Distilled-MNN/resolve/main"
        
        // 下载URL列表（按优先级）
        private val MODEL_DOWNLOAD_URLS = listOf(
            // 1. ModelScope主源
            "https://modelscope.cn/taobao-mnn/Qwen3.5-4B-Claude-4.6-Opus-Reasoning-Distilled-MNN/resolve/main",
            // 2. HuggingFace镜像
            "https://hf-mirror.com/taobao-mnn/Qwen3.5-4B-Claude-4.6-Opus-Reasoning-Distilled-MNN/resolve/main"
        )
    }
    
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    private val modelsDir: File by lazy {
        File(context.filesDir, "models").apply { mkdirs() }
    }
    
    // MNN模型目录
    private val modelDir: File by lazy {
        File(modelsDir, MNN_MODEL_DIR).apply { mkdirs() }
    }
    
    // config.json文件路径
    private val modelFile: File by lazy {
        File(modelDir, MODEL_FILE_NAME)
    }
    
    /**
     * 检查MNN模型是否已完整下载
     */
    fun isModelExtracted(): Boolean {
        val versionMatch = prefs.getString(KEY_MODEL_VERSION, "") == CURRENT_MODEL_VERSION
        val dirExists = modelDir.exists() && modelDir.isDirectory
        val configExists = modelFile.exists()
        
        // 检查所有必需文件是否存在
        val allFilesExist = MNN_MODEL_FILES.all { fileName ->
            File(modelDir, fileName).exists()
        }
        
        return versionMatch && dirExists && configExists && allFilesExist
    }
    
    /**
     * 获取MNN模型目录路径
     */
    fun getModelPath(): String? {
        return if (modelFile.exists()) {
            modelDir.absolutePath
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
     * 下载/解压MNN模型
     * 从URL下载多文件到 modelsDir/MNN_MODEL_DIR/
     * 包含三个阶段：下载、等待加载、加载中
     * 支持多URL自动fallback
     */
    fun extractModel(): Flow<ExtractProgress> = flow {
        // 检查模型是否已完整下载
        if (isModelExtracted()) {
            Log.i(TAG, "MNN model already extracted: ${modelDir.absolutePath}")
            emit(ExtractProgress(100, "模型已就绪"))
            return@flow
        }
        
        // 确保目录存在
        modelDir.mkdirs()
        
        // 阶段1：下载（支持多URL fallback）
        emit(ExtractProgress(0, "准备下载MNN模型..."))
        
        var lastException: Exception? = null
        var downloadedFromUrl: String? = null
        
        for ((index, baseUrl) in MODEL_DOWNLOAD_URLS.withIndex()) {
            val sourceName = if (index == 0) "ModelScope" else "HuggingFace"
            Log.i(TAG, "Trying download from $sourceName: $baseUrl")
            com.localai.server.util.FileLog.log("ModelExtractor", "Trying download from $sourceName: $baseUrl")
            emit(ExtractProgress(1, "连接 $sourceName 服务器..."))
            
            try {
                downloadMNNModelFiles(baseUrl)
                downloadedFromUrl = baseUrl
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
        
        // 阶段2：等待MNN初始化
        emit(ExtractProgress(98, "下载完成"))
        emit(ExtractProgress(98, "正在初始化 MNN 引擎..."))
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
        
        Log.i(TAG, "MNN model downloaded successfully: ${modelDir.absolutePath}")
        emit(ExtractProgress(100, "MNN模型准备完成"))
        
    }.catch { e ->
        Log.e(TAG, "Failed to download MNN model", e)
        // 清理可能的不完整文件和状态
        if (modelDir.exists()) {
            modelDir.deleteRecursively()
        }
        prefs.edit()
            .putBoolean(KEY_MODEL_EXTRACTED, false)
            .putString(KEY_MODEL_VERSION, "")
            .apply()
        emit(ExtractProgress(-1, "下载失败: ${e.message}"))
    }.flowOn(Dispatchers.IO)
    
    /**
     * 下载MNN模型的所有文件
     */
    private suspend fun FlowCollector<ExtractProgress>.downloadMNNModelFiles(baseUrl: String) {
        val totalFiles = MNN_MODEL_FILES.size
        var downloadedFiles = 0
        
        for (fileName in MNN_MODEL_FILES) {
            val fileUrl = "$baseUrl/$fileName"
            Log.i(TAG, "Downloading $fileName...")
            emit(ExtractProgress(
                (downloadedFiles * 95 / totalFiles).coerceAtLeast(2),
                "下载 $fileName..."
            ))
            
            downloadMNNFile(fileUrl, File(modelDir, fileName))
            downloadedFiles++
        }
    }
    
    /**
     * 从URL下载单个MNN模型文件
     */
    private suspend fun FlowCollector<ExtractProgress>.downloadMNNFile(urlString: String, targetFile: File) {
        var connection: HttpURLConnection? = null
        var downloaded = 0L
        var startTime = System.currentTimeMillis()
        var lastUpdateTime = startTime
        
        try {
            // 手动处理重定向
            var currentUrl = urlString
            var redirectCount = 0
            val maxRedirects = 5
            
            while (redirectCount < maxRedirects) {
                val url = URL(currentUrl)
                connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 30000
                connection.readTimeout = 300000 // 5分钟超时
                connection.requestMethod = "GET"
                connection.instanceFollowRedirects = false
                connection.connect()
                
                val responseCode = connection.responseCode
                com.localai.server.util.FileLog.log("ModelExtractor", "URL: $currentUrl, Response: $responseCode")
                
                if (responseCode == HttpURLConnection.HTTP_MOVED_PERM || 
                    responseCode == HttpURLConnection.HTTP_MOVED_TEMP || 
                    responseCode == HttpURLConnection.HTTP_SEE_OTHER ||
                    responseCode == 307 || responseCode == 308) {
                    val location = connection.getHeaderField("Location")
                    com.localai.server.util.FileLog.log("ModelExtractor", "Redirect to: $location")
                    connection.disconnect()
                    if (location.isNullOrEmpty()) {
                        throw Exception("重定向但未提供Location头")
                    }
                    currentUrl = location
                    redirectCount++
                    continue
                }
                
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    throw Exception("服务器返回错误: $responseCode")
                }
                break
            }
            
            com.localai.server.util.FileLog.log("ModelExtractor", "Final URL: $currentUrl")
            
            val finalConnection = connection ?: throw Exception("连接失败")
            val contentLength = finalConnection.contentLength.toLong()
            val totalSize = if (contentLength > 0) contentLength else EXPECTED_TOTAL_SIZE
            Log.i(TAG, "Content-Length: $contentLength, file: ${targetFile.name}")
            
            finalConnection.inputStream.use { input ->
                FileOutputStream(targetFile).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var read: Int
                    
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloaded += read
                        
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastUpdateTime >= 1000) {
                            lastUpdateTime = currentTime
                            
                            val percent = if (totalSize > 0) {
                                (downloaded * 100 / totalSize).toInt()
                            } else {
                                0
                            }
                            
                            val downloadedMB = downloaded / (1024 * 1024)
                            val totalMB = if (totalSize > 0) totalSize / (1024 * 1024) else 0
                            
                            val speedMBps = String.format("%.1f", downloaded / (1024 * 1024) / ((currentTime - startTime) / 1000.0).coerceAtLeast(1.0))
                            
                            val speedBytesPerSec = if (currentTime - startTime > 0) {
                                (downloaded * 1000 / (currentTime - startTime)).toLong()
                            } else {
                                0L
                            }
                            
                            emit(ExtractProgress(
                                percent = percent.coerceIn(2, 95),
                                message = "下载 ${targetFile.name} $percent% | $downloadedMB/${if (totalMB > 0) totalMB else "--"} MB | $speedMBps MB/s"
                            ))
                        }
                    }
                }
            }
            
            Log.i(TAG, "Downloaded ${targetFile.name}: ${targetFile.length()} bytes")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download ${targetFile.name}", e)
            targetFile.delete()
            throw e
        } finally {
            connection?.disconnect()
        }
    }
}

/**
 * 下载进度数据类
 */
data class ExtractProgress(
    val percent: Int,
    val message: String
)
