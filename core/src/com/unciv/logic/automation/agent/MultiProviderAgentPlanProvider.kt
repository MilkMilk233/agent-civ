package com.unciv.logic.automation.agent

import com.unciv.logic.civilization.Civilization
import com.unciv.utils.Log
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.timeout
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
import kotlin.math.min

class MultiProviderAgentPlanProvider(
    private val apiKey: String,
    private val baseUrl: String,
    private val provider: LlmProvider,
    private val strategistModel: String,
    private val tacticalModel: String,
    private val requestTimeoutMs: Long,
    private val connectTimeoutMs: Long,
    private val socketTimeoutMs: Long,
    private val maxAttempts: Int,
    private val retryDelayMs: Long,
    ) : AgentPlanProvider {

    private data class AttemptTimeouts(
        val requestTimeoutMs: Long,
        val connectTimeoutMs: Long,
        val socketTimeoutMs: Long,
    )

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

    override fun buildStrategistMemo(
        memory: AgentMemory,
        observation: AgentObservation,
        empireObservation: AgentEmpireObservation,
        civInfo: Civilization,
        refreshRequest: AgentStrategistRefreshRequest,
    ): AgentStrategicPlan? = runBlocking {
        throwIfCancelled()
        val strategistBriefJson = AgentStrategistPromptBuilder.strategistBriefJson(memory, observation, empireObservation, refreshRequest)
        val prompt = AgentStrategistPromptBuilder.build(memory, observation, empireObservation, refreshRequest)
        AgentObservability.record(
            type = "strategist_llm_request",
            message = "Sending strategist prompt to LLM provider",
            civName = civInfo.civName,
            turn = civInfo.gameInfo.turns,
            details = mapOf(
                "provider" to provider.name,
                "model" to strategistModel,
                "baseUrl" to baseUrl,
                "requestTimeoutMs" to requestTimeoutMs.toString(),
                "connectTimeoutMs" to connectTimeoutMs.toString(),
                "socketTimeoutMs" to socketTimeoutMs.toString(),
                "maxAttempts" to maxAttempts.toString(),
                "memoryJson" to AgentPromptBuilder.memoryJson(memory),
                "empireObservationJson" to AgentPromptBuilder.empireObservationJson(empireObservation),
                "strategistBriefJson" to strategistBriefJson,
                "refreshRequestJson" to json.encodeToString(refreshRequest),
                "prompt" to prompt,
            ),
        )

        val planText = requestStructuredText(prompt, civInfo.civName, civInfo.gameInfo.turns, eventPrefix = "strategist")
            ?: return@runBlocking null
        throwIfCancelled()

        AgentObservability.record(
            type = "strategist_llm_response",
            message = "Received raw strategist LLM response",
            civName = civInfo.civName,
            turn = civInfo.gameInfo.turns,
            details = mapOf(
                "provider" to provider.name,
                "model" to strategistModel,
                "rawResponse" to planText,
            ),
        )

        try {
            val parsedPlan = json.decodeFromString<AgentStrategicPlan>(planText)
            AgentObservability.record(
                type = "strategist_llm_plan_parsed",
                message = "Structured strategist memo decoded successfully",
                civName = civInfo.civName,
                turn = civInfo.gameInfo.turns,
                details = mapOf(
                    "winPath" to (parsedPlan.memo.winPath ?: ""),
                    "campaignStage" to parsedPlan.memo.campaignStage,
                    "decisiveObjective" to parsedPlan.memo.decisiveObjective,
                    "reviewInTurns" to parsedPlan.memo.reviewInTurns.toString(),
                    "notes" to (parsedPlan.notes ?: ""),
                    "parsedPlan" to json.encodeToString(parsedPlan),
                ),
            )
            parsedPlan
        } catch (ex: SerializationException) {
            Log.debug("AI (agent): strategist output decoding failed, provider=%s", provider)
            Log.debug("AI (agent): strategist decode exception", ex)
            AgentObservability.record(
                type = "strategist_llm_parse_error",
                message = "Failed to decode strategist memo",
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

    override fun buildPlan(
        memory: AgentMemory,
        observation: AgentObservation,
        empireObservation: AgentEmpireObservation,
        civInfo: Civilization,
        retryContext: AgentRetryContext?,
    ): AgentActionPlan? = runBlocking {
        throwIfCancelled()
        val prompt = AgentPromptBuilder.build(memory, observation, empireObservation, retryContext)
        val plannerBriefJson = AgentPromptBuilder.plannerBriefJson(memory, observation, empireObservation)
        AgentObservability.record(
            type = "llm_request",
            message = "Sending prompt to LLM provider",
            civName = civInfo.civName,
            turn = civInfo.gameInfo.turns,
            details = mapOf(
                "provider" to provider.name,
                "model" to tacticalModel,
                "baseUrl" to baseUrl,
                "requestTimeoutMs" to requestTimeoutMs.toString(),
                "connectTimeoutMs" to connectTimeoutMs.toString(),
                "socketTimeoutMs" to socketTimeoutMs.toString(),
                "maxAttempts" to maxAttempts.toString(),
                "memoryJson" to AgentPromptBuilder.memoryJson(memory),
                "empireObservationJson" to AgentPromptBuilder.empireObservationJson(empireObservation),
                "plannerBriefJson" to plannerBriefJson,
                "retryAttempt" to (retryContext?.retryAttempt?.toString() ?: "0"),
                "retryMax" to (retryContext?.maxRetries?.toString() ?: "0"),
                "retryContextJson" to (retryContext?.let { AgentPromptBuilder.retryContextJson(it) } ?: ""),
                "prompt" to prompt,
            ),
        )

        val planText = requestStructuredText(prompt, civInfo.civName, civInfo.gameInfo.turns) ?: return@runBlocking null
        throwIfCancelled()

        AgentObservability.record(
            type = "llm_response",
            message = "Received raw LLM response",
            civName = civInfo.civName,
            turn = civInfo.gameInfo.turns,
            details = mapOf(
                "provider" to provider.name,
                "model" to tacticalModel,
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

    private suspend fun requestStructuredText(
        prompt: String,
        civName: String,
        turn: Int,
        eventPrefix: String = "",
    ): String? {
        return when {
            isGatewayBaseUrl(baseUrl) -> requestOpenAiCompatible(prompt, civName, turn, eventPrefix = eventPrefix)
            provider == LlmProvider.Google -> requestGoogleDirect(prompt, civName, turn, eventPrefix = eventPrefix)
            provider == LlmProvider.Anthropic -> requestAnthropicDirect(prompt, civName, turn, eventPrefix = eventPrefix)
            else -> requestOpenAiCompatible(prompt, civName, turn, eventPrefix = eventPrefix)
        }
    }

    private suspend fun requestOpenAiCompatible(prompt: String, civName: String, turn: Int, eventPrefix: String = ""): String? {
        val url = "${baseUrl.normalizedBase()}/v1/chat/completions"
        val model = resolveModelForRequest(eventPrefix)
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

        val responseJson = postJson(url, payload, civName = civName, turn = turn, eventPrefix = eventPrefix) ?: return null
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

    private suspend fun requestAnthropicDirect(prompt: String, civName: String, turn: Int, eventPrefix: String = ""): String? {
        val url = "${baseUrl.normalizedBase()}/v1/messages"
        val model = resolveModelForRequest(eventPrefix)
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
            eventPrefix = eventPrefix,
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

    private suspend fun requestGoogleDirect(prompt: String, civName: String, turn: Int, eventPrefix: String = ""): String? {
        val model = resolveModelForRequest(eventPrefix)
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

        val responseJson = postJson(url, payload, useBearerToken = false, civName = civName, turn = turn, eventPrefix = eventPrefix) ?: return null
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
        eventPrefix: String = "",
    ): JsonObject? {
        var lastException: Exception? = null
        var attemptsUsed = 0

        repeat(maxAttempts) { index ->
            attemptsUsed = index + 1
            throwIfCancelled()
            val attemptTimeouts = timeoutsForAttempt(attemptsUsed)
            try {
                val response = client.post(url) {
                    timeout {
                        requestTimeoutMillis = attemptTimeouts.requestTimeoutMs
                        connectTimeoutMillis = attemptTimeouts.connectTimeoutMs
                        socketTimeoutMillis = attemptTimeouts.socketTimeoutMs
                    }
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
                    val delayMs = retryDelayForAttempt(attemptsUsed)
                    AgentObservability.record(
                        type = eventType(eventPrefix, "llm_retry_scheduled"),
                        message = "Provider request will be retried after retryable HTTP status",
                        civName = civName,
                        turn = turn,
                        details = mapOf(
                            "url" to url,
                            "provider" to provider.name,
                            "model" to resolveModelForRequest(eventPrefix),
                            "reason" to "http_status",
                            "status" to response.status.toString(),
                            "attempt" to attemptsUsed.toString(),
                            "nextAttempt" to (attemptsUsed + 1).toString(),
                            "maxAttempts" to maxAttempts.toString(),
                            "requestTimeoutMs" to attemptTimeouts.requestTimeoutMs.toString(),
                            "connectTimeoutMs" to attemptTimeouts.connectTimeoutMs.toString(),
                            "socketTimeoutMs" to attemptTimeouts.socketTimeoutMs.toString(),
                            "delayMs" to delayMs.toString(),
                        ),
                    )
                    Log.debug(
                        "AI (agent): provider returned %s on attempt %s/%s, retrying url=%s timeout=%s nextDelay=%s",
                        response.status,
                        attemptsUsed,
                        maxAttempts,
                        url,
                        attemptTimeouts.requestTimeoutMs,
                        delayMs,
                    )
                    delay(delayMs)
                    return@repeat
                }

                Log.debug("AI (agent): provider returned %s, url=%s", response.status, url)
                AgentObservability.record(
                    type = eventType(eventPrefix, "llm_http_error"),
                    message = "Provider returned non-200 status",
                    civName = civName,
                    turn = turn,
                    details = mapOf(
                        "status" to response.status.toString(),
                        "url" to url,
                        "provider" to provider.name,
                        "model" to resolveModelForRequest(eventPrefix),
                        "attempts" to attemptsUsed.toString(),
                        "requestTimeoutMs" to attemptTimeouts.requestTimeoutMs.toString(),
                        "connectTimeoutMs" to attemptTimeouts.connectTimeoutMs.toString(),
                        "socketTimeoutMs" to attemptTimeouts.socketTimeoutMs.toString(),
                    ),
                )
                return null
            } catch (ex: Exception) {
                if (ex is CancellationException || Thread.currentThread().isInterrupted) {
                    throw cancellationException(ex)
                }
                lastException = ex
                if (attemptsUsed < maxAttempts) {
                    val delayMs = retryDelayForAttempt(attemptsUsed)
                    AgentObservability.record(
                        type = eventType(eventPrefix, "llm_retry_scheduled"),
                        message = "Provider request failed and will be retried",
                        civName = civName,
                        turn = turn,
                        details = mapOf(
                            "url" to url,
                            "provider" to provider.name,
                            "model" to resolveModelForRequest(eventPrefix),
                            "reason" to "request_error",
                            "attempt" to attemptsUsed.toString(),
                            "nextAttempt" to (attemptsUsed + 1).toString(),
                            "maxAttempts" to maxAttempts.toString(),
                            "requestTimeoutMs" to attemptTimeouts.requestTimeoutMs.toString(),
                            "connectTimeoutMs" to attemptTimeouts.connectTimeoutMs.toString(),
                            "socketTimeoutMs" to attemptTimeouts.socketTimeoutMs.toString(),
                            "delayMs" to delayMs.toString(),
                            "error" to (ex.message ?: ex::class.simpleName.orEmpty()),
                        ),
                    )
                    Log.debug(
                        "AI (agent): provider request failed on attempt %s/%s, retrying url=%s timeout=%s nextDelay=%s",
                        attemptsUsed,
                        maxAttempts,
                        url,
                        attemptTimeouts.requestTimeoutMs,
                        delayMs,
                    )
                    Log.debug("AI (agent): provider exception", ex)
                    delay(delayMs)
                    return@repeat
                }
            }
        }

        val ex = lastException ?: return null
        Log.debug("AI (agent): provider request failed, url=%s", url)
        Log.debug("AI (agent): provider exception", ex)
        AgentObservability.record(
            type = eventType(eventPrefix, "llm_request_error"),
            message = "Provider request failed",
            civName = civName,
            turn = turn,
            details = mapOf(
                "url" to url,
                "provider" to provider.name,
                "model" to resolveModelForRequest(eventPrefix),
                "attempts" to attemptsUsed.toString(),
                "requestTimeoutMs" to timeoutsForAttempt(attemptsUsed).requestTimeoutMs.toString(),
                "connectTimeoutMs" to timeoutsForAttempt(attemptsUsed).connectTimeoutMs.toString(),
                "socketTimeoutMs" to timeoutsForAttempt(attemptsUsed).socketTimeoutMs.toString(),
                "error" to (ex.message ?: ex::class.simpleName.orEmpty()),
            ),
        )
        return null
    }

    private fun timeoutsForAttempt(attempt: Int): AttemptTimeouts {
        val requestTimeoutForAttempt = when {
            attempt <= 1 -> requestTimeoutMs
            attempt == 2 -> maxOf(requestTimeoutMs, firstRetryRequestTimeoutMs)
            else -> maxOf(requestTimeoutMs, laterRetryRequestTimeoutMs)
        }

        return AttemptTimeouts(
            requestTimeoutMs = requestTimeoutForAttempt,
            connectTimeoutMs = connectTimeoutMs,
            socketTimeoutMs = maxOf(socketTimeoutMs, requestTimeoutForAttempt),
        )
    }

    private fun retryDelayForAttempt(attempt: Int): Long {
        val exponent = (attempt - 1).coerceAtLeast(0)
        val scaledDelay = retryDelayMs * (1L shl exponent.coerceAtMost(3))
        return min(scaledDelay, maxRetryDelayMs)
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

    private fun resolveModelForRequest(eventPrefix: String): String {
        return if (eventPrefix.startsWith("strategist")) strategistModel else tacticalModel
    }

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
        const val defaultRequestTimeoutMs = 300_000L
        const val defaultConnectTimeoutMs = 10_000L
        const val defaultSocketTimeoutMs = 300_000L
        const val defaultMaxAttempts = 5
        const val defaultRetryDelayMs = 1_000L
        const val firstRetryRequestTimeoutMs = 600_000L
        const val laterRetryRequestTimeoutMs = 1_200_000L
        const val maxRetryDelayMs = 8_000L

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

    private fun eventType(prefix: String, baseType: String): String {
        return if (prefix.isBlank()) baseType else "${prefix}_${baseType}"
    }
}
