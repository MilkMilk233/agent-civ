package com.unciv.logic.automation.agent

import com.unciv.logic.civilization.Civilization
import com.unciv.utils.Log

interface AgentPlanProvider {
    fun buildStrategistMemo(
        memory: AgentMemory,
        observation: AgentObservation,
        empireObservation: AgentEmpireObservation,
        civInfo: Civilization,
        refreshRequest: AgentStrategistRefreshRequest,
    ): AgentStrategicPlan?

    fun buildPlan(
        memory: AgentMemory,
        observation: AgentObservation,
        empireObservation: AgentEmpireObservation,
        civInfo: Civilization,
        retryContext: AgentRetryContext? = null,
    ): AgentActionPlan?
}

enum class LlmProvider {
    OpenAI,
    Google,
    Anthropic,
}

object AgentPlanProviderFactory {
    private var warnedAboutMissingKey = false

    val provider: AgentPlanProvider by lazy {
        val provider = parseProvider(System.getenv("UNCIV_LLM_PROVIDER")?.trim())
        val apiKey = System.getenv("UNCIV_LLM_API_KEY")?.trim().orEmpty()
            .ifEmpty { System.getenv("UNCIV_GEMINI_API_KEY")?.trim().orEmpty() }
        val baseUrl = System.getenv("UNCIV_LLM_BASE_URL")?.trim().orEmpty()
            .ifEmpty { MultiProviderAgentPlanProvider.defaultGatewayBaseUrl }

        val sharedModel = System.getenv("UNCIV_LLM_MODEL")?.trim().orEmpty().ifEmpty {
            if (provider == LlmProvider.Google) {
                System.getenv("UNCIV_GEMINI_MODEL")?.trim().orEmpty()
            } else ""
        }
        val strategistModel = System.getenv("UNCIV_LLM_STRATEGIST_MODEL")?.trim().orEmpty()
            .ifEmpty { sharedModel }
        val tacticalModel = System.getenv("UNCIV_LLM_TACTICAL_MODEL")?.trim().orEmpty()
            .ifEmpty { sharedModel }

        if (apiKey.isNotEmpty()) {
            MultiProviderAgentPlanProvider(
                apiKey = apiKey,
                baseUrl = baseUrl,
                provider = provider,
                strategistModel = if (strategistModel.isNotEmpty()) strategistModel else MultiProviderAgentPlanProvider.defaultModel(provider),
                tacticalModel = if (tacticalModel.isNotEmpty()) tacticalModel else MultiProviderAgentPlanProvider.defaultModel(provider),
                requestTimeoutMs = MultiProviderAgentPlanProvider.envLong(
                    "UNCIV_LLM_REQUEST_TIMEOUT_MS",
                    MultiProviderAgentPlanProvider.defaultRequestTimeoutMs,
                ),
                connectTimeoutMs = MultiProviderAgentPlanProvider.envLong(
                    "UNCIV_LLM_CONNECT_TIMEOUT_MS",
                    MultiProviderAgentPlanProvider.defaultConnectTimeoutMs,
                ),
                socketTimeoutMs = MultiProviderAgentPlanProvider.envLong(
                    "UNCIV_LLM_SOCKET_TIMEOUT_MS",
                    MultiProviderAgentPlanProvider.defaultSocketTimeoutMs,
                ),
                maxAttempts = MultiProviderAgentPlanProvider.envInt(
                    "UNCIV_LLM_MAX_ATTEMPTS",
                    MultiProviderAgentPlanProvider.defaultMaxAttempts,
                ),
                retryDelayMs = MultiProviderAgentPlanProvider.envLong(
                    "UNCIV_LLM_RETRY_DELAY_MS",
                    MultiProviderAgentPlanProvider.defaultRetryDelayMs,
                ),
            )
        } else {
            if (!warnedAboutMissingKey) {
                warnedAboutMissingKey = true
                Log.debug("AI (agent): set UNCIV_LLM_API_KEY (or UNCIV_GEMINI_API_KEY), falling back to legacy AI")
            }
            NoopAgentPlanProvider
        }
    }

    private fun parseProvider(provider: String?): LlmProvider {
        return when (provider?.lowercase()) {
            "google", "gemini" -> LlmProvider.Google
            "anthropic", "claude" -> LlmProvider.Anthropic
            else -> LlmProvider.OpenAI
        }
    }
}

object NoopAgentPlanProvider : AgentPlanProvider {
    override fun buildStrategistMemo(
        memory: AgentMemory,
        observation: AgentObservation,
        empireObservation: AgentEmpireObservation,
        civInfo: Civilization,
        refreshRequest: AgentStrategistRefreshRequest,
    ): AgentStrategicPlan? = null

    override fun buildPlan(
        memory: AgentMemory,
        observation: AgentObservation,
        empireObservation: AgentEmpireObservation,
        civInfo: Civilization,
        retryContext: AgentRetryContext?,
    ): AgentActionPlan? = null
}
