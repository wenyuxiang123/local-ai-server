package com.localai.server.optimizer

import android.content.Context
import android.util.Log
import com.localai.server.ai.CodeAnalyzer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 代码优化器
 * AI 分析问题后生成修复代码并应用补丁
 */
@Singleton
class CodeOptimizer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val codeAnalyzer: CodeAnalyzer
) {
    companion object {
        private const val TAG = "CodeOptimizer"
        
        // 补丁保存目录
        private const val PATCHES_DIR = "patches"
        
        // 备份目录
        private const val BACKUP_DIR = "backup"
    }
    
    private val patchesDir: File by lazy {
        File(context.filesDir, PATCHES_DIR).apply { mkdirs() }
    }
    
    private val backupDir: File by lazy {
        File(context.cacheDir, BACKUP_DIR).apply { mkdirs() }
    }
    
    private val _optimizationState = MutableStateFlow(OptimizationState())
    val optimizationState: StateFlow<OptimizationState> = _optimizationState.asStateFlow()
    
    /**
     * 优化状态
     */
    data class OptimizationState(
        val isOptimizing: Boolean = false,
        val currentTask: String = "",
        val progress: Int = 0,
        val appliedPatches: List<String> = emptyList(),
        val availablePatches: List<PatchInfo> = emptyList(),
        val error: String? = null
    )
    
    /**
     * 补丁信息
     */
    data class PatchInfo(
        val id: String,
        val name: String,
        val description: String,
        val category: CodeAnalyzer.Category,
        val originalCode: String,
        val optimizedCode: String,
        val impact: Impact,
        val file: String,
        val line: Int
    )
    
    /**
     * 影响程度
     */
    enum class Impact {
        LOW,      // 低影响
        MEDIUM,   // 中等影响
        HIGH      // 高影响
    }
    
    /**
     * 生成优化补丁
     */
    suspend fun generatePatches(): List<PatchInfo> = withContext(Dispatchers.IO) {
        _optimizationState.value = _optimizationState.value.copy(
            isOptimizing = true,
            currentTask = "分析代码问题..."
        )
        
        // 获取分析结果
        val analysisResults = codeAnalyzer.analysisState.value.results
        
        val patches = mutableListOf<PatchInfo>()
        
        // 根据分析结果生成补丁
        for (result in analysisResults) {
            val patch = generatePatchForIssue(result)
            if (patch != null) {
                patches.add(patch)
            }
        }
        
        // 保存补丁
        savePatches(patches)
        
        _optimizationState.value = _optimizationState.value.copy(
            isOptimizing = false,
            availablePatches = patches,
            progress = 100
        )
        
        patches
    }
    
    /**
     * 根据分析结果生成补丁
     */
    private fun generatePatchForIssue(result: CodeAnalyzer.AnalysisResult): PatchInfo? {
        // 根据问题类别生成对应的优化代码
        val optimization = when (result.category) {
            CodeAnalyzer.Category.PERFORMANCE -> generatePerformancePatch(result)
            CodeAnalyzer.Category.MEMORY_LEAK -> generateMemoryPatch(result)
            CodeAnalyzer.Category.THREAD_SAFETY -> generateThreadSafetyPatch(result)
            CodeAnalyzer.Category.RESOURCE_LEAK -> generateResourceLeakPatch(result)
            CodeAnalyzer.Category.CODE_SMELL -> generateCodeSmellPatch(result)
            CodeAnalyzer.Category.BEST_PRACTICE -> generateBestPracticePatch(result)
            CodeAnalyzer.Category.SECURITY -> null // 安全问题需要谨慎处理
        }
        
        return optimization
    }
    
    /**
     * 生成性能优化补丁
     */
    private fun generatePerformancePatch(result: CodeAnalyzer.AnalysisResult): PatchInfo {
        val (original, optimized) = when {
            result.message.contains("主线程") -> {
                Pair(
                    """
                    // 主线程网络请求（错误）
                    val response = client.newCall(request).execute()
                    """.trimIndent(),
                    """
                    // 在 IO 线程执行（正确）
                    suspend fun fetchData(): Response {
                        return withContext(Dispatchers.IO) {
                            client.newCall(request).execute()
                        }
                    }
                    """.trimIndent()
                )
            }
            result.message.contains("重复计算") -> {
                Pair(
                    """
                    for (item in items) {
                        process(item, items.size)
                    }
                    """.trimIndent(),
                    """
                    val size = items.size
                    for (item in items) {
                        process(item, size)
                    }
                    """.trimIndent()
                )
            }
            result.message.contains("字符串拼接") -> {
                Pair(
                    """
                    var result = ""
                    for (item in items) {
                        result += item.toString()
                    }
                    """.trimIndent(),
                    """
                    val result = buildString {
                        for (item in items) {
                            append(item.toString())
                        }
                    }
                    """.trimIndent()
                )
            }
            else -> {
                Pair(result.message, result.suggestion)
            }
        }
        
        return PatchInfo(
            id = "perf_${System.currentTimeMillis()}",
            name = "性能优化: ${result.message}",
            description = result.suggestion,
            category = result.category,
            originalCode = original,
            optimizedCode = optimized,
            impact = Impact.MEDIUM,
            file = result.file,
            line = result.line
        )
    }
    
    /**
     * 生成内存优化补丁
     */
    private fun generateMemoryPatch(result: CodeAnalyzer.AnalysisResult): PatchInfo {
        val (original, optimized) = when {
            result.message.contains("Handler") -> {
                Pair(
                    """
                    class MyActivity : AppCompatActivity() {
                        private val handler = Handler(Looper.getMainLooper())
                        private val runnable = Runnable { ... }
                        
                        fun start() {
                            handler.postDelayed(runnable, 1000)
                        }
                        // onDestroy 中没有移除回调！
                    }
                    """.trimIndent(),
                    """
                    class MyActivity : AppCompatActivity() {
                        private val handler = Handler(Looper.getMainLooper())
                        private val runnable = Runnable { ... }
                        
                        fun start() {
                            handler.postDelayed(runnable, 1000)
                        }
                        
                        override fun onDestroy() {
                            super.onDestroy()
                            handler.removeCallbacks(runnable)
                        }
                    }
                    """.trimIndent()
                )
            }
            result.message.contains("匿名内部类") -> {
                Pair(
                    """
                    viewModelScope.launch {
                        btn.setOnClickListener {
                            process(data) // 隐式引用外部类
                        }
                    }
                    """.trimIndent(),
                    """
                    viewModelScope.launch {
                        btn.setOnClickListener {
                            viewModel.processData() // 使用显式方法
                        }
                    }
                    """.trimIndent()
                )
            }
            else -> {
                Pair(result.message, result.suggestion)
            }
        }
        
        return PatchInfo(
            id = "mem_${System.currentTimeMillis()}",
            name = "内存优化: ${result.message}",
            description = result.suggestion,
            category = result.category,
            originalCode = original,
            optimizedCode = optimized,
            impact = Impact.HIGH,
            file = result.file,
            line = result.line
        )
    }
    
    /**
     * 生成线程安全补丁
     */
    private fun generateThreadSafetyPatch(result: CodeAnalyzer.AnalysisResult): PatchInfo {
        val (original, optimized) = when {
            result.message.contains("Flow.collect") -> {
                Pair(
                    """
                    // 在错误的协程作用域中收集
                    scope.launch {
                        flow.collect { value ->
                            updateUI(value)
                        }
                    }
                    """.trimIndent(),
                    """
                    // 在正确的 lifecycleScope 中收集
                    lifecycleScope.launch {
                        flow.collect { value ->
                            updateUI(value)
                        }
                    }
                    """.trimIndent()
                )
            }
            result.message.contains("可变状态") -> {
                Pair(
                    """
                    class ViewModel : ViewModel() {
                        var counter = 0 // 可变状态，可能线程不安全
                    }
                    """.trimIndent(),
                    """
                    class ViewModel : ViewModel() {
                        private val _counter = MutableStateFlow(0)
                        val counter: StateFlow<Int> = _counter.asStateFlow()
                    }
                    """.trimIndent()
                )
            }
            else -> {
                Pair(result.message, result.suggestion)
            }
        }
        
        return PatchInfo(
            id = "thread_${System.currentTimeMillis()}",
            name = "线程安全: ${result.message}",
            description = result.suggestion,
            category = result.category,
            originalCode = original,
            optimizedCode = optimized,
            impact = Impact.MEDIUM,
            file = result.file,
            line = result.line
        )
    }
    
    /**
     * 生成资源泄漏补丁
     */
    private fun generateResourceLeakPatch(result: CodeAnalyzer.AnalysisResult): PatchInfo {
        val (original, optimized) = when {
            result.message.contains("InputStream") || result.message.contains("FileInputStream") -> {
                Pair(
                    """
                    val input = FileInputStream(file)
                    val content = input.bufferedReader().readText()
                    // 没有关闭流！
                    """.trimIndent(),
                    """
                    val content = File(file).inputStream().use { input ->
                        input.bufferedReader().readText()
                    }
                    """.trimIndent()
                )
            }
            result.message.contains("Cursor") -> {
                Pair(
                    """
                    val cursor = db.query(...)
                    while (cursor.moveToNext()) {
                        // 使用 cursor
                    }
                    // 没有关闭 cursor！
                    """.trimIndent(),
                    """
                    db.query(...).use { cursor ->
                        while (cursor.moveToNext()) {
                            // 使用 cursor
                        }
                    }
                    """.trimIndent()
                )
            }
            else -> {
                Pair(result.message, result.suggestion)
            }
        }
        
        return PatchInfo(
            id = "resource_${System.currentTimeMillis()}",
            name = "资源管理: ${result.message}",
            description = result.suggestion,
            category = result.category,
            originalCode = original,
            optimizedCode = optimized,
            impact = Impact.HIGH,
            file = result.file,
            line = result.line
        )
    }
    
    /**
     * 生成代码异味补丁
     */
    private fun generateCodeSmellPatch(result: CodeAnalyzer.AnalysisResult): PatchInfo {
        val (original, optimized) = when {
            result.message.contains("魔法数字") -> {
                Pair(
                    """
                    delay(3000)
                    val buffer = ByteArray(8192)
                    """.trimIndent(),
                    """
                    companion object {
                        private const val DELAY_MS = 3000L
                        private const val BUFFER_SIZE = 8192
                    }
                    
                    delay(DELAY_MS)
                    val buffer = ByteArray(BUFFER_SIZE)
                    """.trimIndent()
                )
            }
            result.message.contains("嵌套层级") -> {
                Pair(
                    """
                    if (a) {
                        if (b) {
                            if (c) {
                                doSomething()
                            }
                        }
                    }
                    """.trimIndent(),
                    """
                    if (!a || !b || !c) return
                    doSomething()
                    // 或者提取为独立方法
                    """.trimIndent()
                )
            }
            else -> {
                Pair(result.message, result.suggestion)
            }
        }
        
        return PatchInfo(
            id = "smell_${System.currentTimeMillis()}",
            name = "代码改进: ${result.message}",
            description = result.suggestion,
            category = result.category,
            originalCode = original,
            optimizedCode = optimized,
            impact = Impact.LOW,
            file = result.file,
            line = result.line
        )
    }
    
    /**
     * 生成最佳实践补丁
     */
    private fun generateBestPracticePatch(result: CodeAnalyzer.AnalysisResult): PatchInfo {
        val (original, optimized) = when {
            result.message.contains("!!") -> {
                Pair(
                    """
                    val value = nullableList!![0]
                    """.trimIndent(),
                    """
                    val value = nullableList?.getOrNull(0) ?: defaultValue
                    // 或者
                    nullableList?.firstOrNull()?.let { process(it) }
                    """.trimIndent()
                )
            }
            result.message.contains("硬编码") -> {
                Pair(
                    """
                    textView.text = "这是一段很长的中文硬编码字符串"
                    """.trimIndent(),
                    """
                    textView.text = getString(R.string.important_message)
                    """.trimIndent()
                )
            }
            else -> {
                Pair(result.message, result.suggestion)
            }
        }
        
        return PatchInfo(
            id = "bestpractice_${System.currentTimeMillis()}",
            name = "最佳实践: ${result.message}",
            description = result.suggestion,
            category = result.category,
            originalCode = original,
            optimizedCode = optimized,
            impact = Impact.LOW,
            file = result.file,
            line = result.line
        )
    }
    
    /**
     * 保存补丁到文件
     */
    private fun savePatches(patches: List<PatchInfo>) {
        patchesDir.listFiles()?.forEach { it.delete() }
        
        patches.forEach { patch ->
            val file = File(patchesDir, "${patch.id}.json")
            val json = """
                {
                    "id": "${patch.id}",
                    "name": "${patch.name}",
                    "description": "${patch.description}",
                    "category": "${patch.category}",
                    "originalCode": ${escapeJson(patch.originalCode)},
                    "optimizedCode": ${escapeJson(patch.optimizedCode)},
                    "impact": "${patch.impact}",
                    "file": "${patch.file}",
                    "line": ${patch.line}
                }
            """.trimIndent()
            file.writeText(json)
        }
    }
    
    /**
     * 应用单个补丁
     */
    suspend fun applyPatch(patchId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val patchFile = File(patchesDir, "$patchId.json")
            if (!patchFile.exists()) {
                return@withContext Result.failure(Exception("补丁文件不存在"))
            }
            
            val patch = parsePatchFile(patchFile)
            
            // 查找源文件
            val sourceDir = File(context.applicationInfo.sourceDir).parentFile?.parentFile
            val sourceFile = findSourceFile(sourceDir, patch.file)
            
            if (sourceFile == null) {
                return@withContext Result.failure(Exception("找不到源文件: ${patch.file}"))
            }
            
            // 备份原文件
            val backupFile = File(backupDir, "${sourceFile.name}.${System.currentTimeMillis()}.bak")
            sourceFile.copyTo(backupFile)
            
            // 读取原文件内容
            val content = sourceFile.readText()
            
            // 应用补丁（简单替换，实际使用时需要更精确的代码定位）
            val newContent = content.replace(patch.originalCode, patch.optimizedCode)
            
            if (content == newContent) {
                return@withContext Result.failure(Exception("无法应用补丁：原始代码在文件中未找到"))
            }
            
            // 写入新内容
            sourceFile.writeText(newContent)
            
            _optimizationState.value = _optimizationState.value.copy(
                appliedPatches = _optimizationState.value.appliedPatches + patchId
            )
            
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply patch", e)
            Result.failure(e)
        }
    }
    
    /**
     * 回滚补丁
     */
    suspend fun rollbackPatch(patchId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val backupFiles = backupDir.listFiles()?.filter {
                it.name.contains(patchId) || it.name.contains("bak")
            }?.sortedByDescending { it.lastModified() }
            
            val latestBackup = backupFiles?.firstOrNull()
                ?: return@withContext Result.failure(Exception("找不到备份文件"))
            
            val patches = _optimizationState.value.availablePatches
            val patch = patches.find { it.id == patchId }
            
            if (patch != null) {
                val sourceDir = File(context.applicationInfo.sourceDir).parentFile?.parentFile
                val sourceFile = findSourceFile(sourceDir, patch.file)
                
                if (sourceFile != null) {
                    latestBackup.copyTo(sourceFile, overwrite = true)
                }
            }
            
            _optimizationState.value = _optimizationState.value.copy(
                appliedPatches = _optimizationState.value.appliedPatches - patchId
            )
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * 解析补丁文件
     */
    private fun parsePatchFile(file: File): PatchInfo {
        val content = file.readText()
        return PatchInfo(
            id = Regex("\"id\":\\s*\"([^\"]+)\"").find(content)?.groupValues?.get(1) ?: "",
            name = Regex("\"name\":\\s*\"([^\"]+)\"").find(content)?.groupValues?.get(1) ?: "",
            description = Regex("\"description\":\\s*\"([^\"]+)\"").find(content)?.groupValues?.get(1) ?: "",
            category = CodeAnalyzer.Category.valueOf(
                Regex("\"category\":\\s*\"([^\"]+)\"").find(content)?.groupValues?.get(1) ?: "CODE_SMELL"
            ),
            originalCode = Regex("\"originalCode\":\\s*\"([^\"]+)\"").find(content)?.groupValues?.get(1) ?: "",
            optimizedCode = Regex("\"optimizedCode\":\\s*\"([^\"]+)\"").find(content)?.groupValues?.get(1) ?: "",
            impact = Impact.valueOf(
                Regex("\"impact\":\\s*\"([^\"]+)\"").find(content)?.groupValues?.get(1) ?: "LOW"
            ),
            file = Regex("\"file\":\\s*\"([^\"]+)\"").find(content)?.groupValues?.get(1) ?: "",
            line = Regex("\"line\":\\s*(\\d+)").find(content)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        )
    }
    
    /**
     * 查找源文件
     */
    private fun findSourceFile(dir: File?, relativePath: String): File? {
        if (dir == null) return null
        
        // 尝试直接匹配
        val direct = File(dir, relativePath)
        if (direct.exists()) return direct
        
        // 在 src 目录中搜索
        val srcDir = File(dir, "src")
        if (srcDir.exists()) {
            return srcDir.walkTopDown()
                .filter { it.name == relativePath.substringAfterLast("/") }
                .firstOrNull()
        }
        
        return null
    }
    
    /**
     * JSON 转义
     */
    private fun escapeJson(text: String): String {
        return text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
    
    /**
     * 获取补丁摘要
     */
    fun getPatchesSummary(): String {
        val patches = _optimizationState.value.availablePatches
        
        return buildString {
            appendLine("📝 优化补丁摘要")
            appendLine("=".repeat(40))
            appendLine()
            appendLine("可用补丁: ${patches.size} 个")
            appendLine("已应用: ${_optimizationState.value.appliedPatches.size} 个")
            appendLine()
            
            val byCategory = patches.groupBy { it.category }
            byCategory.forEach { (category, categoryPatches) ->
                appendLine("${category.name}: ${categoryPatches.size} 个")
                categoryPatches.forEach { patch ->
                    val status = if (_optimizationState.value.appliedPatches.contains(patch.id)) "✓" else "○"
                    val impactEmoji = when (patch.impact) {
                        Impact.HIGH -> "🔴"
                        Impact.MEDIUM -> "🟡"
                        Impact.LOW -> "🟢"
                    }
                    appendLine("  $status $impactEmoji ${patch.name}")
                }
                appendLine()
            }
        }
    }
}
