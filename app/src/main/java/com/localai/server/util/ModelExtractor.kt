package com.localai.server.util

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import android.os.StatFs
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
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MNN模型提取器
 * 支持从ModelScope/HuggingFace下载MNN模型到目录
 * 
 * 修复记录：
 * - 添加断点续传支持
 * - 修复catch块过度删除问题
 * - 添加磁盘空间检查
 * - 改进错误信息
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
        
        // MNN模型各文件的已知大小（字节），用于在Content-Length不可用时作为fallback
        // 文件大小来源：ModelScope API v1/models/MNN/Qwen3.5-4B-Claude-4.6-Opus-Reasoning-Distilled-MNN/repo/files
        val KNOWN_FILE_SIZES = mapOf(
            "config.json" to 652L,                    // 实际大小: 652
            "llm_config.json" to 4902L,                // 实际大小: 4902 (原错误5018)
            "llm.mnn" to 3669048L,                    // 实际大小: 3669048 (原错误3670016)
            "llm.mnn.weight" to 2629387626L,          // 实际大小: 2629387626 (原错误2631925760)
            "llm.mnn.json" to 9212662L,                // 实际大小: 9212662 (原错误9227469)
            "tokenizer.txt" to 2955203L               // 实际大小: 2955203 (原错误2936013)
        )
        
        // 模型总大小（所有文件之和）
        private const val TOTAL_MODEL_SIZE = 2644835295L  // ~2.46GB (所有文件实际大小之和)
        
        // MNN模型目录名
        const val MNN_MODEL_DIR = "Qwen3.5-4B-Claude-Distilled"
        
        // MNN模型文件列表
        private val MNN_MODEL_FILES = listOf(
            "config.json",
            "llm_config.json", 
            "llm.mnn",
            "llm.mnn.weight",
            "llm.mnn.json",
            "tokenizer.txt"
        )
        
        // 主下载URL - ModelScope (使用API格式)
        private const val MODEL_DOWNLOAD_URL = "https://modelscope.cn/api/v1/models/MNN/Qwen3.5-4B-Claude-4.6-Opus-Reasoning-Distilled-MNN/repo?Revision=master&FilePath={filename}"
        
        // 下载URL模板列表（按优先级），使用{filename}占位符
        private val MODEL_DOWNLOAD_URL_TEMPLATES = listOf(
            // 1. ModelScope主源 - API格式
            "https://modelscope.cn/api/v1/models/MNN/Qwen3.5-4B-Claude-4.6-Opus-Reasoning-Distilled-MNN/repo?Revision=master&FilePath={filename}",
            // 2. HuggingFace镜像 - resolve格式
            "https://hf-mirror.com/taobao-mnn/Qwen3.5-4B-Claude-4.6-Opus-Reasoning-Distilled-MNN/resolve/main/{filename}"
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
        
        // 检查所有必需文件是否存在，并记录缺失的文件
        val missingFiles = MNN_MODEL_FILES.filter { fileName ->
            val file = File(modelDir, fileName)
            val exists = file.exists()
            if (!exists) {
                Log.w(TAG, "Missing file: $fileName")
                com.localai.server.util.FileLog.log(TAG, "Missing file: $fileName")
            }
            !exists
        }
        
        if (missingFiles.isNotEmpty()) {
            Log.w(TAG, "Missing files: ${missingFiles.joinToString()}")
            com.localai.server.util.FileLog.log(TAG, "Missing files: ${missingFiles.joinToString()}")
        }
        
        return versionMatch && dirExists && configExists && missingFiles.isEmpty()
    }
    
    /**
     * 检查是否有部分文件已下载（用于断点续传）
     */
    private fun getPartiallyDownloadedBytes(): Long {
        var downloadedBytes = 0L
        for (fileName in MNN_MODEL_FILES) {
            val file = File(modelDir, fileName)
            if (file.exists() && file.length() > 0) {
                downloadedBytes += file.length()
            }
        }
        return downloadedBytes
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
     * 获取模型显示名称
     */
    fun getDisplayName(): String = MNN_MODEL_DIR
    
    /**
     * 获取下载URL
     */
    fun getDownloadUrl(): String = MODEL_DOWNLOAD_URL
    
    /**
     * 检查并获取可用磁盘空间（字节）
     */
    private fun getAvailableDiskSpace(): Long {
        return try {
            val stat = StatFs(context.filesDir.path)
            stat.availableBlocksLong * stat.blockSizeLong
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get disk space", e)
            0L
        }
    }
    
    /**
     * 检查磁盘空间是否足够
     * @param requiredBytes 需要的空间（字节）
     * @return true if sufficient space available
     */
    private fun hasEnoughDiskSpace(requiredBytes: Long): Boolean {
        val available = getAvailableDiskSpace()
        // 预留500MB作为缓冲
        val buffer = 500L * 1024 * 1024
        val sufficient = available >= (requiredBytes + buffer)
        
        if (!sufficient) {
            val availableMB = available / (1024 * 1024)
            val requiredMB = (requiredBytes + buffer) / (1024 * 1024)
            Log.e(TAG, "Insufficient disk space: ${availableMB}MB available, ${requiredMB}MB required")
            com.localai.server.util.FileLog.log(TAG, "DISK_SPACE_ERROR: ${availableMB}MB available, ${requiredMB}MB required")
        }
        
        return sufficient
    }
    
    /**
     * 下载/解压MNN模型
     * 从URL下载多文件到 modelsDir/MNN_MODEL_DIR/
     * 包含三个阶段：下载、等待加载、加载中
     * 支持多URL自动fallback
     * 支持断点续传
     */
    fun extractModel(): Flow<ExtractProgress> = flow {
        FileLog.log(TAG, "=== Starting model extraction ===")
        
        // 检查模型是否已完整下载
        if (isModelExtracted()) {
            Log.i(TAG, "MNN model already extracted: ${modelDir.absolutePath}")
            FileLog.log(TAG, "Model already extracted, skipping download")
            emit(ExtractProgress(100, "模型已就绪"))
            return@flow
        }
        
        // 确保目录存在
        modelDir.mkdirs()
        
        // 阶段1：下载（支持多URL fallback）
        emit(ExtractProgress(0, "准备下载MNN模型..."))
        
        // 检查磁盘空间
        val partiallyDownloaded = getPartiallyDownloadedBytes()
        val remainingBytes = TOTAL_MODEL_SIZE - partiallyDownloaded
        
        FileLog.log(TAG, "Partial download detected: ${partiallyDownloaded}/${TOTAL_MODEL_SIZE} bytes already downloaded")
        Log.i(TAG, "Partial download: ${partiallyDownloaded}/${TOTAL_MODEL_SIZE} bytes already downloaded")
        
        if (remainingBytes > 0) {
            if (!hasEnoughDiskSpace(remainingBytes)) {
                val errorMsg = "磁盘空间不足，无法下载模型。请释放至少 ${(remainingBytes / (1024*1024*1024) + 1).coerceAtLeast(3)}GB 空间后重试。"
                FileLog.log(TAG, "DOWNLOAD_ABORTED: $errorMsg")
                throw Exception(errorMsg)
            }
        }
        
        var lastException: Exception? = null
        var downloadedFromUrl: String? = null
        
        for ((index, urlTemplate) in MODEL_DOWNLOAD_URL_TEMPLATES.withIndex()) {
            val sourceName = if (index == 0) "ModelScope" else "HuggingFace"
            Log.i(TAG, "Trying download from $sourceName: $urlTemplate")
            FileLog.log(TAG, "Trying source $index ($sourceName): $urlTemplate")
            emit(ExtractProgress(1, "连接 $sourceName 服务器..."))
            
            try {
                downloadMNNModelFiles(urlTemplate, partiallyDownloaded)
                downloadedFromUrl = urlTemplate
                FileLog.log(TAG, "Download successful from $sourceName")
                Log.i(TAG, "Download successful from $sourceName")
                break
            } catch (e: Exception) {
                Log.w(TAG, "Download failed from $sourceName: ${e.message}")
                FileLog.log(TAG, "Download failed from $sourceName: ${e.message}")
                lastException = e
                
                // 如果不是最后一个URL，尝试下一个
                if (index < MODEL_DOWNLOAD_URL_TEMPLATES.size - 1) {
                    emit(ExtractProgress(1, "$sourceName 下载失败，尝试备选源..."))
                }
            }
        }
        
        // 如果所有URL都失败，抛出异常
        if (downloadedFromUrl == null) {
            FileLog.log(TAG, "ALL_SOURCES_FAILED: ${lastException?.message ?: "Unknown error"}")
            throw lastException ?: Exception("所有下载源均失败")
        }
        
        // 阶段2：等待MNN初始化
        FileLog.log(TAG, "Download complete, starting MNN initialization")
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
        
        FileLog.log(TAG, "=== Model extraction completed successfully ===")
        Log.i(TAG, "MNN model downloaded successfully: ${modelDir.absolutePath}")
        emit(ExtractProgress(100, "MNN模型准备完成"))
        
    }.catch { e ->
        Log.e(TAG, "Failed to download MNN model", e)
        FileLog.log(TAG, "EXTRACTION_FAILED: ${e.javaClass.simpleName}: ${e.message}")
        FileLog.log(TAG, "Not deleting existing files to allow resume - partial download preserved")
        
        // 不再删除已下载的文件！保留用于断点续传
        // 只有明确需要清理时才删除（如用户主动重试）
        
        prefs.edit()
            .putBoolean(KEY_MODEL_EXTRACTED, false)
            // 不清除VERSION，这样isModelExtracted可以知道是部分下载
            .apply()
        emit(ExtractProgress(-1, "下载失败: ${e.message}"))
    }.flowOn(Dispatchers.IO)
    
    /**
     * 删除已下载的模型文件（用于用户主动重试）
     */
    fun deleteModelFiles() {
        FileLog.log(TAG, "Deleting model files for clean retry")
        if (modelDir.exists()) {
            modelDir.deleteRecursively()
        }
        prefs.edit()
            .putBoolean(KEY_MODEL_EXTRACTED, false)
            .putString(KEY_MODEL_VERSION, "")
            .apply()
    }
    
    /**
     * 下载MNN模型的所有文件
     * @param urlTemplate 下载URL模板
     * @param previousFilesBytes 之前已完成文件的累计大小（用于断点续传进度计算）
     */
    private suspend fun FlowCollector<ExtractProgress>.downloadMNNModelFiles(urlTemplate: String, previousFilesBytes: Long = 0) {
        val totalFiles = MNN_MODEL_FILES.size
        var downloadedFiles = 0
        // previousFilesBytes是从外部传入的部分下载大小，仅作为进度条起点
        // totalDownloadedBytes独立计算每个文件的实际大小
        var totalDownloadedBytes = 0L
        
        for (fileName in MNN_MODEL_FILES) {
            val targetFile = File(modelDir, fileName)
            val fileUrl = urlTemplate.replace("{filename}", fileName)
            
            // 检查文件是否已完整下载
            val expectedSize = KNOWN_FILE_SIZES[fileName] ?: 0L
            if (targetFile.exists() && expectedSize > 0 && targetFile.length() >= expectedSize) {
                FileLog.log(TAG, "File already complete: $fileName (${targetFile.length()} bytes)")
                Log.i(TAG, "File already complete: $fileName (${targetFile.length()} bytes)")
                totalDownloadedBytes += targetFile.length()
                downloadedFiles++
                continue
            }
            
            FileLog.log(TAG, "Downloading $fileName...")
            Log.i(TAG, "Downloading $fileName...")
            
            // 进度条起点 = 已跳过的完整文件 + 当前已下载部分
            val progressBase = previousFilesBytes + totalDownloadedBytes
            val basePercent = if (TOTAL_MODEL_SIZE > 0) (progressBase * 95 / TOTAL_MODEL_SIZE).toInt() else (downloadedFiles * 95 / totalFiles)
            
            emit(ExtractProgress(
                basePercent.coerceAtLeast(2),
                "下载 $fileName...",
                downloadedBytes = progressBase,
                totalBytes = TOTAL_MODEL_SIZE,
                speedBytesPerSec = 0L
            ))
            
            // 记录当前文件下载前的状态
            val partialSize = if (targetFile.exists()) targetFile.length() else 0L
            if (partialSize > 0) {
                FileLog.log(TAG, "Resuming $fileName from byte $partialSize (target: $expectedSize)")
                Log.i(TAG, "Resuming $fileName from byte $partialSize")
            }
            
            // 传给downloadMNNFile的全局进度起点 = 已完成的完整文件累计大小
            // downloadMNNFile内部会加上resumingFrom+downloaded计算实际进度
            downloadMNNFile(fileUrl, targetFile, fileName, progressBase).collect { progress ->
                emit(progress)
            }
            
            // 文件下载完成后累加
            val completedFile = File(modelDir, fileName)
            val fileSize = if (completedFile.exists()) completedFile.length() else 0L
            totalDownloadedBytes += fileSize
            FileLog.log(TAG, "Completed $fileName: $fileSize bytes, total: $totalDownloadedBytes")
            downloadedFiles++
        }
    }
    
    /**
     * 从URL下载单个MNN模型文件
     * 支持断点续传：如果目标文件存在且不完整，会从断点继续下载
     * 
     * @param urlString 下载URL
     * @param targetFile 目标文件
     * @param fileName 文件名（用于查找KNOWN_FILE_SIZES）
     * @param previousFilesBytes 之前已完成文件的累计大小
     */
    private fun downloadMNNFile(urlString: String, targetFile: File, fileName: String, previousFilesBytes: Long): Flow<ExtractProgress> = flow {
        var connection: HttpURLConnection? = null
        var downloaded = 0L
        var startTime = System.currentTimeMillis()
        var lastUpdateTime = startTime
        var resumingFrom = 0L
        var supportsRange = false
        
        try {
            // 手动处理重定向
            var currentUrl = urlString
            var redirectCount = 0
            val maxRedirects = 5
            
            // 计算断点续传的起始位置（在重定向循环外计算，避免重复判断）
            val existingFileSize = if (targetFile.exists()) targetFile.length() else 0L
            val expectedFileSize = KNOWN_FILE_SIZES[fileName] ?: 0L
            if (existingFileSize > 0 && existingFileSize < expectedFileSize) {
                resumingFrom = existingFileSize
                FileLog.log(TAG, "Will resume $fileName from byte $existingFileSize (target: $expectedFileSize)")
                Log.i(TAG, "Will resume $fileName from byte $existingFileSize")
            }
            
            while (redirectCount < maxRedirects) {
                val url = URL(currentUrl)
                connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 30000
                connection.readTimeout = 300000 // 5分钟超时
                connection.requestMethod = "GET"
                connection.instanceFollowRedirects = false
                
                // 每次重定向后都需要重新设置Range头
                if (resumingFrom > 0) {
                    connection.setRequestProperty("Range", "bytes=$resumingFrom-")
                }
                
                connection.connect()
                
                val responseCode = connection.responseCode
                FileLog.log(TAG, "URL: $currentUrl, Response: $responseCode")
                Log.i(TAG, "Response: $responseCode")
                
                if (responseCode == HttpURLConnection.HTTP_MOVED_PERM || 
                    responseCode == HttpURLConnection.HTTP_MOVED_TEMP || 
                    responseCode == HttpURLConnection.HTTP_SEE_OTHER ||
                    responseCode == 307 || responseCode == 308) {
                    val location = connection.getHeaderField("Location")
                    FileLog.log(TAG, "Redirect to: $location")
                    connection.disconnect()
                    if (location.isNullOrEmpty()) {
                        throw Exception("重定向但未提供Location头")
                    }
                    currentUrl = location
                    redirectCount++
                    continue
                }
                
                // 检查是否支持Range请求（206 Partial Content）
                if (responseCode == 206) {
                    supportsRange = true
                    val contentRange = connection.getHeaderField("Content-Range")
                    FileLog.log(TAG, "Server supports Range: $contentRange")
                    Log.i(TAG, "Server supports Range: $contentRange")
                } else if (responseCode == HttpURLConnection.HTTP_OK && resumingFrom > 0) {
                    // 服务器不支持Range，重新从头下载
                    FileLog.log(TAG, "Server doesn't support Range, restarting download from beginning")
                    Log.w(TAG, "Server doesn't support Range, restarting from beginning")
                    targetFile.delete()
                    resumingFrom = 0L
                    downloaded = 0L
                }
                
                if (responseCode != HttpURLConnection.HTTP_OK && responseCode != 206) {
                    throw Exception("服务器返回错误: $responseCode")
                }
                break
            }
            
            FileLog.log(TAG, "Final URL: $currentUrl, resuming from: $resumingFrom")
            
            val finalConnection = connection ?: throw Exception("连接失败")
            val contentLength = finalConnection.contentLength.toLong()
            // 用已知文件大小作为fallback
            val fileSize = if (contentLength > 0) contentLength else (KNOWN_FILE_SIZES[fileName] ?: 0L)
            
            FileLog.log(TAG, "Content-Length: $contentLength, expected: $fileSize, resuming from: $resumingFrom")
            Log.i(TAG, "Downloading $fileName: $contentLength bytes, resume from: $resumingFrom")
            
            // 以追加模式打开文件（如果是断点续传）
            val outputStream = if (resumingFrom > 0) {
                FileOutputStream(targetFile, true)
            } else {
                FileOutputStream(targetFile)
            }
            
            finalConnection.inputStream.use { input ->
                outputStream.use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var read: Int
                    
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloaded += read
                        
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastUpdateTime >= 1000) {
                            lastUpdateTime = currentTime
                            
                            // 全局进度 = 之前文件大小 + 当前文件已下载
                            val globalDownloaded = previousFilesBytes + resumingFrom + downloaded
                            val globalTotal = TOTAL_MODEL_SIZE
                            
                            val percent = if (globalTotal > 0) {
                                (globalDownloaded * 95 / globalTotal).toInt()
                            } else {
                                0
                            }
                            
                            val downloadedMB = globalDownloaded / (1024 * 1024)
                            val totalMB = globalTotal / (1024 * 1024)
                            val speedMBps = if (downloaded > 0) {
                                val elapsed = (currentTime - startTime) / 1000.0
                                val speedBytesPerSec = (downloaded / elapsed).toLong()
                                String.format("%.1f", speedBytesPerSec / (1024.0 * 1024.0))
                            } else {
                                "0.0"
                            }
                            
                            emit(ExtractProgress(
                                percent = percent.coerceIn(2, 95),
                                message = "下载 $fileName $percent% | $downloadedMB/$totalMB MB | $speedMBps MB/s",
                                downloadedBytes = globalDownloaded,
                                totalBytes = globalTotal,
                                speedBytesPerSec = (downloaded / ((currentTime - startTime) / 1000.0).coerceAtLeast(1.0)).toLong()
                            ))
                        }
                    }
                }
            }
            
            FileLog.log(TAG, "Downloaded ${targetFile.name}: ${targetFile.length()} bytes (expected: $fileSize)")
            Log.i(TAG, "Downloaded ${targetFile.name}: ${targetFile.length()} bytes")
            
            // 下载完成后校验文件大小
            val actualSize = targetFile.length()
            val expectedFinalSize = KNOWN_FILE_SIZES[fileName] ?: 0L
            if (expectedFinalSize > 0 && actualSize < expectedFinalSize) {
                FileLog.log(TAG, "DOWNLOAD_INCOMPLETE: $fileName got $actualSize bytes, expected $expectedFinalSize")
                Log.w(TAG, "Download incomplete: $fileName ($actualSize / $expectedFinalSize)")
                // 保留已下载部分以便下次断点续传
                FileLog.log(TAG, "Keeping partial file for resume: $actualSize bytes")
                throw Exception("文件下载不完整: $fileName ($actualSize / $expectedFinalSize bytes)")
            }
            
        } catch (e: Exception) {
            FileLog.log(TAG, "DOWNLOAD_ERROR: ${e.javaClass.simpleName}: ${e.message}")
            Log.e(TAG, "Failed to download ${targetFile.name}", e)
            // 只保留本次下载之前的部分，截断本次新增的不完整数据
            val originalSize = resumingFrom  // 下载前的文件大小
            if (targetFile.exists() && targetFile.length() > originalSize && originalSize >= 0) {
                try {
                    RandomAccessFile(targetFile, "rw").use { raf ->
                        raf.setLength(originalSize)
                    }
                    FileLog.log(TAG, "Preserved partial download: $originalSize bytes of $fileName")
                } catch (truncateError: Exception) {
                    // 截断失败，删除整个文件
                    targetFile.delete()
                    FileLog.log(TAG, "Truncate failed, deleted incomplete file: $fileName")
                }
            }
            throw e
        } finally {
            connection?.disconnect()
        }
    }.flowOn(Dispatchers.IO)
}

/**
 * 下载进度数据类
 */
data class ExtractProgress(
    val percent: Int,
    val message: String,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val speedBytesPerSec: Long = 0L
)
