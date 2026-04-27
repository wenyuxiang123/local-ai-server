package com.localai.server.ai

import android.content.Context
import android.util.Log
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
 * AI 代码分析服务
 * 分析应用源代码，检测性能瓶颈、潜在 bug、代码异味
 */
@Singleton
class CodeAnalyzer @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "CodeAnalyzer"
    }
    
    private val _analysisState = MutableStateFlow(AnalysisState())
    val analysisState: StateFlow<AnalysisState> = _analysisState.asStateFlow()
    
    /**
     * 分析状态
     */
    data class AnalysisState(
        val isAnalyzing: Boolean = false,
        val progress: Int = 0,
        val currentFile: String = "",
        val results: List<AnalysisResult> = emptyList(),
        val summary: AnalysisSummary? = null,
        val error: String? = null
    )
    
    /**
     * 分析结果
     */
    data class AnalysisResult(
        val file: String,
        val line: Int,
        val severity: Severity,
        val category: Category,
        val message: String,
        val suggestion: String
    )
    
    /**
     * 严重程度
     */
    enum class Severity {
        INFO,       // 信息
        WARNING,    // 警告
        ERROR,      // 错误
        CRITICAL   // 严重
    }
    
    /**
     * 问题类别
     */
    enum class Category {
        PERFORMANCE,    // 性能问题
        MEMORY_LEAK,    // 内存泄漏
        THREAD_SAFETY,  // 线程安全
        RESOURCE_LEAK,  // 资源泄漏
        CODE_SMELL,     // 代码异味
        SECURITY,       // 安全问题
        BEST_PRACTICE   // 最佳实践
    }
    
    /**
     * 分析摘要
     */
    data class AnalysisSummary(
        val totalFiles: Int,
        val totalLines: Int,
        val totalIssues: Int,
        val issuesBySeverity: Map<Severity, Int>,
        val issuesByCategory: Map<Category, Int>,
        val overallScore: Int, // 0-100
        val recommendations: List<String>
    )
    
    /**
     * 分析结果
     */
    data class AnalysisReport(
        val results: List<AnalysisResult>,
        val summary: AnalysisSummary
    )
    
    /**
     * 开始代码分析
     */
    suspend fun analyzeCode(sourceDir: File? = null): AnalysisReport = withContext(Dispatchers.IO) {
        _analysisState.value = AnalysisState(isAnalyzing = true)
        
        val results = mutableListOf<AnalysisResult>()
        val sourcePath = sourceDir ?: File(context.applicationInfo.sourceDir).parentFile?.parentFile
        
        if (sourcePath == null || !sourcePath.exists()) {
            _analysisState.value = AnalysisState(
                isAnalyzing = false,
                error = "源代码目录不存在"
            )
            return@withContext AnalysisReport(emptyList(), AnalysisSummary(0, 0, 0, emptyMap(), emptyMap(), 0, emptyList()))
        }
        
        // 查找 Kotlin 和 Java 源文件
        val sourceFiles = findSourceFiles(sourcePath)
        
        Log.i(TAG, "Found ${sourceFiles.size} source files to analyze")
        
        var totalLines = 0
        sourceFiles.forEachIndexed { index, file ->
            val progress = ((index + 1) * 100) / sourceFiles.size
            _analysisState.value = _analysisState.value.copy(
                progress = progress,
                currentFile = file.relativeTo(sourcePath).path
            )
            
            try {
                val fileResults = analyzeFile(file)
                results.addAll(fileResults)
                totalLines += file.readLines().size
            } catch (e: Exception) {
                Log.w(TAG, "Failed to analyze ${file.name}", e)
            }
        }
        
        // 生成摘要
        val summary = generateSummary(results, sourceFiles.size, totalLines)
        
        _analysisState.value = _analysisState.value.copy(
            isAnalyzing = false,
            results = results,
            summary = summary,
            progress = 100
        )
        
        AnalysisReport(results, summary)
    }
    
    /**
     * 查找源文件
     */
    private fun findSourceFiles(dir: File): List<File> {
        val files = mutableListOf<File>()
        
        if (dir.name == "build" || dir.name == ".gradle" || dir.name == "cache") {
            return files
        }
        
        dir.listFiles()?.forEach { file ->
            when {
                file.isDirectory && file.name != "build" -> {
                    files.addAll(findSourceFiles(file))
                }
                file.extension in listOf("kt", "java") -> {
                    files.add(file)
                }
            }
        }
        
        return files
    }
    
    /**
     * 分析单个文件
     */
    private fun analyzeFile(file: File): List<AnalysisResult> {
        val results = mutableListOf<AnalysisResult>()
        val content = file.readText()
        val lines = content.lines()
        
        // 性能分析
        analyzePerformance(file.name, lines, results)
        
        // 内存泄漏分析
        analyzeMemoryLeaks(file.name, lines, results)
        
        // 线程安全分析
        analyzeThreadSafety(file.name, lines, results)
        
        // 资源泄漏分析
        analyzeResourceLeaks(file.name, lines, results)
        
        // 代码异味分析
        analyzeCodeSmell(file.name, lines, results)
        
        // 最佳实践分析
        analyzeBestPractices(file.name, lines, results)
        
        return results
    }
    
    /**
     * 性能分析
     */
    private fun analyzePerformance(
        fileName: String,
        lines: List<String>,
        results: MutableList<AnalysisResult>
    ) {
        // 检测在主线程进行 I/O 操作
        lines.forEachIndexed { index, line ->
            val trimmed = line.trim()
            
            // 在协程外部进行网络请求
            if (trimmed.contains("http.") && !trimmed.contains("suspend") && !trimmed.contains("Dispatchers.IO")) {
                results.add(AnalysisResult(
                    file = fileName,
                    line = index + 1,
                    severity = Severity.ERROR,
                    category = Category.PERFORMANCE,
                    message = "可能在主线程进行网络请求",
                    suggestion = "使用 withContext(Dispatchers.IO) 或 suspend 函数"
                ))
            }
            
            // 大循环没有批处理
            if (trimmed.contains("for (") && index + 1 < lines.size) {
                val nextLines = lines.subList(index, minOf(index + 10, lines.size))
                if (nextLines.none { it.contains("batch") || it.contains("chunk") }) {
                    // 可能是大数据循环
                }
            }
            
            // 检测重复的计算
            if (trimmed.contains(".size") && trimmed.contains("forEach")) {
                results.add(AnalysisResult(
                    file = fileName,
                    line = index + 1,
                    severity = Severity.WARNING,
                    category = Category.PERFORMANCE,
                    message = "循环中重复计算集合大小",
                    suggestion = "考虑在循环前缓存 size 值"
                ))
            }
            
            // String concatenation in loop
            if (trimmed.contains("for") && lines.subList(index, minOf(index + 20, lines.size)).any { 
                it.contains("str +") || it.contains("StringBuilder") 
            }) {
                results.add(AnalysisResult(
                    file = fileName,
                    line = index + 1,
                    severity = Severity.INFO,
                    category = Category.PERFORMANCE,
                    message = "检测到循环中可能的字符串拼接",
                    suggestion = "使用 StringBuilder 代替 + 操作符"
                ))
            }
        }
    }
    
    /**
     * 内存泄漏分析
     */
    private fun analyzeMemoryLeaks(
        fileName: String,
        lines: List<String>,
        results: MutableList<AnalysisResult>
    ) {
        var braceCount = 0
        var inLambda = false
        
        lines.forEachIndexed { index, line ->
            val trimmed = line.trim()
            
            // 匿名内部类持有外部引用
            if (trimmed.contains("object :") || trimmed.contains("Thread(")) {
                results.add(AnalysisResult(
                    file = fileName,
                    line = index + 1,
                    severity = Severity.WARNING,
                    category = Category.MEMORY_LEAK,
                    message = "匿名内部类可能持有 Activity 引用",
                    suggestion = "使用弱引用或 viewLifecycleOwner"
                ))
            }
            
            // Handler 泄漏
            if (trimmed.contains("Handler(") && !trimmed.contains("weakHandler")) {
                results.add(AnalysisResult(
                    file = fileName,
                    line = index + 1,
                    severity = Severity.ERROR,
                    category = Category.MEMORY_LEAK,
                    message = "Handler 可能导致内存泄漏",
                    suggestion = "使用 WeakHandler 或在 onDestroy 中移除回调"
                ))
            }
            
            // 非静态内部类
            if (trimmed.startsWith("class ") && !trimmed.contains("inner class") && !trimmed.contains("companion object")) {
                if (!trimmed.contains("private") && !trimmed.contains("internal")) {
                    results.add(AnalysisResult(
                        file = fileName,
                        line = index + 1,
                        severity = Severity.INFO,
                        category = Category.MEMORY_LEAK,
                        message = "非静态内部类会持有外部类引用",
                        suggestion = "考虑使用静态内部类或单独的文件"
                    ))
                }
            }
            
            // 闭包中引用外部变量
            if (trimmed.contains("{") || trimmed.contains("}")) {
                braceCount += trimmed.count { it == '{' } - trimmed.count { it == '}' }
                if (trimmed.contains("->")) inLambda = true
            }
        }
    }
    
    /**
     * 线程安全分析
     */
    private fun analyzeThreadSafety(
        fileName: String,
        lines: List<String>,
        results: MutableList<AnalysisResult>
    ) {
        var hasMutex = false
        var hasMutableState = false
        
        lines.forEachIndexed { index, line ->
            val trimmed = line.trim()
            
            // 可变状态在多线程环境
            if (trimmed.contains("var ") && 
                !trimmed.contains("volatile") && 
                !trimmed.contains("@Volatile") &&
                !trimmed.contains("Atomic")) {
                hasMutableState = true
            }
            
            // 使用 Mutex
            if (trimmed.contains("Mutex()") || trimmed.contains("Mutex.withLock")) {
                hasMutex = true
            }
            
            // Flow 在非主线程收集
            if (trimmed.contains(".collect") && !trimmed.contains("lifecycleScope") && !trimmed.contains("viewModelScope")) {
                results.add(AnalysisResult(
                    file = fileName,
                    line = index + 1,
                    severity = Severity.WARNING,
                    category = Category.THREAD_SAFETY,
                    message = "Flow.collect 可能在错误的协程作用域",
                    suggestion = "确保在正确的 lifecycleScope 或 viewModelScope 中收集"
                ))
            }
            
            // SharedFlow 没有缓冲
            if (trimmed.contains("SharedFlow(") && !trimmed.contains("buffer")) {
                results.add(AnalysisResult(
                    file = fileName,
                    line = index + 1,
                    severity = Severity.INFO,
                    category = Category.THREAD_SAFETY,
                    message = "SharedFlow 没有设置缓冲区",
                    suggestion = "考虑添加 buffer() 或 replay 参数"
                ))
            }
        }
        
        if (hasMutableState && !hasMutex) {
            results.add(AnalysisResult(
                file = fileName,
                line = 1,
                severity = Severity.INFO,
                category = Category.THREAD_SAFETY,
                message = "代码中存在可变状态变量",
                suggestion = "确保多线程访问时的同步"
            ))
        }
    }
    
    /**
     * 资源泄漏分析
     */
    private fun analyzeResourceLeaks(
        fileName: String,
        lines: List<String>,
        results: MutableList<AnalysisResult>
    ) {
        lines.forEachIndexed { index, line ->
            val trimmed = line.trim()
            
            // File/Stream 没有使用 use
            if ((trimmed.contains("FileInputStream") || 
                 trimmed.contains("FileOutputStream") ||
                 trimmed.contains("InputStream") ||
                 trimmed.contains("OutputStream")) &&
                !trimmed.contains(".use {") &&
                !trimmed.contains("use(")) {
                results.add(AnalysisResult(
                    file = fileName,
                    line = index + 1,
                    severity = Severity.ERROR,
                    category = Category.RESOURCE_LEAK,
                    message = "流资源可能未正确关闭",
                    suggestion = "使用 .use { } 自动关闭资源"
                ))
            }
            
            // 数据库游标没有关闭
            if (trimmed.contains("cursor.") && !trimmed.contains("close()")) {
                results.add(AnalysisResult(
                    file = fileName,
                    line = index + 1,
                    severity = Severity.WARNING,
                    category = Category.RESOURCE_LEAK,
                    message = "Cursor 可能未关闭",
                    suggestion = "使用 use { } 或在 finally 中关闭"
                ))
            }
            
            // OkHttpClient 没有复用
            if (trimmed.contains("OkHttpClient.Builder()") && lines.take(index + 1).none { 
                it.contains("private val") && it.contains("OkHttpClient") 
            }) {
                results.add(AnalysisResult(
                    file = fileName,
                    line = index + 1,
                    severity = Severity.WARNING,
                    category = Category.RESOURCE_LEAK,
                    message = "OkHttpClient 应被复用",
                    suggestion = "使用单例模式创建 OkHttpClient"
                ))
            }
        }
    }
    
    /**
     * 代码异味分析
     */
    private fun analyzeCodeSmell(
        fileName: String,
        lines: List<String>,
        results: MutableList<AnalysisResult>
    ) {
        // 过长的方法
        if (lines.size > 100) {
            results.add(AnalysisResult(
                file = fileName,
                line = 1,
                severity = Severity.WARNING,
                category = Category.CODE_SMELL,
                message = "文件超过 100 行，可能需要拆分",
                suggestion = "考虑将大文件拆分为多个小文件或方法"
            ))
        }
        
        // 过长的方法检测
        var braceCount = 0
        var methodStart = 0
        lines.forEachIndexed { index, line ->
            val trimmed = line.trim()
            
            if (trimmed.contains("fun ") && !trimmed.contains("//") && !trimmed.contains("*")) {
                methodStart = index
                braceCount = 0
            }
            
            braceCount += trimmed.count { it == '{' } - trimmed.count { it == '}' }
            
            if (braceCount == 0 && methodStart > 0) {
                val methodLength = index - methodStart
                if (methodLength > 50) {
                    results.add(AnalysisResult(
                        file = fileName,
                        line = methodStart + 1,
                        severity = Severity.WARNING,
                        category = Category.CODE_SMELL,
                        message = "方法超过 50 行",
                        suggestion = "考虑拆分方法，提高可读性和可维护性"
                    ))
                }
            }
        }
        
        // Magic Numbers
        lines.forEachIndexed { index, line ->
            val magicNumberPattern = Regex("(?:delay|sleep|timeout|size|count|limit|offset)\\s*[:=]?\\s*(\\d{3,})")
            magicNumberPattern.find(line)?.let { match ->
                results.add(AnalysisResult(
                    file = fileName,
                    line = index + 1,
                    severity = Severity.INFO,
                    category = Category.CODE_SMELL,
                    message = "发现魔法数字: ${match.groupValues[1]}",
                    suggestion = "使用有意义的常量替代"
                ))
            }
        }
        
        // 嵌套过深
        var nestedLevel = 0
        var maxNested = 0
        lines.forEachIndexed { index, line ->
            nestedLevel += line.count { it == '{' } - line.count { it == '}' }
            maxNested = maxOf(maxNested, nestedLevel)
            
            if (nestedLevel > 4) {
                results.add(AnalysisResult(
                    file = fileName,
                    line = index + 1,
                    severity = Severity.INFO,
                    category = Category.CODE_SMELL,
                    message = "嵌套层级过深",
                    suggestion = "考虑提取方法或使用早期返回"
                ))
            }
        }
    }
    
    /**
     * 最佳实践分析
     */
    private fun analyzeBestPractices(
        fileName: String,
        lines: List<String>,
        results: MutableList<AnalysisResult>
    ) {
        lines.forEachIndexed { index, line ->
            val trimmed = line.trim()
            
            // 使用 ?. 代替 !!
            if (trimmed.contains("!!")) {
                results.add(AnalysisResult(
                    file = fileName,
                    line = index + 1,
                    severity = Severity.WARNING,
                    category = Category.BEST_PRACTICE,
                    message = "使用 !! 运算符可能导致 NPE",
                    suggestion = "考虑使用 ?. ?: 代替"
                ))
            }
            
            // 硬编码字符串
            if (trimmed.matches(Regex(".*\"[^\"]{20,}\".*")) && 
                !trimmed.contains("@StringRes") &&
                !trimmed.contains("Log.")) {
                if (index > 0 && !lines[index - 1].contains("@Suppress")) {
                    results.add(AnalysisResult(
                        file = fileName,
                        line = index + 1,
                        severity = Severity.INFO,
                        category = Category.BEST_PRACTICE,
                        message = "发现硬编码字符串",
                        suggestion = "考虑使用字符串资源文件"
                    ))
                }
            }
            
            // 使用 TODO 而不是 FIXME
            if (trimmed.contains("FIXME")) {
                results.add(AnalysisResult(
                    file = fileName,
                    line = index + 1,
                    severity = Severity.INFO,
                    category = Category.BEST_PRACTICE,
                    message = "代码中有 FIXME 标记",
                    suggestion = "尽快修复这个问题"
                ))
            }
        }
    }
    
    /**
     * 生成分析摘要
     */
    private fun generateSummary(
        results: List<AnalysisResult>,
        totalFiles: Int,
        totalLines: Int
    ): AnalysisSummary {
        val issuesBySeverity = results.groupBy { it.severity }
            .mapValues { it.value.size }
        
        val issuesByCategory = results.groupBy { it.category }
            .mapValues { it.value.size }
        
        // 计算总体评分 (100 - 扣分)
        var score = 100
        score -= (issuesBySeverity[Severity.CRITICAL] ?: 0) * 10
        score -= (issuesBySeverity[Severity.ERROR] ?: 0) * 5
        score -= (issuesBySeverity[Severity.WARNING] ?: 0) * 2
        score -= ((issuesBySeverity[Severity.INFO] ?: 0) * 0.5).toInt()
        score = score.coerceIn(0, 100).toInt()
        
        // 生成建议
        val recommendations = mutableListOf<String>()
        
        if ((issuesBySeverity[Severity.CRITICAL] ?: 0) > 0) {
            recommendations.add("🔴 优先修复 ${issuesBySeverity[Severity.CRITICAL]} 个严重问题")
        }
        if ((issuesBySeverity[Severity.ERROR] ?: 0) > 0) {
            recommendations.add("🔴 修复 ${issuesBySeverity[Severity.ERROR]} 个错误问题")
        }
        if ((issuesByCategory[Category.PERFORMANCE] ?: 0) > 3) {
            recommendations.add("⚡ 考虑优化性能问题")
        }
        if ((issuesByCategory[Category.MEMORY_LEAK] ?: 0) > 0) {
            recommendations.add("💾 检查并修复内存泄漏")
        }
        if ((issuesByCategory[Category.RESOURCE_LEAK] ?: 0) > 0) {
            recommendations.add("📁 确保资源正确关闭")
        }
        
        if (recommendations.isEmpty()) {
            recommendations.add("✅ 代码质量良好，继续保持!")
        }
        
        return AnalysisSummary(
            totalFiles = totalFiles,
            totalLines = totalLines,
            totalIssues = results.size,
            issuesBySeverity = issuesBySeverity,
            issuesByCategory = issuesByCategory,
            overallScore = score,
            recommendations = recommendations
        )
    }
    
    /**
     * 获取代码健康报告
     */
    fun getHealthReport(): String {
        val state = _analysisState.value
        val summary = state.summary ?: return "请先运行代码分析"
        
        return buildString {
            appendLine("📊 代码健康报告")
            appendLine("=".repeat(40))
            appendLine()
            appendLine("📈 概览:")
            appendLine("  • 分析文件: ${summary.totalFiles} 个")
            appendLine("  • 代码总行数: ${summary.totalLines} 行")
            appendLine("  • 发现问题: ${summary.totalIssues} 个")
            appendLine("  • 代码评分: ${summary.overallScore}/100")
            appendLine()
            appendLine("⚠️ 问题分布:")
            summary.issuesBySeverity.forEach { (severity, count) ->
                val emoji = when (severity) {
                    Severity.CRITICAL -> "🔴"
                    Severity.ERROR -> "🔴"
                    Severity.WARNING -> "🟡"
                    Severity.INFO -> "🔵"
                }
                appendLine("  $emoji $severity: $count 个")
            }
            appendLine()
            appendLine("📋 类别分布:")
            summary.issuesByCategory.forEach { (category, count) ->
                appendLine("  • $category: $count 个")
            }
            appendLine()
            appendLine("💡 建议:")
            summary.recommendations.forEach { rec ->
                appendLine("  $rec")
            }
        }
    }
}
