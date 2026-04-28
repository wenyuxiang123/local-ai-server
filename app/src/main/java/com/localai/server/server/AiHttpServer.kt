package com.localai.server.server

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.localai.server.engine.LlamaEngine
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.TimeoutCancellationException

/**
 * HTTP 服务器，提供 OpenAI 兼容 API
 */
class AiHttpServer(
    private val engine: LlamaEngine,
    port: Int = 8080
) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "AiHttpServer"
    }

    private val gson = Gson()
    private val serverScope = CoroutineScope(Dispatchers.IO)

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        Log.d(TAG, "Request: $method $uri")

        return when {
            uri == "/" && method == Method.GET -> {
                jsonResponse("""{"status": "ok", "message": "Local AI Server running"}""")
            }

            uri == "/v1/models" && method == Method.GET -> {
                handleListModels()
            }

            uri == "/v1/chat/completions" && method == Method.POST -> {
                handleChatCompletions(session)
            }

            uri == "/health" && method == Method.GET -> {
                jsonResponse("""{"status": "healthy"}""")
            }

            else -> {
                newFixedLengthResponse(
                    Response.Status.NOT_FOUND,
                    "application/json",
                    """{"error": "Not found"}"""
                )
            }
        }
    }

    private fun handleListModels(): Response {
        val modelName = engine.getLoadedModelName() ?: "unknown"
        val response = """
            {
                "object": "list",
                "data": [
                    {
                        "id": "$modelName",
                        "object": "model",
                        "created": ${System.currentTimeMillis() / 1000},
                        "owned_by": "local"
                    }
                ]
            }
        """.trimIndent()
        return jsonResponse(response)
    }

    private fun handleChatCompletions(session: IHTTPSession): Response {
        try {
            // 解析请求体
            val files = ConcurrentHashMap<String, String>()
            session.parseBody(files)
            val body = files["postData"] ?: files.values.firstOrNull() ?: ""

            Log.d(TAG, "Request body: ${body.take(500)}")

            val request = gson.fromJson(body, ChatRequest::class.java)

            if (request.messages.isNullOrEmpty()) {
                return errorResponse("messages is required")
            }

            // 检查模型是否已加载
            if (!engine.isModelLoaded()) {
                return errorResponse("Model not loaded", 503)
            }

            // 构建完整 prompt
            val prompt = buildPrompt(request.messages)

            Log.i(TAG, "Processing prompt: ${prompt.take(100)}...")

            // 同步生成响应
            var generatedText: String? = null
            var genError: String? = null
            
            runBlocking {
                try {
                    generatedText = withTimeout(120_000L) {
                        engine.generate(
                            prompt = prompt,
                            maxTokens = request.max_tokens ?: 2048,
                            temperature = request.temperature ?: 0.7f
                        )
                    }
                } catch (e: IllegalStateException) {
                    Log.e(TAG, "Engine state error: ${e.message}", e)
                    genError = "模型状态异常: ${e.message}"
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    Log.e(TAG, "Generation timeout", e)
                    genError = "生成超时，模型推理时间过长"
                } catch (e: Exception) {
                    Log.e(TAG, "Generation failed: ${e.message}", e)
                    genError = "生成失败: ${e.message}"
                }
            }

            if (generatedText == null) {
                return errorResponse(genError ?: "Generation failed", 500)
            }

            Log.i(TAG, "Generated response: ${generatedText.take(100)}...")

            // 构建响应
            val chatResponse = ChatResponse(
                id = "chatcmpl-${System.currentTimeMillis()}",
                choices = listOf(
                    Choice(
                        index = 0,
                        message = ResponseMessage(
                            role = "assistant",
                            content = generatedText
                        ),
                        finish_reason = "stop"
                    )
                ),
                usage = Usage(
                    prompt_tokens = prompt.length / 4,
                    completion_tokens = generatedText.length / 4,
                    total_tokens = (prompt.length + generatedText.length) / 4
                )
            )

            return jsonResponse(gson.toJson(chatResponse))

        } catch (e: Exception) {
            Log.e(TAG, "Error handling chat request", e)
            return errorResponse(e.message ?: "Internal error", 500)
        }
    }

    private fun buildPrompt(messages: List<ChatMessage>): String {
        val sb = StringBuilder()

        for (msg in messages) {
            when (msg.role) {
                "system" -> {
                    sb.append("<|im_start|>system\n${msg.content}<|im_end|>\n")
                }
                "user" -> {
                    sb.append("<|im_start|>user\n${msg.content}<|im_end|>\n")
                }
                "assistant" -> {
                    sb.append("<|im_start|>assistant\n${msg.content}<|im_end|>\n")
                }
            }
        }

        sb.append("<|im_start|>assistant\n")

        return sb.toString()
    }

    private fun jsonResponse(json: String): Response {
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json",
            json
        )
    }

    private fun errorResponse(message: String, status: Int = 400): Response {
        val error = """{"error": {"message": "$message", "type": "api_error"}}"""
        return newFixedLengthResponse(
            Response.Status.lookup(status) ?: Response.Status.BAD_REQUEST,
            "application/json",
            error
        )
    }
}

// 请求/响应数据类
data class ChatMessage(
    val role: String,
    val content: String
)

data class ChatRequest(
    val model: String? = null,
    val messages: List<ChatMessage>? = null,
    val max_tokens: Int? = 2048,
    val temperature: Float? = 0.7f,
    val stream: Boolean? = false
)

data class ChatResponse(
    val id: String,
    val `object`: String = "chat.completion",
    val created: Long = System.currentTimeMillis() / 1000,
    val model: String = "local",
    val choices: List<Choice>,
    val usage: Usage
)

data class Choice(
    val index: Int,
    val message: ResponseMessage,
    val finish_reason: String
)

data class ResponseMessage(
    val role: String,
    val content: String
)

data class Usage(
    val prompt_tokens: Int,
    val completion_tokens: Int,
    val total_tokens: Int
)
