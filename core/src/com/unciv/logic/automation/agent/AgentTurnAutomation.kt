package com.unciv.logic.automation.agent

import com.unciv.logic.automation.civilization.NextTurnAutomation
import com.unciv.logic.civilization.Civilization
import com.unciv.utils.Log
import kotlin.math.max

object AgentTurnAutomation {
    private val executor = AgentActionExecutor()
    private const val maxPlanningAttemptsPerTurn = 3

    fun automateCivMoves(civInfo: Civilization) {
        if (civInfo.isDefeated() || civInfo.isSpectator() || civInfo.isBarbarian || civInfo.isCityState) {
            NextTurnAutomation.automateCivMoves(civInfo)
            return
        }

        val observation = AgentObservationBuilder.build(civInfo)
        val memory = AgentMemoryManager.prepareForTurn(civInfo, observation)
        val observationJson = AgentPromptBuilder.observationJson(observation)
        val memoryJson = AgentMemoryManager.memoryJson(memory)
        AgentObservability.record(
            type = "turn_start",
            message = "Built observation for AI agent turn",
            civName = civInfo.civName,
            turn = civInfo.gameInfo.turns,
            details = mapOf(
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
                "memoryMode" to memory.strategicPosture.mode,
                "memoryCityIntents" to memory.cityIntents.size.toString(),
                "memoryUnitAssignments" to memory.unitAssignments.size.toString(),
                "memoryRecentFailures" to memory.recentFailures.size.toString(),
                "memoryJson" to memoryJson,
                "observationJson" to observationJson,
            ),
        )
        var retryContext: AgentRetryContext? = null
        var selectedPlan: AgentActionPlan? = null
        var validationReport: AgentActionExecutor.ValidationReport? = null
        var planningAttempts = 0
        var fallbackReason: String? = null

        while (planningAttempts < maxPlanningAttemptsPerTurn) {
            planningAttempts += 1
            val plan = AgentPlanProviderFactory.provider.buildPlan(memory, observation, civInfo, retryContext)
            if (plan == null) {
                fallbackReason = "No plan produced"
                break
            }

            if (plan.handoffToLegacyAI) {
                selectedPlan = plan
                fallbackReason = "Planner requested legacy handoff"
                break
            }

            val validation = executor.validate(civInfo, plan)
            when (validation.status) {
                AgentActionExecutor.ValidationStatus.Valid,
                AgentActionExecutor.ValidationStatus.NoOp -> {
                    selectedPlan = plan
                    validationReport = validation
                    break
                }
                AgentActionExecutor.ValidationStatus.Invalid -> {
                    AgentObservability.record(
                        type = "plan_validation_failed",
                        message = "Plan failed validation before real execution",
                        civName = civInfo.civName,
                        turn = civInfo.gameInfo.turns,
                        details = mapOf(
                            "attempt" to planningAttempts.toString(),
                            "maxAttempts" to maxPlanningAttemptsPerTurn.toString(),
                            "failedPlanJson" to AgentPromptBuilder.planJson(plan),
                            "validationFailuresJson" to AgentPromptBuilder.retryContextJson(
                                AgentRetryContextFactory.fromValidation(
                                    plan = plan,
                                    validation = validation,
                                    retryAttempt = minOf(planningAttempts, maxPlanningAttemptsPerTurn - 1),
                                    maxRetries = maxPlanningAttemptsPerTurn - 1,
                                )
                            ),
                        ),
                    )

                    if (planningAttempts >= maxPlanningAttemptsPerTurn) {
                        selectedPlan = plan
                        validationReport = validation
                        fallbackReason = "Plan remained invalid after ${maxPlanningAttemptsPerTurn - 1} retries"
                        break
                    }

                    retryContext = AgentRetryContextFactory.fromValidation(
                        plan = plan,
                        validation = validation,
                        retryAttempt = planningAttempts,
                        maxRetries = maxPlanningAttemptsPerTurn - 1,
                    )
                    AgentObservability.record(
                        type = "plan_retry_requested",
                        message = "Requesting planner retry after validation failure",
                        civName = civInfo.civName,
                        turn = civInfo.gameInfo.turns,
                        details = mapOf(
                            "attempt" to planningAttempts.toString(),
                            "nextAttempt" to (planningAttempts + 1).toString(),
                            "maxAttempts" to maxPlanningAttemptsPerTurn.toString(),
                            "retryContextJson" to AgentPromptBuilder.retryContextJson(retryContext),
                        ),
                    )
                }
            }
        }

        if (selectedPlan == null) {
            val updatedMemory = AgentMemoryManager.updateAfterTurn(
                civInfo = civInfo,
                observation = observation,
                startingMemory = memory,
                plan = null,
                report = null,
                usedLegacyFallback = true,
                fallbackReason = fallbackReason ?: "No plan produced",
            )
            Log.debug("AI (agent): no usable plan produced for civ %s on turn %s, falling back to legacy AI", civInfo.civName, civInfo.gameInfo.turns)
            AgentObservability.record(
                type = "plan_missing",
                message = "No usable plan produced, falling back to legacy AI",
                civName = civInfo.civName,
                turn = civInfo.gameInfo.turns,
                details = mapOf(
                    "planningAttempts" to planningAttempts.toString(),
                    "fallbackReason" to (fallbackReason ?: "No plan produced"),
                    "memoryMode" to updatedMemory.strategicPosture.mode,
                    "memoryCityIntents" to updatedMemory.cityIntents.size.toString(),
                    "memoryUnitAssignments" to updatedMemory.unitAssignments.size.toString(),
                    "memoryRecentFailures" to updatedMemory.recentFailures.size.toString(),
                ),
            )
            NextTurnAutomation.automateCivMoves(civInfo)
            return
        }

        if (selectedPlan.handoffToLegacyAI) {
            val updatedMemory = AgentMemoryManager.updateAfterTurn(
                civInfo = civInfo,
                observation = observation,
                startingMemory = memory,
                plan = selectedPlan,
                report = null,
                usedLegacyFallback = true,
                fallbackReason = fallbackReason ?: "Planner requested legacy handoff",
            )
            AgentObservability.record(
                type = "fallback_legacy",
                message = "Planner requested legacy fallback",
                civName = civInfo.civName,
                turn = civInfo.gameInfo.turns,
                details = mapOf(
                    "planningAttempts" to planningAttempts.toString(),
                    "executedActions" to "0",
                    "rejectedActions" to "0",
                    "handoffToLegacyAI" to "true",
                    "plannedActions" to selectedPlan.actions.size.toString(),
                    "fallbackReason" to (fallbackReason ?: "Planner requested legacy handoff"),
                    "memoryMode" to updatedMemory.strategicPosture.mode,
                    "memoryCityIntents" to updatedMemory.cityIntents.size.toString(),
                    "memoryUnitAssignments" to updatedMemory.unitAssignments.size.toString(),
                    "memoryRecentFailures" to updatedMemory.recentFailures.size.toString(),
                ),
            )
            NextTurnAutomation.automateCivMoves(civInfo)
            return
        }

        val validation = validationReport
        if (validation == null) {
            val updatedMemory = AgentMemoryManager.updateAfterTurn(
                civInfo = civInfo,
                observation = observation,
                startingMemory = memory,
                plan = selectedPlan,
                report = null,
                usedLegacyFallback = true,
                fallbackReason = "Plan validation did not complete",
            )
            AgentObservability.record(
                type = "fallback_legacy",
                message = "Plan validation did not complete",
                civName = civInfo.civName,
                turn = civInfo.gameInfo.turns,
                details = mapOf(
                    "planningAttempts" to planningAttempts.toString(),
                    "executedActions" to "0",
                    "rejectedActions" to "0",
                    "plannedActions" to selectedPlan.actions.size.toString(),
                    "fallbackReason" to "Plan validation did not complete",
                    "memoryMode" to updatedMemory.strategicPosture.mode,
                    "memoryCityIntents" to updatedMemory.cityIntents.size.toString(),
                    "memoryUnitAssignments" to updatedMemory.unitAssignments.size.toString(),
                    "memoryRecentFailures" to updatedMemory.recentFailures.size.toString(),
                ),
            )
            NextTurnAutomation.automateCivMoves(civInfo)
            return
        }

        if (validation.status == AgentActionExecutor.ValidationStatus.Invalid) {
            val updatedMemory = AgentMemoryManager.updateAfterTurn(
                civInfo = civInfo,
                observation = observation,
                startingMemory = memory,
                plan = selectedPlan,
                report = null,
                usedLegacyFallback = true,
                fallbackReason = fallbackReason ?: "Plan remained invalid after retries",
                validationFailures = validation.rejectedOutcomes,
            )
            AgentObservability.record(
                type = "fallback_legacy",
                message = "Plan remained invalid after retries; handing turn to legacy AI",
                civName = civInfo.civName,
                turn = civInfo.gameInfo.turns,
                details = mapOf(
                    "planningAttempts" to planningAttempts.toString(),
                    "executedActions" to "0",
                    "rejectedActions" to validation.rejectedActionsPreview.toString(),
                    "plannedActions" to selectedPlan.actions.size.toString(),
                    "fallbackReason" to (fallbackReason ?: "Plan remained invalid after retries"),
                    "validationFailuresJson" to AgentPromptBuilder.retryContextJson(
                        AgentRetryContextFactory.fromValidation(
                            plan = selectedPlan,
                            validation = validation,
                            retryAttempt = minOf(planningAttempts, maxPlanningAttemptsPerTurn - 1),
                            maxRetries = maxPlanningAttemptsPerTurn - 1,
                        )
                    ),
                    "memoryMode" to updatedMemory.strategicPosture.mode,
                    "memoryCityIntents" to updatedMemory.cityIntents.size.toString(),
                    "memoryUnitAssignments" to updatedMemory.unitAssignments.size.toString(),
                    "memoryRecentFailures" to updatedMemory.recentFailures.size.toString(),
                ),
            )
            NextTurnAutomation.automateCivMoves(civInfo)
            return
        }

        if (validation.status == AgentActionExecutor.ValidationStatus.NoOp) {
            val updatedMemory = AgentMemoryManager.updateAfterTurn(
                civInfo = civInfo,
                observation = observation,
                startingMemory = memory,
                plan = selectedPlan,
                report = null,
                usedLegacyFallback = false,
                intentionalNoOp = true,
            )
            AgentObservability.record(
                type = "plan_applied",
                message = "Intentional no-op plan accepted without legacy fallback",
                civName = civInfo.civName,
                turn = civInfo.gameInfo.turns,
                details = mapOf(
                    "planningAttempts" to planningAttempts.toString(),
                    "executedActions" to "0",
                    "rejectedActions" to "0",
                    "plannedActions" to selectedPlan.actions.count { it !is AgentActionCommand.EndTurn }.toString(),
                    "intentionalNoOp" to "true",
                    "memoryMode" to updatedMemory.strategicPosture.mode,
                    "memoryCityIntents" to updatedMemory.cityIntents.size.toString(),
                    "memoryUnitAssignments" to updatedMemory.unitAssignments.size.toString(),
                    "memoryRecentFailures" to updatedMemory.recentFailures.size.toString(),
                ),
            )
            return
        }

        val report = executor.execute(civInfo, selectedPlan)
        if (report.rejectedActions > 0) {
            val updatedMemory = AgentMemoryManager.updateAfterTurn(
                civInfo = civInfo,
                observation = observation,
                startingMemory = memory,
                plan = selectedPlan,
                report = report,
                usedLegacyFallback = true,
                fallbackReason = "Validated plan still rejected during live execution",
            )
            AgentObservability.record(
                type = "fallback_legacy",
                message = "Validated plan diverged during live execution; handing turn to legacy AI",
                civName = civInfo.civName,
                turn = civInfo.gameInfo.turns,
                details = mapOf(
                    "planningAttempts" to planningAttempts.toString(),
                    "executedActions" to report.executedActions.toString(),
                    "rejectedActions" to report.rejectedActions.toString(),
                    "plannedActions" to selectedPlan.actions.size.toString(),
                    "fallbackReason" to "Validated plan still rejected during live execution",
                    "memoryMode" to updatedMemory.strategicPosture.mode,
                    "memoryCityIntents" to updatedMemory.cityIntents.size.toString(),
                    "memoryUnitAssignments" to updatedMemory.unitAssignments.size.toString(),
                    "memoryRecentFailures" to updatedMemory.recentFailures.size.toString(),
                ),
            )
            NextTurnAutomation.automateCivMoves(civInfo)
            return
        }

        val updatedMemory = AgentMemoryManager.updateAfterTurn(
            civInfo = civInfo,
            observation = observation,
            startingMemory = memory,
            plan = selectedPlan,
            report = report,
            usedLegacyFallback = false,
        )
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
                "planningAttempts" to planningAttempts.toString(),
                "executedActions" to report.executedActions.toString(),
                "rejectedActions" to report.rejectedActions.toString(),
                "plannedActions" to selectedPlan.actions.size.toString(),
                "memoryMode" to updatedMemory.strategicPosture.mode,
                "memoryCityIntents" to updatedMemory.cityIntents.size.toString(),
                "memoryUnitAssignments" to updatedMemory.unitAssignments.size.toString(),
                "memoryRecentFailures" to updatedMemory.recentFailures.size.toString(),
            ),
        )
    }
}
