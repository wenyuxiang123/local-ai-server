package com.localai.server.network

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WebView渲染搜索服务 - 绕过反爬机制
 * 使用真实浏览器渲染提取搜索结果
 */
@Singleton
class WebViewSearchService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "WebViewSearch"
        private const val SEARCH_TIMEOUT = 15_000L // 15秒超时
    }

    // 使用WebSearchService的数据类（SearchResult, SearchResponse）

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 使用WebView渲染搜索页面并提取结果
     * 搜索顺序：必应中国 → DuckDuckGo → 百度
     */
    suspend fun search(query: String): WebSearchService.SearchResponse {
        com.localai.server.util.FileLog.log(TAG, "WebView搜索开始: " + query)
        
        // 依次尝试各搜索源
        val searchSources = listOf(
            "bing" to "https://cn.bing.com/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}",
            "duckduckgo" to "https://html.duckduckgo.com/html/?q=${java.net.URLEncoder.encode(query, "UTF-8")}",
            "baidu" to "https://www.baidu.com/s?wd=${java.net.URLEncoder.encode(query, "UTF-8")}"
        )
        
        for ((sourceName, url) in searchSources) {
            try {
                com.localai.server.util.FileLog.log(TAG, "尝试搜索源: " + sourceName)
                val html = renderPage(url)
                if (html != null) {
                    val results = when (sourceName) {
                        "bing" -> parseBingResults(html)
                        "duckduckgo" -> parseDuckDuckGoResults(html)
                        "baidu" -> parseBaiduResults(html)
                        else -> emptyList()
                    }
                    com.localai.server.util.FileLog.log(TAG, sourceName + "返回" + results.size + "条结果")
                    if (results.isNotEmpty()) {
                        return WebSearchService.SearchResponse(query, results)
                    }
                }
            } catch (e: Exception) {
                com.localai.server.util.FileLog.log(TAG, sourceName + "搜索异常: " + e.message)
            }
        }
        
        com.localai.server.util.FileLog.log(TAG, "所有搜索源均无结果")
        return WebSearchService.SearchResponse(query, emptyList(), error = "所有搜索源均无结果")
    }

    /**
     * 使用WebView渲染页面，返回渲染后的HTML
     */
    private suspend fun renderPage(url: String): String? = suspendCancellableCoroutine { continuation ->
        mainHandler.post {
            try {
                val webView = WebView(context)
                var finished = false
                
                val settings = webView.settings
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.setSupportZoom(false)
                settings.blockNetworkImage = true  // 不加载图片，加速渲染
                settings.javaScriptCanOpenWindowsAutomatically = false
                
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, pageUrl: String?) {
                        super.onPageFinished(view, pageUrl)
                        if (finished) return
                        // 页面加载完成后，延迟800ms让JS执行完毕，然后提取HTML
                        mainHandler.postDelayed({
                            if (finished) return@postDelayed
                            view?.evaluateJavascript("document.documentElement.outerHTML") { html ->
                                if (!finished) {
                                    finished = true
                                    val cleanedHtml = html
                                        ?.removeSurrounding("\"")
                                        ?.replace("\\u003C", "<")
                                        ?.replace("\\u003E", ">")
                                        ?.replace("\\\"", "\"")
                                        ?.replace("\\n", "\n")
                                        ?.replace("\\t", "\t")
                                        ?.replace("\\/", "/")
                                    continuation.resume(cleanedHtml) {}
                                    mainHandler.post { view?.destroy() }
                                }
                            }
                        }, 800)
                    }
                    
                    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                        if (!finished && request?.isForMainFrame == true) {
                            finished = true
                            continuation.resume(null) {}
                            mainHandler.post { view?.destroy() }
                        }
                    }
                }
                
                // 超时处理
                mainHandler.postDelayed({
                    if (!finished) {
                        finished = true
                        continuation.resume(null) {}
                        mainHandler.post { try { webView.destroy() } catch (_: Exception) {} }
                    }
                }, SEARCH_TIMEOUT)
                
                webView.loadUrl(url)
                
                continuation.invokeOnCancellation {
                    finished = true
                    mainHandler.post { try { webView.destroy() } catch (_: Exception) {} }
                }
            } catch (e: Exception) {
                Log.e(TAG, "WebView创建异常", e)
                continuation.resume(null) {}
            }
        }
    }

    // 解析必应搜索结果
    private fun parseBingResults(html: String): List<WebSearchService.SearchResult> {
        val results = mutableListOf<WebSearchService.SearchResult>()
        try {
            // 必应结果在 class="b_algo" 的 li 中
            val algoPattern = Regex("""class="[^"]*b_algo[^"]*"[^>]*>.*?<a[^>]+href="([^"]+)"[^>]*>(.*?)</a>.*?(?:<p[^>]*>(.*?)</p>)?""", RegexOption.DOT_MATCHES_ALL)
            for (match in algoPattern.findAll(html).take(5)) {
                val url = match.groupValues[1].trim()
                val title = match.groupValues[2].replace(Regex("<[^>]+>"), "").trim()
                val snippet = match.groupValues[3].replace(Regex("<[^>]+>"), "").trim().take(150)
                if (url.isNotEmpty() && title.isNotEmpty() && !url.contains("bing.com") && !url.contains("microsoft.com")) {
                    results.add(WebSearchService.SearchResult(title, url, snippet, "必应"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Parse Bing error", e)
        }
        return results
    }

    // 解析DuckDuckGo搜索结果
    private fun parseDuckDuckGoResults(html: String): List<WebSearchService.SearchResult> {
        val results = mutableListOf<WebSearchService.SearchResult>()
        try {
            val resultPattern = Regex("""class="result__a"[^>]*href="([^"]+)"[^>]*>(.*?)</a>.*?class="result__snippet"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
            for (match in resultPattern.findAll(html).take(5)) {
                val url = match.groupValues[1].trim()
                val title = match.groupValues[2].replace(Regex("<[^>]+>"), "").trim()
                val snippet = match.groupValues[3].replace(Regex("<[^>]+>"), "").trim().take(150)
                if (url.isNotEmpty() && title.isNotEmpty()) {
                    results.add(WebSearchService.SearchResult(title, url, snippet, "DuckDuckGo"))
                }
            }
            // 备用：匹配更宽松的结果
            if (results.size < 2) {
                val fallbackPattern = Regex("""href="(https?://[^"]+)"[^>]*class="result__a"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
                for (match in fallbackPattern.findAll(html).take(5)) {
                    val url = match.groupValues[1].trim()
                    val title = match.groupValues[2].replace(Regex("<[^>]+>"), "").trim()
                    if (url.isNotEmpty() && title.isNotEmpty() && results.none { r: WebSearchService.SearchResult -> r.url == url }) {
                        results.add(WebSearchService.SearchResult(title, url, "", "DuckDuckGo"))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Parse DDG error", e)
        }
        return results
    }

    // 解析百度搜索结果
    private fun parseBaiduResults(html: String): List<WebSearchService.SearchResult> {
        val results = mutableListOf<WebSearchService.SearchResult>()
        try {
            val titlePattern = Regex("""class="[^"]*c-title[^"]*"[^>]*>.*?<a[^>]+href="([^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
            for (match in titlePattern.findAll(html).take(5)) {
                val url = match.groupValues[1].trim()
                val title = match.groupValues[2].replace(Regex("<[^>]+>"), "").trim()
                if (url.isNotEmpty() && title.isNotEmpty() && !url.contains("baidu.com")) {
                    results.add(WebSearchService.SearchResult(title, url, "", "百度"))
                }
            }
            // 备用匹配
            if (results.size < 2) {
                val fallbackPattern = Regex("""<a[^>]+href="(https?://(?!.*baidu\.com)[^"]+)"[^>]*>([^<]{5,100})</a>""", RegexOption.DOT_MATCHES_ALL)
                for (match in fallbackPattern.findAll(html).take(5)) {
                    val url = match.groupValues[1].trim()
                    val title = match.groupValues[2].replace(Regex("<[^>]+>"), "").trim()
                    if (url.isNotEmpty() && title.isNotEmpty() && results.none { r: WebSearchService.SearchResult -> r.url == url }) {
                        results.add(WebSearchService.SearchResult(title, url, "", "百度"))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Parse Baidu error", e)
        }
        return results
    }

    fun buildSearchContext(results: List<WebSearchService.SearchResult>): String {
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
        return if (context.length > WebSearchService.MAX_SEARCH_CONTEXT_LENGTH) {
            context.take(WebSearchService.MAX_SEARCH_CONTEXT_LENGTH)
        } else {
            context
        }
    }
}
