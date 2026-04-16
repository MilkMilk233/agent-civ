package com.unciv.logic.automation.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class AgentControlDomain(val wireName: String) {
    Empire("empire"),
    City("city"),
    Unit("unit"),
    Diplomacy("diplomacy"),
    ;

    companion object {
        fun fromOutcome(
            commandType: String,
            candidateId: String?,
        ): AgentControlDomain? = when (commandType) {
            "select_empire_option" -> fromEmpireCandidateId(candidateId)
            "select_city_option" -> City
            "select_unit_option" -> Unit
            else -> null
        }

        private fun fromEmpireCandidateId(candidateId: String?): AgentControlDomain {
            return when {
                candidateId == null -> Empire
                candidateId.startsWith("diplo:") || candidateId.startsWith("trade:") -> Diplomacy
                else -> Empire
            }
        }
    }
}

@Serializable
data class AgentDomainOutcomeSummary(
    val domain: String,
    val planned: Int = 0,
    val executed: Int = 0,
    val rejected: Int = 0,
)

object AgentTurnDiagnostics {
    private val json = Json {
        prettyPrint = false
        explicitNulls = false
        encodeDefaults = true
    }

    fun outcomeSummary(outcomes: List<AgentActionExecutor.ActionOutcome>): List<AgentDomainOutcomeSummary> {
        val byDomain = linkedMapOf<String, AgentDomainOutcomeSummary>()
        for (outcome in outcomes) {
            val domain = outcome.domain ?: continue
            val current = byDomain[domain.wireName] ?: AgentDomainOutcomeSummary(domain = domain.wireName)
            val updated = when (outcome.status) {
                AgentActionExecutor.ActionStatus.Executed -> current.copy(
                    planned = current.planned + 1,
                    executed = current.executed + 1,
                )
                AgentActionExecutor.ActionStatus.Rejected -> current.copy(
                    planned = current.planned + 1,
                    rejected = current.rejected + 1,
                )
            }
            byDomain[domain.wireName] = updated
        }
        return byDomain.values.toList()
    }

    fun outcomeSummaryJson(outcomes: List<AgentActionExecutor.ActionOutcome>): String = json.encodeToString(outcomeSummary(outcomes))

    fun affectedDomains(outcomes: List<AgentActionExecutor.ActionOutcome>): List<String> {
        return outcomes.asSequence()
            .mapNotNull { it.domain?.wireName }
            .distinct()
            .toList()
    }

    fun affectedDomainsCsv(outcomes: List<AgentActionExecutor.ActionOutcome>): String {
        return affectedDomains(outcomes).ifEmpty { listOf("all") }.joinToString(",")
    }
}
