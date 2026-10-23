package com.newlychat.app.data

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.HeaderMap
import retrofit2.http.POST
import retrofit2.http.QueryMap
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
    suspend fun sendMessage(
        messages: List<ProviderMessage>,
        apiKey: String,
        model: String,
        temperature: Double = 1.0,
        maxTokens: Int = 2048
    ): AiResponse
}

interface ProviderApi {
    @POST
    suspend fun post(
        @Url url: String,
        @HeaderMap headers: Map<String, String>,
        @Body body: JsonObject
    ): Response<JsonObject>

    @GET
    suspend fun get(
        @Url url: String,
        @HeaderMap headers: Map<String, String>,
        @QueryMap query: Map<String, String>
    ): Response<JsonObject>
}

class ProviderFactory {
    private val api = Retrofit.Builder()
        .baseUrl("https://api.anthropic.com/")
        .client(
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .callTimeout(150, TimeUnit.SECONDS)
                .build()
        )
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(ProviderApi::class.java)

    fun create(type: AiProviderType): AiProvider = HttpAiProvider(type, api)

    suspend fun listModels(type: AiProviderType, apiKey: String): List<String> =
        withContext(Dispatchers.IO) {
            when (type) {
                AiProviderType.CLAUDE -> listClaudeModels(apiKey)
                AiProviderType.OPENAI -> listOpenAiModels(apiKey)
                AiProviderType.GEMINI -> listGeminiModels(apiKey)
                AiProviderType.MISTRAL -> listMistralModels(apiKey)
            }
        }

    private suspend fun listClaudeModels(apiKey: String): List<String> {
        val headers = mapOf("x-api-key" to apiKey, "anthropic-version" to "2023-06-01")
        val models = mutableListOf<String>()
        var cursor: String? = null
        do {
            val query = buildMap {
                put("limit", "1000")
                cursor?.let { put("after_id", it) }
            }
            val response = api.get("https://api.anthropic.com/v1/models", headers, query).bodyOrThrow()
            val page = response.getAsJsonArray("data") ?: JsonArray()
            models += page.mapNotNull { item ->
                val model = item.asJsonObject
                val lifecycle = model.get("lifecycle")?.asString
                model.get("id")?.asString?.takeIf { lifecycle == null || lifecycle == "active" }
            }
            cursor = if (response.get("has_more")?.asBoolean == true) {
                response.get("last_id")?.asString ?: page.lastOrNull()?.asJsonObject?.get("id")?.asString
            } else {
                null
            }
        } while (cursor != null)
        return models.distinct().sorted()
    }

    private suspend fun listOpenAiModels(apiKey: String): List<String> {
        val response = api.get(
            "https://api.openai.com/v1/models",
            mapOf("Authorization" to "Bearer $apiKey"),
            emptyMap()
        ).bodyOrThrow()
        return (response.getAsJsonArray("data") ?: JsonArray()).mapNotNull { item ->
            item.asJsonObject.get("id")?.asString?.takeIf(::isChatModel)
        }.distinct().sorted()
    }

    private suspend fun listGeminiModels(apiKey: String): List<String> {
        val headers = mapOf("x-goog-api-key" to apiKey)
        val models = mutableListOf<String>()
        var pageToken: String? = null
        do {
            val query = buildMap {
                put("pageSize", "1000")
                pageToken?.let { put("pageToken", it) }
            }
            val response = api.get(
                "https://generativelanguage.googleapis.com/v1beta/models",
                headers,
                query
            ).bodyOrThrow()
            response.getAsJsonArray("models")?.forEach { item ->
                val model = item.asJsonObject
                val supportsChat = model.getAsJsonArray("supportedGenerationMethods")
                    ?.any { it.asString == "generateContent" } == true
                if (supportsChat) {
                    model.get("name")?.asString?.removePrefix("models/")?.let(models::add)
                }
            }
            pageToken = response.get("nextPageToken")?.asString?.takeIf { it.isNotBlank() }
        } while (pageToken != null)
        return models.distinct().sorted()
    }

    private suspend fun listMistralModels(apiKey: String): List<String> {
        val response = api.get(
            "https://api.mistral.ai/v1/models",
            mapOf("Authorization" to "Bearer $apiKey"),
            emptyMap()
        ).bodyOrThrow()
        return (response.getAsJsonArray("data") ?: JsonArray()).mapNotNull { item ->
            val model = item.asJsonObject
            val supportsChat = model.getAsJsonObject("capabilities")
                ?.get("completion_chat")?.asBoolean == true
            model.get("id")?.asString?.takeIf { supportsChat }
        }.distinct().sorted()
    }

    private fun isChatModel(id: String): Boolean {
        val lowerId = id.lowercase()
        val looksLikeChatModel = lowerId.startsWith("gpt-") ||
            listOf("o1", "o3", "o4").any { lowerId == it || lowerId.startsWith("$it-") } ||
            lowerId == "chatgpt-4o-latest"
        val nonChatTerms = listOf("audio", "transcribe", "tts", "embedding", "realtime", "moderation")
        return looksLikeChatModel && nonChatTerms.none(lowerId::contains)
    }

    private fun Response<JsonObject>.bodyOrThrow(): JsonObject {
        if (!isSuccessful) {
            val message = errorBody()?.string()?.take(500)
            throw IllegalStateException(message ?: "Unable to load models (${code()})")
        }
        return body() ?: throw IllegalStateException("Empty response while loading models.")
    }
}

private class HttpAiProvider(
    override val type: AiProviderType,
    private val api: ProviderApi
) : AiProvider {
    override suspend fun sendMessage(
        messages: List<ProviderMessage>,
        apiKey: String,
        model: String,
        temperature: Double,
        maxTokens: Int
    ): AiResponse = withContext(Dispatchers.IO) {
        val (url, headers, body) = request(messages, apiKey, model, temperature, maxTokens)
        val response = api.post(url, headers, body)
        if (!response.isSuccessful) {
            val message = response.errorBody()?.string()?.take(500)
            throw IllegalStateException(message ?: "Provider request failed (${response.code()})")
        }
        val json = response.body() ?: throw IllegalStateException("Empty response from ${type.label}")
        parseResponse(json, model)
    }

    private fun request(
        messages: List<ProviderMessage>,
        apiKey: String,
        model: String,
        temperature: Double,
        maxTokens: Int
    ): Triple<String, Map<String, String>, JsonObject> {
        val body = JsonObject()
        val headers = mutableMapOf("Content-Type" to "application/json")
        val url = when (type) {
            AiProviderType.CLAUDE -> {
                headers["x-api-key"] = apiKey
                headers["anthropic-version"] = "2023-06-01"
                body.addProperty("model", model)
                body.addProperty("max_tokens", maxTokens)
                body.addProperty("temperature", temperature.coerceIn(0.0, 1.0))
                body.add("messages", messages.asJsonArray())
                "https://api.anthropic.com/v1/messages"
            }
            AiProviderType.OPENAI, AiProviderType.MISTRAL -> {
                headers["Authorization"] = "Bearer " + apiKey
                body.addProperty("model", model)
                if (type == AiProviderType.OPENAI && isReasoningModel(model)) {
                    body.addProperty("max_completion_tokens", maxTokens)
                } else {
                    body.addProperty("max_tokens", maxTokens)
                    body.addProperty("temperature", temperature.coerceIn(0.0, if (type == AiProviderType.MISTRAL) 1.5 else 2.0))
                }
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
                body.add("generationConfig", JsonObject().apply {
                    addProperty("temperature", temperature.coerceIn(0.0, 2.0))
                    addProperty("maxOutputTokens", maxTokens)
                })
                "https://generativelanguage.googleapis.com/v1beta/models/${model.removePrefix("models/")}:generateContent"
            }
        }
        return Triple(url, headers, body)
    }

    private fun isReasoningModel(model: String): Boolean =
        listOf("o1", "o3", "o4").any { model == it || model.startsWith("$it-") }

    private fun parseResponse(json: JsonObject, requestedModel: String): AiResponse {
        val usage = json.getAsJsonObject("usage")
        val content: String
        val model: String
        val id: String
        val finishReason: String
        when (type) {
            AiProviderType.CLAUDE -> {
                content = json.getAsJsonArray("content")?.firstOrNull()?.asJsonObject
                    ?.get("text")?.asString.orEmpty()
                model = json.get("model")?.asString ?: requestedModel
                id = json.get("id")?.asString.orEmpty()
                finishReason = json.get("stop_reason")?.asString.orEmpty()
            }
            AiProviderType.OPENAI, AiProviderType.MISTRAL -> {
                val choice = json.getAsJsonArray("choices")?.firstOrNull()?.asJsonObject
                content = choice?.getAsJsonObject("message")?.get("content")?.asString.orEmpty()
                model = json.get("model")?.asString ?: requestedModel
                id = json.get("id")?.asString.orEmpty()
                finishReason = choice?.get("finish_reason")?.asString.orEmpty()
            }
            AiProviderType.GEMINI -> {
                content = json.getAsJsonArray("candidates")?.firstOrNull()?.asJsonObject
                    ?.getAsJsonObject("content")?.getAsJsonArray("parts")?.firstOrNull()
                    ?.asJsonObject?.get("text")?.asString.orEmpty()
                model = json.get("modelVersion")?.asString ?: requestedModel
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
    this@asJsonArray.forEach { message ->
        add(JsonObject().apply {
            addProperty("role", message.role)
            addProperty("content", message.content)
        })
    }
}
