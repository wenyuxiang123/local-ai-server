package com.localai.server.network

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 自动更新服务
 * 检查应用新版本，下载更新包并提示安装
 */
@Singleton
class AutoUpdateService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "AutoUpdateService"
        
        // 更新检查地址（可配置为 GitHub API、FIR 等）
        private const val UPDATE_CHECK_URL = "https://api.github.com/repos/your-repo/local-ai-server/releases/latest"
        
        // 备用检查地址
        private const val FIR_IM_URL = "https://api.fir.im/apps/latest/"
        private const val APP_ID = "your_app_id"
        
        // 下载状态
        const val STATUS_IDLE = 0
        const val STATUS_CHECKING = 1
        const val STATUS_DOWNLOADING = 2
        const val STATUS_DOWNLOADED = 3
        const val STATUS_FAILED = 4
    }
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
    
    private val updateDir: File by lazy {
        File(context.filesDir, "updates").apply { mkdirs() }
    }
    
    private val _updateState = MutableStateFlow(UpdateState())
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()
    
    /**
     * 更新状态数据类
     */
    data class UpdateState(
        val status: Int = STATUS_IDLE,
        val currentVersion: String = "",
        val latestVersion: String = "",
        val releaseNotes: String = "",
        val downloadUrl: String = "",
        val downloadedFile: File? = null,
        val progress: Int = 0,
        val error: String? = null
    ) {
        val hasUpdate: Boolean
            get() {
                if (latestVersion.isEmpty() || currentVersion.isEmpty()) return false
                val parts1 = latestVersion.split(".")
                val parts2 = currentVersion.split(".")
                val maxLen = maxOf(parts1.size, parts2.size)
                for (i in 0 until maxLen) {
                    val num1 = parts1.getOrElse(i) { "0" }.toIntOrNull() ?: 0
                    val num2 = parts2.getOrElse(i) { "0" }.toIntOrNull() ?: 0
                    if (num1 > num2) return true
                    if (num1 < num2) return false
                }
                return false
            }
        
        val isNewerVersion: Boolean
            get() = latestVersion.isNotEmpty() && latestVersion != currentVersion
    }
    
    /**
     * 比较版本号
     * @return 正数表示 v1 > v2, 负数表示 v1 < v2, 0 表示相等
     */
    private fun compareVersions(v1: String, v2: String): Int {
        if (v1.isEmpty() || v2.isEmpty()) return 0
        val parts1 = v1.split(".")
        val parts2 = v2.split(".")
        val maxLen = maxOf(parts1.size, parts2.size)
        for (i in 0 until maxLen) {
            val num1 = parts1.getOrElse(i) { "0" }.toIntOrNull() ?: 0
            val num2 = parts2.getOrElse(i) { "0" }.toIntOrNull() ?: 0
            if (num1 != num2) return num1.compareTo(num2)
        }
        return 0
    }
    
    /**
     * 版本信息数据类
     */
    data class VersionInfo(
        val version: String,
        val versionCode: Int,
        val releaseNotes: String,
        val downloadUrl: String,
        val fileSize: Long,
        val releaseDate: String
    )
    
    init {
        _updateState.value = _updateState.value.copy(
            currentVersion = getCurrentVersion()
        )
    }
    
    /**
     * 获取当前版本
     */
    fun getCurrentVersion(): String {
        return try {
            val packageInfo: PackageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "1.0.0"
        } catch (e: PackageManager.NameNotFoundException) {
            "1.0.0"
        }
    }
    
    /**
     * 获取当前版本号
     */
    fun getCurrentVersionCode(): Int {
        return try {
            val packageInfo: PackageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                packageInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode
            }
        } catch (e: PackageManager.NameNotFoundException) {
            1
        }
    }
    
    /**
     * 检查更新
     */
    suspend fun checkForUpdate(): Result<VersionInfo?> = withContext(Dispatchers.IO) {
        _updateState.value = _updateState.value.copy(status = STATUS_CHECKING, error = null)
        
        try {
            // 尝试 GitHub API
            val versionInfo = checkGitHubReleases()
            
            if (versionInfo != null) {
                _updateState.value = _updateState.value.copy(
                    status = STATUS_IDLE,
                    latestVersion = versionInfo.version,
                    releaseNotes = versionInfo.releaseNotes,
                    downloadUrl = versionInfo.downloadUrl
                )
                return@withContext Result.success(versionInfo)
            }
            
            // 尝试 FIR.im
            val firVersion = checkFirIm()
            
            if (firVersion != null) {
                _updateState.value = _updateState.value.copy(
                    status = STATUS_IDLE,
                    latestVersion = firVersion.version,
                    releaseNotes = firVersion.releaseNotes,
                    downloadUrl = firVersion.downloadUrl
                )
                return@withContext Result.success(firVersion)
            }
            
            _updateState.value = _updateState.value.copy(
                status = STATUS_IDLE,
                error = "已是最新版本"
            )
            Result.success(null)
        } catch (e: Exception) {
            Log.e(TAG, "Check update failed", e)
            _updateState.value = _updateState.value.copy(
                status = STATUS_FAILED,
                error = "检查更新失败: ${e.message}"
            )
            Result.failure(e)
        }
    }
    
    /**
     * 检查 GitHub Releases
     */
    private suspend fun checkGitHubReleases(): VersionInfo? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(UPDATE_CHECK_URL)
                .header("Accept", "application/vnd.github.v3+json")
                .build()
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext null
                }
                
                val responseBody = response.body?.string() ?: return@withContext null
                val json = JSONObject(responseBody)
                
                val tagName = json.optString("tag_name", "").removePrefix("v")
                val name = json.optString("name", "")
                val releaseNotes = json.optString("body", "")
                
                // 查找 APK 下载链接
                val assets = json.optJSONArray("assets") ?: return@withContext null
                var downloadUrl = ""
                var fileSize = 0L
                
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.optString("name", "")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        downloadUrl = asset.optString("browser_download_url", "")
                        fileSize = asset.optLong("size", 0)
                        break
                    }
                }
                
                if (downloadUrl.isEmpty()) {
                    return@withContext null
                }
                
                VersionInfo(
                    version = tagName,
                    versionCode = parseVersionCode(tagName),
                    releaseNotes = releaseNotes,
                    downloadUrl = downloadUrl,
                    fileSize = fileSize,
                    releaseDate = json.optString("published_at", "")
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "GitHub check failed", e)
            null
        }
    }
    
    /**
     * 检查 FIR.im
     */
    private suspend fun checkFirIm(): VersionInfo? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$FIR_IM_URL$APP_ID")
                .build()
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext null
                }
                
                val body = response.body?.string() ?: return@withContext null
                val json = JSONObject(body)
                
                val version = json.optString("version", "")
                val versionShort = json.optString("versionShort", "")
                val build = json.optString("build", "")
                val changelog = json.optString("changelog", "")
                
                val binary = json.optJSONObject("binary") ?: return@withContext null
                val downloadUrl = binary.optString("install_url", "")
                val fileSize = binary.optLong("fsize", 0)
                
                VersionInfo(
                    version = "$version ($build)",
                    versionCode = build.toIntOrNull() ?: 1,
                    releaseNotes = changelog,
                    downloadUrl = downloadUrl,
                    fileSize = fileSize,
                    releaseDate = ""
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "FIR.im check failed", e)
            null
        }
    }
    
    /**
     * 下载更新包
     */
    suspend fun downloadUpdate(url: String): Result<File> = withContext(Dispatchers.IO) {
        _updateState.value = _updateState.value.copy(status = STATUS_DOWNLOADING, progress = 0)
        
        try {
            val fileName = url.substringAfterLast("/").substringBefore("?")
            val targetFile = File(updateDir, fileName)
            
            val request = Request.Builder().url(url).build()
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("下载失败: HTTP ${response.code}"))
                }
                
                val totalBytes = response.body?.contentLength() ?: -1L
                var downloaded = 0L
                
                response.body?.byteStream()?.use { input ->
                    targetFile.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            downloaded += read
                            
                            val progress = if (totalBytes > 0) ((downloaded * 100) / totalBytes).toInt() else 0
                            _updateState.value = _updateState.value.copy(progress = progress)
                        }
                    }
                }
            }
            
            _updateState.value = _updateState.value.copy(
                status = STATUS_DOWNLOADED,
                downloadedFile = targetFile
            )
            
            Result.success(targetFile)
        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            _updateState.value = _updateState.value.copy(
                status = STATUS_FAILED,
                error = "下载失败: ${e.message}"
            )
            Result.failure(e)
        }
    }
    
    /**
     * 安装更新
     */
    fun installUpdate(file: File): Boolean {
        return try {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                setDataAndType(
                    androidx.core.content.FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file
                    ),
                    "application/vnd.android.package-archive"
                )
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Install failed", e)
            false
        }
    }
    
    /**
     * 解析版本号
     */
    private fun parseVersionCode(version: String): Int {
        return try {
            version.split(".").take(3).joinToString("").toInt()
        } catch (e: Exception) {
            1
        }
    }
    
    /**
     * 清理旧版本更新包
     */
    fun cleanupOldUpdates() {
        updateDir.listFiles()?.forEach { file ->
            // 保留最近3个更新包
            val files = updateDir.listFiles()?.sortedByDescending { it.lastModified() }
            files?.drop(3)?.forEach { it.delete() }
        }
    }
}
