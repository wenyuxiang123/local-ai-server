package com.localai.server.network

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 联网搜索服务 - 支持多源 Fallback（国内优先）
 * 搜索顺序：百度 → 必应中国 → DuckDuckGo
 * 任一源返回 >= 1 条结果即停止尝试
 */
@Singleton
class WebSearchService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "WebSearchService"
        
        // 搜索源URL
        private const val BAIDU_SEARCH = "https://www.baidu.com/s?wd="
        private const val BING_CN_SEARCH = "https://cn.bing.com/search?q="
        private const val DUCKDUCKGO_HTML = "https://html.duckduckgo.com/html/"
        
        // 最大搜索结果数
        private const val MAX_RESULTS = 5
        
        // 摘要最大长度
        private const val MAX_SNIPPET_LENGTH = 150
        
        // 超时时间
        private const val TIMEOUT_SECONDS = 15L
        
        // 搜索上下文最大长度
        const val MAX_SEARCH_CONTEXT_LENGTH = 800
    }
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
    
    /**
     * 搜索结果数据类
     */
    data class SearchResult(
        val title: String,
        val url: String,
        val snippet: String,
        val source: String = ""
    )
    
    data class SearchResponse(
        val query: String,
        val results: List<SearchResult>,
        val error: String? = null
    )
    
    /**
     * 执行多源网络搜索
     * 搜索顺序：百度 → 必应中国 → DuckDuckGo
     * @param query 搜索关键词
     */
    suspend fun search(query: String): SearchResponse = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Searching for: $query with multi-source fallback")
            com.localai.server.util.FileLog.log(TAG, "开始搜索: " + query)
            
            // 按顺序尝试各搜索源，任一源返回 >= 1 条结果即停止
            // 1. 尝试百度
            val baiduResults = searchWithBaidu(query)
            if (baiduResults.isNotEmpty()) {
                Log.i(TAG, "Baidu returned ${baiduResults.size} results")
                com.localai.server.util.FileLog.log(TAG, "百度搜索返回 " + baiduResults.size + " 条结果")
                return@withContext SearchResponse(query, baiduResults)
            }
            
            // 2. 尝试必应中国
            val bingResults = searchWithBingCN(query)
            if (bingResults.isNotEmpty()) {
                Log.i(TAG, "Bing CN returned ${bingResults.size} results")
                com.localai.server.util.FileLog.log(TAG, "必应搜索返回 " + bingResults.size + " 条结果")
                return@withContext SearchResponse(query, bingResults)
            }
            
            // 3. 最后尝试 DuckDuckGo
            val ddgResults = searchWithDuckDuckGo(query)
            if (ddgResults.isNotEmpty()) {
                Log.i(TAG, "DuckDuckGo returned ${ddgResults.size} results")
                com.localai.server.util.FileLog.log(TAG, "DuckDuckGo搜索返回 " + ddgResults.size + " 条结果")
                return@withContext SearchResponse(query, ddgResults)
            }
            
            // 所有源都无结果
            Log.w(TAG, "All search sources returned empty results")
            com.localai.server.util.FileLog.log(TAG, "所有搜索源都未返回结果")
            SearchResponse(query, emptyList(), error = "所有搜索源都未返回结果")
            
        } catch (e: Exception) {
            Log.e(TAG, "Search failed", e)
            SearchResponse(query, emptyList(), error = e.message ?: "搜索失败")
        }
    }
    
    /**
     * 使用百度搜索
     */
    private suspend fun searchWithBaidu(query: String): List<SearchResult> = withContext(Dispatchers.IO) {
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "${BAIDU_SEARCH}${encodedQuery}"
            
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .build()
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Baidu search failed: ${response.code}")
                    return@withContext emptyList()
                }
                
                val body = response.body?.string() ?: return@withContext emptyList()
                parseBaiduHtml(body)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Baidu search error", e)
            emptyList()
        }
    }
    
    /**
     * 解析百度搜索结果 HTML
     */
    private fun parseBaiduHtml(html: String): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        
        try {
            // 匹配百度搜索结果块
            val titlePattern = Pattern.compile(
                "<h3[^>]*class=\"[^\"]*c-title[^\"]*\"[^>]*>.*?<a[^>]*href=\"([^\"]+)\"[^>]*>([^<]+)</a>",
                Pattern.DOTALL
            )
            
            val titleMatcher = titlePattern.matcher(html)
            var count = 0
            
            while (titleMatcher.find() && count < MAX_RESULTS) {
                val url = titleMatcher.group(1)?.trim() ?: ""
                val title = titleMatcher.group(2)?.replace(Regex("<[^>]+>"), "")?.trim() ?: ""
                
                // 跳过百度自己的链接
                if (url.contains("baidu.com") || title.isEmpty() || url.isEmpty()) {
                    continue
                }
                
                // 尝试从URL附近获取摘要
                val snippet = extractSnippetNearUrl(html, url, "百度")
                
                if (url.isNotEmpty() && title.isNotEmpty()) {
                    results.add(SearchResult(
                        title = title,
                        url = url,
                        snippet = snippet,
                        source = "百度"
                    ))
                    count++
                }
            }
            
            // 如果标题匹配不够，尝试备用的通用匹配
            if (results.size < 3) {
                val fallbackPattern = Pattern.compile(
                    "<a[^>]+href=\"(https?://(?!.*baidu\\.com)[^\"]+)\"[^>]*>([^<]{5,100})</a>",
                    Pattern.DOTALL
                )
                val fallbackMatcher = fallbackPattern.matcher(html)
                
                while (fallbackMatcher.find() && count < MAX_RESULTS) {
                    val url = fallbackMatcher.group(1)?.trim() ?: ""
                    val title = fallbackMatcher.group(2)?.replace(Regex("<[^>]+>"), "")?.trim() ?: ""
                    
                    if (url.isNotEmpty() && title.isNotEmpty() && 
                        !results.any { it.url == url }) {
                        results.add(SearchResult(
                            title = title,
                            url = url,
                            snippet = "",
                            source = "百度"
                        ))
                        count++
                    }
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse Baidu HTML", e)
        }
        
        return results
    }
    
    /**
     * 使用必应中国搜索
     */
    private suspend fun searchWithBingCN(query: String): List<SearchResult> = withContext(Dispatchers.IO) {
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "${BING_CN_SEARCH}${encodedQuery}"
            
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .build()
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Bing CN search failed: ${response.code}")
                    return@withContext emptyList()
                }
                
                val body = response.body?.string() ?: return@withContext emptyList()
                parseBingCNHtml(body)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Bing CN search error", e)
            emptyList()
        }
    }
    
    /**
     * 解析必应中国搜索结果 HTML
     */
    private fun parseBingCNHtml(html: String): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        
        try {
            // 匹配必应搜索结果
            val resultPattern = Pattern.compile(
                "<li[^>]*class=\"[^\"]*b_algo[^\"]*\"[^>]*>.*?<h2[^>]*>.*?<a[^>]+href=\"([^\"]+)\"[^>]*>([^<]+)</a>.*?<p[^>]*>([\\s\\S]*?)</p>",
                Pattern.DOTALL
            )
            
            val matcher = resultPattern.matcher(html)
            var count = 0
            
            while (matcher.find() && count < MAX_RESULTS) {
                val url = matcher.group(1)?.trim() ?: ""
                val title = matcher.group(2)?.replace(Regex("<[^>]+>"), "")?.trim() ?: ""
                var snippet = matcher.group(3)?.replace(Regex("<[^>]+>"), "")?.trim() ?: ""
                
                // 截断过长摘要
                if (snippet.length > MAX_SNIPPET_LENGTH) {
                    snippet = snippet.take(MAX_SNIPPET_LENGTH) + "..."
                }
                
                if (url.isNotEmpty() && title.isNotEmpty()) {
                    results.add(SearchResult(
                        title = title,
                        url = url,
                        snippet = snippet,
                        source = "必应"
                    ))
                    count++
                }
            }
            
            // 备用匹配
            if (results.size < 2) {
                val fallbackPattern = Pattern.compile(
                    "<a[^>]+href=\"(https?://[^\"]+)\"[^>]*>([^<]{10,150})</a>",
                    Pattern.DOTALL
                )
                val fallbackMatcher = fallbackPattern.matcher(html)
                
                while (fallbackMatcher.find() && count < MAX_RESULTS) {
                    val url = fallbackMatcher.group(1)?.trim() ?: ""
                    val title = fallbackMatcher.group(2)?.replace(Regex("<[^>]+>"), "")?.trim() ?: ""
                    
                    if (url.isNotEmpty() && title.isNotEmpty() && 
                        !url.contains("bing.com") &&
                        !results.any { it.url == url }) {
                        results.add(SearchResult(
                            title = title,
                            url = url,
                            snippet = "",
                            source = "必应"
                        ))
                        count++
                    }
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse Bing CN HTML", e)
        }
        
        return results
    }
    
    /**
     * 使用 DuckDuckGo HTML 搜索（降级为最后一个备选）
     */
    private suspend fun searchWithDuckDuckGo(query: String): List<SearchResult> = withContext(Dispatchers.IO) {
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "${DUCKDUCKGO_HTML}?q=${encodedQuery}"
            
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36")
                .header("Accept", "text/html")
                .build()
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext emptyList()
                }
                
                val body = response.body?.string() ?: return@withContext emptyList()
                parseDuckDuckGoHtml(body)
            }
        } catch (e: Exception) {
            Log.e(TAG, "DuckDuckGo search error", e)
            emptyList()
        }
    }
    
    /**
     * 解析 DuckDuckGo HTML 结果
     */
    private fun parseDuckDuckGoHtml(html: String): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        
        try {
            // 匹配结果块
            val resultPattern = Pattern.compile(
                "<a class=\"result__a\" href=\"([^\"]+)\"[^>]*>([^<]+)</a>.*?<a class=\"result__snippet\"[^>]*>([^<]+)</a>",
                Pattern.DOTALL
            )
            
            val matcher = resultPattern.matcher(html)
            var count = 0
            
            while (matcher.find() && count < MAX_RESULTS) {
                val url = matcher.group(1) ?: ""
                val title = matcher.group(2)?.replace(Regex("<[^>]+>"), "")?.trim() ?: ""
                val snippet = matcher.group(3)?.replace(Regex("<[^>]+>"), "")?.trim() ?: ""
                
                if (url.isNotEmpty() && title.isNotEmpty()) {
                    results.add(SearchResult(
                        title = title,
                        url = url,
                        snippet = snippet,
                        source = "DuckDuckGo"
                    ))
                    count++
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse DuckDuckGo HTML", e)
        }
        
        return results
    }
    
    /**
     * 从URL附近提取摘要内容
     */
    private fun extractSnippetNearUrl(html: String, url: String, source: String): String {
        try {
            val urlIndex = html.indexOf(url)
            if (urlIndex < 0) return ""
            
            // 提取URL后1500个字符范围内的内容
            val endIndex = minOf(urlIndex + 1500, html.length)
            val nearContent = html.substring(urlIndex, endIndex)
            
            // 尝试匹配摘要模式
            val snippetPatterns = listOf(
                Pattern.compile("<span[^>]*>([\\s\\S]{10,200}?)</span>"),
                Pattern.compile("<p[^>]*>([\\s\\S]{10,200}?)</p>"),
                Pattern.compile(">([^<]{20,200}?)<")
            )
            
            for (pattern in snippetPatterns) {
                val matcher = pattern.matcher(nearContent)
                if (matcher.find()) {
                    var snippet = matcher.group(1)?.replace(Regex("<[^>]+>"), "")?.trim() ?: ""
                    if (snippet.length > MAX_SNIPPET_LENGTH) {
                        snippet = snippet.take(MAX_SNIPPET_LENGTH) + "..."
                    }
                    if (snippet.isNotBlank()) {
                        return snippet
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract snippet", e)
        }
        return ""
    }
    
    /**
     * 构建搜索上下文（限制总长度）
     * @param results 搜索结果列表
     * @return 格式化的搜索上下文
     */
    fun buildSearchContext(results: List<SearchResult>): String {
        if (results.isEmpty()) return ""
        
        val context = buildString {
            appendLine("请基于以下搜索结果回答用户问题：")
            appendLine()
            
            results.forEachIndexed { index, result ->
                appendLine("${index + 1}. ${result.title}")
                if (result.snippet.isNotEmpty()) {
                    appendLine("   ${result.snippet}")
                }
                appendLine("   来源: ${result.url}")
                appendLine()
            }
        }
        
        // 限制总长度
        return if (context.length > MAX_SEARCH_CONTEXT_LENGTH) {
            context.take(MAX_SEARCH_CONTEXT_LENGTH)
        } else {
            context
        }
    }
    
    /**
     * 获取网页内容
     */
    suspend fun fetchPage(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .build()
            
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    response.body?.string()
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch page: $url", e)
            null
        }
    }
    
    /**
     * 从网页内容中提取主要文本
     */
    fun extractMainContent(html: String): String {
        try {
            // 移除 script 和 style 标签
            var content = html.replace(Regex("<script[^>]*>[\\s\\S]*?</script>"), "")
            content = content.replace(Regex("<style[^>]*>[\\s\\S]*?</style>"), "")
            
            // 移除 HTML 标签
            content = content.replace(Regex("<[^>]+>"), " ")
            
            // 移除多余空白
            content = content.replace(Regex("\\s+"), " ").trim()
            
            return content
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract main content", e)
            return ""
        }
    }
}
