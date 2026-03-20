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
        fun fromPlanAction(action: AgentActionCommand): AgentControlDomain? = when (action) {
            is AgentActionCommand.SelectEmpireOption -> fromEmpireCandidateId(action.candidateId)
            is AgentActionCommand.SelectCityOption,
            is AgentActionCommand.CityChooseConstruction -> City
            is AgentActionCommand.SelectUnitOption,
            is AgentActionCommand.UnitMove,
            is AgentActionCommand.UnitAction -> Unit
            is AgentActionCommand.EndTurn -> null
        }

        fun fromOutcome(
            commandType: String,
            candidateId: String?,
        ): AgentControlDomain? = when (commandType) {
            "select_empire_option" -> fromEmpireCandidateId(candidateId)
            "select_city_option", "city_choose_construction" -> City
            "select_unit_option", "unit_move", "unit_action" -> Unit
            else -> null
        }

        private fun fromEmpireCandidateId(candidateId: String?): AgentControlDomain {
            return when {
                candidateId == null -> Empire
                candidateId.startsWith("diplo:") || candidateId.startsWith("trade:") || candidateId.startsWith("spy:") -> Diplomacy
                else -> Empire
            }
        }
    }
}

@Serializable
data class AgentDomainSupportSnapshot(
    val supportedDomains: List<String>,
    val availableCandidatesByDomain: Map<String, Int>,
)

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

    fun supportSnapshot(
        observation: AgentObservation,
        empireObservation: AgentEmpireObservation,
    ): AgentDomainSupportSnapshot {
        val available = linkedMapOf(
            AgentControlDomain.Empire.wireName to (
                empireObservation.researchCandidates.size +
                    empireObservation.policyCandidates.size +
                    empireObservation.macroCandidates.count {
                        !it.candidateId.startsWith("diplo:") &&
                            !it.candidateId.startsWith("trade:") &&
                            !it.candidateId.startsWith("spy:")
                    }
                ),
            AgentControlDomain.City.wireName to (
                observation.citiesNeedingAttention.sumOf { it.cityOptionCandidates.size } +
                    observation.citiesNeedingAttention.count { it.availableConstructions.isNotEmpty() }
                ),
            AgentControlDomain.Unit.wireName to (
                observation.actionableUnits.sumOf { it.legalActionCandidates.size + it.unitOptionCandidates.size } +
                    observation.actionableUnits.count { it.hasMovement }
                ),
            AgentControlDomain.Diplomacy.wireName to (
                empireObservation.diplomacyCandidates.size +
                    empireObservation.spyCandidates.size
                ),
        )
        return AgentDomainSupportSnapshot(
            supportedDomains = AgentControlDomain.entries.map { it.wireName },
            availableCandidatesByDomain = available,
        )
    }

    fun supportSnapshotJson(snapshot: AgentDomainSupportSnapshot): String = json.encodeToString(snapshot)

    fun plannedDomainCounts(plan: AgentActionPlan?): Map<String, Int> {
        if (plan == null) return emptyMap()
        val counts = linkedMapOf<String, Int>()
        for (action in plan.actions) {
            val domain = AgentControlDomain.fromPlanAction(action) ?: continue
            counts[domain.wireName] = (counts[domain.wireName] ?: 0) + 1
        }
        return counts
    }

    fun plannedDomainCountsJson(plan: AgentActionPlan?): String = json.encodeToString(plannedDomainCounts(plan))

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
