package com.unciv.logic.automation.agent

import com.unciv.logic.civilization.Civilization
import com.unciv.utils.Log
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import java.util.concurrent.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class MultiProviderAgentPlanProvider(
    private val apiKey: String,
    private val baseUrl: String,
    private val provider: LlmProvider,
    private val model: String,
    private val requestTimeoutMs: Long,
    private val connectTimeoutMs: Long,
    private val socketTimeoutMs: Long,
    private val maxAttempts: Int,
    private val retryDelayMs: Long,
) : AgentPlanProvider {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private val client = HttpClient(CIO) {
        engine {
            requestTimeout = requestTimeoutMs
            endpoint.connectTimeout = connectTimeoutMs
            endpoint.socketTimeout = socketTimeoutMs
            endpoint.connectAttempts = 2
        }
        install(HttpTimeout) {
            requestTimeoutMillis = requestTimeoutMs
            connectTimeoutMillis = connectTimeoutMs
            socketTimeoutMillis = socketTimeoutMs
        }
        install(ContentNegotiation) {
            json(json)
        }
    }

    override fun buildPlan(observation: AgentObservation, civInfo: Civilization): AgentActionPlan? = runBlocking {
        throwIfCancelled()
        val prompt = AgentPromptBuilder.build(observation)
        AgentObservability.record(
            type = "llm_request",
            message = "Sending prompt to LLM provider",
            civName = civInfo.civName,
            turn = civInfo.gameInfo.turns,
            details = mapOf(
                "provider" to provider.name,
                "model" to model,
                "baseUrl" to baseUrl,
                "requestTimeoutMs" to requestTimeoutMs.toString(),
                "connectTimeoutMs" to connectTimeoutMs.toString(),
                "socketTimeoutMs" to socketTimeoutMs.toString(),
                "maxAttempts" to maxAttempts.toString(),
                "prompt" to prompt,
            ),
        )

        val planText = when {
            isGatewayBaseUrl(baseUrl) -> requestOpenAiCompatible(prompt, civInfo.civName, civInfo.gameInfo.turns)
            provider == LlmProvider.Google -> requestGoogleDirect(prompt, civInfo.civName, civInfo.gameInfo.turns)
            provider == LlmProvider.Anthropic -> requestAnthropicDirect(prompt, civInfo.civName, civInfo.gameInfo.turns)
            else -> requestOpenAiCompatible(prompt, civInfo.civName, civInfo.gameInfo.turns)
        } ?: return@runBlocking null
        throwIfCancelled()

        AgentObservability.record(
            type = "llm_response",
            message = "Received raw LLM response",
            civName = civInfo.civName,
            turn = civInfo.gameInfo.turns,
            details = mapOf(
                "provider" to provider.name,
                "model" to model,
                "rawResponse" to planText,
            ),
        )

        try {
            val parsedPlan = json.decodeFromString<AgentActionPlan>(planText)
            AgentObservability.record(
                type = "llm_plan_parsed",
                message = "Structured plan decoded successfully",
                civName = civInfo.civName,
                turn = civInfo.gameInfo.turns,
                details = mapOf(
                    "actions" to parsedPlan.actions.size.toString(),
                    "handoffToLegacyAI" to parsedPlan.handoffToLegacyAI.toString(),
                    "notes" to (parsedPlan.notes ?: ""),
                    "parsedPlan" to json.encodeToString(parsedPlan),
                ),
            )
            parsedPlan
        } catch (ex: SerializationException) {
            Log.debug("AI (agent): structured output decoding failed, provider=%s", provider)
            Log.debug("AI (agent): decode exception", ex)
            AgentObservability.record(
                type = "llm_parse_error",
                message = "Failed to decode structured plan",
                civName = civInfo.civName,
                turn = civInfo.gameInfo.turns,
                details = mapOf(
                    "provider" to provider.name,
                    "error" to (ex.message ?: ex::class.simpleName.orEmpty()),
                    "rawResponse" to planText,
                ),
            )
            null
        }
    }

    private suspend fun requestOpenAiCompatible(prompt: String, civName: String, turn: Int): String? {
        val url = "${baseUrl.normalizedBase()}/v1/chat/completions"
        val payload = buildJsonObject {
            put("model", JsonPrimitive(model))
            put("temperature", JsonPrimitive(0.2))
            put("response_format", buildJsonObject { put("type", JsonPrimitive("json_object")) })
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", JsonPrimitive("user"))
                    put("content", JsonPrimitive(prompt))
                })
            })
        }

        val responseJson = postJson(url, payload, civName = civName, turn = turn) ?: return null
        val contentElement = responseJson["choices"]
            ?.jsonArray
            ?.firstOrNull()
            ?.jsonObject
            ?.get("message")
            ?.jsonObject
            ?.get("content")
            ?: return null

        return contentElement.toContentString().trim().ifEmpty { null }
    }

    private suspend fun requestAnthropicDirect(prompt: String, civName: String, turn: Int): String? {
        val url = "${baseUrl.normalizedBase()}/v1/messages"
        val payload = buildJsonObject {
            put("model", JsonPrimitive(model))
            put("max_tokens", JsonPrimitive(1500))
            put("temperature", JsonPrimitive(0.2))
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", JsonPrimitive("user"))
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", JsonPrimitive("text"))
                            put("text", JsonPrimitive(prompt))
                        })
                    })
                })
            })
        }

        val responseJson = postJson(
            url,
            payload,
            extraHeaders = mapOf("x-api-key" to apiKey, "anthropic-version" to "2023-06-01"),
            useBearerToken = false,
            civName = civName,
            turn = turn,
        ) ?: return null

        return responseJson["content"]
            ?.jsonArray
            ?.firstOrNull()
            ?.jsonObject
            ?.get("text")
            ?.jsonPrimitive
            ?.content
            ?.trim()
            ?.ifEmpty { null }
    }

    private suspend fun requestGoogleDirect(prompt: String, civName: String, turn: Int): String? {
        val url = "${baseUrl.normalizedBase()}/v1beta/models/$model:generateContent?key=$apiKey"
        val payload = buildJsonObject {
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("parts", buildJsonArray {
                        add(buildJsonObject {
                            put("text", JsonPrimitive(prompt))
                        })
                    })
                })
            })
            put("generationConfig", buildJsonObject {
                put("temperature", JsonPrimitive(0.2))
                put("responseMimeType", JsonPrimitive("application/json"))
            })
        }

        val responseJson = postJson(url, payload, useBearerToken = false, civName = civName, turn = turn) ?: return null
        return responseJson["candidates"]
            ?.jsonArray
            ?.firstOrNull()
            ?.jsonObject
            ?.get("content")
            ?.jsonObject
            ?.get("parts")
            ?.jsonArray
            ?.firstOrNull()
            ?.jsonObject
            ?.get("text")
            ?.jsonPrimitive
            ?.content
            ?.trim()
            ?.ifEmpty { null }
    }

    private suspend fun postJson(
        url: String,
        payload: JsonElement,
        extraHeaders: Map<String, String> = emptyMap(),
        useBearerToken: Boolean = true,
        civName: String? = null,
        turn: Int? = null,
    ): JsonObject? {
        var lastException: Exception? = null
        var attemptsUsed = 0

        repeat(maxAttempts) { index ->
            attemptsUsed = index + 1
            throwIfCancelled()
            try {
                val response = client.post(url) {
                    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    if (useBearerToken) header(HttpHeaders.Authorization, "Bearer $apiKey")
                    for ((name, value) in extraHeaders) header(name, value)
                    setBody(payload)
                }
                throwIfCancelled()

                if (response.status == HttpStatusCode.OK) {
                    return json.parseToJsonElement(response.body<String>()).jsonObject
                }

                val shouldRetry = attemptsUsed < maxAttempts && response.status.value.let { it == 429 || it >= 500 }
                if (shouldRetry) {
                    Log.debug(
                        "AI (agent): provider returned %s on attempt %s/%s, retrying url=%s",
                        response.status,
                        attemptsUsed,
                        maxAttempts,
                        url,
                    )
                    delay(retryDelayMs)
                    return@repeat
                }

                Log.debug("AI (agent): provider returned %s, url=%s", response.status, url)
                AgentObservability.record(
                    type = "llm_http_error",
                    message = "Provider returned non-200 status",
                    civName = civName,
                    turn = turn,
                    details = mapOf(
                        "status" to response.status.toString(),
                        "url" to url,
                        "provider" to provider.name,
                        "model" to model,
                        "attempts" to attemptsUsed.toString(),
                        "requestTimeoutMs" to requestTimeoutMs.toString(),
                        "connectTimeoutMs" to connectTimeoutMs.toString(),
                        "socketTimeoutMs" to socketTimeoutMs.toString(),
                    ),
                )
                return null
            } catch (ex: Exception) {
                if (ex is CancellationException || Thread.currentThread().isInterrupted) {
                    throw cancellationException(ex)
                }
                lastException = ex
                if (attemptsUsed < maxAttempts) {
                    Log.debug(
                        "AI (agent): provider request failed on attempt %s/%s, retrying url=%s",
                        attemptsUsed,
                        maxAttempts,
                        url,
                    )
                    Log.debug("AI (agent): provider exception", ex)
                    delay(retryDelayMs)
                    return@repeat
                }
            }
        }

        val ex = lastException ?: return null
        Log.debug("AI (agent): provider request failed, url=%s", url)
        Log.debug("AI (agent): provider exception", ex)
        AgentObservability.record(
            type = "llm_request_error",
            message = "Provider request failed",
            civName = civName,
            turn = turn,
            details = mapOf(
                "url" to url,
                "provider" to provider.name,
                "model" to model,
                "attempts" to attemptsUsed.toString(),
                "requestTimeoutMs" to requestTimeoutMs.toString(),
                "connectTimeoutMs" to connectTimeoutMs.toString(),
                "socketTimeoutMs" to socketTimeoutMs.toString(),
                "error" to (ex.message ?: ex::class.simpleName.orEmpty()),
            ),
        )
        return null
    }

    private fun throwIfCancelled() {
        if (Thread.currentThread().isInterrupted) {
            throw CancellationException("Planner request cancelled")
        }
    }

    private fun cancellationException(cause: Exception): CancellationException {
        return CancellationException("Planner request cancelled").apply { initCause(cause) }
    }

    private fun String.normalizedBase(): String = trim().trimEnd('/')

    private fun isGatewayBaseUrl(url: String): Boolean {
        val normalized = url.normalizedBase().lowercase()
        return normalized.contains("ai-gateway.andrew.cmu.edu")
    }

    private fun JsonElement.toContentString(): String {
        return when {
            this is kotlinx.serialization.json.JsonPrimitive -> content
            this is kotlinx.serialization.json.JsonArray -> this
                .mapNotNull { element ->
                    when (element) {
                        is kotlinx.serialization.json.JsonObject -> element["text"]?.jsonPrimitive?.contentOrNull
                        is kotlinx.serialization.json.JsonPrimitive -> element.contentOrNull
                        else -> null
                    }
                }
                .joinToString("\n")
            else -> toString()
        }
    }

    companion object {
        const val defaultGatewayBaseUrl = "https://ai-gateway.andrew.cmu.edu"
        const val defaultRequestTimeoutMs = 60_000L
        const val defaultConnectTimeoutMs = 10_000L
        const val defaultSocketTimeoutMs = 60_000L
        const val defaultMaxAttempts = 1
        const val defaultRetryDelayMs = 1_000L

        fun defaultModel(provider: LlmProvider): String = when (provider) {
            LlmProvider.OpenAI -> "gpt-4o-mini"
            LlmProvider.Google -> "gemini-2.5-flash"
            LlmProvider.Anthropic -> "claude-3-5-sonnet-latest"
        }

        fun envLong(name: String, default: Long): Long =
            System.getenv(name)?.trim()?.toLongOrNull()?.takeIf { it > 0 } ?: default

        fun envInt(name: String, default: Int): Int =
            System.getenv(name)?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: default
    }
}
