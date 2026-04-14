package com.unciv.logic.automation.agent

import com.unciv.logic.automation.civilization.NextTurnAutomation
import com.unciv.logic.civilization.Civilization
import com.unciv.utils.Log
import kotlin.math.max

object AgentTurnAutomation {
    private val executor = AgentActionExecutor()
    private const val maxPlanningAttemptsPerTurn = 3
    private const val maxPlanningPassesPerTurn = 2

    private data class PlanningPassResult(
        val selectedPlan: AgentActionPlan?,
        val validationReport: AgentActionExecutor.ValidationReport?,
        val planningAttempts: Int,
        val fallbackReason: String?,
    )

    fun automateCivMoves(civInfo: Civilization) {
        if (civInfo.isDefeated() || civInfo.isSpectator() || civInfo.isBarbarian || civInfo.isCityState) {
            NextTurnAutomation.automateCivMoves(civInfo)
            return
        }

        AgentCityProjectPolicy.enforceSingleProject(civInfo)
        val seedMemory = civInfo.agentMemory.clone()
        val initialObservation = AgentObservationBuilder.build(civInfo, seedMemory)
        val initialEmpireObservation = AgentEmpireObservationBuilder.build(civInfo, seedMemory).observation
        var memory = AgentMemoryManager.prepareForTurn(civInfo, initialObservation, initialEmpireObservation)
        var observation = AgentObservationBuilder.build(civInfo, memory)
        var empireObservation = AgentEmpireObservationBuilder.build(civInfo, memory).observation
        var strategistRefreshCount = 0

        AgentMemoryManager.shouldRefreshStrategist(memory, observation, empireObservation)?.let { refreshRequest ->
            AgentObservability.record(
                type = "strategist_refresh_requested",
                message = "Refreshing strategist memo before tactical planning",
                civName = civInfo.civName,
                turn = civInfo.gameInfo.turns,
                details = mapOf(
                    "urgency" to refreshRequest.urgency,
                    "reason" to refreshRequest.reason,
                ),
            )
            val strategicPlan = AgentPlanProviderFactory.provider.buildStrategistMemo(
                memory = memory,
                observation = observation,
                empireObservation = empireObservation,
                civInfo = civInfo,
                refreshRequest = refreshRequest,
            )
            if (strategicPlan != null) {
                memory = AgentMemoryManager.applyStrategicMemo(memory, observation, empireObservation, refreshRequest, strategicPlan)
                civInfo.agentMemory = memory.clone()
                observation = AgentObservationBuilder.build(civInfo, memory)
                empireObservation = AgentEmpireObservationBuilder.build(civInfo, memory).observation
                strategistRefreshCount += 1
                AgentObservability.record(
                    type = "strategist_memo_applied",
                    message = "Strategist memo updated before tactical planning",
                    civName = civInfo.civName,
                    turn = civInfo.gameInfo.turns,
                    details = mapOf(
                        "urgency" to refreshRequest.urgency,
                        "reason" to refreshRequest.reason,
                        "strategistMemoJson" to AgentMemoryManager.strategistMemoJson(memory.lastStrategistMemo),
                        "notes" to (strategicPlan.notes ?: ""),
                    ),
                )
            } else {
                AgentObservability.record(
                    type = "strategist_memo_missing",
                    message = "Strategist refresh produced no memo; keeping prior memo",
                    civName = civInfo.civName,
                    turn = civInfo.gameInfo.turns,
                    details = mapOf(
                        "urgency" to refreshRequest.urgency,
                        "reason" to refreshRequest.reason,
                    ),
                )
            }
        }

        var observationJson = ""
        var empireObservationJson = ""
        var worldFactsJson = ""
        var memoryJson = ""
        var plannerBrief = AgentPromptBuilder.plannerBrief(memory, observation, empireObservation)
        var plannerBriefJson = ""

        fun rebuildArtifacts() {
            observationJson = AgentPromptBuilder.observationJson(observation)
            empireObservationJson = AgentPromptBuilder.empireObservationJson(empireObservation)
            worldFactsJson = AgentWorldFactsBuilder.json(civInfo)
            memoryJson = AgentMemoryManager.memoryJson(memory)
            plannerBrief = AgentPromptBuilder.plannerBrief(memory, observation, empireObservation)
            plannerBriefJson = AgentPromptBuilder.plannerBriefJson(plannerBrief)
        }

        fun recordObservationStart(passIndex: Int, eventType: String, message: String, extraDetails: Map<String, String> = emptyMap()) {
            AgentObservability.record(
                type = eventType,
                message = message,
                civName = civInfo.civName,
                turn = civInfo.gameInfo.turns,
                details = mapOf(
                    "turnPass" to passIndex.toString(),
                    "summaryCities" to observation.empireSummary.cityCount.toString(),
                    "summaryUnits" to observation.empireSummary.unitCount.toString(),
                    "knownCivs" to observation.empireSummary.knownCivs.toString(),
                    "visibleTiles" to observation.empireSummary.visibleTiles.toString(),
                    "gold" to observation.empireSummary.gold.toString(),
                    "sciencePerTurn" to observation.empireSummary.sciencePerTurn.toString(),
                    "culturePerTurn" to observation.empireSummary.culturePerTurn.toString(),
                    "faithPerTurn" to observation.empireSummary.faithPerTurn.toString(),
                    "happiness" to observation.empireSummary.happiness.toString(),
                    "isAtWar" to observation.empireSummary.isAtWar.toString(),
                    "priorityFacts" to observation.priorityFacts.size.toString(),
                    "observedCities" to observation.cities.size.toString(),
                    "expandedCities" to observation.perceptionSummary.expandedCities.toString(),
                    "observedUnits" to observation.units.size.toString(),
                    "expandedUnits" to observation.perceptionSummary.expandedUnits.toString(),
                    "visibleThreatsAndTargets" to observation.visibleThreatsAndTargets.size.toString(),
                    "opportunities" to observation.opportunities.size.toString(),
                    "memoryCampaignStage" to memory.campaign.stage,
                    "memoryCityIntents" to memory.cityIntents.size.toString(),
                    "memoryUnitAssignments" to memory.unitAssignments.size.toString(),
                    "memoryRecentFailures" to memory.recentFailures.size.toString(),
                    "strategistRefreshCount" to strategistRefreshCount.toString(),
                    "memoryJson" to memoryJson,
                    "strategistMemoJson" to AgentMemoryManager.strategistMemoJson(memory.lastStrategistMemo),
                    "observationJson" to observationJson,
                    "empireObservationJson" to empireObservationJson,
                    "worldFactsJson" to worldFactsJson,
                    "plannerBriefJson" to plannerBriefJson,
                    "empireResearchCandidates" to empireObservation.researchCandidates.size.toString(),
                    "empirePolicyCandidates" to empireObservation.policyCandidates.size.toString(),
                    "empireMacroCandidates" to empireObservation.macroCandidates.size.toString(),
                    "empireDiplomacyCandidates" to empireObservation.diplomacyCandidates.size.toString(),
                    "currentResearch" to (empireObservation.currentResearch ?: ""),
                    "currentResearchTurnsLeft" to (empireObservation.currentResearchTurnsLeft?.toString() ?: ""),
                ) + extraDetails,
            )
        }

        fun selectPlanForState(passIndex: Int, allowCityCreationBoundary: Boolean): PlanningPassResult {
            var retryContext: AgentRetryContext? = null
            var selectedPlan: AgentActionPlan? = null
            var validationReport: AgentActionExecutor.ValidationReport? = null
            var planningAttempts = 0
            var fallbackReason: String? = null

            while (planningAttempts < maxPlanningAttemptsPerTurn) {
                planningAttempts += 1
                val plan = AgentPlanProviderFactory.provider.buildPlan(memory, observation, empireObservation, civInfo, retryContext)
                if (plan == null) {
                    fallbackReason = "No plan produced"
                    break
                }

                if (plan.handoffToLegacyAI) {
                    selectedPlan = plan
                    fallbackReason = "Planner requested legacy handoff"
                    break
                }

                plan.strategistRefreshRequest?.let { request ->
                    if (strategistRefreshCount < 2) {
                        val approvedRefresh = AgentMemoryManager.shouldHonorTacticalStrategistRefresh(memory, observation, empireObservation, request)
                        if (approvedRefresh != null) {
                            AgentObservability.record(
                                type = "strategist_refresh_requested_by_tactical",
                                message = "Tactical planner requested an emergency strategist refresh",
                                civName = civInfo.civName,
                                turn = civInfo.gameInfo.turns,
                                details = mapOf(
                                    "turnPass" to passIndex.toString(),
                                    "attempt" to planningAttempts.toString(),
                                    "urgency" to approvedRefresh.urgency,
                                    "reason" to approvedRefresh.reason,
                                    "planJson" to AgentPromptBuilder.planJson(plan),
                                ),
                            )
                            val strategicPlan = AgentPlanProviderFactory.provider.buildStrategistMemo(
                                memory = memory,
                                observation = observation,
                                empireObservation = empireObservation,
                                civInfo = civInfo,
                                refreshRequest = approvedRefresh,
                            )
                            if (strategicPlan != null) {
                                memory = AgentMemoryManager.applyStrategicMemo(memory, observation, empireObservation, approvedRefresh, strategicPlan)
                                civInfo.agentMemory = memory.clone()
                                observation = AgentObservationBuilder.build(civInfo, memory)
                                empireObservation = AgentEmpireObservationBuilder.build(civInfo, memory).observation
                                rebuildArtifacts()
                                strategistRefreshCount += 1
                                retryContext = null
                                AgentObservability.record(
                                    type = "strategist_memo_applied",
                                    message = "Strategist memo updated after tactical emergency refresh",
                                    civName = civInfo.civName,
                                    turn = civInfo.gameInfo.turns,
                                    details = mapOf(
                                        "turnPass" to passIndex.toString(),
                                        "urgency" to approvedRefresh.urgency,
                                        "reason" to approvedRefresh.reason,
                                        "strategistMemoJson" to AgentMemoryManager.strategistMemoJson(memory.lastStrategistMemo),
                                        "notes" to (strategicPlan.notes ?: ""),
                                    ),
                                )
                                continue
                            }
                        }
                    }
                }

                val validation = executor.validate(
                    civInfo,
                    plan,
                    ExecutionOptions(stopAfterCityCreation = allowCityCreationBoundary),
                )
                when (validation.status) {
                    AgentActionExecutor.ValidationStatus.Valid -> {
                        selectedPlan = plan
                        validationReport = validation
                        break
                    }
                    AgentActionExecutor.ValidationStatus.NoOp -> {
                        selectedPlan = plan
                        validationReport = validation
                        break
                    }
                    AgentActionExecutor.ValidationStatus.Invalid -> {
                        val salvaged = trySalvageSoftValidationFailure(
                            civInfo = civInfo,
                            plan = plan,
                            validation = validation,
                            stopAfterCityCreation = allowCityCreationBoundary,
                            passIndex = passIndex,
                            planningAttempt = planningAttempts,
                        )
                        if (salvaged != null) {
                            selectedPlan = salvaged.first
                            validationReport = salvaged.second
                            break
                        }

                        AgentObservability.record(
                            type = "plan_validation_failed",
                            message = "Plan failed validation before real execution",
                            civName = civInfo.civName,
                            turn = civInfo.gameInfo.turns,
                            details = mapOf(
                                "turnPass" to passIndex.toString(),
                                "attempt" to planningAttempts.toString(),
                                "maxAttempts" to maxPlanningAttemptsPerTurn.toString(),
                                "failedPlanJson" to AgentPromptBuilder.planJson(plan),
                                "affectedDomains" to AgentTurnDiagnostics.affectedDomainsCsv(validation.outcomes),
                                "outcomeDomainSummaryJson" to AgentTurnDiagnostics.outcomeSummaryJson(validation.outcomes),
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
                                "turnPass" to passIndex.toString(),
                                "attempt" to planningAttempts.toString(),
                                "nextAttempt" to (planningAttempts + 1).toString(),
                                "maxAttempts" to maxPlanningAttemptsPerTurn.toString(),
                                "retryContextJson" to AgentPromptBuilder.retryContextJson(retryContext),
                            ),
                        )
                    }
                }
            }

            return PlanningPassResult(
                selectedPlan = selectedPlan,
                validationReport = validationReport,
                planningAttempts = planningAttempts,
                fallbackReason = fallbackReason,
            )
        }

        rebuildArtifacts()
        recordObservationStart(
            passIndex = 1,
            eventType = "turn_start",
            message = "Built observation for AI agent turn",
        )
        val firstPassResult = selectPlanForState(passIndex = 1, allowCityCreationBoundary = maxPlanningPassesPerTurn > 1)
        var selectedPlan: AgentActionPlan? = firstPassResult.selectedPlan
        var validationReport: AgentActionExecutor.ValidationReport? = firstPassResult.validationReport
        var planningAttempts = firstPassResult.planningAttempts
        var fallbackReason: String? = firstPassResult.fallbackReason

        if (selectedPlan == null) {
            val updatedMemory = AgentMemoryManager.updateAfterTurn(
                civInfo = civInfo,
                observation = observation,
                empireObservation = empireObservation,
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
                ) + memoryDetails(updatedMemory) + domainDetails(
                    plan = null,
                ),
            )
            NextTurnAutomation.automateCivMoves(civInfo)
            return
        }

        if (selectedPlan.handoffToLegacyAI) {
            val updatedMemory = AgentMemoryManager.updateAfterTurn(
                civInfo = civInfo,
                observation = observation,
                empireObservation = empireObservation,
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
                ) + memoryDetails(updatedMemory) + domainDetails(
                    plan = selectedPlan,
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
                empireObservation = empireObservation,
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
                ) + memoryDetails(updatedMemory) + domainDetails(
                    plan = selectedPlan,
                ),
            )
            NextTurnAutomation.automateCivMoves(civInfo)
            return
        }

        if (validation.status == AgentActionExecutor.ValidationStatus.Invalid) {
            val updatedMemory = AgentMemoryManager.updateAfterTurn(
                civInfo = civInfo,
                observation = observation,
                empireObservation = empireObservation,
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
                    "fallbackScope" to AgentTurnDiagnostics.affectedDomainsCsv(validation.rejectedOutcomes),
                    "validationFailuresJson" to AgentPromptBuilder.retryContextJson(
                        AgentRetryContextFactory.fromValidation(
                            plan = selectedPlan,
                            validation = validation,
                            retryAttempt = minOf(planningAttempts, maxPlanningAttemptsPerTurn - 1),
                            maxRetries = maxPlanningAttemptsPerTurn - 1,
                        )
                    ),
                ) + memoryDetails(updatedMemory) + domainDetails(
                    plan = selectedPlan,
                    outcomes = validation.outcomes,
                ),
            )
            NextTurnAutomation.automateCivMoves(civInfo)
            return
        }

        if (validation.status == AgentActionExecutor.ValidationStatus.NoOp) {
            val updatedMemory = AgentMemoryManager.updateAfterTurn(
                civInfo = civInfo,
                observation = observation,
                empireObservation = empireObservation,
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
                ) + memoryDetails(updatedMemory) + domainDetails(
                    plan = selectedPlan,
                ),
            )
            return
        }

        fun combineReports(
            first: AgentActionExecutor.ExecutionReport,
            second: AgentActionExecutor.ExecutionReport? = null,
        ): AgentActionExecutor.ExecutionReport {
            if (second == null) return first
            return AgentActionExecutor.ExecutionReport(
                executedActions = first.executedActions + second.executedActions,
                rejectedActions = first.rejectedActions + second.rejectedActions,
                outcomes = first.outcomes + second.outcomes,
                haltedForCityCreationBoundary = second.haltedForCityCreationBoundary,
            )
        }

        val firstPassReport = executor.execute(
            civInfo,
            selectedPlan,
            ExecutionOptions(stopAfterCityCreation = maxPlanningPassesPerTurn > 1),
        )
        if (firstPassReport.rejectedActions > 0 && !canIgnoreSoftRejections(firstPassReport.outcomes)) {
            val updatedMemory = AgentMemoryManager.updateAfterTurn(
                civInfo = civInfo,
                observation = observation,
                empireObservation = empireObservation,
                startingMemory = memory,
                plan = selectedPlan,
                report = firstPassReport,
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
                    "executedActions" to firstPassReport.executedActions.toString(),
                    "rejectedActions" to firstPassReport.rejectedActions.toString(),
                    "plannedActions" to selectedPlan.actions.size.toString(),
                    "fallbackReason" to "Validated plan still rejected during live execution",
                    "fallbackScope" to AgentTurnDiagnostics.affectedDomainsCsv(firstPassReport.outcomes.filter { it.status == AgentActionExecutor.ActionStatus.Rejected }),
                ) + memoryDetails(updatedMemory) + domainDetails(
                    plan = selectedPlan,
                    outcomes = firstPassReport.outcomes,
                ),
            )
            NextTurnAutomation.automateCivMoves(civInfo)
            return
        }

        var finalPlan = selectedPlan
        var finalReport = firstPassReport
        var turnPasses = 1

        if (firstPassReport.haltedForCityCreationBoundary && maxPlanningPassesPerTurn > 1) {
            turnPasses = 2
            AgentObservability.record(
                type = "mid_turn_replan_requested",
                message = "Stopping after city creation and rebuilding action space for the same turn",
                civName = civInfo.civName,
                turn = civInfo.gameInfo.turns,
                details = mapOf(
                    "turnPass" to "1",
                    "boundary" to "city_creation",
                    "executedActions" to firstPassReport.executedActions.toString(),
                    "plannedActions" to selectedPlan.actions.size.toString(),
                ) + domainDetails(
                    plan = selectedPlan,
                    outcomes = firstPassReport.outcomes,
                ),
            )

            observation = AgentObservationBuilder.build(civInfo, memory)
            empireObservation = AgentEmpireObservationBuilder.build(civInfo, memory).observation
            rebuildArtifacts()
            recordObservationStart(
                passIndex = 2,
                eventType = "turn_replan_start",
                message = "Rebuilt observation after city creation boundary",
                extraDetails = mapOf("boundary" to "city_creation"),
            )

            val secondPassResult = selectPlanForState(passIndex = 2, allowCityCreationBoundary = false)
            planningAttempts += secondPassResult.planningAttempts
            selectedPlan = secondPassResult.selectedPlan
            validationReport = secondPassResult.validationReport
            fallbackReason = secondPassResult.fallbackReason

            if (selectedPlan == null) {
                val updatedMemory = AgentMemoryManager.updateAfterTurn(
                    civInfo = civInfo,
                    observation = observation,
                    empireObservation = empireObservation,
                    startingMemory = memory,
                    plan = null,
                    report = firstPassReport,
                    usedLegacyFallback = true,
                    fallbackReason = fallbackReason ?: "No plan produced after city-creation replan",
                )
                AgentObservability.record(
                    type = "plan_missing",
                    message = "No usable second-pass plan produced after city creation; falling back to legacy AI",
                    civName = civInfo.civName,
                    turn = civInfo.gameInfo.turns,
                    details = mapOf(
                        "turnPasses" to turnPasses.toString(),
                        "planningAttempts" to planningAttempts.toString(),
                        "executedActions" to firstPassReport.executedActions.toString(),
                        "fallbackReason" to (fallbackReason ?: "No plan produced after city-creation replan"),
                    ) + memoryDetails(updatedMemory) + domainDetails(
                        plan = null,
                        outcomes = firstPassReport.outcomes,
                    ),
                )
                NextTurnAutomation.automateCivMoves(civInfo)
                return
            }

            if (selectedPlan.handoffToLegacyAI) {
                val updatedMemory = AgentMemoryManager.updateAfterTurn(
                    civInfo = civInfo,
                    observation = observation,
                    empireObservation = empireObservation,
                    startingMemory = memory,
                    plan = selectedPlan,
                    report = firstPassReport,
                    usedLegacyFallback = true,
                    fallbackReason = fallbackReason ?: "Planner requested legacy handoff after city-creation replan",
                )
                AgentObservability.record(
                    type = "fallback_legacy",
                    message = "Second-pass planner requested legacy fallback",
                    civName = civInfo.civName,
                    turn = civInfo.gameInfo.turns,
                    details = mapOf(
                        "turnPasses" to turnPasses.toString(),
                        "planningAttempts" to planningAttempts.toString(),
                        "executedActions" to firstPassReport.executedActions.toString(),
                        "rejectedActions" to "0",
                        "handoffToLegacyAI" to "true",
                        "plannedActions" to selectedPlan.actions.size.toString(),
                        "fallbackReason" to (fallbackReason ?: "Planner requested legacy handoff after city-creation replan"),
                    ) + memoryDetails(updatedMemory) + domainDetails(
                        plan = selectedPlan,
                        outcomes = firstPassReport.outcomes,
                    ),
                )
                NextTurnAutomation.automateCivMoves(civInfo)
                return
            }

            val secondValidation = validationReport
            if (secondValidation == null) {
                val updatedMemory = AgentMemoryManager.updateAfterTurn(
                    civInfo = civInfo,
                    observation = observation,
                    empireObservation = empireObservation,
                    startingMemory = memory,
                    plan = selectedPlan,
                    report = firstPassReport,
                    usedLegacyFallback = true,
                    fallbackReason = "Second-pass validation did not complete",
                )
                AgentObservability.record(
                    type = "fallback_legacy",
                    message = "Second-pass validation did not complete",
                    civName = civInfo.civName,
                    turn = civInfo.gameInfo.turns,
                    details = mapOf(
                        "turnPasses" to turnPasses.toString(),
                        "planningAttempts" to planningAttempts.toString(),
                        "executedActions" to firstPassReport.executedActions.toString(),
                        "rejectedActions" to "0",
                        "plannedActions" to selectedPlan.actions.size.toString(),
                        "fallbackReason" to "Second-pass validation did not complete",
                    ) + memoryDetails(updatedMemory) + domainDetails(
                        plan = selectedPlan,
                        outcomes = firstPassReport.outcomes,
                    ),
                )
                NextTurnAutomation.automateCivMoves(civInfo)
                return
            }

            if (secondValidation.status == AgentActionExecutor.ValidationStatus.Invalid) {
                val updatedMemory = AgentMemoryManager.updateAfterTurn(
                    civInfo = civInfo,
                    observation = observation,
                    empireObservation = empireObservation,
                    startingMemory = memory,
                    plan = selectedPlan,
                    report = firstPassReport,
                    usedLegacyFallback = true,
                    fallbackReason = fallbackReason ?: "Second-pass plan remained invalid after retries",
                    validationFailures = secondValidation.rejectedOutcomes,
                )
                AgentObservability.record(
                    type = "fallback_legacy",
                    message = "Second-pass plan remained invalid after retries; handing turn to legacy AI",
                    civName = civInfo.civName,
                    turn = civInfo.gameInfo.turns,
                    details = mapOf(
                        "turnPasses" to turnPasses.toString(),
                        "planningAttempts" to planningAttempts.toString(),
                        "executedActions" to firstPassReport.executedActions.toString(),
                        "rejectedActions" to secondValidation.rejectedActionsPreview.toString(),
                        "plannedActions" to selectedPlan.actions.size.toString(),
                        "fallbackReason" to (fallbackReason ?: "Second-pass plan remained invalid after retries"),
                        "fallbackScope" to AgentTurnDiagnostics.affectedDomainsCsv(secondValidation.rejectedOutcomes),
                        "validationFailuresJson" to AgentPromptBuilder.retryContextJson(
                            AgentRetryContextFactory.fromValidation(
                                plan = selectedPlan,
                                validation = secondValidation,
                                retryAttempt = minOf(planningAttempts, maxPlanningAttemptsPerTurn - 1),
                                maxRetries = maxPlanningAttemptsPerTurn - 1,
                            )
                        ),
                    ) + memoryDetails(updatedMemory) + domainDetails(
                        plan = selectedPlan,
                        outcomes = firstPassReport.outcomes + secondValidation.outcomes,
                    ),
                )
                NextTurnAutomation.automateCivMoves(civInfo)
                return
            }

            if (secondValidation.status == AgentActionExecutor.ValidationStatus.NoOp) {
                finalPlan = selectedPlan
                finalReport = firstPassReport
            } else {
                val secondPassReport = executor.execute(civInfo, selectedPlan)
                if (secondPassReport.rejectedActions > 0 && !canIgnoreSoftRejections(secondPassReport.outcomes)) {
                    val combinedFailureReport = combineReports(firstPassReport, secondPassReport)
                    val updatedMemory = AgentMemoryManager.updateAfterTurn(
                        civInfo = civInfo,
                        observation = observation,
                        empireObservation = empireObservation,
                        startingMemory = memory,
                        plan = selectedPlan,
                        report = combinedFailureReport,
                        usedLegacyFallback = true,
                        fallbackReason = "Validated second-pass plan still rejected during live execution",
                    )
                    AgentObservability.record(
                        type = "fallback_legacy",
                        message = "Validated second-pass plan diverged during live execution; handing turn to legacy AI",
                        civName = civInfo.civName,
                        turn = civInfo.gameInfo.turns,
                        details = mapOf(
                            "turnPasses" to turnPasses.toString(),
                            "planningAttempts" to planningAttempts.toString(),
                            "executedActions" to combinedFailureReport.executedActions.toString(),
                            "rejectedActions" to combinedFailureReport.rejectedActions.toString(),
                            "plannedActions" to selectedPlan.actions.size.toString(),
                            "fallbackReason" to "Validated second-pass plan still rejected during live execution",
                            "fallbackScope" to AgentTurnDiagnostics.affectedDomainsCsv(
                                combinedFailureReport.outcomes.filter { it.status == AgentActionExecutor.ActionStatus.Rejected }
                            ),
                        ) + memoryDetails(updatedMemory) + domainDetails(
                            plan = selectedPlan,
                            outcomes = combinedFailureReport.outcomes,
                        ),
                    )
                    NextTurnAutomation.automateCivMoves(civInfo)
                    return
                }
                finalPlan = selectedPlan
                finalReport = combineReports(firstPassReport, secondPassReport)
            }
        }

        val updatedMemory = AgentMemoryManager.updateAfterTurn(
            civInfo = civInfo,
            observation = observation,
            empireObservation = empireObservation,
            startingMemory = memory,
            plan = finalPlan,
            report = finalReport,
            usedLegacyFallback = false,
        )
        Log.debug(
            "AI (agent) applied plan for civ %s on turn %s, executed=%s rejected=%s",
            civInfo.civName,
            civInfo.gameInfo.turns,
            finalReport.executedActions,
            finalReport.rejectedActions,
        )
        AgentObservability.record(
            type = "plan_applied",
            message = "Plan executed without legacy fallback",
            civName = civInfo.civName,
            turn = civInfo.gameInfo.turns,
            details = mapOf(
                "turnPasses" to turnPasses.toString(),
                "planningAttempts" to planningAttempts.toString(),
                "executedActions" to finalReport.executedActions.toString(),
                "rejectedActions" to finalReport.rejectedActions.toString(),
                "plannedActions" to finalPlan.actions.size.toString(),
            ) + memoryDetails(updatedMemory) + domainDetails(
                plan = finalPlan,
                outcomes = finalReport.outcomes,
            ),
        )
    }

    private fun trySalvageSoftValidationFailure(
        civInfo: Civilization,
        plan: AgentActionPlan,
        validation: AgentActionExecutor.ValidationReport,
        stopAfterCityCreation: Boolean,
        passIndex: Int,
        planningAttempt: Int,
    ): Pair<AgentActionPlan, AgentActionExecutor.ValidationReport>? {
        val rejectedOutcomes = validation.rejectedOutcomes
        if (rejectedOutcomes.isEmpty() || !rejectedOutcomes.all(::isSoftRejectedOutcome)) return null

        val prunedActions = plan.actions.filterNot { action -> rejectedOutcomes.any { matchesOutcome(action, it) } }
        if (prunedActions.size == plan.actions.size) return null
        if (prunedActions.none { it !is AgentActionCommand.EndTurn }) return null

        val salvagedPlan = plan.copy(
            actions = prunedActions,
            notes = appendPlanNote(plan.notes, "Auto-pruned ${rejectedOutcomes.size} low-value invalid action(s) after validation."),
        )
        val salvagedValidation = executor.validate(
            civInfo,
            salvagedPlan,
            ExecutionOptions(stopAfterCityCreation = stopAfterCityCreation),
        )
        if (salvagedValidation.status == AgentActionExecutor.ValidationStatus.Invalid) return null

        AgentObservability.record(
            type = "plan_validation_soft_pruned",
            message = "Removed low-value invalid actions and kept the rest of the plan",
            civName = civInfo.civName,
            turn = civInfo.gameInfo.turns,
            details = mapOf(
                "turnPass" to passIndex.toString(),
                "attempt" to planningAttempt.toString(),
                "originalPlanJson" to AgentPromptBuilder.planJson(plan),
                "salvagedPlanJson" to AgentPromptBuilder.planJson(salvagedPlan),
                "validationFailuresJson" to AgentPromptBuilder.retryContextJson(
                    AgentRetryContextFactory.fromValidation(
                        plan = plan,
                        validation = validation,
                        retryAttempt = minOf(planningAttempt, maxPlanningAttemptsPerTurn - 1),
                        maxRetries = maxPlanningAttemptsPerTurn - 1,
                    )
                ),
            ),
        )

        return salvagedPlan to salvagedValidation
    }

    private fun canIgnoreSoftRejections(outcomes: List<AgentActionExecutor.ActionOutcome>): Boolean {
        val rejected = outcomes.filter { it.status == AgentActionExecutor.ActionStatus.Rejected }
        if (rejected.isEmpty()) return false
        val executedMeaningful = outcomes.any {
            it.status == AgentActionExecutor.ActionStatus.Executed && it.commandType != "end_turn"
        }
        return executedMeaningful && rejected.all(::isSoftRejectedOutcome)
    }

    private fun isSoftRejectedOutcome(outcome: AgentActionExecutor.ActionOutcome): Boolean = when {
        outcome.commandType == "select_unit_option" &&
            outcome.reason == "Unit option rejected: frontier move is no longer attractive or reachable" -> true
        outcome.commandType == "unit_move" &&
            outcome.reason == "Unit move rejected: movement produced no position change" -> true
        else -> false
    }

    private fun matchesOutcome(action: AgentActionCommand, outcome: AgentActionExecutor.ActionOutcome): Boolean = when (action) {
        is AgentActionCommand.SelectEmpireOption ->
            outcome.commandType == "select_empire_option" && action.candidateId == outcome.candidateId
        is AgentActionCommand.SelectCityOption ->
            outcome.commandType == "select_city_option" && action.candidateId == outcome.candidateId
        is AgentActionCommand.SelectUnitOption ->
            outcome.commandType == "select_unit_option" && action.candidateId == outcome.candidateId
        is AgentActionCommand.UnitMove ->
            outcome.commandType == "unit_move" && action.unitId == outcome.unitId
        is AgentActionCommand.UnitAction ->
            outcome.commandType == "unit_action" && action.unitId == outcome.unitId && action.actionType == outcome.actionType
        is AgentActionCommand.EndTurn -> false
    }

    private fun appendPlanNote(existing: String?, extra: String): String =
        when {
            existing.isNullOrBlank() -> extra
            existing.contains(extra) -> existing
            else -> "$existing $extra"
        }

    private fun memoryDetails(memory: AgentMemory): Map<String, String> = mapOf(
        "memoryCampaignStage" to memory.campaign.stage,
        "memoryCityIntents" to memory.cityIntents.size.toString(),
        "memoryUnitAssignments" to memory.unitAssignments.size.toString(),
        "memoryRecentFailures" to memory.recentFailures.size.toString(),
        "memoryWinPath" to (memory.lastStrategistMemo.winPath ?: ""),
        "memoryPhase" to memory.lastStrategistMemo.phase,
        "strategistMemoJson" to AgentMemoryManager.strategistMemoJson(memory.lastStrategistMemo),
    )

    private fun domainDetails(
        plan: AgentActionPlan?,
        outcomes: List<AgentActionExecutor.ActionOutcome> = emptyList(),
    ): Map<String, String> {
        val details = linkedMapOf<String, String>()
        if (outcomes.isNotEmpty()) {
            details["outcomeDomainSummaryJson"] = AgentTurnDiagnostics.outcomeSummaryJson(outcomes)
            details["affectedDomains"] = AgentTurnDiagnostics.affectedDomainsCsv(outcomes)
        }
        return details
    }

}
