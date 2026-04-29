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
import com.localai.server.util.FileLog

/**
 * HTTP 服务器，提供 OpenAI 兼容 API
 * 支持返回 llama.cpp 优化配置信息
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

            uri == "/v1/config" && method == Method.GET -> {
                handleGetConfig()
            }

            uri == "/health" && method == Method.GET -> {
                handleHealth()
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

    /**
     * 健康检查端点 - 返回服务器状态和优化配置
     */
    private fun handleHealth(): Response {
        val modelLoaded = engine.isModelLoaded()
        val modelName = engine.getLoadedModelName()
        val optConfig = engine.getOptimizationConfig()
        
        val health = JsonObject().apply {
            addProperty("status", if (modelLoaded) "healthy" else "model_not_loaded")
            addProperty("model_loaded", modelLoaded)
            addProperty("model_name", modelName ?: "none")
            addProperty("timestamp", System.currentTimeMillis() / 1000)
        }
        
        return jsonResponse(gson.toJson(health))
    }

    /**
     * 获取当前优化配置
     */
    private fun handleGetConfig(): Response {
        val optConfig = engine.getOptimizationConfig()
        val config = JsonObject().apply {
            addProperty("n_ctx", optConfig.nCtx)
            addProperty("n_threads", optConfig.nThreads)
            addProperty("n_batch", optConfig.nBatch)
            addProperty("flash_attn", optConfig.flashAttn)
            addProperty("cache_type", optConfig.cacheType)
            addProperty("n_gpu_layers", optConfig.nGpuLayers)
            addProperty("backend", optConfig.backend)
            addProperty("gpu_enabled", optConfig.nGpuLayers != 0)
        }
        
        return jsonResponse(gson.toJson(config))
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
                        "owned_by": "local",
                        "optimization": {
                            "flash_attn": ${engine.getOptimizationConfig().flashAttn},
                            "cache_type": "${engine.getOptimizationConfig().cacheType}",
                            "gpu_layers": ${engine.getOptimizationConfig().nGpuLayers}
                        }
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
            val modelLoaded = engine.isModelLoaded()
            val modelName = engine.getLoadedModelName()
            Log.i(TAG, "Chat request received: modelLoaded=$modelLoaded, modelName=$modelName")
            FileLog.log(TAG, "Chat request: modelLoaded=$modelLoaded, model=$modelName, messages=${request.messages?.size}")
            if (!modelLoaded) {
                Log.w(TAG, "Model not loaded! Returning 503")
                return errorResponse("Model not loaded", 503)
            }

            // 构建完整 prompt
            val prompt = buildPrompt(request.messages)

            Log.i(TAG, "Processing prompt: ${prompt.take(100)}...")
            FileLog.log(TAG, "Processing prompt: ${prompt.take(100)}..., maxTokens=${request.max_tokens ?: 2048}")

            // 同步生成响应
            var generatedText: String? = null
            var genError: String? = null
            
            runBlocking {
                try {
                    generatedText = withTimeout(600_000L) {
                        engine.generate(
                            prompt = prompt,
                            maxTokens = request.max_tokens ?: 2048,
                            temperature = request.temperature ?: 0.7f
                        )
                    }
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    Log.e(TAG, "Generation timeout after 600s", e)
                    genError = "生成超时(600s)，模型推理时间过长，请减少输入长度或换用更小模型"
                    FileLog.log(TAG, "Generation TIMEOUT after 600s")
                } catch (e: IllegalStateException) {
                    Log.e(TAG, "Engine state error: ${e.message}", e)
                    genError = "模型状态异常: ${e.message}"
                } catch (e: Exception) {
                    Log.e(TAG, "Generation failed: ${e.message}", e)
                    genError = "生成失败: ${e.message}"
                }
            }

            if (generatedText == null) {
                return errorResponse(genError ?: "Generation failed", 500)
            }

            Log.i(TAG, "Generated response: ${generatedText.take(100)}...")
            FileLog.log(TAG, "Generated response: ${generatedText.length} chars, first=${generatedText.take(50)}")

            // 过滤思考内容（Qwen3 thinking mode）
            generatedText = filterThinkingContent(generatedText)
            FileLog.log(TAG, "After filter: ${generatedText.length} chars")

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
                    // 默认关闭思考模式，提升日常对话响应速度
                    val systemContent = if (msg.content.contains("/think") || msg.content.contains("/no_think")) {
                        msg.content  // 用户已手动指定，不覆盖
                    } else {
                        "${msg.content} /no_think"  // 默认关闭思考
                    }
                    sb.append("<|im_start|>system\n${systemContent}<|im_end|>\n")
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

    /**
     * 过滤思考内容（Qwen3 thinking mode）
     * 移除 <think...</think 块
     */
    private fun filterThinkingContent(text: String): String {
        val filtered = text.replace(Regex("<think[\\s\\S]*?</think\\s*>", RegexOption.MULTILINE), "").trim()
        return if (filtered.isNotBlank()) filtered else text
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

/**
 * 优化配置响应
 */
data class OptimizationConfigResponse(
    val n_ctx: Int,
    val n_threads: Int,
    val n_batch: Int,
    val flash_attn: Boolean,
    val cache_type: String,
    val n_gpu_layers: Int,
    val backend: String,
    val gpu_enabled: Boolean
)
