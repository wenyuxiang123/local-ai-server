package com.localai.server.data.repository

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class ChatMessage(
    val role: String,
    val content: String
)

data class ChatRequest(
    val model: String = "qwen3",
    val messages: List<ChatMessage>,
    val max_tokens: Int = 2048,
    val temperature: Float = 0.7f,
    val stream: Boolean = false
)

data class ChatResponse(
    val id: String?,
    val choices: List<Choice>?,
    val error: ErrorResponse?
)

data class Choice(
    val message: MessageContent?
)

data class MessageContent(
    val role: String?,
    val content: String?
)

data class ErrorResponse(
    val message: String?,
    val type: String?
)

@Singleton
class ChatApiService @Inject constructor() {
    
    companion object {
        private const val TAG = "ChatApiService"
        private const val DEFAULT_BASE_URL = "http://localhost:8080"
    }
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    
    private val gson = Gson()
    
    suspend fun sendMessage(
        baseUrl: String = DEFAULT_BASE_URL,
        messages: List<ChatMessage>
    ): Result<String> = withContext(Dispatchers.IO) {
        var lastException: Exception? = null
        val maxRetries = 2
        
        repeat(maxRetries) { attempt ->
            try {
                val requestBody = ChatRequest(
                    messages = messages,
                    max_tokens = 2048,
                    temperature = 0.7f
                )
                
                val json = gson.toJson(requestBody)
                Log.d(TAG, "Request (attempt ${attempt + 1}): $json")
                
                val request = Request.Builder()
                    .url("$baseUrl/v1/chat/completions")
                    .post(json.toRequestBody("application/json".toMediaType()))
                    .build()
                
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string()
                    Log.d(TAG, "Response code: ${response.code}")
                    Log.d(TAG, "Response body: ${body?.take(200)}")
                    
                    if (!response.isSuccessful) {
                        // Parse error details from response body
                        val errorDetail = try {
                            val jsonObj = body?.let { gson.fromJson(it, com.google.gson.JsonObject::class.java) }
                            jsonObj?.getAsJsonObject("error")?.get("message")?.asString 
                                ?: response.message
                        } catch (_: Exception) {
                            response.message
                        }
                        
                        // 503 means model not ready, don't retry
                        if (response.code == 503) {
                            return@withContext Result.failure(
                                IOException("模型未加载: $errorDetail")
                            )
                        }
                        // Don't retry 500 errors that indicate engine state issues
                        if (response.code == 500 && errorDetail.contains("状态异常")) {
                            return@withContext Result.failure(
                                IOException("AI服务错误: $errorDetail")
                            )
                        }
                        lastException = IOException("HTTP ${response.code}: $errorDetail")
                        if (attempt < maxRetries - 1) {
                            Thread.sleep(2000L * (attempt + 1))
                            return@repeat
                        }
                        return@withContext Result.failure(lastException!!)
                    }
                    
                    body?.let {
                        val chatResponse = gson.fromJson(it, ChatResponse::class.java)
                        
                        // Check for API error
                        chatResponse.error?.let { error ->
                            return@withContext Result.failure(
                                IOException(error.message ?: "API Error")
                            )
                        }
                        
                        // Extract assistant message
                        val assistantMessage = chatResponse.choices
                            ?.firstOrNull()
                            ?.message
                            ?.content
                            ?: ""
                        
                        return@withContext Result.success(assistantMessage)
                    }
                    
                    return@withContext Result.failure(IOException("Empty response body"))
                }
            } catch (e: java.net.SocketException) {
                Log.w(TAG, "Connection error (attempt ${attempt + 1}): ${e.message}")
                lastException = e
                if (attempt < maxRetries - 1) {
                    Thread.sleep(2000L * (attempt + 1))
                }
            } catch (e: java.net.SocketTimeoutException) {
                Log.w(TAG, "Timeout (attempt ${attempt + 1}): ${e.message}")
                lastException = e
                if (attempt < maxRetries - 1) {
                    Thread.sleep(2000L * (attempt + 1))
                }
            } catch (e: java.io.IOException) {
                Log.w(TAG, "IO error (attempt ${attempt + 1}): ${e.message}")
                lastException = e
                if (attempt < maxRetries - 1) {
                    Thread.sleep(2000L * (attempt + 1))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending message", e)
                return@withContext Result.failure(e)
            }
        }
        
        Result.failure(lastException ?: IOException("Unknown error after retries"))
    }
    
    // Build messages for API from database messages
    fun buildMessages(userContent: String, historyMessages: List<ChatMessage>, thinkMode: Boolean = false): List<ChatMessage> {
        val allMessages = historyMessages.toMutableList()
        
        // 根据思考模式设置系统提示词
        if (allMessages.isEmpty()) {
            val thinkTag = if (thinkMode) " /think" else " /no_think"
            allMessages.add(ChatMessage("system", "You are a helpful assistant.$thinkTag"))
        }
        
        allMessages.add(ChatMessage("user", userContent))
        
        return allMessages
    }

    /**
     * Build messages with custom system prompt (for web search context)
     */
    fun buildMessagesWithSystemPrompt(
        userContent: String,
        historyMessages: List<ChatMessage>,
        systemPrompt: String
    ): List<ChatMessage> {
        val allMessages = mutableListOf<ChatMessage>()
        
        // Add custom system prompt
        allMessages.add(ChatMessage("system", systemPrompt))
        
        // Add history messages
        allMessages.addAll(historyMessages)
        
        // Add user message
        allMessages.add(ChatMessage("user", userContent))
        
        return allMessages
    }
}
