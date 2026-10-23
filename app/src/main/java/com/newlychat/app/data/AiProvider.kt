package com.newlychat.app.data

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.HeaderMap
import retrofit2.http.POST
import retrofit2.http.Url

enum class AiProviderType(val label: String, val keyName: String, val model: String) {
    CLAUDE("Claude", "api_key_claude", "claude-3-5-sonnet-20241022"),
    OPENAI("OpenAI", "api_key_openai", "gpt-4o-mini"),
    GEMINI("Gemini", "api_key_gemini", "gemini-1.5-flash"),
    MISTRAL("Mistral", "api_key_mistral", "mistral-small-latest")
}

data class ProviderMessage(val role: String, val content: String)

data class AiResponse(
    val id: String,
    val content: String,
    val model: String,
    val provider: AiProviderType,
    val inputTokens: Int,
    val outputTokens: Int,
    val finishReason: String
)

interface AiProvider {
    val type: AiProviderType
    suspend fun sendMessage(messages: List<ProviderMessage>, apiKey: String): AiResponse
}

interface ProviderApi {
    @POST
    suspend fun post(
        @Url url: String,
        @HeaderMap headers: Map<String, String>,
        @Body body: JsonObject
    ): Response<JsonObject>
}

class ProviderFactory {
    private val api = Retrofit.Builder()
        .baseUrl("https://api.anthropic.com/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(ProviderApi::class.java)

    fun create(type: AiProviderType): AiProvider = HttpAiProvider(type, api)
}

private class HttpAiProvider(
    override val type: AiProviderType,
    private val api: ProviderApi
) : AiProvider {
    override suspend fun sendMessage(
        messages: List<ProviderMessage>,
        apiKey: String
    ): AiResponse = withContext(Dispatchers.IO) {
        val (url, headers, body) = request(messages, apiKey)
        val response = api.post(url, headers, body)
        if (!response.isSuccessful) {
            val message = response.errorBody()?.string()?.take(500)
            throw IllegalStateException(message ?: "Provider request failed (${response.code()})")
        }
        val json = response.body() ?: throw IllegalStateException("Empty response from ${type.label}")
        parseResponse(json)
    }

    private fun request(messages: List<ProviderMessage>, apiKey: String): Triple<String, Map<String, String>, JsonObject> {
        val body = JsonObject()
        val headers = mutableMapOf("Content-Type" to "application/json")
        val url = when (type) {
            AiProviderType.CLAUDE -> {
                headers["x-api-key"] = apiKey
                headers["anthropic-version"] = "2023-06-01"
                body.addProperty("model", type.model)
                body.addProperty("max_tokens", 2048)
                body.add("messages", messages.asJsonArray())
                "https://api.anthropic.com/v1/messages"
            }
            AiProviderType.OPENAI, AiProviderType.MISTRAL -> {
                headers["Authorization"] = "Bearer " + apiKey
                body.addProperty("model", type.model)
                body.addProperty("max_tokens", 2048)
                body.add("messages", messages.asJsonArray())
                val base = if (type == AiProviderType.OPENAI) "https://api.openai.com" else "https://api.mistral.ai"
                "$base/v1/chat/completions"
            }
            AiProviderType.GEMINI -> {
                headers["x-goog-api-key"] = apiKey
                body.add("contents", JsonArray().apply {
                    messages.forEach { message ->
                        add(JsonObject().apply {
                            addProperty("role", if (message.role == "assistant") "model" else "user")
                            add("parts", JsonArray().apply {
                                add(JsonObject().apply { addProperty("text", message.content) })
                            })
                        })
                    }
                })
                "https://generativelanguage.googleapis.com/v1beta/models/${type.model}:generateContent"
            }
        }
        return Triple(url, headers, body)
    }

    private fun parseResponse(json: JsonObject): AiResponse {
        val usage = json.getAsJsonObject("usage")
        val content: String
        val model: String
        val id: String
        val finishReason: String
        when (type) {
            AiProviderType.CLAUDE -> {
                content = json.getAsJsonArray("content")?.firstOrNull()?.asJsonObject
                    ?.get("text")?.asString.orEmpty()
                model = json.get("model")?.asString ?: type.model
                id = json.get("id")?.asString.orEmpty()
                finishReason = json.get("stop_reason")?.asString.orEmpty()
            }
            AiProviderType.OPENAI, AiProviderType.MISTRAL -> {
                val choice = json.getAsJsonArray("choices")?.firstOrNull()?.asJsonObject
                content = choice?.getAsJsonObject("message")?.get("content")?.asString.orEmpty()
                model = json.get("model")?.asString ?: type.model
                id = json.get("id")?.asString.orEmpty()
                finishReason = choice?.get("finish_reason")?.asString.orEmpty()
            }
            AiProviderType.GEMINI -> {
                content = json.getAsJsonArray("candidates")?.firstOrNull()?.asJsonObject
                    ?.getAsJsonObject("content")?.getAsJsonArray("parts")?.firstOrNull()
                    ?.asJsonObject?.get("text")?.asString.orEmpty()
                model = type.model
                id = json.get("responseId")?.asString.orEmpty()
                finishReason = json.getAsJsonArray("candidates")?.firstOrNull()?.asJsonObject
                    ?.get("finishReason")?.asString.orEmpty()
            }
        }
        val tokenUsage = if (type == AiProviderType.GEMINI) json.getAsJsonObject("usageMetadata") else usage
        val input = tokenUsage?.get(if (type == AiProviderType.GEMINI) "promptTokenCount" else if (type == AiProviderType.CLAUDE) "input_tokens" else "prompt_tokens")?.asInt ?: 0
        val output = tokenUsage?.get(if (type == AiProviderType.GEMINI) "candidatesTokenCount" else if (type == AiProviderType.CLAUDE) "output_tokens" else "completion_tokens")?.asInt ?: 0
        return AiResponse(id, content, model, type, input, output, finishReason)
    }
}

private fun List<ProviderMessage>.asJsonArray(): JsonArray = JsonArray().apply {
    forEach { message ->
        add(JsonObject().apply {
            addProperty("role", message.role)
            addProperty("content", message.content)
        })
    }
}
