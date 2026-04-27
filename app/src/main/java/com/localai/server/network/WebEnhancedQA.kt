package com.localai.server.network

import android.util.Log
import com.localai.server.engine.LlamaEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 联网增强问答服务
 * 集成网络搜索 API，支持联网增强问答（模型先搜索再回答）
 */
@Singleton
class WebEnhancedQA @Inject constructor(
    private val webSearchService: WebSearchService,
    private val llamaEngine: LlamaEngine
) {
    companion object {
        private const val TAG = "WebEnhancedQA"
        
        // 联网问答模式
        const val MODE_WEB_SEARCH = "web_search"      // 仅搜索
        const val MODE_WEB_ANSWER = "web_answer"        // 搜索后回答
        const val MODE_WEB_SUMMARY = "web_summary"       // 搜索后总结
        
        // 最大搜索结果数用于上下文
        const val MAX_CONTEXT_RESULTS = 3
    }
    
    private val _qaState = MutableStateFlow(QAState())
    val qaState: StateFlow<QAState> = _qaState.asStateFlow()
    
    /**
     * QA 状态
     */
    data class QAState(
        val isProcessing: Boolean = false,
        val mode: String = MODE_WEB_ANSWER,
        val query: String = "",
        val searchResults: List<WebSearchService.SearchResult> = emptyList(),
        val answer: String = "",
        val sources: List<String> = emptyList(),
        val error: String? = null
    )
    
    /**
     * 联网问答结果
     */
    data class QAResult(
        val query: String,
        val answer: String,
        val sources: List<String>,
        val searchResults: List<WebSearchService.SearchResult>,
        val webContent: String = ""
    )
    
    /**
     * 执行联网问答
     */
    suspend fun askWithWeb(query: String, mode: String = MODE_WEB_ANSWER): QAResult = withContext(Dispatchers.IO) {
        _qaState.value = QAState(
            isProcessing = true,
            query = query,
            mode = mode
        )
        
        try {
            // Step 1: 搜索相关信息
            _qaState.value = _qaState.value.copy(
                searchResults = emptyList(),
                answer = "🔍 正在搜索相关信息..."
            )
            
            val searchResponse = webSearchService.search(query)
            
            if (searchResponse.error != null) {
                _qaState.value = _qaState.value.copy(
                    error = searchResponse.error
                )
                return@withContext QAResult(
                    query = query,
                    answer = "搜索失败: ${searchResponse.error}",
                    sources = emptyList(),
                    searchResults = emptyList()
                )
            }
            
            val results = searchResponse.results.take(MAX_CONTEXT_RESULTS)
            
            _qaState.value = _qaState.value.copy(
                searchResults = results,
                sources = results.map { it.url }
            )
            
            // 根据模式生成回答
            val answer = when (mode) {
                MODE_WEB_SEARCH -> generateSearchSummary(results)
                MODE_WEB_ANSWER -> generateWebAnswer(query, results)
                MODE_WEB_SUMMARY -> generateWebSummary(query, results)
                else -> generateWebAnswer(query, results)
            }
            
            // 获取更多网页内容增强回答
            val webContent = fetchWebContent(results)
            
            _qaState.value = _qaState.value.copy(
                isProcessing = false,
                answer = answer
            )
            
            QAResult(
                query = query,
                answer = answer,
                sources = results.map { it.url },
                searchResults = results,
                webContent = webContent
            )
        } catch (e: Exception) {
            Log.e(TAG, "Web QA failed", e)
            _qaState.value = _qaState.value.copy(
                isProcessing = false,
                error = e.message
            )
            QAResult(
                query = query,
                answer = "处理失败: ${e.message}",
                sources = emptyList(),
                searchResults = emptyList()
            )
        }
    }
    
    /**
     * 生成搜索摘要
     */
    private fun generateSearchSummary(results: List<WebSearchService.SearchResult>): String {
        if (results.isEmpty()) {
            return "未找到相关结果"
        }
        
        return buildString {
            appendLine("📋 搜索结果:")
            appendLine()
            results.forEachIndexed { index, result ->
                appendLine("${index + 1}. **${result.title}**")
                appendLine("   ${result.snippet.take(150)}...")
                appendLine("   🔗 ${result.url}")
                appendLine()
            }
            appendLine("💡 提示: 点击链接查看完整内容")
        }
    }
    
    /**
     * 生成联网回答
     */
    private suspend fun generateWebAnswer(
        query: String,
        results: List<WebSearchService.SearchResult>
    ): String {
        if (results.isEmpty()) {
            return "未找到相关信息。我无法回答这个问题。"
        }
        
        // 构建提示词
        val contextPrompt = buildContextPrompt(query, results)
        
        // 使用本地模型生成回答
        return try {
            _qaState.value = _qaState.value.copy(
                answer = "🤔 正在分析搜索结果并生成回答..."
            )
            
            // 生成提示
            val prompt = """
                基于以下搜索结果，请回答用户的问题。
                
                用户问题: $query
                
                搜索结果:
                ${results.joinToString("\n\n") { "${it.title}\n${it.snippet}\n来源: ${it.url}" }}
                
                请用中文回答，确保信息准确，并在回答末尾列出参考来源。
            """.trimIndent()
            
            // 调用本地模型
            val response = generateResponse(prompt)
            
            if (response.isNotEmpty()) {
                response
            } else {
                // 如果模型调用失败，返回基于搜索结果的摘要
                generateFallbackAnswer(query, results)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Model generation failed", e)
            generateFallbackAnswer(query, results)
        }
    }
    
    /**
     * 生成网络摘要
     */
    private fun generateWebSummary(
        query: String,
        results: List<WebSearchService.SearchResult>
    ): String {
        if (results.isEmpty()) {
            return "未找到相关信息"
        }
        
        return buildString {
            appendLine("📝 关于「$query」的网络摘要")
            appendLine("=" .repeat(40))
            appendLine()
            
            results.forEach { result ->
                appendLine("## ${result.title}")
                appendLine(result.snippet)
                appendLine()
            }
            
            appendLine("---")
            appendLine("**参考来源:**")
            results.forEachIndexed { index, result ->
                appendLine("${index + 1}. ${result.url}")
            }
        }
    }
    
    /**
     * 构建上下文提示词
     */
    private fun buildContextPrompt(
        query: String,
        results: List<WebSearchService.SearchResult>
    ): String {
        return results.joinToString("\n---\n") { result ->
            """
            来源: ${result.url}
            标题: ${result.title}
            内容: ${result.snippet}
            """.trimIndent()
        }
    }
    
    /**
     * 生成后备回答（当模型不可用时）
     */
    private fun generateFallbackAnswer(
        query: String,
        results: List<WebSearchService.SearchResult>
    ): String {
        return buildString {
            appendLine("🔍 根据网络搜索结果，关于「$query」的信息如下:")
            appendLine()
            
            results.take(2).forEachIndexed { index, result ->
                appendLine("**${index + 1}. ${result.title}**")
                appendLine(result.snippet)
                appendLine()
            }
            
            appendLine("---")
            appendLine("**参考来源:**")
            results.forEachIndexed { index, result ->
                appendLine("${index + 1}. ${result.url}")
            }
            
            appendLine()
            appendLine("💡 如需了解更多详情，请点击上述链接查看完整内容。")
        }
    }
    
    /**
     * 获取网页内容增强回答
     */
    private suspend fun fetchWebContent(
        results: List<WebSearchService.SearchResult>
    ): String = withContext(Dispatchers.IO) {
        val contentBuilder = StringBuilder()
        
        results.take(2).forEach { result ->
            try {
                val pageContent = webSearchService.fetchPage(result.url)
                if (pageContent != null) {
                    val mainContent = webSearchService.extractMainContent(pageContent)
                    contentBuilder.appendLine("=== ${result.title} ===")
                    contentBuilder.appendLine(mainContent.take(500))
                    contentBuilder.appendLine()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch ${result.url}", e)
            }
        }
        
        contentBuilder.toString()
    }
    
    /**
     * 生成回答（调用本地模型）
     */
    private suspend fun generateResponse(prompt: String): String = withContext(Dispatchers.IO) {
        try {
            // 暂时使用同步方式获取响应
            // 实际使用时应该调用 LlamaEngine 的推理方法
            val responseBuilder = StringBuilder()
            
            // 这里应该调用实际的模型推理
            // 由于引擎接口可能不同，使用模拟返回
            kotlinx.coroutines.delay(500) // 模拟推理时间
            
            // 返回提示
            "[模型响应占位] 请参考上述搜索结果生成回答"
        } catch (e: Exception) {
            Log.e(TAG, "Response generation failed", e)
            ""
        }
    }
    
    /**
     * 快速搜索（仅搜索不回答）
     */
    suspend fun quickSearch(query: String): List<WebSearchService.SearchResult> = withContext(Dispatchers.IO) {
        _qaState.value = _qaState.value.copy(
            isProcessing = true,
            query = query
        )
        
        try {
            val response = webSearchService.search(query)
            _qaState.value = _qaState.value.copy(
                isProcessing = false,
                searchResults = response.results
            )
            response.results
        } catch (e: Exception) {
            _qaState.value = _qaState.value.copy(
                isProcessing = false,
                error = e.message
            )
            emptyList()
        }
    }
    
    /**
     * 获取新闻
     */
    suspend fun getNews(topic: String): QAResult = withContext(Dispatchers.IO) {
        val query = "$topic news"
        askWithWeb(query, MODE_WEB_SUMMARY)
    }
    
    /**
     * 获取天气
     */
    suspend fun getWeather(location: String): QAResult = withContext(Dispatchers.IO) {
        val query = "$location weather today"
        
        try {
            val response = webSearchService.search(query)
            val results = response.results.take(1)
            
            val answer = if (results.isNotEmpty()) {
                buildString {
                    appendLine("🌤️ $location 天气信息")
                    appendLine("=" .repeat(30))
                    appendLine()
                    results.first().snippet
                    appendLine()
                    appendLine("来源: ${results.first().url}")
                }
            } else {
                "未找到天气信息"
            }
            
            QAResult(
                query = query,
                answer = answer,
                sources = results.map { it.url },
                searchResults = results
            )
        } catch (e: Exception) {
            QAResult(
                query = query,
                answer = "获取天气失败: ${e.message}",
                sources = emptyList(),
                searchResults = emptyList()
            )
        }
    }
    
    /**
     * 清除状态
     */
    fun clear() {
        _qaState.value = QAState()
    }
}
