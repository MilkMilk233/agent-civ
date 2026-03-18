package com.unciv.logic.automation.agent

import com.unciv.logic.civilization.Civilization
import com.unciv.utils.Log
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
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
) : AgentPlanProvider {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(json)
        }
    }

    override fun buildPlan(observation: AgentObservation, civInfo: Civilization): AgentActionPlan? = runBlocking {
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
                "prompt" to prompt,
            ),
        )

        val planText = when {
            isGatewayBaseUrl(baseUrl) -> requestOpenAiCompatible(prompt, civInfo.civName, civInfo.gameInfo.turns)
            provider == LlmProvider.Google -> requestGoogleDirect(prompt, civInfo.civName, civInfo.gameInfo.turns)
            provider == LlmProvider.Anthropic -> requestAnthropicDirect(prompt, civInfo.civName, civInfo.gameInfo.turns)
            else -> requestOpenAiCompatible(prompt, civInfo.civName, civInfo.gameInfo.turns)
        } ?: return@runBlocking null

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
    ) = try {
        val response = client.post(url) {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            if (useBearerToken) header(HttpHeaders.Authorization, "Bearer $apiKey")
            for ((name, value) in extraHeaders) header(name, value)
            setBody(payload)
        }

        if (response.status != HttpStatusCode.OK) {
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
                ),
            )
            null
        } else {
            json.parseToJsonElement(response.body<String>()).jsonObject
        }
    } catch (ex: Exception) {
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
                "error" to (ex.message ?: ex::class.simpleName.orEmpty()),
            ),
        )
        null
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

        fun defaultModel(provider: LlmProvider): String = when (provider) {
            LlmProvider.OpenAI -> "gpt-4o-mini"
            LlmProvider.Google -> "gemini-2.5-flash"
            LlmProvider.Anthropic -> "claude-3-5-sonnet-latest"
        }
    }
}
