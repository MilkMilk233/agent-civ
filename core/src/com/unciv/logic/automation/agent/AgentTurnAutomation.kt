package com.unciv.logic.automation.agent

import com.unciv.logic.automation.civilization.NextTurnAutomation
import com.unciv.logic.civilization.Civilization
import com.unciv.utils.Log
import kotlin.math.max

object AgentTurnAutomation {
    private val executor = AgentActionExecutor()

    fun automateCivMoves(civInfo: Civilization) {
        if (civInfo.isDefeated() || civInfo.isSpectator() || civInfo.isBarbarian || civInfo.isCityState) {
            NextTurnAutomation.automateCivMoves(civInfo)
            return
        }

        val observation = AgentObservationBuilder.build(civInfo)
        val observationJson = AgentPromptBuilder.observationJson(observation)
        AgentObservability.record(
            type = "turn_start",
            message = "Built observation for AI agent turn",
            civName = civInfo.civName,
            turn = civInfo.gameInfo.turns,
            details = mapOf(
                "observationVersion" to "2",
                "cities" to observation.empireSummary.cityCount.toString(),
                "units" to observation.empireSummary.unitCount.toString(),
                "knownCivs" to observation.empireSummary.knownCivs.toString(),
                "visibleTiles" to observation.empireSummary.visibleTiles.toString(),
                "gold" to observation.empireSummary.gold.toString(),
                "sciencePerTurn" to observation.empireSummary.sciencePerTurn.toString(),
                "culturePerTurn" to observation.empireSummary.culturePerTurn.toString(),
                "faithPerTurn" to observation.empireSummary.faithPerTurn.toString(),
                "happiness" to observation.empireSummary.happiness.toString(),
                "isAtWar" to observation.empireSummary.isAtWar.toString(),
                "priorityFacts" to observation.priorityFacts.size.toString(),
                "citiesNeedingAttention" to observation.citiesNeedingAttention.size.toString(),
                "actionableUnits" to observation.actionableUnits.size.toString(),
                "visibleThreatsAndTargets" to observation.visibleThreatsAndTargets.size.toString(),
                "opportunities" to observation.opportunities.size.toString(),
                "observationJson" to observationJson,
            ),
        )
        val plan = AgentPlanProviderFactory.provider.buildPlan(observation, civInfo)
        if (plan == null) {
            Log.debug("AI (agent): no plan produced for civ %s on turn %s, falling back to legacy AI", civInfo.civName, civInfo.gameInfo.turns)
            AgentObservability.record(
                type = "plan_missing",
                message = "No plan produced, falling back to legacy AI",
                civName = civInfo.civName,
                turn = civInfo.gameInfo.turns,
            )
            NextTurnAutomation.automateCivMoves(civInfo)
            return
        }

        val report = executor.execute(civInfo, plan)
        val tooManyRejections = report.rejectedActions > max(3, report.executedActions * 2)
        val shouldFallback = plan.handoffToLegacyAI || report.executedActions == 0 || tooManyRejections

        if (shouldFallback) {
            Log.debug(
                "AI (agent) fallback to legacy for civ %s, executed=%s rejected=%s",
                civInfo.civName,
                report.executedActions,
                report.rejectedActions,
            )
            AgentObservability.record(
                type = "fallback_legacy",
                message = "Plan execution requested fallback to legacy AI",
                civName = civInfo.civName,
                turn = civInfo.gameInfo.turns,
                details = mapOf(
                    "executedActions" to report.executedActions.toString(),
                    "rejectedActions" to report.rejectedActions.toString(),
                    "handoffToLegacyAI" to plan.handoffToLegacyAI.toString(),
                    "plannedActions" to plan.actions.size.toString(),
                ),
            )
            NextTurnAutomation.automateCivMoves(civInfo)
        } else {
            Log.debug(
                "AI (agent) applied plan for civ %s on turn %s, executed=%s rejected=%s",
                civInfo.civName,
                civInfo.gameInfo.turns,
                report.executedActions,
                report.rejectedActions,
            )
            AgentObservability.record(
                type = "plan_applied",
                message = "Plan executed without legacy fallback",
                civName = civInfo.civName,
                turn = civInfo.gameInfo.turns,
                details = mapOf(
                    "executedActions" to report.executedActions.toString(),
                    "rejectedActions" to report.rejectedActions.toString(),
                    "plannedActions" to plan.actions.size.toString(),
                ),
            )
        }
    }
}
