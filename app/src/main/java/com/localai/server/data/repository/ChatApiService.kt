package com.localai.server.data.repository

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
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
    val message: MessageContent?,
    val delta: DeltaContent?
)

data class MessageContent(
    val role: String?,
    val content: String?
)

data class DeltaContent(
    val content: String?
)

data class ErrorResponse(
    val message: String?,
    val type: String?
)

/**
 * 流式输出结果
 */
sealed class StreamResult {
    data class Token(val token: String) : StreamResult()
    data class Error(val message: String) : StreamResult()
    object Complete : StreamResult()
}

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
    
    /**
     * 流式输出：每生成一个token就回调，实现打字机效果
     */
    fun sendMessageStream(
        baseUrl: String = DEFAULT_BASE_URL,
        messages: List<ChatMessage>
    ): Flow<StreamResult> = flow {
        try {
            val requestBody = ChatRequest(
                messages = messages,
                max_tokens = 2048,
                temperature = 0.7f,
                stream = true
            )
            
            val json = gson.toJson(requestBody)
            Log.d(TAG, "Stream request: ${json.take(200)}")
            
            val request = Request.Builder()
                .url("$baseUrl/v1/chat/completions")
                .post(json.toRequestBody("application/json".toMediaType()))
                .build()
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: ""
                    Log.e(TAG, "Stream error: ${response.code} - ${errorBody.take(200)}")
                    emit(StreamResult.Error("HTTP ${response.code}: ${response.message}"))
                    return@use
                }
                
                val inputStream = response.body?.byteStream() ?: run {
                    emit(StreamResult.Error("空响应"))
                    return@use
                }
                
                val reader = inputStream.bufferedReader()
                var line: String?
                
                while (reader.readLine().also { line = it } != null) {
                    val currentLine = line ?: continue
                    
                    // SSE格式: data: {...}
                    if (currentLine.startsWith("data: ")) {
                        val data = currentLine.substring(6).trim()
                        
                        // 流结束标记
                        if (data == "[DONE]") {
                            emit(StreamResult.Complete)
                            break
                        }
                        
                        try {
                            // 解析JSON
                            val jsonObj = gson.fromJson(data, JsonObject::class.java)
                            val choices = jsonObj.getAsJsonArray("choices")
                            
                            if (choices != null && choices.size() > 0) {
                                val choice = choices[0].asJsonObject
                                val delta = choice.getAsJsonObject("delta")
                                val content = delta?.get("content")?.asString
                                
                                if (!content.isNullOrEmpty()) {
                                    emit(StreamResult.Token(content))
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to parse stream token: ${e.message}")
                        }
                    }
                }
                
                reader.close()
                inputStream.close()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Stream error: ${e.message}", e)
            emit(StreamResult.Error(e.message ?: "流式传输错误"))
        }
    }.flowOn(Dispatchers.IO)
    
    /**
     * 非流式输出（兼容旧代码）
     */
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
                            val jsonObj = body?.let { gson.fromJson(it, JsonObject::class.java) }
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
        val allMessages = mutableListOf<ChatMessage>()
        
        // 始终在开头添加系统提示词（控制思考模式）
        val thinkTag = if (thinkMode) " /think" else " /no_think"
        allMessages.add(ChatMessage("system", "You are a helpful assistant.$thinkTag"))
        
        // 添加历史消息（跳过已有的 system 消息，避免重复）
        historyMessages.forEach { msg ->
            if (msg.role != "system") {
                allMessages.add(msg)
            }
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
