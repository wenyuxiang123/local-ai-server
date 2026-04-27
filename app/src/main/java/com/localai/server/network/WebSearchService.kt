package com.localai.server.network

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 联网搜索服务 - 支持 Bing/Google 搜索 API
 * 用于联网增强问答，让 AI 能够获取最新信息
 */
@Singleton
class WebSearchService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "WebSearchService"
        
        // 使用 DuckDuckGo 作为免费搜索源（无需API Key）
        private const val DUCKDUCKGO_API = "https://api.duckduckgo.com/"
        private const val DUCKDUCKGO_HTML = "https://html.duckduckgo.com/html/"
        
        // 备用：Bing Search API (需要API Key)
        private const val BING_API = "https://api.bing.microsoft.com/v7.0/search"
        
        // 最大搜索结果数
        private const val MAX_RESULTS = 10
        
        // 超时时间
        private const val TIMEOUT_SECONDS = 30L
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
     * 执行网络搜索
     * @param query 搜索关键词
     * @param bingApiKey Bing API Key（可选，如果不提供则使用 DuckDuckGo）
     */
    suspend fun search(query: String, bingApiKey: String? = null): SearchResponse = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Searching for: $query")
            
            // 优先尝试 DuckDuckGo
            val results = searchWithDuckDuckGo(query)
            
            if (results.isNotEmpty()) {
                SearchResponse(query, results)
            } else {
                // 如果 DuckDuckGo 失败，尝试获取网页内容
                val fallbackResults = searchWithFallback(query)
                SearchResponse(query, fallbackResults)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Search failed", e)
            SearchResponse(query, emptyList(), error = e.message)
        }
    }
    
    /**
     * 使用 DuckDuckGo API 搜索
     */
    private suspend fun searchWithDuckDuckGo(query: String): List<SearchResult> = withContext(Dispatchers.IO) {
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "${DUCKDUCKGO_API}?q=${encodedQuery}&format=json&no_html=1&skip_disambig=1"
            
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36")
                .build()
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "DuckDuckGo API failed: ${response.code}")
                    return@withContext emptyList()
                }
                
                val body = response.body?.string() ?: return@withContext emptyList()
                val json = JSONObject(body)
                
                val results = mutableListOf<SearchResult>()
                
                // 解析 RelatedTopics
                val relatedTopics = json.optJSONArray("RelatedTopics")
                if (relatedTopics != null) {
                    for (i in 0 until minOf(relatedTopics.length(), MAX_RESULTS)) {
                        val topic = relatedTopics.getJSONObject(i)
                        if (topic.has("Text")) {
                            results.add(
                                SearchResult(
                                    title = topic.optString("Text", "").take(100),
                                    url = topic.optString("URL", ""),
                                    snippet = topic.optString("Text", ""),
                                    source = "DuckDuckGo"
                                )
                            )
                        }
                    }
                }
                
                results
            }
        } catch (e: Exception) {
            Log.e(TAG, "DuckDuckGo search error", e)
            emptyList()
        }
    }
    
    /**
     * 备用搜索方法 - 使用 DuckDuckGo HTML 页面解析
     */
    private suspend fun searchWithFallback(query: String): List<SearchResult> = withContext(Dispatchers.IO) {
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "${DUCKDUCKGO_HTML}?q=${encodedQuery}"
            
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36")
                .build()
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext emptyList()
                }
                
                val body = response.body?.string() ?: return@withContext emptyList()
                parseDuckDuckGoHtml(body)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fallback search error", e)
            emptyList()
        }
    }
    
    /**
     * 解析 DuckDuckGo HTML 结果
     */
    private fun parseDuckDuckGoHtml(html: String): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        
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
                results.add(SearchResult(title, url, snippet, "DuckDuckGo"))
                count++
            }
        }
        
        return results
    }
    
    /**
     * 使用 Bing Search API 搜索（需要 API Key）
     */
    suspend fun searchWithBing(query: String, apiKey: String): SearchResponse = withContext(Dispatchers.IO) {
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "$BING_API?q=${encodedQuery}&count=${MAX_RESULTS}&mkt=zh-CN"
            
            val request = Request.Builder()
                .url(url)
                .header("Ocp-Apim-Subscription-Key", apiKey)
                .build()
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext SearchResponse(query, emptyList(), "Bing API 失败: ${response.code}")
                }
                
                val body = response.body?.string() ?: return@withContext SearchResponse(query, emptyList(), "空响应")
                val json = JSONObject(body)
                
                val results = mutableListOf<SearchResult>()
                val webPages = json.optJSONObject("webPages")
                
                if (webPages != null) {
                    val value = webPages.optJSONArray("value")
                    if (value != null) {
                        for (i in 0 until minOf(value.length(), MAX_RESULTS)) {
                            val item = value.getJSONObject(i)
                            results.add(
                                SearchResult(
                                    title = item.optString("name", ""),
                                    url = item.optString("url", ""),
                                    snippet = item.optString("snippet", ""),
                                    source = "Bing"
                                )
                            )
                        }
                    }
                }
                
                SearchResponse(query, results)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Bing search error", e)
            SearchResponse(query, emptyList(), e.message)
        }
    }
    
    /**
     * 获取网页内容
     */
    suspend fun fetchPage(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36")
                .build()
            
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    response.body?.string()
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fetch page error", e)
            null
        }
    }
    
    /**
     * 从网页内容中提取正文
     */
    fun extractMainContent(html: String): String {
        // 移除 script 和 style 标签
        var content = html.replace(Regex("<script[^>]*>[\\s\\S]*?</script>"), "")
        content = content.replace(Regex("<style[^>]*>[\\s\\S]*?</style>"), "")
        
        // 移除 HTML 标签
        content = content.replace(Regex("<[^>]+>"), " ")
        
        // 清理空白字符
        content = content.replace(Regex("\\s+"), " ").trim()
        
        return content
    }
}
