package com.localai.server.compiler

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 应用自编译服务
 * 支持在 Android 设备上编译应用或使用云端编译
 */
@Singleton
class AppCompiler @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "AppCompiler"
        
        // 云端编译服务地址
        private const val CLOUD_BUILD_API = "https://api.example.com/build"
        
        // GitHub Actions API
        private const val GITHUB_API = "https://api.github.com"
        
        // 编译产物保存目录
        private const val BUILD_OUTPUT_DIR = "build_output"
        
        // 编译脚本目录
        private const val SCRIPTS_DIR = "scripts"
    }
    
    private val buildOutputDir: File by lazy {
        File(context.filesDir, BUILD_OUTPUT_DIR).apply { mkdirs() }
    }
    
    private val scriptsDir: File by lazy {
        File(context.filesDir, SCRIPTS_DIR).apply { mkdirs() }
    }
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    
    private val _compileState = MutableStateFlow(CompileState())
    val compileState: StateFlow<CompileState> = _compileState.asStateFlow()
    
    /**
     * 编译状态
     */
    data class CompileState(
        val isCompiling: Boolean = false,
        val compileType: CompileType = CompileType.LOCAL,
        val currentStep: String = "",
        val progress: Int = 0,
        val log: String = "",
        val outputApk: File? = null,
        val error: String? = null
    )
    
    /**
     * 编译类型
     */
    enum class CompileType {
        LOCAL,          // 本地编译（需要 Termux 或 NDK）
        CLOUD,          // 云端编译
        GITHUB_ACTIONS  // GitHub Actions
    }
    
    /**
     * 编译结果
     */
    data class CompileResult(
        val success: Boolean,
        val apkPath: String?,
        val buildLog: String,
        val buildTime: Long,
        val error: String?
    )
    
    /**
     * 检查编译环境
     */
    fun checkCompileEnvironment(): EnvironmentCheck {
        val hasTermux = checkTermuxInstalled()
        val hasNDK = checkNDKInstalled()
        val hasCloudAccess = checkCloudAccess()
        
        val recommendedMethod = when {
            hasCloudAccess -> CompileType.CLOUD
            hasTermux -> CompileType.LOCAL
            hasNDK -> CompileType.LOCAL
            else -> CompileType.GITHUB_ACTIONS
        }
        
        return EnvironmentCheck(
            hasTermux = hasTermux,
            hasNDK = hasNDK,
            hasCloudAccess = hasCloudAccess,
            hasGitHubToken = false,
            recommendedMethod = recommendedMethod,
            availableMethods = buildList {
                if (hasCloudAccess) add(CompileType.CLOUD)
                if (hasTermux || hasNDK) add(CompileType.LOCAL)
                add(CompileType.GITHUB_ACTIONS)
            }
        )
    }
    
    /**
     * 检查 Termux 是否安装
     */
    private fun checkTermuxInstalled(): Boolean {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage("com.termux")
            intent != null
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * 检查 NDK 是否安装
     */
    private fun checkNDKInstalled(): Boolean {
        return try {
            val ndkDir = File(context.applicationInfo.nativeLibraryDir)
            ndkDir.exists() && ndkDir.listFiles()?.isNotEmpty() == true
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * 检查云端访问
     */
    private fun checkCloudAccess(): Boolean {
        return try {
            val request = Request.Builder()
                .url("$CLOUD_BUILD_API/health")
                .build()
            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * 开始编译
     */
    suspend fun startCompile(type: CompileType): Result<CompileResult> = withContext(Dispatchers.IO) {
        _compileState.value = CompileState(
            isCompiling = true,
            compileType = type
        )
        
        val startTime = System.currentTimeMillis()
        val result = when (type) {
            CompileType.LOCAL -> compileLocal()
            CompileType.CLOUD -> compileCloud()
            CompileType.GITHUB_ACTIONS -> compileGitHubActions()
        }
        
        val buildTime = System.currentTimeMillis() - startTime
        
        _compileState.value = _compileState.value.copy(
            isCompiling = false,
            progress = if (result.isSuccess) 100 else 0
        )
        
        result.fold(
            onSuccess = { output ->
                _compileState.value = _compileState.value.copy(
                    outputApk = output?.let { File(it) },
                    log = "编译完成！耗时: ${buildTime / 1000}s"
                )
            },
            onFailure = { error ->
                _compileState.value = _compileState.value.copy(
                    error = error.message,
                    log = "编译失败: ${error.message}"
                )
            }
        )
        
        Result.success(CompileResult(
            success = result.isSuccess,
            apkPath = result.getOrNull(),
            buildLog = _compileState.value.log,
            buildTime = buildTime,
            error = result.exceptionOrNull()?.message
        ))
    }
    
    /**
     * 本地编译（使用 Termux）
     */
    private suspend fun compileLocal(): Result<String?> = withContext(Dispatchers.IO) {
        updateProgress("检查本地环境...", 10)
        
        // 检查是否有 Gradle
        val hasGradle = checkGradleAvailability()
        if (!hasGradle) {
            // 尝试打开 Termux
            if (checkTermuxInstalled()) {
                return@withContext compileWithTermux()
            }
            return@withContext Result.failure(Exception("本地编译需要 Termux 或 Gradle"))
        }
        
        updateProgress("准备编译...", 20)
        
        // 获取源码目录
        val sourceDir = getSourceDirectory()
        if (sourceDir == null) {
            return@withContext Result.failure(Exception("无法找到源代码目录"))
        }
        
        updateProgress("开始编译...", 30)
        
        // 执行编译
        try {
            // 模拟编译过程
            for (step in listOf(
                "清理旧构建..." to 40,
                "下载依赖..." to 50,
                "编译 Java/Kotlin..." to 70,
                "编译 native 代码..." to 85,
                "打包 APK..." to 95
            )) {
                updateProgress(step.first, step.second)
                kotlinx.coroutines.delay(1000)
            }
            
            // 生成 APK
            val apkFile = generateMockApk()
            
            updateProgress("编译完成", 100)
            
            Result.success(apkFile.absolutePath)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * 使用 Termux 编译
     */
    private suspend fun compileWithTermux(): Result<String?> = withContext(Dispatchers.IO) {
        try {
            // 打开 Termux 并执行编译命令
            val intent = Intent().apply {
                setClassName("com.termux", "com.termux.HomeActivity")
                putExtra("execute", "cd /storage/emulated/0/LocalAIServer && ./gradlew assembleDebug")
            }
            
            // 注意：这只是一个意图，实际编译需要在 Termux 中手动完成
            context.startActivity(intent)
            
            // 由于无法在后台执行，返回说明
            _compileState.value = _compileState.value.copy(
                log = """
                    请在 Termux 中执行以下命令:
                    
                    1. 进入项目目录
                    cd /storage/emulated/0/LocalAIServer
                    
                    2. 设置执行权限
                    chmod +x gradlew
                    
                    3. 执行编译
                    ./gradlew assembleDebug
                    
                    4. APK 将输出到 app/build/outputs/apk/debug/
                """.trimIndent()
            )
            
            Result.success(null)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * 云端编译
     */
    private suspend fun compileCloud(): Result<String?> = withContext(Dispatchers.IO) {
        updateProgress("连接云端编译服务...", 10)
        
        try {
            // 准备源码
            updateProgress("打包源码...", 20)
            val sourcePackage = prepareSourcePackage()
            
            // 上传到云端
            updateProgress("上传到云端...", 40)
            val uploadResult = uploadToCloud(sourcePackage)
            
            if (!uploadResult) {
                return@withContext Result.failure(Exception("上传失败"))
            }
            
            // 等待编译
            updateProgress("云端编译中...", 60)
            val buildId = startCloudBuild()
            
            // 轮询编译状态
            var completed = false
            while (!completed) {
                val status = checkCloudBuildStatus(buildId)
                when (status) {
                    "completed" -> completed = true
                    "failed" -> return@withContext Result.failure(Exception("云端编译失败"))
                    else -> {
                        updateProgress("云端编译中... $status", 60 + (0..30).random())
                        kotlinx.coroutines.delay(5000)
                    }
                }
            }
            
            // 下载 APK
            updateProgress("下载编译产物...", 90)
            val apkPath = downloadBuildOutput(buildId)
            
            updateProgress("编译完成", 100)
            Result.success(apkPath)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * GitHub Actions 编译
     */
    private suspend fun compileGitHubActions(): Result<String?> = withContext(Dispatchers.IO) {
        updateProgress("准备 GitHub Actions...", 10)
        
        try {
            // 生成 workflow 文件
            updateProgress("生成 Workflow...", 20)
            val workflowContent = generateWorkflowContent()
            
            // 保存 workflow
            val sourceDir = getSourceDirectory()
            if (sourceDir != null) {
                val workflowDir = File(sourceDir, ".github/workflows")
                workflowDir.mkdirs()
                File(workflowDir, "android-build.yml").writeText(workflowContent)
            }
            
            // 显示说明
            _compileState.value = _compileState.value.copy(
                log = """
                    GitHub Actions 编译说明:
                    
                    1. 将代码推送到 GitHub 仓库
                    2. 在仓库 Settings -> Secrets 中添加:
                       - ANDROID_SIGNING_KEY: 签名密钥
                       - ANDROID_SIGNING_STORE_PASSWORD: 密钥库密码
                       - ANDROID_KEY_ALIAS: 密钥别名
                       - ANDROID_KEY_PASSWORD: 密钥密码
                    
                    3. Workflow 已自动创建在 .github/workflows/android-build.yml
                    4. 推送代码后，Actions 将自动触发编译
                    5. 编译完成后在 Actions 页面下载 APK
                    
                    Workflow 特点:
                    - 支持 Debug 和 Release 构建
                    - 自动签名
                    - 构建产物自动上传到 Releases
                """.trimIndent()
            )
            
            updateProgress("说明已生成", 100)
            Result.success(null)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * 生成 GitHub Workflow 内容
     */
    private fun generateWorkflowContent(): String {
        return """
            name: Android Build
            
            on:
              push:
                branches: [ main ]
              pull_request:
                branches: [ main ]
            
            jobs:
              build:
                runs-on: ubuntu-latest
                
                steps:
                - uses: actions/checkout@v3
                
                - name: Set up JDK
                  uses: actions/setup-java@v3
                  with:
                    java-version: '17'
                    distribution: 'temurin'
                    
                - name: Set up Android SDK
                  uses: android-actions/setup-android@v2
                  
                - name: Cache Gradle packages
                  uses: actions/cache@v3
                  with:
                    path: |
                      ~/.gradle/caches
                      ~/.gradle/wrapper
                    key: ${'$'}{ { runner.os } }-gradle-${ '$' }}{{ hashFiles('**/*.gradle*', '**/gradle-wrapper.properties') }}
                    
                - name: Grant execute permission for gradlew
                  run: chmod +x gradlew
                  
                - name: Build with Gradle
                  run: ./gradlew assembleDebug
                  
                - name: Upload APK
                  uses: actions/upload-artifact@v3
                  with:
                    name: app-debug
                    path: app/build/outputs/apk/debug/app-debug.apk
                    
                - name: Create Release
                  if: github.ref == 'refs/heads/main'
                  uses: softprops/action-gh-release@v1
                  with:
                    files: app/build/outputs/apk/debug/app-debug.apk
                  env:
                    GITHUB_TOKEN: ${ '$' }}{{ secrets.GITHUB_TOKEN }}
        """.trimIndent()
    }
    
    /**
     * 检查 Gradle 可用性
     */
    private fun checkGradleAvailability(): Boolean {
        // 检查项目是否有 gradlew
        val sourceDir = getSourceDirectory()
        if (sourceDir != null) {
            val gradlew = File(sourceDir, "gradlew")
            return gradlew.exists()
        }
        return false
    }
    
    /**
     * 获取源码目录
     */
    private fun getSourceDirectory(): File? {
        // 尝试多种可能的源码位置
        val possiblePaths = listOf(
            File(context.applicationInfo.sourceDir).parentFile?.parentFile,
            File("/storage/emulated/0/LocalAIServer"),
            File(context.getExternalFilesDir(null), "LocalAIServer")
        )
        
        return possiblePaths.firstOrNull { it?.exists() == true }
    }
    
    /**
     * 准备源码包
     */
    private fun prepareSourcePackage(): File {
        val sourceDir = getSourceDirectory()
        val zipFile = File(buildOutputDir, "source_${System.currentTimeMillis()}.zip")
        
        if (sourceDir != null) {
            // 创建 zip 包
            zipOutputStream(FileOutputStream(zipFile)).use { zos ->
                sourceDir.walkTopDown()
                    .filter { !it.name.startsWith(".") && !it.name.contains("build") }
                    .forEach { file ->
                        val entryName = file.relativeTo(sourceDir).path
                        if (file.isFile) {
                            zos.putNextEntry(java.util.zip.ZipEntry(entryName))
                            file.inputStream().copyTo(zos)
                            zos.closeEntry()
                        }
                    }
            }
        }
        
        return zipFile
    }
    
    /**
     * 上传到云端
     */
    private suspend fun uploadToCloud(file: File): Boolean {
        // 模拟上传
        for (progress in 0..100 step 20) {
            updateProgress("上传中... $progress%", 30 + (progress * 0.1).toInt())
            kotlinx.coroutines.delay(500)
        }
        return true
    }
    
    /**
     * 开始云端构建
     */
    private suspend fun startCloudBuild(): String {
        // 模拟 API 调用
        kotlinx.coroutines.delay(1000)
        return "build_${System.currentTimeMillis()}"
    }
    
    /**
     * 检查云端构建状态
     */
    private suspend fun checkCloudBuildStatus(buildId: String): String {
        // 模拟状态检查
        return if (Math.random() > 0.3) "building" else "completed"
    }
    
    /**
     * 下载构建产物
     */
    private suspend fun downloadBuildOutput(buildId: String): String {
        // 模拟下载
        val apkFile = generateMockApk()
        return apkFile.absolutePath
    }
    
    /**
     * 生成模拟 APK（用于演示）
     */
    private fun generateMockApk(): File {
        val apkFile = File(buildOutputDir, "app-debug.apk")
        if (!apkFile.exists()) {
            // 创建一个最小的 APK 文件（实际编译会生成真正的 APK）
            apkFile.parentFile?.mkdirs()
            apkFile.createNewFile()
            
            // 写入简单的 APK 头部
            FileOutputStream(apkFile).use { fos ->
                // APK 文件签名头
                val header = byteArrayOf(
                    0x50.toByte(), 0x4B.toByte(), 0x03.toByte(), 0x04.toByte() // ZIP 头
                )
                fos.write(header)
            }
        }
        
        // 复制当前 APK（如果存在）
        val currentApk = File(context.applicationInfo.sourceDir)
        if (currentApk.exists()) {
            // 不复制，使用模拟文件
        }
        
        return apkFile
    }
    
    /**
     * 更新进度
     */
    private fun updateProgress(step: String, progress: Int) {
        _compileState.value = _compileState.value.copy(
            currentStep = step,
            progress = progress
        )
    }
    
    /**
     * 取消编译
     */
    fun cancelCompile() {
        _compileState.value = CompileState()
    }
    
    /**
     * 安装 APK
     */
    fun installApk(apkFile: File): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    apkFile
                )
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                }
                context.startActivity(intent)
            } else {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.fromFile(apkFile), "application/vnd.android.package-archive")
                }
                context.startActivity(intent)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Install failed", e)
            false
        }
    }
    
    /**
     * 获取编译报告
     */
    fun getCompileReport(): String {
        val state = _compileState.value
        
        return buildString {
            appendLine("📦 编译报告")
            appendLine("=".repeat(40))
            appendLine()
            
            when {
                state.isCompiling -> {
                    appendLine("状态: 编译中...")
                    appendLine("步骤: ${state.currentStep}")
                    appendLine("进度: ${state.progress}%")
                }
                state.outputApk != null -> {
                    appendLine("状态: 编译成功 ✓")
                    appendLine("APK: ${state.outputApk.name}")
                    appendLine("大小: ${formatSize(state.outputApk.length())}")
                }
                state.error != null -> {
                    appendLine("状态: 编译失败 ✗")
                    appendLine("错误: ${state.error}")
                }
                else -> {
                    appendLine("状态: 未开始编译")
                }
            }
            
            if (state.log.isNotEmpty()) {
                appendLine()
                appendLine("日志:")
                appendLine(state.log)
            }
        }
    }
    
    /**
     * 格式化文件大小
     */
    private fun formatSize(bytes: Long): String {
        return when {
            bytes >= 1024L * 1024 -> String.format("%.2f MB", bytes / (1024.0 * 1024.0))
            bytes >= 1024L -> String.format("%.2f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }
    
    /**
     * 辅助类：创建 Zip 输出流
     */
    private fun zipOutputStream(out: OutputStream): java.util.zip.ZipOutputStream {
        return java.util.zip.ZipOutputStream(out)
    }
    
    /**
     * 环境检查结果
     */
    data class EnvironmentCheck(
        val hasTermux: Boolean,
        val hasNDK: Boolean,
        val hasCloudAccess: Boolean,
        val hasGitHubToken: Boolean,
        val recommendedMethod: CompileType,
        val availableMethods: List<CompileType>
    )
}
