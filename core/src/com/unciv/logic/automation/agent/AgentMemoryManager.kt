package com.unciv.logic.automation.agent

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object AgentMemoryManager {
    private const val recentFailureWindowTurns = 8
    private const val maxRecentFailures = 8
    private const val cityIntentHorizonTurns = 5
    private const val unitAssignmentHorizonTurns = 4
    private const val recentChangeHorizonTurns = 12
    private const val maxRecentChanges = 10
    private const val maxLessons = 8
    private const val maxTacticianTurnLogEntries = 8
    private const val maxStrategistTriggerLedgerEntries = 16

    private val canonicalUnitAssignmentRoles = setOf(
        "auto_explore",
        "hold_position",
        "heal_and_hold",
        "stage_near_target_city",
        "attack_target_city",
        "fallback_and_heal",
        "move_to_tile",
        "settle_city_site",
        "improve_tile",
        "attack_target",
        "stop_auto_explore",
        "upgrade_self",
        "pillage_here",
        "use_special_ability",
    )

    private val json = Json {
        prettyPrint = false
        explicitNulls = false
    }

    fun memoryJson(memory: AgentMemory): String = json.encodeToString(memory)
    fun strategistMemoJson(memo: AgentStrategistMemoMemory): String = json.encodeToString(memo)

    fun prepareForTurn(
        civInfo: Civilization,
        observation: AgentObservation,
        empireObservation: AgentEmpireObservation,
    ): AgentMemory {
        val turn = civInfo.gameInfo.turns
        val validCities = civInfo.cities.associateBy { cityKey(it.location.x, it.location.y) }
        val validUnits = civInfo.units.getCivUnits().associateBy { it.id }
        val existing = civInfo.agentMemory.clone()

        val basePrepared = existing.copy(
            worldModel = refreshWorldModelAnchors(pruneWorldModel(existing.worldModel, turn), observation, turn),
            campaign = pruneCampaign(existing.campaign, empireObservation, turn),
            empirePlan = pruneEmpirePlan(existing.empirePlan, turn),
            campaignControl = pruneCampaignControl(existing.campaignControl, turn),
            recentChanges = pruneNotes(existing.recentChanges, turn, maxRecentChanges),
            lessons = ArrayList(existing.lessons.takeLast(maxLessons).map { it.copy() }),
            lastStrategistMemo = existing.lastStrategistMemo.copy(
                decisionFrame = existing.lastStrategistMemo.decisionFrame.copy(),
                controlLanes = existing.lastStrategistMemo.controlLanes.copy(
                    driftWarnings = ArrayList(existing.lastStrategistMemo.controlLanes.driftWarnings)
                ),
                campaignControl = existing.lastStrategistMemo.campaignControl.copy(),
                reviewCityNames = ArrayList(existing.lastStrategistMemo.reviewCityNames),
            ),
            tacticianTurnLog = pruneTacticianTurnLog(existing.tacticianTurnLog, turn),
            cityIntents = ArrayList(
                existing.cityIntents
                    .filter { it.staleAfterTurn >= turn && cityKey(it.cityX, it.cityY) in validCities }
                    .map { it.copy(reasons = ArrayList(it.reasons)) }
            ),
            unitAssignments = reconcileLiveUnitAssignments(
                existing.unitAssignments
                    .filter { it.staleAfterTurn >= turn && it.unitId in validUnits }
                    .map { it.copy() },
                validUnits.values,
                turn,
            ),
            recentFailures = ArrayList(
                existing.recentFailures
                    .filter { turn - it.turn <= recentFailureWindowTurns }
                    .takeLast(maxRecentFailures)
                .map { it.copy() }
            ),
            strategistTriggerLedger = pruneStrategistTriggerLedger(existing.strategistTriggerLedger, turn),
        )
        val prepared = basePrepared.copy(
            campaignControl = reconcileCampaignControl(
                current = basePrepared.campaignControl,
                memo = basePrepared.lastStrategistMemo,
                snapshot = buildCampaignControlSnapshot(observation, empireObservation, basePrepared.lastStrategistMemo),
                turn = turn,
                latestCommitmentLevel = basePrepared.tacticianTurnLog.lastOrNull()?.commitmentLevel,
                latestBattleReadiness = basePrepared.tacticianTurnLog.lastOrNull()?.battleReadiness,
                latestSupplyHealth = basePrepared.tacticianTurnLog.lastOrNull()?.supplyHealth,
            ),
        )
        val completedAssignments = ensureUnitAssignmentsCoverAllUnits(civInfo, observation, prepared, turn)

        civInfo.agentMemory = completedAssignments.clone()
        return completedAssignments
    }

    fun updateAfterTurn(
        civInfo: Civilization,
        observation: AgentObservation,
        empireObservation: AgentEmpireObservation,
        startingMemory: AgentMemory,
        plan: AgentActionPlan?,
        report: AgentActionExecutor.ExecutionReport?,
        usedLegacyFallback: Boolean,
        fallbackReason: String? = null,
        validationFailures: List<AgentActionExecutor.ActionOutcome> = emptyList(),
        intentionalNoOp: Boolean = false,
    ): AgentMemory {
        val turn = civInfo.gameInfo.turns
        val refreshed = prepareNotebookForTurn(startingMemory, observation, empireObservation, turn)
        val intentReconciliation = reconcileIntentCarryover(
            civInfo = civInfo,
            existingCityIntents = refreshed.cityIntents,
            existingUnitAssignments = refreshed.unitAssignments,
            turn = turn,
        )
        val recentFailures = buildRecentFailures(
            existing = startingMemory.recentFailures,
            turn = turn,
            plan = plan,
            report = report,
            usedLegacyFallback = usedLegacyFallback,
            fallbackReason = fallbackReason,
            validationFailures = validationFailures,
        )

        val afterActionNotes = buildAfterActionNotes(
            turn = turn,
            plan = plan,
            report = report,
            usedLegacyFallback = usedLegacyFallback,
            fallbackReason = fallbackReason,
            intentionalNoOp = intentionalNoOp,
        )

        if (plan == null || usedLegacyFallback || intentionalNoOp) {
            val tacticianEntry = buildTacticianTurnLogEntry(
                civInfo = civInfo,
                observation = observation,
                empireObservation = empireObservation,
                startingMemory = startingMemory,
                plan = plan,
                report = report,
                usedLegacyFallback = usedLegacyFallback,
                fallbackReason = fallbackReason,
                intentionalNoOp = intentionalNoOp,
                completed = intentReconciliation.completed,
                obsolete = intentReconciliation.obsolete,
            )
            val updated = refreshed.copy(
                recentChanges = mergeRecentChanges(refreshed.recentChanges, afterActionNotes, turn),
                tacticianTurnLog = mergeTacticianTurnLog(
                    refreshed.tacticianTurnLog,
                    tacticianEntry,
                ),
                cityIntents = intentReconciliation.cityIntents,
                unitAssignments = intentReconciliation.unitAssignments,
                recentFailures = recentFailures,
            )
            val reconciled = updated.copy(
                campaignControl = reconcileCampaignControl(
                    current = updated.campaignControl,
                    memo = updated.lastStrategistMemo,
                    snapshot = buildCampaignControlSnapshot(civInfo, observation, empireObservation, updated.lastStrategistMemo),
                    turn = turn,
                    latestCommitmentLevel = updated.tacticianTurnLog.lastOrNull()?.commitmentLevel,
                    latestBattleReadiness = updated.tacticianTurnLog.lastOrNull()?.battleReadiness,
                    latestSupplyHealth = updated.tacticianTurnLog.lastOrNull()?.supplyHealth,
                ),
            )
            val completedAssignments = ensureUnitAssignmentsCoverAllUnits(civInfo, observation, reconciled, turn)
            civInfo.agentMemory = completedAssignments.clone()
            return completedAssignments
        }

        val newCityIntents = deriveCityIntents(observation, plan, turn)
        val newUnitAssignments = deriveUnitAssignments(observation, plan, turn)
        val plannedCityKeys = newCityIntents.map { cityKey(it.cityX, it.cityY) }.toSet()
        val touchedUnitIds = extractTouchedUnitIds(plan)

        val preservedCityIntents = intentReconciliation.cityIntents
            .filter { it.staleAfterTurn >= turn && cityKey(it.cityX, it.cityY) !in plannedCityKeys }
            .map { it.copy(reasons = ArrayList(it.reasons)) }
        val preservedUnitAssignments = intentReconciliation.unitAssignments
            .filter { it.staleAfterTurn >= turn && it.unitId !in touchedUnitIds }
            .map { it.copy() }

        val tacticianEntry = buildTacticianTurnLogEntry(
            civInfo = civInfo,
            observation = observation,
            empireObservation = empireObservation,
            startingMemory = startingMemory,
            plan = plan,
            report = report,
            usedLegacyFallback = usedLegacyFallback,
            fallbackReason = fallbackReason,
            intentionalNoOp = intentionalNoOp,
            completed = intentReconciliation.completed,
            obsolete = intentReconciliation.obsolete,
        )

        val updated = refreshed.copy(
            cityIntents = ArrayList(preservedCityIntents + newCityIntents),
            unitAssignments = ArrayList(preservedUnitAssignments + newUnitAssignments),
            recentChanges = mergeRecentChanges(refreshed.recentChanges, afterActionNotes, turn),
            tacticianTurnLog = mergeTacticianTurnLog(
                refreshed.tacticianTurnLog,
                tacticianEntry,
            ),
            recentFailures = recentFailures,
        )
        val reconciled = updated.copy(
            campaignControl = reconcileCampaignControl(
                current = updated.campaignControl,
                memo = updated.lastStrategistMemo,
                snapshot = buildCampaignControlSnapshot(civInfo, observation, empireObservation, updated.lastStrategistMemo),
                turn = turn,
                latestCommitmentLevel = updated.tacticianTurnLog.lastOrNull()?.commitmentLevel,
                latestBattleReadiness = updated.tacticianTurnLog.lastOrNull()?.battleReadiness,
                latestSupplyHealth = updated.tacticianTurnLog.lastOrNull()?.supplyHealth,
            ),
        )
        val completedAssignments = ensureUnitAssignmentsCoverAllUnits(civInfo, observation, reconciled, turn)
        civInfo.agentMemory = completedAssignments.clone()
        return completedAssignments
    }

    fun shouldRefreshStrategist(
        memory: AgentMemory,
        observation: AgentObservation,
        empireObservation: AgentEmpireObservation,
    ): AgentStrategistRefreshRequest? {
        val turn = observation.turn
        val memo = memory.lastStrategistMemo
        if (!hasStrategistMemo(memo)) {
            return AgentStrategistRefreshRequest(
                urgency = "initial",
                reason = "No strategist memo exists yet. Build the first game notebook and high-level handoff for this match.",
                source = "initial",
            )
        }
        hardRefreshIfEmergency(memory, observation, empireObservation, requested = null)?.let { return it }
        evaluateStrategistReviewContract(memory, observation, empireObservation)?.let { return it }
        val reviewAnchorTurn = strategistReviewAnchorTurn(memo, turn)
        val maxAgeTurns = memo.reviewContract.maxAgeTurns.coerceAtLeast(0)
        if (maxAgeTurns > 0 && turn - reviewAnchorTurn >= maxAgeTurns) {
            return AgentStrategistRefreshRequest(
                urgency = "scheduled",
                reason = "The strategist review contract hit its max age backstop and should be refreshed from the current board state.",
                source = "max_age",
            )
        }
        return null
    }

    fun shouldHonorTacticalStrategistRefresh(
        memory: AgentMemory,
        observation: AgentObservation,
        empireObservation: AgentEmpireObservation,
        requested: AgentStrategistRefreshRequest,
    ): AgentStrategistRefreshRequest? {
        if (!requested.urgency.equals("emergency", ignoreCase = true)) return null
        return hardRefreshIfEmergency(memory, observation, empireObservation, requested)
    }

    fun applyStrategicMemo(
        memory: AgentMemory,
        observation: AgentObservation,
        empireObservation: AgentEmpireObservation,
        refreshRequest: AgentStrategistRefreshRequest,
        strategicPlan: AgentStrategicPlan,
    ): AgentMemory {
        val turn = observation.turn
        val gameContext = empireObservation.gameContext
        val normalizedReviewContract = normalizeStrategistReviewContract(strategicPlan.memo.reviewContract, gameContext)

        val memo = AgentStrategistMemoMemory(
            gameArchetype = gameContext.archetype,
            winPath = strategicPlan.memo.winPath?.trim().takeUnless { it.isNullOrEmpty() },
            campaignStage = strategicPlan.memo.campaignStage.trim(),
            decisiveObjective = strategicPlan.memo.decisiveObjective.trim().takeUnless { it.isEmpty() },
            conversionBlocker = strategicPlan.memo.conversionBlocker?.trim().takeUnless { it.isNullOrEmpty() },
            decisionFrame = normalizeStrategistDecisionFrame(strategicPlan.memo.decisionFrame),
            controlLanes = normalizeStrategistControlLanes(strategicPlan.memo.controlLanes),
            reviewContract = normalizedReviewContract,
            campaignControl = normalizeCampaignControlLabels(strategicPlan.memo.campaignControl),
            thesis = strategicPlan.memo.thesis?.trim().takeUnless { it.isNullOrEmpty() },
            pastSummary = strategicPlan.memo.pastSummary?.trim().takeUnless { it.isNullOrEmpty() },
            currentSituation = strategicPlan.memo.currentSituation?.trim().takeUnless { it.isNullOrEmpty() },
            futurePlan = strategicPlan.memo.futurePlan?.trim().takeUnless { it.isNullOrEmpty() },
            tacticianHandoff = strategicPlan.memo.tacticianHandoff?.trim().takeUnless { it.isNullOrEmpty() },
            reviewCityCount = observation.empireSummary.cityCount,
            reviewMilitaryUnitCount = observation.empireSummary.militaryUnitCount,
            reviewIsAtWar = observation.empireSummary.isAtWar,
            reviewContactComplete = empireObservation.gameContext.contactComplete,
            reviewResearch = empireObservation.currentResearch,
            reviewVisibleRivalCities = observation.visibleThreatsAndTargets.count { it.kind == "city" && it.civName != observation.civName },
            reviewVisibleRivalCapitals = observation.visibleThreatsAndTargets.count {
                it.kind == "city" &&
                    it.civName != observation.civName &&
                    it.facts.any { fact -> fact.equals("Capital", ignoreCase = true) }
            },
            reviewVisibleRivalUnits = observation.visibleThreatsAndTargets.count { it.kind == "unit" && it.civName != observation.civName },
            reviewPrimaryRivalCiv = extractPrimaryRivalCiv(memory, observation, empireObservation),
            reviewCityNames = ArrayList(observation.cities.map { it.name }.sorted()),
            createdTurn = if (hasStrategistMemo(memory.lastStrategistMemo) && isSameCampaignThread(memory.lastStrategistMemo, strategicPlan.memo)) {
                memory.lastStrategistMemo.createdTurn
            } else {
                turn
            },
            lastReviewedTurn = turn,
            lastRefreshReason = refreshRequest.reason,
        )

        val updated = memory.copy(
            worldModel = refreshWorldModelAnchors(buildWorldModel(memory.worldModel, strategicPlan.memo, turn), observation, turn),
            campaign = buildCampaignMemory(memory.campaign, strategicPlan.memo, observation, empireObservation, turn),
            empirePlan = buildEmpirePlan(memory.empirePlan, strategicPlan.memo, turn),
            campaignControl = buildCampaignControl(memory.campaignControl, memory.lastStrategistMemo, strategicPlan.memo, turn),
            recentChanges = buildNoteList("recent_change", "change", strategicPlan.memo.recentChanges, turn, recentChangeHorizonTurns, maxRecentChanges),
            lessons = buildNoteList("lesson", "lesson", strategicPlan.memo.lessons, turn, turn + 200, maxLessons),
            lastStrategistMemo = memo,
            tacticianTurnLog = pruneTacticianTurnLog(memory.tacticianTurnLog, turn),
            cityIntents = ArrayList(memory.cityIntents.map { it.copy(reasons = ArrayList(it.reasons)) }),
            unitAssignments = ArrayList(memory.unitAssignments.map { it.copy() }),
            recentFailures = ArrayList(memory.recentFailures.map { it.copy() }),
            strategistTriggerLedger = recordStrategistTriggerLedger(
                existing = memory.strategistTriggerLedger,
                previousMemo = memory.lastStrategistMemo,
                refreshRequest = refreshRequest,
                turn = turn,
            ),
        )
        return updated
    }

    private fun hardRefreshIfEmergency(
        memory: AgentMemory,
        observation: AgentObservation,
        empireObservation: AgentEmpireObservation,
        requested: AgentStrategistRefreshRequest?,
    ): AgentStrategistRefreshRequest? {
        val memo = memory.lastStrategistMemo
        if (!hasStrategistMemo(memo)) return requested
        val primaryThreat = empireObservation.victoryThreats.firstOrNull()
        val requestedReason = requested?.reason?.trim().orEmpty()
        if (observation.empireSummary.isAtWar && !looksWarAware(memory)) {
            return AgentStrategistRefreshRequest(
                urgency = "emergency",
                reason = requestedReason.ifBlank { "War or active hostilities broke the current campaign notebook and need a fresh strategist memo." },
                source = requested?.source ?: "emergency",
                triggerKind = requested?.triggerKind,
                triggerMetric = requested?.triggerMetric,
                triggerWithinTurns = requested?.triggerWithinTurns,
            )
        }
        if (primaryThreat?.threatLevel == "critical" &&
            memory.campaign.primaryRivalCiv != null &&
            memory.campaign.primaryRivalCiv != primaryThreat.civName
        ) {
            return AgentStrategistRefreshRequest(
                urgency = "emergency",
                reason = requestedReason.ifBlank { "${primaryThreat.civName} is now the critical rival, so the strategist notebook should be refreshed immediately." },
                source = requested?.source ?: "emergency",
                triggerKind = requested?.triggerKind,
                triggerMetric = requested?.triggerMetric,
                triggerWithinTurns = requested?.triggerWithinTurns,
            )
        }
        if (requested != null && requested.urgency.equals("emergency", ignoreCase = true)) {
            return AgentStrategistRefreshRequest(
                urgency = "emergency",
                reason = requestedReason.ifBlank { "The tactician reported a strategic break in the current notebook." },
                source = requested.source ?: "emergency",
                triggerKind = requested.triggerKind,
                triggerMetric = requested.triggerMetric,
                triggerWithinTurns = requested.triggerWithinTurns,
            )
        }
        return null
    }

    private fun evaluateStrategistReviewContract(
        memory: AgentMemory,
        observation: AgentObservation,
        empireObservation: AgentEmpireObservation,
    ): AgentStrategistRefreshRequest? {
        val memo = memory.lastStrategistMemo
        val reviewContract = memo.reviewContract.takeUnless { it.isEmpty() } ?: return null
        for (trigger in reviewContract.triggers.take(4)) {
            if (hasConsumedStrategistReviewTrigger(memory, memo, trigger)) continue
            val refreshReason = evaluateStrategistReviewTrigger(trigger, memo, memory, observation, empireObservation) ?: continue
            return AgentStrategistRefreshRequest(
                urgency = "contract",
                reason = refreshReason,
                source = "review_contract",
                triggerKind = trigger.kind,
                triggerMetric = normalizeStrategistReviewMetric(trigger.metric),
                triggerWithinTurns = trigger.withinTurns,
            )
        }
        return null
    }

    private fun evaluateStrategistReviewTrigger(
        trigger: AgentStrategistReviewTrigger,
        memo: AgentStrategistMemoMemory,
        memory: AgentMemory,
        observation: AgentObservation,
        empireObservation: AgentEmpireObservation,
    ): String? {
        val reviewAnchorTurn = strategistReviewAnchorTurn(memo, observation.turn)
        val turnsSinceReview = (observation.turn - reviewAnchorTurn).coerceAtLeast(0)
        val currentState = currentReviewMetricState(memo, memory, observation, empireObservation, trigger.metric)
        return when (trigger.kind) {
            "milestone_reached" -> if (currentState) {
                trigger.summary?.takeIf { it.isNotBlank() }
                    ?: defaultReviewTriggerReason(trigger.kind, trigger.metric)
            } else {
                null
            }
            "deadline_missed" -> {
                val withinTurns = trigger.withinTurns?.coerceAtLeast(1) ?: return null
                if (turnsSinceReview >= withinTurns && !currentState) {
                    trigger.summary?.takeIf { it.isNotBlank() }
                        ?: "The strategist review deadline for ${humanizeReviewMetric(trigger.metric)} was missed and the memo should be rewritten."
                } else {
                    null
                }
            }
            "assumption_broken", "contract_broken" -> if (currentState) {
                trigger.summary?.takeIf { it.isNotBlank() }
                    ?: defaultReviewTriggerReason(trigger.kind, trigger.metric)
            } else {
                null
            }
            else -> null
        }
    }

    private fun currentReviewMetricState(
        memo: AgentStrategistMemoMemory,
        memory: AgentMemory,
        observation: AgentObservation,
        empireObservation: AgentEmpireObservation,
        metric: String,
    ): Boolean {
        val normalizedMetric = normalizeStrategistReviewMetric(metric) ?: return false
        val currentVisibleRivalCities = observation.visibleThreatsAndTargets.count { it.kind == "city" && it.civName != observation.civName }
        val currentVisibleRivalCapitals = observation.visibleThreatsAndTargets.count {
            it.kind == "city" &&
                it.civName != observation.civName &&
                it.facts.any { fact -> fact.equals("Capital", ignoreCase = true) }
        }
        val latestMismatch = memory.tacticianTurnLog
            .lastOrNull { entry ->
                entry.basedOnStrategistTurn == memo.lastReviewedTurn &&
                    entry.actionSurfaceMismatch.isNotEmpty()
            }
            ?.actionSurfaceMismatch
            .orEmpty()
        return when (normalizedMetric) {
            "contact_made" -> empireObservation.gameContext.contactComplete
            "city_founded", "city_captured", "city_count_increased", "capture_capital" ->
                observation.empireSummary.cityCount > memo.reviewCityCount
            "second_city_founded" -> observation.empireSummary.cityCount >= 2
            "war_declared", "war_started_unexpectedly" -> observation.empireSummary.isAtWar
            "rival_city_visible" -> currentVisibleRivalCities > memo.reviewVisibleRivalCities
            "rival_capital_visible" -> currentVisibleRivalCapitals > memo.reviewVisibleRivalCapitals
            "primary_rival_changed" -> {
                val currentPrimaryRival = extractPrimaryRivalCiv(memory, observation, empireObservation)
                currentPrimaryRival != null &&
                    memo.reviewPrimaryRivalCiv != null &&
                    currentPrimaryRival != memo.reviewPrimaryRivalCiv
            }
            "target_site_contested" -> {
                val checkpointKind = memory.campaignControl.nextCheckpointKind ?: memo.campaignControl.nextCheckpointKind
                val mode = memory.lastStrategistMemo.decisionFrame.decisionMode ?: memo.decisionFrame.decisionMode ?: memo.campaignStage
                val expansionLike = isExpansionObjective(checkpointKind) || campaignModeFamily(mode) == "expand"
                expansionLike &&
                    currentVisibleRivalCities > memo.reviewVisibleRivalCities &&
                    observation.empireSummary.cityCount <= memo.reviewCityCount
            }
            "action_surface_mismatch" -> latestMismatch.isNotEmpty()
            "supply_collapsing" -> memory.campaignControl.supplyHealth.equals("collapsing", ignoreCase = true)
            else -> false
        }
    }

    private fun defaultReviewTriggerReason(kind: String, metric: String): String {
        val humanMetric = humanizeReviewMetric(metric)
        return when (kind) {
            "milestone_reached" -> "The strategist milestone was reached: $humanMetric."
            "assumption_broken" -> "A strategist assumption broke: $humanMetric."
            "contract_broken" -> "The strategist contract no longer matches execution reality: $humanMetric."
            else -> "The strategist review trigger fired: $humanMetric."
        }
    }

    private fun humanizeReviewMetric(metric: String): String {
        return (normalizeStrategistReviewMetric(metric) ?: metric)
            .trim()
            .replace('_', ' ')
            .ifBlank { "the current phase boundary" }
    }

    private fun normalizeStrategistReviewMetric(metric: String?): String? {
        val normalized = metric
            ?.trim()
            ?.lowercase()
            ?.replace(' ', '_')
            ?.takeUnless { it.isEmpty() }
            ?: return null
        return when (normalized) {
            "capital_founded", "found_capital", "first_city_founded" -> "city_founded"
            "capital_captured", "capital_taken" -> "capture_capital"
            "war_started" -> "war_declared"
            "enemy_city_visible", "city_visible" -> "rival_city_visible"
            "enemy_capital_visible", "capital_visible" -> "rival_capital_visible"
            "site_contested", "target_site_claimed", "settle_site_contested" -> "target_site_contested"
            else -> normalized
        }
    }

    private fun strategistReviewAnchorTurn(
        memo: AgentStrategistMemoMemory,
        fallbackTurn: Int,
    ): Int {
        if (!hasStrategistMemo(memo)) return fallbackTurn
        return when {
            memo.lastReviewedTurn > 0 -> memo.lastReviewedTurn
            memo.createdTurn > 0 -> memo.createdTurn
            else -> 0
        }
    }

    private fun looksWarAware(memory: AgentMemory): Boolean {
        val haystack = listOfNotNull(
            memory.campaign.stage,
            memory.campaign.summary,
            memory.campaign.decisiveObjective,
            memory.campaign.conversionBlocker,
            memory.campaign.reinforcementPlan,
            memory.lastStrategistMemo.futurePlan,
            memory.lastStrategistMemo.tacticianHandoff,
        ).joinToString(" ").lowercase()
        return listOf("war", "assault", "siege", "march", "front", "capture", "rebuild", "pressure").any { it in haystack }
    }

    private fun prepareNotebookForTurn(
        memory: AgentMemory,
        observation: AgentObservation,
        empireObservation: AgentEmpireObservation,
        turn: Int,
    ): AgentMemory {
        return memory.copy(
            worldModel = refreshWorldModelAnchors(pruneWorldModel(memory.worldModel, turn), observation, turn),
            campaign = pruneCampaign(memory.campaign, empireObservation, turn),
            empirePlan = pruneEmpirePlan(memory.empirePlan, turn),
            campaignControl = pruneCampaignControl(memory.campaignControl, turn),
            recentChanges = pruneNotes(memory.recentChanges, turn, maxRecentChanges),
            lessons = ArrayList(memory.lessons.takeLast(maxLessons).map { it.copy() }),
            lastStrategistMemo = memory.lastStrategistMemo.copy(
                campaignControl = memory.lastStrategistMemo.campaignControl.copy(),
                reviewCityNames = ArrayList(memory.lastStrategistMemo.reviewCityNames),
            ),
            tacticianTurnLog = pruneTacticianTurnLog(memory.tacticianTurnLog, turn),
            cityIntents = ArrayList(memory.cityIntents.map { it.copy(reasons = ArrayList(it.reasons)) }),
            unitAssignments = ArrayList(memory.unitAssignments.map { it.copy() }),
            recentFailures = ArrayList(memory.recentFailures.map { it.copy() }),
        )
    }

    private fun reconcileLiveUnitAssignments(
        assignments: List<UnitAssignmentMemory>,
        liveUnits: Collection<MapUnit>,
        turn: Int,
    ): ArrayList<UnitAssignmentMemory> {
        val unitsById = liveUnits.associateBy { it.id }
        val reconciled = linkedMapOf<Int, UnitAssignmentMemory>()

        for (assignment in assignments) {
            val unit = unitsById[assignment.unitId] ?: continue
            if (!isCanonicalUnitAssignmentRole(assignment.role)) continue
            if (assignment.role == "auto_explore" && !unit.isExploring()) continue
            reconciled[assignment.unitId] = if (assignment.role == "auto_explore") {
                assignment.copy(
                    unitName = unit.name,
                    targetX = unit.getTile().position.x,
                    targetY = unit.getTile().position.y,
                    detail = assignment.detail ?: "Auto-explore active",
                    assignmentSource = normalizeCarriedAssignmentSource(assignment.assignmentSource),
                    assignmentCategory = defaultUnitAssignmentCategory("auto_explore"),
                    executionMode = "engine_auto",
                    completionPolicy = "until_switched",
                    lastProgressTurn = turn,
                    staleAfterTurn = turn + unitAssignmentHorizonTurns,
                )
            } else {
                assignment.copy(
                    unitName = unit.name,
                    assignmentSource = normalizeCarriedAssignmentSource(assignment.assignmentSource),
                    assignmentCategory = defaultUnitAssignmentCategory(assignment.role),
                    executionMode = defaultUnitAssignmentExecutionMode(assignment.role),
                    completionPolicy = defaultUnitAssignmentCompletionPolicy(assignment.role),
                )
            }
        }

        for (unit in liveUnits) {
            if (!unit.isExploring()) continue
            val existing = reconciled[unit.id]
            if (existing != null && existing.role != "auto_explore") continue
            reconciled[unit.id] = buildUnitAssignmentMemory(
                unitId = unit.id,
                unitName = unit.name,
                role = "auto_explore",
                targetX = unit.getTile().position.x,
                targetY = unit.getTile().position.y,
                detail = "Auto-explore active",
                turn = turn,
                assignmentSource = "auto_filled",
            )
        }

        return ArrayList(reconciled.values)
    }

    private fun ensureUnitAssignmentsCoverAllUnits(
        civInfo: Civilization,
        observation: AgentObservation,
        memory: AgentMemory,
        turn: Int,
    ): AgentMemory {
        val liveUnits = civInfo.units.getCivUnits().toList().sortedBy { it.id }
        if (liveUnits.isEmpty()) return memory

        val assignmentsByUnitId = linkedMapOf<Int, UnitAssignmentMemory>()
        for (assignment in reconcileLiveUnitAssignments(memory.unitAssignments, liveUnits, turn)) {
            assignmentsByUnitId[assignment.unitId] = assignment
        }

        val unitOptionContext = AgentUnitOptionBuilder.build(civInfo, memory)
        val unitById = observation.units.associateBy { it.id }
        val candidateObservations = unitOptionContext.observationsByUnitId
            .values
            .flatten()
            .associateBy { it.candidateId }

        for (unit in liveUnits) {
            if (assignmentsByUnitId.containsKey(unit.id)) continue
            val autoAssignment = deriveAutoFilledUnitAssignment(
                unit = unit,
                unitObservation = unitById[unit.id],
                candidateObservations = unitOptionContext.observationsByUnitId[unit.id].orEmpty(),
                unitById = unitById,
                allCandidateObservations = candidateObservations,
                turn = turn,
            ) ?: buildFallbackHoldAssignment(unit, turn)
            assignmentsByUnitId[unit.id] = autoAssignment
        }

        return memory.copy(unitAssignments = ArrayList(assignmentsByUnitId.values))
    }

    private fun deriveAutoFilledUnitAssignment(
        unit: MapUnit,
        unitObservation: AgentUnitObservation?,
        candidateObservations: List<UnitOptionCandidateObservation>,
        unitById: Map<Int, AgentUnitObservation>,
        allCandidateObservations: Map<String, UnitOptionCandidateObservation>,
        turn: Int,
    ): UnitAssignmentMemory? {
        val candidateId = selectAutoFilledUnitCandidateId(unit, candidateObservations)
            ?: return null
        return deriveUnitOptionAssignment(candidateId, unitById, allCandidateObservations, turn)
            ?.copy(assignmentSource = "auto_filled")
    }

    private fun selectAutoFilledUnitCandidateId(
        unit: MapUnit,
        candidateObservations: List<UnitOptionCandidateObservation>,
    ): String? {
        fun firstMatching(prefix: String): String? =
            candidateObservations.firstOrNull { it.candidateId.startsWith(prefix) }?.candidateId

        return when {
            unit.name == "Scout" ->
                firstMatching("unitautoexplore:${unit.id}") ?: firstMatching("unithold:${unit.id}:")
            unit.name == "Settler" ->
                firstMatching("unitsettle:${unit.id}:") ?: firstMatching("unithold:${unit.id}:")
            unit.name == "Worker" ->
                firstMatching("unitworkerimprove:${unit.id}:") ?:
                    firstMatching("unitworkerreposition:${unit.id}:") ?:
                    firstMatching("unithold:${unit.id}:")
            isOperationalAutoFillCombatUnit(unit) ->
                firstMatching("unitattackcity:${unit.id}:") ?:
                    firstMatching("unitstagecity:${unit.id}:") ?:
                    firstMatching("unithold:${unit.id}:")
            else ->
                firstMatching("unithold:${unit.id}:")
        }
    }

    private fun buildFallbackHoldAssignment(
        unit: MapUnit,
        turn: Int,
    ): UnitAssignmentMemory {
        return buildUnitAssignmentMemory(
            unitId = unit.id,
            unitName = unit.name,
            role = "hold_position",
            targetX = unit.getTile().position.x,
            targetY = unit.getTile().position.y,
            detail = "Auto-filled hold assignment because no stronger unit job was available.",
            turn = turn,
            assignmentSource = "auto_filled",
        )
    }

    private fun normalizeCarriedAssignmentSource(source: String): String = when (source) {
        "auto_filled" -> "auto_filled"
        "carried_forward" -> "carried_forward"
        else -> "carried_forward"
    }

    private fun isOperationalAutoFillCombatUnit(unit: MapUnit): Boolean {
        if (!unit.isMilitary()) return false
        if (unit.baseUnit.movesLikeAirUnits || unit.baseUnit.isWaterUnit) return false
        if (unit.name == "Scout") return false
        return unit.baseUnit.isMelee() || unit.baseUnit.isRanged()
    }

    private data class CampaignControlSnapshot(
        val cityCount: Int,
        val militaryUnitCount: Int,
        val unitSupply: Int,
        val unitSupplyDeficit: Int,
        val unitSupplyProductionPenaltyPercent: Int,
        val settlerUnits: Int,
        val settlersReady: Int,
        val isAtWar: Boolean,
        val contactComplete: Boolean,
        val gold: Int,
        val happiness: Int,
        val sciencePerTurn: Int,
        val visibleRivalCities: Int,
        val visibleRivalUnits: Int,
        val objectiveVisible: Boolean,
        val objectiveIsCapital: Boolean,
        val frontlineCombatUnits: Int,
        val healthyCaptureUnitsNearObjective: Int,
        val rangedSupportUnitsNearObjective: Int,
        val primaryThreatScoreDelta: Int? = null,
        val primaryThreatForceDelta: Int? = null,
        val primaryThreatTechnologyDelta: Int? = null,
    )

    private val campaignCombatRoles = setOf("melee", "ranged", "siege", "mounted", "armored", "naval_melee", "naval_ranged")
    private val campaignCaptureRoles = setOf("melee", "mounted", "armored", "naval_melee")
    private val campaignRangedRoles = setOf("ranged", "siege", "naval_ranged")

    private fun buildCampaignControlSnapshot(
        observation: AgentObservation,
        empireObservation: AgentEmpireObservation,
        memo: AgentStrategistMemoMemory,
    ): CampaignControlSnapshot {
        val objectiveTarget = selectCampaignControlObjectiveTarget(memo, observation.visibleThreatsAndTargets)
        val frontlineUnits = objectiveTarget?.let { target ->
            observation.units.filter { unit ->
                unit.role in campaignCombatRoles &&
                    axialDistance(unit.x, unit.y, target.x, target.y) <= 4
            }.toList()
        }.orEmpty()
        val primaryThreat = empireObservation.victoryThreats.firstOrNull {
            memo.reviewPrimaryRivalCiv != null && it.civName == memo.reviewPrimaryRivalCiv
        } ?: empireObservation.victoryThreats.firstOrNull()
        val objectiveText = listOfNotNull(memo.decisiveObjective, memo.futurePlan, memo.tacticianHandoff)
            .joinToString(" ")
            .lowercase()
        return CampaignControlSnapshot(
            cityCount = observation.empireSummary.cityCount,
            militaryUnitCount = observation.empireSummary.militaryUnitCount,
            unitSupply = observation.empireSummary.unitSupply,
            unitSupplyDeficit = observation.empireSummary.unitSupplyDeficit,
            unitSupplyProductionPenaltyPercent = observation.empireSummary.unitSupplyProductionPenaltyPercent,
            settlerUnits = observation.units.count { it.role == "settler" },
            settlersReady = observation.empireSummary.settlersReady,
            isAtWar = observation.empireSummary.isAtWar,
            contactComplete = empireObservation.gameContext.contactComplete,
            gold = observation.empireSummary.gold,
            happiness = observation.empireSummary.happiness,
            sciencePerTurn = observation.empireSummary.sciencePerTurn,
            visibleRivalCities = observation.visibleThreatsAndTargets.count { it.kind == "city" && it.civName != observation.civName },
            visibleRivalUnits = observation.visibleThreatsAndTargets.count { it.kind == "unit" && it.civName != observation.civName },
            objectiveVisible = objectiveTarget != null,
            objectiveIsCapital = objectiveTarget?.facts?.any { it.equals("Capital", ignoreCase = true) } == true ||
                objectiveText.contains("capital"),
            frontlineCombatUnits = frontlineUnits.size,
            healthyCaptureUnitsNearObjective = frontlineUnits.count {
                it.role in campaignCaptureRoles &&
                    it.health >= 70 &&
                    objectiveTarget != null &&
                    axialDistance(it.x, it.y, objectiveTarget.x, objectiveTarget.y) <= 2
            },
            rangedSupportUnitsNearObjective = frontlineUnits.count {
                it.role in campaignRangedRoles && it.health >= 60
            },
            primaryThreatScoreDelta = primaryThreat?.scoreDeltaVsUs,
            primaryThreatForceDelta = primaryThreat?.forceDeltaVsUs,
            primaryThreatTechnologyDelta = primaryThreat?.technologyDeltaVsUs,
        )
    }

    private fun buildCampaignControlSnapshot(
        civInfo: Civilization,
        startingObservation: AgentObservation,
        empireObservation: AgentEmpireObservation,
        memo: AgentStrategistMemoMemory,
    ): CampaignControlSnapshot {
        val objectiveTarget = selectCampaignControlObjectiveTarget(memo, startingObservation.visibleThreatsAndTargets)
        val frontlineUnits = objectiveTarget?.let { target ->
            civInfo.units.getCivUnits().filter { unit ->
                val role = classifyCampaignUnitRole(unit)
                role in campaignCombatRoles &&
                    axialDistance(unit.getTile().position.x, unit.getTile().position.y, target.x, target.y) <= 4
            }.toList()
        }.orEmpty()
        val primaryThreat = empireObservation.victoryThreats.firstOrNull {
            memo.reviewPrimaryRivalCiv != null && it.civName == memo.reviewPrimaryRivalCiv
        } ?: empireObservation.victoryThreats.firstOrNull()
        val objectiveText = listOfNotNull(memo.decisiveObjective, memo.futurePlan, memo.tacticianHandoff)
            .joinToString(" ")
            .lowercase()
        return CampaignControlSnapshot(
            cityCount = civInfo.cities.size,
            militaryUnitCount = civInfo.units.getCivUnits().count { it.isMilitary() },
            unitSupply = civInfo.stats.getUnitSupply(),
            unitSupplyDeficit = civInfo.stats.getUnitSupplyDeficit(),
            unitSupplyProductionPenaltyPercent = (-civInfo.stats.getUnitSupplyProductionPenalty()).toInt(),
            settlerUnits = civInfo.units.getCivUnits().count { classifyCampaignUnitRole(it) == "settler" },
            settlersReady = civInfo.units.getCivUnits().count { classifyCampaignUnitRole(it) == "settler" && it.currentMovement > 0f },
            isAtWar = civInfo.isAtWar(),
            contactComplete = civInfo.getKnownCivs().any { !it.isBarbarian && !it.isCityState },
            gold = civInfo.gold,
            happiness = civInfo.getHappiness(),
            sciencePerTurn = civInfo.stats.statsForNextTurn.science.toInt(),
            visibleRivalCities = startingObservation.visibleThreatsAndTargets.count { it.kind == "city" && it.civName != startingObservation.civName },
            visibleRivalUnits = startingObservation.visibleThreatsAndTargets.count { it.kind == "unit" && it.civName != startingObservation.civName },
            objectiveVisible = objectiveTarget != null,
            objectiveIsCapital = objectiveTarget?.facts?.any { it.equals("Capital", ignoreCase = true) } == true ||
                objectiveText.contains("capital"),
            frontlineCombatUnits = frontlineUnits.size,
            healthyCaptureUnitsNearObjective = frontlineUnits.count { unit ->
                val role = classifyCampaignUnitRole(unit)
                role in campaignCaptureRoles &&
                    unit.health >= 70 &&
                    objectiveTarget != null &&
                    axialDistance(unit.getTile().position.x, unit.getTile().position.y, objectiveTarget.x, objectiveTarget.y) <= 2
            },
            rangedSupportUnitsNearObjective = frontlineUnits.count { unit ->
                classifyCampaignUnitRole(unit) in campaignRangedRoles &&
                    unit.health >= 60
            },
            primaryThreatScoreDelta = primaryThreat?.scoreDeltaVsUs,
            primaryThreatForceDelta = primaryThreat?.forceDeltaVsUs,
            primaryThreatTechnologyDelta = primaryThreat?.technologyDeltaVsUs,
        )
    }

    private fun reconcileCampaignControl(
        current: AgentCampaignControlMemory,
        memo: AgentStrategistMemoMemory,
        snapshot: CampaignControlSnapshot,
        turn: Int,
        latestCommitmentLevel: String?,
        latestBattleReadiness: String?,
        latestSupplyHealth: String?,
    ): AgentCampaignControlMemory {
        if (!hasStrategistMemo(memo)) return pruneCampaignControl(current.copy(lastUpdatedTurn = turn), turn)

        val labels = normalizeCampaignControlLabels(memo.campaignControl)
        val objectiveKind = deriveCampaignObjectiveKind(memo, labels, current)
        val checkpointKind = labels.nextCheckpointKind ?: current.nextCheckpointKind ?: objectiveKind
        val checkpointSummary = labels.nextCheckpointSummary
            ?: memo.decisionFrame.nextCheckpoint
            ?: memo.decisiveObjective
            ?: memo.futurePlan
            ?: current.nextCheckpointSummary
        val checkpointHorizonTurns = labels.checkpointHorizonTurns
            ?: current.checkpointHorizonTurns
            ?: defaultCampaignCheckpointHorizon(checkpointKind)
        val commitmentStartedTurn = if (!current.isEmpty()) current.commitmentStartedTurn else if (hasStrategistMemo(memo)) memo.createdTurn else turn
        val commitmentAgeTurns = (turn - commitmentStartedTurn).coerceAtLeast(0)
        val battleReadiness = deriveBattleReadiness(
            latestLabel = latestBattleReadiness,
            memoLabel = labels.battleReadiness,
            objectiveKind = objectiveKind,
            snapshot = snapshot,
        )
        val supplyHealth = deriveSupplyHealth(
            latestLabel = latestSupplyHealth,
            memoLabel = labels.supplyHealth,
            snapshot = snapshot,
        )
        val launchWindowOpen = deriveLaunchWindowOpen(
            objectiveKind = objectiveKind,
            battleReadiness = battleReadiness,
            supplyHealth = supplyHealth,
            snapshot = snapshot,
        )
        val commitmentLevel = deriveCommitmentLevel(
            latestLabel = latestCommitmentLevel,
            memoLabel = labels.commitmentLevel,
            objectiveKind = objectiveKind,
            launchWindowOpen = launchWindowOpen,
            snapshot = snapshot,
            commitmentAgeTurns = commitmentAgeTurns,
        )
        val checkpointAchieved = isCheckpointAchieved(
            checkpointKind = checkpointKind,
            snapshot = snapshot,
            memo = memo,
        )
        val checkpointStatus = when {
            checkpointKind.isNullOrBlank() -> ""
            checkpointAchieved -> "completed"
            launchWindowOpen && commitmentAgeTurns >= checkpointHorizonTurns -> "holding"
            commitmentAgeTurns >= checkpointHorizonTurns -> "missed"
            commitmentLevel == "probing" -> "open"
            else -> "building"
        }
        val holdingCosts = deriveCampaignHoldingCosts(
            objectiveKind = objectiveKind,
            snapshot = snapshot,
            battleReadiness = battleReadiness,
            supplyHealth = supplyHealth,
            checkpointStatus = checkpointStatus,
            launchWindowOpen = launchWindowOpen,
            commitmentAgeTurns = commitmentAgeTurns,
            checkpointHorizonTurns = checkpointHorizonTurns,
        )
        val pivotTriggers = deriveCampaignPivotTriggers(
            objectiveKind = objectiveKind,
            snapshot = snapshot,
            battleReadiness = battleReadiness,
            supplyHealth = supplyHealth,
            checkpointStatus = checkpointStatus,
            launchWindowOpen = launchWindowOpen,
            commitmentAgeTurns = commitmentAgeTurns,
            checkpointHorizonTurns = checkpointHorizonTurns,
        )
        val pivotTriggerKind = labels.pivotTriggerKind ?: when {
            supplyHealth == "collapsing" -> "supply_collapse"
            checkpointStatus == "missed" -> "missed_checkpoint"
            launchWindowOpen && pivotTriggers.isNotEmpty() -> "stalled_launch"
            pivotTriggers.isNotEmpty() -> "campaign_drift"
            else -> null
        }

        return pruneCampaignControl(
            AgentCampaignControlMemory(
                commitmentLevel = commitmentLevel,
                battleReadiness = battleReadiness,
                supplyHealth = supplyHealth,
                nextCheckpointKind = checkpointKind,
                nextCheckpointSummary = checkpointSummary,
                checkpointHorizonTurns = checkpointHorizonTurns,
                pivotTriggerKind = pivotTriggerKind,
                checkpointStatus = checkpointStatus,
                commitmentStartedTurn = commitmentStartedTurn,
                lastCheckpointTurn = if (checkpointAchieved) turn else current.lastCheckpointTurn.takeIf { it > 0 } ?: commitmentStartedTurn,
                launchWindowOpen = launchWindowOpen,
                holdingCosts = ArrayList(holdingCosts),
                pivotTriggers = ArrayList(pivotTriggers),
                lastUpdatedTurn = turn,
            ),
            turn,
        )
    }

    private fun deriveCommitmentLevel(
        latestLabel: String?,
        memoLabel: String?,
        objectiveKind: String?,
        launchWindowOpen: Boolean,
        snapshot: CampaignControlSnapshot,
        commitmentAgeTurns: Int,
    ): String {
        normalizeControlLabel(latestLabel)?.let { return it }
        normalizeControlLabel(memoLabel)?.let { return it }
        if (snapshot.isAtWar) return "committed"
        if (launchWindowOpen && commitmentAgeTurns >= 1) return "committed"
        if (isExpansionObjective(objectiveKind) || isWarLikeObjective(objectiveKind)) return "building"
        return "probing"
    }

    private fun deriveBattleReadiness(
        latestLabel: String?,
        memoLabel: String?,
        objectiveKind: String?,
        snapshot: CampaignControlSnapshot,
    ): String {
        normalizeControlLabel(latestLabel)?.let { return it }
        normalizeControlLabel(memoLabel)?.let { return it }
        return when {
            isExpansionObjective(objectiveKind) && snapshot.settlersReady > 0 -> "ready"
            isExpansionObjective(objectiveKind) && snapshot.settlerUnits > 0 -> "nearly_ready"
            isExpansionObjective(objectiveKind) -> "not_ready"
            isWarLikeObjective(objectiveKind) && snapshot.isAtWar && snapshot.frontlineCombatUnits >= 2 -> "engaged"
            isWarLikeObjective(objectiveKind) &&
                snapshot.objectiveVisible &&
                snapshot.healthyCaptureUnitsNearObjective >= 1 &&
                snapshot.rangedSupportUnitsNearObjective >= 1 -> "ready"
            isWarLikeObjective(objectiveKind) &&
                (snapshot.frontlineCombatUnits >= 2 || snapshot.militaryUnitCount >= 4) -> "nearly_ready"
            isWarLikeObjective(objectiveKind) -> "not_ready"
            else -> "forming"
        }
    }

    private fun deriveSupplyHealth(
        latestLabel: String?,
        memoLabel: String?,
        snapshot: CampaignControlSnapshot,
    ): String {
        normalizeControlLabel(latestLabel)?.let { return it }
        normalizeControlLabel(memoLabel)?.let { return it }
        return when {
            snapshot.unitSupplyDeficit >= 3 || snapshot.unitSupplyProductionPenaltyPercent >= 30 -> "collapsing"
            snapshot.unitSupplyDeficit > 0 || snapshot.unitSupplyProductionPenaltyPercent > 0 -> "fragile"
            snapshot.gold <= -75 || snapshot.happiness <= -5 -> "collapsing"
            snapshot.gold < 0 || snapshot.happiness < 0 -> "fragile"
            snapshot.cityCount <= 1 && snapshot.militaryUnitCount >= 8 -> "strained"
            snapshot.gold <= 40 && snapshot.militaryUnitCount >= maxOf(6, snapshot.cityCount * 3) -> "strained"
            snapshot.sciencePerTurn <= 0 && snapshot.militaryUnitCount >= maxOf(6, snapshot.cityCount * 3) -> "fragile"
            else -> "healthy"
        }
    }

    private fun deriveLaunchWindowOpen(
        objectiveKind: String?,
        battleReadiness: String,
        supplyHealth: String,
        snapshot: CampaignControlSnapshot,
    ): Boolean {
        if (supplyHealth == "collapsing") return false
        return when {
            isExpansionObjective(objectiveKind) -> snapshot.settlersReady > 0 || battleReadiness == "ready"
            isWarLikeObjective(objectiveKind) -> snapshot.objectiveVisible && battleReadiness in setOf("ready", "engaged")
            else -> battleReadiness == "ready"
        }
    }

    private fun isCheckpointAchieved(
        checkpointKind: String?,
        snapshot: CampaignControlSnapshot,
        memo: AgentStrategistMemoMemory,
    ): Boolean {
        return when (checkpointKind) {
            "city_founded", "city_captured" -> snapshot.cityCount > memo.reviewCityCount
            "second_city_founded" -> snapshot.cityCount >= 2
            "war_declared" -> snapshot.isAtWar
            "contact_made" -> snapshot.contactComplete
            "capture_capital" -> snapshot.cityCount > memo.reviewCityCount
            else -> false
        }
    }

    private fun deriveCampaignHoldingCosts(
        objectiveKind: String?,
        snapshot: CampaignControlSnapshot,
        battleReadiness: String,
        supplyHealth: String,
        checkpointStatus: String,
        launchWindowOpen: Boolean,
        commitmentAgeTurns: Int,
        checkpointHorizonTurns: Int,
    ): List<String> {
        val costs = linkedSetOf<String>()
        if (isExpansionObjective(objectiveKind) && snapshot.settlersReady > 0 && checkpointStatus != "completed") {
            costs += "A ready Settler is waiting while the next city is still unresolved."
        }
        if (isWarLikeObjective(objectiveKind) && launchWindowOpen && !snapshot.isAtWar && commitmentAgeTurns >= checkpointHorizonTurns) {
            costs += "The battle package looks launchable, but the campaign is still waiting instead of converting."
        }
        if (snapshot.cityCount <= 1 && snapshot.militaryUnitCount >= 8) {
            costs += "A one-city empire is carrying a campaign-sized military package."
        }
        if (supplyHealth in setOf("strained", "fragile", "collapsing")) {
            costs += "Campaign sustainment is under pressure while this line remains unresolved."
        }
        if (battleReadiness == "ready" && snapshot.primaryThreatScoreDelta != null && snapshot.primaryThreatScoreDelta > 0 && commitmentAgeTurns >= checkpointHorizonTurns) {
            costs += "The rival score lead is growing while a ready campaign still has not converted."
        }
        return costs.take(4)
    }

    private fun deriveCampaignPivotTriggers(
        objectiveKind: String?,
        snapshot: CampaignControlSnapshot,
        battleReadiness: String,
        supplyHealth: String,
        checkpointStatus: String,
        launchWindowOpen: Boolean,
        commitmentAgeTurns: Int,
        checkpointHorizonTurns: Int,
    ): List<String> {
        val triggers = linkedSetOf<String>()
        if (checkpointStatus == "missed") {
            triggers += "The campaign missed its checkpoint and should either convert now or be rewritten."
        }
        if (supplyHealth == "collapsing") {
            triggers += "Campaign supply is collapsing faster than the current line is paying back."
        }
        if (launchWindowOpen && isWarLikeObjective(objectiveKind) && !snapshot.isAtWar && commitmentAgeTurns >= checkpointHorizonTurns) {
            triggers += "The war line looks ready enough, but the empire is still delaying the launch."
        }
        if (battleReadiness == "not_ready" && snapshot.militaryUnitCount >= 8 && supplyHealth in setOf("fragile", "collapsing")) {
            triggers += "The empire is paying for buildup that is still not actually ready to convert."
        }
        if (snapshot.primaryThreatTechnologyDelta != null && snapshot.primaryThreatTechnologyDelta > 0 && commitmentAgeTurns >= checkpointHorizonTurns) {
            triggers += "The rival tech edge is improving while this campaign remains unresolved."
        }
        return triggers.take(4)
    }

    private fun defaultCampaignCheckpointHorizon(checkpointKind: String?): Int {
        return when (checkpointKind) {
            "contact_made", "city_founded", "second_city_founded" -> 4
            "war_declared", "city_captured", "capture_capital" -> 5
            else -> 5
        }
    }

    private fun normalizeControlLabel(raw: String?): String? {
        return raw?.trim()
            ?.lowercase()
            ?.replace(' ', '_')
            ?.takeUnless { it.isEmpty() }
    }

    private fun selectCampaignControlObjectiveTarget(
        memo: AgentStrategistMemoMemory,
        visibleTargets: List<VisibleTargetObservation>,
    ): VisibleTargetObservation? {
        val visibleCities = visibleTargets.filter { it.kind == "city" }
        if (visibleCities.isEmpty()) return null
        val objectiveText = listOfNotNull(memo.decisiveObjective, memo.futurePlan, memo.tacticianHandoff)
            .joinToString(" ")
            .lowercase()
        val primaryRival = memo.reviewPrimaryRivalCiv?.lowercase()
        visibleCities.firstOrNull { target -> objectiveText.contains(target.name.lowercase()) }?.let { return it }
        visibleCities.firstOrNull {
            primaryRival != null &&
                it.civName.lowercase() == primaryRival &&
                it.facts.any { fact -> fact.equals("Capital", ignoreCase = true) }
        }?.let { return it }
        visibleCities.firstOrNull {
            primaryRival != null && it.civName.lowercase() == primaryRival
        }?.let { return it }
        visibleCities.firstOrNull { target ->
            target.facts.any { it.equals("Capital", ignoreCase = true) } && objectiveText.contains("capital")
        }?.let { return it }
        return visibleCities.minByOrNull { it.distanceToClosestUnit ?: Int.MAX_VALUE }
    }

    private fun axialDistance(ax: Int, ay: Int, bx: Int, by: Int): Int {
        val dx = ax - bx
        val dy = ay - by
        val dz = -dx - dy
        return maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy), kotlin.math.abs(dz))
    }

    private fun classifyCampaignUnitRole(unit: MapUnit): String {
        return when {
            unit.baseUnit.isCityFounder() -> "settler"
            unit.cache.hasUniqueToBuildImprovements -> "worker"
            unit.isGreatPerson() -> "great_person"
            unit.baseUnit.isAirUnit() -> "air"
            unit.baseUnit.isWaterUnit && unit.baseUnit.isRanged() -> "naval_ranged"
            unit.baseUnit.isWaterUnit -> "naval_melee"
            unit.baseUnit.isProbablySiegeUnit() -> "siege"
            unit.baseUnit.isRanged() -> "ranged"
            unit.isCivilian() -> "civilian"
            else -> "melee"
        }
    }

    private fun isWarLikeObjective(objectiveKind: String?): Boolean {
        return objectiveKind in setOf("declare_war", "capture_city", "capture_capital", "assault", "siege")
    }

    private fun isExpansionObjective(objectiveKind: String?): Boolean {
        return objectiveKind in setOf("expand", "settle_city", "found_city", "second_city_founded")
    }

    private fun deriveCampaignObjectiveKind(
        memo: AgentStrategistMemoMemory,
        labels: AgentCampaignControlLabels,
        current: AgentCampaignControlMemory,
    ): String? {
        labels.nextCheckpointKind?.let { return it }
        current.nextCheckpointKind?.let { return it }
        return when (campaignModeFamily(memo.decisionFrame.decisionMode ?: memo.campaignStage)) {
            "expand" -> "found_city"
            "war" -> "declare_war"
            else -> null
        }
    }

    private fun normalizeMemoValidity(raw: String?): String? {
        return raw?.trim()?.lowercase()?.takeUnless { it.isEmpty() }
    }

    private fun pruneStrategistTriggerLedger(
        entries: List<StrategistTriggerLedgerEntry>,
        turn: Int,
    ): ArrayList<StrategistTriggerLedgerEntry> {
        return ArrayList(
            entries
                .filter { it.firedTurn <= turn }
                .takeLast(maxStrategistTriggerLedgerEntries)
                .map { it.copy() }
        )
    }

    private fun recordStrategistTriggerLedger(
        existing: List<StrategistTriggerLedgerEntry>,
        previousMemo: AgentStrategistMemoMemory,
        refreshRequest: AgentStrategistRefreshRequest,
        turn: Int,
    ): ArrayList<StrategistTriggerLedgerEntry> {
        val ledger = pruneStrategistTriggerLedger(existing, turn)
        if (refreshRequest.source != "review_contract" || !hasStrategistMemo(previousMemo)) {
            return ledger
        }
        val triggerKind = refreshRequest.triggerKind?.trim()?.lowercase()?.takeUnless { it.isEmpty() } ?: return ledger
        val triggerMetric = normalizeStrategistReviewMetric(refreshRequest.triggerMetric) ?: return ledger
        val memoReviewedTurn = previousMemo.lastReviewedTurn
        val triggerWithinTurns = refreshRequest.triggerWithinTurns?.coerceAtLeast(1)
        val alreadyRecorded = ledger.any { entry ->
            entry.source == "review_contract" &&
                entry.memoReviewedTurn == memoReviewedTurn &&
                entry.triggerKind == triggerKind &&
                entry.triggerMetric == triggerMetric &&
                entry.triggerWithinTurns == triggerWithinTurns
        }
        if (!alreadyRecorded) {
            ledger += StrategistTriggerLedgerEntry(
                source = "review_contract",
                memoReviewedTurn = memoReviewedTurn,
                triggerKind = triggerKind,
                triggerMetric = triggerMetric,
                triggerWithinTurns = triggerWithinTurns,
                firedTurn = turn,
            )
        }
        return ArrayList(ledger.takeLast(maxStrategistTriggerLedgerEntries))
    }

    private fun hasConsumedStrategistReviewTrigger(
        memory: AgentMemory,
        memo: AgentStrategistMemoMemory,
        trigger: AgentStrategistReviewTrigger,
    ): Boolean {
        if (!hasStrategistMemo(memo)) return false
        val normalizedMetric = normalizeStrategistReviewMetric(trigger.metric) ?: return false
        val triggerKind = trigger.kind.trim().lowercase().takeUnless { it.isEmpty() } ?: return false
        val memoReviewedTurn = memo.lastReviewedTurn
        val triggerWithinTurns = trigger.withinTurns?.coerceAtLeast(1)
        return memory.strategistTriggerLedger.any { entry ->
            entry.source == "review_contract" &&
                entry.memoReviewedTurn == memoReviewedTurn &&
                entry.triggerKind == triggerKind &&
                entry.triggerMetric == normalizedMetric &&
                entry.triggerWithinTurns == triggerWithinTurns
        }
    }

    private fun pruneTacticianTurnLog(
        entries: List<TacticianTurnLogEntry>,
        turn: Int,
    ): ArrayList<TacticianTurnLogEntry> {
        return ArrayList(
            entries
                .filter { it.turn <= turn }
                .takeLast(maxTacticianTurnLogEntries)
                .map { entry ->
                entry.copy(
                    whatChanged = ArrayList(entry.whatChanged),
                    completed = ArrayList(entry.completed),
                    stillBlocked = ArrayList(entry.stillBlocked),
                    obsolete = ArrayList(entry.obsolete),
                    actionSurfaceMismatch = ArrayList(entry.actionSurfaceMismatch),
                    memoValidity = entry.memoValidity,
                    commitmentLevel = entry.commitmentLevel,
                    battleReadiness = entry.battleReadiness,
                    supplyHealth = entry.supplyHealth,
                )
            }
        )
    }

    private fun mergeTacticianTurnLog(
        existing: List<TacticianTurnLogEntry>,
        entry: TacticianTurnLogEntry?,
    ): ArrayList<TacticianTurnLogEntry> {
        val merged = ArrayList(
            existing.takeLast(maxTacticianTurnLogEntries).map { existingEntry ->
                existingEntry.copy(
                    whatChanged = ArrayList(existingEntry.whatChanged),
                    completed = ArrayList(existingEntry.completed),
                    stillBlocked = ArrayList(existingEntry.stillBlocked),
                    obsolete = ArrayList(existingEntry.obsolete),
                    actionSurfaceMismatch = ArrayList(existingEntry.actionSurfaceMismatch),
                    memoValidity = existingEntry.memoValidity,
                    commitmentLevel = existingEntry.commitmentLevel,
                    battleReadiness = existingEntry.battleReadiness,
                    supplyHealth = existingEntry.supplyHealth,
                )
            }
        )
        if (entry != null) merged += entry
        return ArrayList(merged.takeLast(maxTacticianTurnLogEntries))
    }

    private fun reconcileIntentCarryover(
        civInfo: Civilization,
        existingCityIntents: List<CityIntentMemory>,
        existingUnitAssignments: List<UnitAssignmentMemory>,
        turn: Int,
    ): IntentCarryoverReconciliation {
        val citiesByKey = civInfo.cities.associateBy { cityKey(it.location.x, it.location.y) }
        val unitsById = civInfo.units.getCivUnits().associateBy { it.id }
        val activeCityIntents = arrayListOf<CityIntentMemory>()
        val activeUnitAssignments = arrayListOf<UnitAssignmentMemory>()
        val completed = arrayListOf<String>()
        val obsolete = arrayListOf<String>()

        for (intent in existingCityIntents) {
            val liveCity = citiesByKey[cityKey(intent.cityX, intent.cityY)]
            if (liveCity == null) {
                obsolete += "${intent.cityName} no longer exists, so the old ${describeCityIntent(intent)} carry-over is obsolete."
                continue
            }

            when (intent.intent) {
                "develop_city", "invest_with_gold" -> {
                    val target = intent.target
                    val liveProject = liveCity.cityConstructions.currentConstructionName().takeIf { it.isNotBlank() }
                    if (target.isNullOrBlank()) {
                        activeCityIntents += intent.copy(staleAfterTurn = turn + cityIntentHorizonTurns)
                    } else if (liveProject == target) {
                        activeCityIntents += intent.copy(lastProgressTurn = turn, staleAfterTurn = turn + cityIntentHorizonTurns)
                    } else {
                        completed += "${intent.cityName} is no longer on $target, so that city instruction is resolved."
                    }
                }
                "city_focus" -> {
                    val targetFocus = intent.target
                    if (!targetFocus.isNullOrBlank() && liveCity.getCityFocus().name == targetFocus) {
                        activeCityIntents += intent.copy(lastProgressTurn = turn, staleAfterTurn = turn + cityIntentHorizonTurns)
                    } else {
                        obsolete += "${intent.cityName} no longer has the ${targetFocus ?: "recorded"} focus, so the old focus reminder is stale."
                    }
                }
                else -> activeCityIntents += intent.copy(staleAfterTurn = turn + cityIntentHorizonTurns)
            }
        }

        for (assignment in existingUnitAssignments) {
            val liveUnit = unitsById[assignment.unitId]
            if (liveUnit == null) {
                completed += "${assignment.unitName.ifBlank { "Unit #${assignment.unitId}" }} is gone, so its old ${describeUnitAssignment(assignment)} carry-over is resolved."
                continue
            }
            val targetReached = assignment.targetX != null &&
                assignment.targetY != null &&
                liveUnit.getTile().position.x == assignment.targetX &&
                liveUnit.getTile().position.y == assignment.targetY
            if (targetReached && assignment.completionPolicy == "until_arrival") {
                completed += "${assignment.unitName.ifBlank { "Unit #${assignment.unitId}" }} reached (${assignment.targetX}, ${assignment.targetY}), so that carry-over is complete."
                continue
            }
            if (assignment.completionPolicy == "until_recovered" && liveUnit.health >= 85) {
                completed += "${assignment.unitName.ifBlank { "Unit #${assignment.unitId}" }} is healthy again, so that fallback-and-heal carry-over is complete."
                continue
            }
            activeUnitAssignments += assignment.copy(staleAfterTurn = turn + unitAssignmentHorizonTurns)
        }

        return IntentCarryoverReconciliation(
            cityIntents = activeCityIntents,
            unitAssignments = activeUnitAssignments,
            completed = completed,
            obsolete = obsolete,
        )
    }

    private fun buildTacticianTurnLogEntry(
        civInfo: Civilization,
        observation: AgentObservation,
        empireObservation: AgentEmpireObservation,
        startingMemory: AgentMemory,
        plan: AgentActionPlan?,
        report: AgentActionExecutor.ExecutionReport?,
        usedLegacyFallback: Boolean,
        fallbackReason: String?,
        intentionalNoOp: Boolean,
        completed: List<String>,
        obsolete: List<String>,
    ): TacticianTurnLogEntry? {
        val turn = civInfo.gameInfo.turns
        val whatChanged = buildWhatChangedNotes(civInfo, observation, empireObservation, plan, report)
        val stillBlocked = summarizeCurrentBlockers(civInfo, observation, empireObservation, startingMemory)
        val reflection = plan?.tacticianReflection
        val reflectionSummary = reflection?.summary?.trim().takeUnless { it.isNullOrEmpty() }
        val planNotes = plan?.notes?.trim().takeUnless { it.isNullOrEmpty() }
        val summary = when {
            usedLegacyFallback -> fallbackReason ?: "Legacy fallback handled the turn after the tactical plan broke."
            intentionalNoOp -> "The tactician intentionally held actions this turn."
            reflectionSummary != null -> reflectionSummary.take(220)
            planNotes != null -> planNotes.take(220)
            report != null -> "Executed ${report.executedActions} tactical actions with ${report.rejectedActions} rejected."
            else -> "Turn completed without a recorded tactical delta."
        }
        val mergedWhatChanged = mergeTurnLogBullets(whatChanged, reflection?.whatChanged, 4)
        val mergedCompleted = mergeTurnLogBullets(completed, reflection?.completed, 4)
        val mergedStillBlocked = mergeTurnLogBullets(stillBlocked, reflection?.stillBlocked, 3)
        val mergedObsolete = mergeTurnLogBullets(obsolete, reflection?.obsolete, 3)
        val mergedActionSurfaceMismatch = mergeTurnLogBullets(
            emptyList(),
            reflection?.actionSurfaceMismatch,
            3,
        )
        val normalizedMemoValidity = normalizeMemoValidity(reflection?.memoValidity)
            ?: if (mergedActionSurfaceMismatch.isNotEmpty()) "strained" else null
        if (
            summary.isBlank() &&
            mergedWhatChanged.isEmpty() &&
            mergedCompleted.isEmpty() &&
            mergedObsolete.isEmpty() &&
            mergedStillBlocked.isEmpty() &&
            mergedActionSurfaceMismatch.isEmpty()
        ) {
            return null
        }
        return TacticianTurnLogEntry(
            turn = turn,
            basedOnStrategistTurn = if (hasStrategistMemo(startingMemory.lastStrategistMemo)) startingMemory.lastStrategistMemo.lastReviewedTurn else null,
            campaignStage = startingMemory.campaign.stage.takeIf { it.isNotBlank() },
            decisiveObjective = startingMemory.campaign.decisiveObjective,
            summary = summary,
            whatChanged = ArrayList(mergedWhatChanged),
            completed = ArrayList(mergedCompleted),
            stillBlocked = ArrayList(mergedStillBlocked),
            obsolete = ArrayList(mergedObsolete),
            actionSurfaceMismatch = ArrayList(mergedActionSurfaceMismatch),
            memoValidity = normalizedMemoValidity,
            commitmentLevel = reflection?.commitmentLevel?.trim()?.takeUnless { it.isNullOrEmpty() },
            battleReadiness = reflection?.battleReadiness?.trim()?.takeUnless { it.isNullOrEmpty() },
            supplyHealth = reflection?.supplyHealth?.trim()?.takeUnless { it.isNullOrEmpty() },
        )
    }

    private fun mergeTurnLogBullets(
        derived: List<String>,
        reflected: List<String>?,
        maxItems: Int,
    ): List<String> {
        val merged = linkedSetOf<String>()
        derived.mapNotNull { it.trim().takeIf(String::isNotEmpty) }.forEach(merged::add)
        reflected.orEmpty().mapNotNull { it.trim().takeIf(String::isNotEmpty) }.forEach(merged::add)
        return merged.take(maxItems)
    }

    private fun buildWhatChangedNotes(
        civInfo: Civilization,
        observation: AgentObservation,
        empireObservation: AgentEmpireObservation,
        plan: AgentActionPlan?,
        report: AgentActionExecutor.ExecutionReport?,
    ): List<String> {
        val changes = arrayListOf<String>()
        if (!observation.empireSummary.isAtWar && civInfo.isAtWar()) {
            changes += "War began this turn."
        }
        if (observation.empireSummary.isAtWar && !civInfo.isAtWar()) {
            changes += "The empire is no longer at war after this turn."
        }
        val cityDelta = civInfo.cities.size - observation.empireSummary.cityCount
        if (cityDelta > 0) {
            changes += "City count increased to ${civInfo.cities.size}."
        }
        if (cityDelta < 0) {
            changes += "City count fell to ${civInfo.cities.size}."
        }
        val currentResearch = civInfo.tech.currentTechnologyName()
        if (empireObservation.currentResearch == null && currentResearch != null) {
            changes += "Research is now set to $currentResearch."
        }
        val declaredWar = plan?.actions
            ?.filterIsInstance<AgentActionCommand.SelectEmpireOption>()
            ?.any { it.candidateId.startsWith("diplo:war:") } == true
        if (declaredWar) {
            changes += "A declare-war action was committed in the tactical plan."
        }
        if (report != null && report.rejectedActions > 0) {
            changes += "${report.rejectedActions} tactical actions were rejected during execution."
        }
        return changes
    }

    private fun summarizeCurrentBlockers(
        civInfo: Civilization,
        observation: AgentObservation,
        empireObservation: AgentEmpireObservation,
        startingMemory: AgentMemory,
    ): List<String> {
        val blockers = linkedSetOf<String>()
        val objective = startingMemory.campaign.decisiveObjective?.lowercase().orEmpty()
        val liveContactComplete = civInfo.getKnownCivs().any { !it.isBarbarian && !it.isCityState }
        if (!empireObservation.gameContext.contactComplete &&
            objective.any { it.isLetter() } &&
            (objective.contains("contact") || objective.contains("second city") || objective.contains("settler"))
        ) {
            if (!liveContactComplete) blockers += "Contact with the rival is still unresolved."
        }
        if (objective.contains("second city") && civInfo.cities.size < 2) {
            blockers += "The second city is still not secured."
        }
        val carriedBlocker = startingMemory.campaign.conversionBlocker?.trim()
        if (!carriedBlocker.isNullOrEmpty()) {
            when {
                carriedBlocker.contains("one city", ignoreCase = true) && civInfo.cities.size < 2 ->
                    blockers += "We are still on one city."
                carriedBlocker.contains("contact", ignoreCase = true) && !liveContactComplete ->
                    blockers += "The contact bottleneck is still real."
                carriedBlocker.contains("melee", ignoreCase = true) && observation.units.none { it.role == "melee" && it.health >= 70 } ->
                    blockers += "Healthy melee remain thin."
            }
        }
        return blockers.toList()
    }

    private fun describeCityIntent(intent: CityIntentMemory): String {
        val target = intent.target?.takeIf { it.isNotBlank() } ?: intent.intent
        return "city intent for $target"
    }

    private fun describeUnitAssignment(assignment: UnitAssignmentMemory): String {
        return assignment.detail?.takeIf { it.isNotBlank() } ?: assignment.role.replace('_', ' ')
    }

    private data class IntentCarryoverReconciliation(
        val cityIntents: ArrayList<CityIntentMemory>,
        val unitAssignments: ArrayList<UnitAssignmentMemory>,
        val completed: List<String>,
        val obsolete: List<String>,
    )

    private fun pruneWorldModel(worldModel: WorldModelMemory, turn: Int): WorldModelMemory {
        return worldModel.copy(
            notes = pruneNotes(worldModel.notes, turn, maxRecentChanges),
            anchors = ArrayList(worldModel.anchors.map { it.copy() }),
        )
    }

    private fun pruneCampaign(campaign: CampaignMemory, empireObservation: AgentEmpireObservation, turn: Int): CampaignMemory {
        return campaign.copy(
            primaryRivalCiv = campaign.primaryRivalCiv ?: empireObservation.victoryThreats.firstOrNull()?.civName,
            doNotDo = ArrayList(campaign.doNotDo.take(4)),
            notes = pruneNotes(campaign.notes, turn, maxRecentChanges),
        )
    }

    private fun pruneEmpirePlan(empirePlan: EmpirePlanMemory, turn: Int): EmpirePlanMemory {
        return empirePlan.copy(
            notes = pruneNotes(empirePlan.notes, turn, maxRecentChanges),
        )
    }

    private fun pruneCampaignControl(campaignControl: AgentCampaignControlMemory, turn: Int): AgentCampaignControlMemory {
        if (campaignControl.isEmpty()) return AgentCampaignControlMemory()
        return campaignControl.copy(
            holdingCosts = ArrayList(campaignControl.holdingCosts.takeLast(4)),
            pivotTriggers = ArrayList(campaignControl.pivotTriggers.takeLast(4)),
            lastUpdatedTurn = campaignControl.lastUpdatedTurn.takeIf { it > 0 } ?: turn,
        )
    }

    private fun refreshWorldModelAnchors(
        worldModel: WorldModelMemory,
        observation: AgentObservation,
        turn: Int,
    ): WorldModelMemory {
        val anchors = ArrayList(worldModel.anchors.map { it.copy() })
        observation.visibleThreatsAndTargets
            .filter { it.kind == "city" && it.civName != observation.civName }
            .forEach { target ->
                upsertAnchor(
                    anchors,
                    MemoryAnchor(
                        kind = if (target.facts.any { it.equals("Capital", ignoreCase = true) }) "capital" else "city",
                        label = target.name,
                        civName = target.civName,
                        x = target.x,
                        y = target.y,
                        firstSeenTurn = turn,
                        lastConfirmedTurn = turn,
                    )
                )
            }
        return worldModel.copy(
            anchors = ArrayList(anchors.sortedByDescending { it.lastConfirmedTurn }.take(12)),
            lastUpdatedTurn = turn,
        )
    }

    private fun buildWorldModel(
        current: WorldModelMemory,
        memo: AgentStrategistMemoDraft,
        turn: Int,
    ): WorldModelMemory {
        val summary = memo.worldModelSummary?.trim().takeUnless { it.isNullOrEmpty() } ?: current.summary
        return current.copy(
            summary = summary,
            notes = buildNoteList("world_model", "inference", memo.worldModelNotes, turn, turn + 60, maxRecentChanges),
            anchors = ArrayList(current.anchors.map { it.copy() }),
            lastUpdatedTurn = turn,
        )
    }

    private fun buildCampaignMemory(
        current: CampaignMemory,
        memo: AgentStrategistMemoDraft,
        observation: AgentObservation,
        empireObservation: AgentEmpireObservation,
        turn: Int,
    ): CampaignMemory {
        val derivedNotes = buildList {
            memo.currentSituation?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it) }
            memo.futurePlan?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it) }
        }
        return CampaignMemory(
            title = memo.campaignTitle?.trim().takeUnless { it.isNullOrEmpty() } ?: current.title,
            stage = memo.campaignStage.trim(),
            decisiveObjective = memo.decisiveObjective.trim().takeUnless { it.isEmpty() } ?: current.decisiveObjective,
            conversionBlocker = memo.conversionBlocker?.trim().takeUnless { it.isNullOrEmpty() } ?: current.conversionBlocker,
            summary = memo.campaignSummary?.trim().takeUnless { it.isNullOrEmpty() } ?: current.summary,
            reinforcementPlan = memo.reinforcementPlan?.trim().takeUnless { it.isNullOrEmpty() } ?: current.reinforcementPlan,
            primaryRivalCiv = current.primaryRivalCiv
                ?: extractPrimaryRivalCiv(memory = null, observation = observation, empireObservation = empireObservation),
            doNotDo = ArrayList(memo.campaignDoNotDo.take(4)),
            notes = buildNoteList("campaign", "implication", derivedNotes, turn, turn + 60, maxRecentChanges),
            lastUpdatedTurn = turn,
        )
    }

    private fun buildEmpirePlan(
        current: EmpirePlanMemory,
        memo: AgentStrategistMemoDraft,
        turn: Int,
    ): EmpirePlanMemory {
        return EmpirePlanMemory(
            summary = memo.empirePlanSummary?.trim().takeUnless { it.isNullOrEmpty() } ?: current.summary,
            purchaseIntent = memo.purchaseIntent?.trim().takeUnless { it.isNullOrEmpty() } ?: current.purchaseIntent,
            notes = buildNoteList("empire_plan", "implication", memo.empirePlanNotes, turn, turn + 60, maxRecentChanges),
            lastUpdatedTurn = turn,
        )
    }

    private fun buildCampaignControl(
        current: AgentCampaignControlMemory,
        previousMemo: AgentStrategistMemoMemory,
        memo: AgentStrategistMemoDraft,
        turn: Int,
    ): AgentCampaignControlMemory {
        val sameThread = isSameCampaignThread(previousMemo, memo)
        val normalizedLabels = normalizeCampaignControlLabels(memo.campaignControl)
        return AgentCampaignControlMemory(
            commitmentLevel = normalizedLabels.commitmentLevel,
            battleReadiness = normalizedLabels.battleReadiness,
            supplyHealth = normalizedLabels.supplyHealth,
            nextCheckpointKind = normalizedLabels.nextCheckpointKind,
            nextCheckpointSummary = normalizedLabels.nextCheckpointSummary,
            checkpointHorizonTurns = normalizedLabels.checkpointHorizonTurns,
            pivotTriggerKind = normalizedLabels.pivotTriggerKind,
            checkpointStatus = if (sameThread) current.checkpointStatus else "",
            commitmentStartedTurn = if (sameThread) {
                if (!current.isEmpty()) current.commitmentStartedTurn else if (hasStrategistMemo(previousMemo)) previousMemo.createdTurn else turn
            } else {
                turn
            },
            lastCheckpointTurn = if (sameThread) {
                if (!current.isEmpty()) current.lastCheckpointTurn else if (hasStrategistMemo(previousMemo)) previousMemo.createdTurn else turn
            } else {
                turn
            },
            launchWindowOpen = if (sameThread) current.launchWindowOpen else false,
            holdingCosts = if (sameThread) ArrayList(current.holdingCosts.takeLast(4)) else arrayListOf(),
            pivotTriggers = if (sameThread) ArrayList(current.pivotTriggers.takeLast(4)) else arrayListOf(),
            lastUpdatedTurn = turn,
        )
    }

    private fun normalizeStrategistReviewContract(
        contract: AgentStrategistReviewContract,
        gameContext: AgentPublicGameContextObservation,
    ): AgentStrategistReviewContract {
        val maxAgeTurns = when {
            gameContext.duelLike && gameContext.gameSpeed.equals("Quick", ignoreCase = true) ->
                contract.maxAgeTurns.coerceIn(5, 10)
            else -> contract.maxAgeTurns.coerceIn(6, 12)
        }
        val triggers = contract.triggers
            .mapNotNull { trigger ->
                val kind = trigger.kind.trim().lowercase().takeUnless { it.isEmpty() } ?: return@mapNotNull null
                val metric = normalizeStrategistReviewMetric(trigger.metric) ?: return@mapNotNull null
                AgentStrategistReviewTrigger(
                    kind = kind,
                    metric = metric,
                    summary = trigger.summary?.trim()?.takeUnless { it.isEmpty() },
                    withinTurns = trigger.withinTurns?.coerceIn(1, 12),
                )
            }
            .take(4)
        return AgentStrategistReviewContract(
            maxAgeTurns = maxAgeTurns,
            triggers = triggers,
        )
    }

    private fun normalizeStrategistDecisionFrame(frame: AgentStrategistDecisionFrame): AgentStrategistDecisionFrame {
        return AgentStrategistDecisionFrame(
            decisionMode = frame.decisionMode?.trim()?.takeUnless { it.isEmpty() },
            targetFrame = frame.targetFrame?.trim()?.takeUnless { it.isEmpty() },
            whyNow = frame.whyNow?.trim()?.takeUnless { it.isEmpty() },
            nextCheckpoint = frame.nextCheckpoint?.trim()?.takeUnless { it.isEmpty() },
            expiryCondition = frame.expiryCondition?.trim()?.takeUnless { it.isEmpty() },
        )
    }

    private fun normalizeStrategistControlLanes(lanes: AgentStrategistControlLanes): AgentStrategistControlLanes {
        return AgentStrategistControlLanes(
            buildControl = lanes.buildControl?.trim()?.takeUnless { it.isEmpty() },
            unitControl = lanes.unitControl?.trim()?.takeUnless { it.isEmpty() },
            workerControl = lanes.workerControl?.trim()?.takeUnless { it.isEmpty() },
            purchaseControl = lanes.purchaseControl?.trim()?.takeUnless { it.isEmpty() },
            techControl = lanes.techControl?.trim()?.takeUnless { it.isEmpty() },
            policyControl = lanes.policyControl?.trim()?.takeUnless { it.isEmpty() },
            driftWarnings = lanes.driftWarnings
                .mapNotNull { it.trim().takeUnless { trimmed -> trimmed.isEmpty() } }
                .take(4),
        )
    }

    private fun normalizeCampaignControlLabels(labels: AgentCampaignControlLabels): AgentCampaignControlLabels {
        return AgentCampaignControlLabels(
            commitmentLevel = labels.commitmentLevel?.trim()?.takeUnless { it.isEmpty() },
            battleReadiness = labels.battleReadiness?.trim()?.takeUnless { it.isEmpty() },
            supplyHealth = labels.supplyHealth?.trim()?.takeUnless { it.isEmpty() },
            nextCheckpointKind = labels.nextCheckpointKind?.trim()?.takeUnless { it.isEmpty() },
            nextCheckpointSummary = labels.nextCheckpointSummary?.trim()?.takeUnless { it.isEmpty() },
            checkpointHorizonTurns = labels.checkpointHorizonTurns?.coerceIn(1, 12),
            pivotTriggerKind = labels.pivotTriggerKind?.trim()?.takeUnless { it.isEmpty() },
        )
    }

    private fun buildRecentFailures(
        existing: List<RecentFailureMemory>,
        turn: Int,
        plan: AgentActionPlan?,
        report: AgentActionExecutor.ExecutionReport?,
        usedLegacyFallback: Boolean,
        fallbackReason: String?,
        validationFailures: List<AgentActionExecutor.ActionOutcome>,
    ): ArrayList<RecentFailureMemory> {
        val failures = ArrayList(
            existing
                .filter { turn - it.turn <= recentFailureWindowTurns }
                .map { it.copy() }
        )

        if (plan == null) {
            failures += RecentFailureMemory(
                turn = turn,
                kind = "plan_missing",
                summary = "No plan produced; legacy AI took over",
            )
        }

        if (usedLegacyFallback) {
            failures += RecentFailureMemory(
                turn = turn,
                kind = "fallback_legacy",
                summary = fallbackReason ?: "Legacy fallback used after agent planning",
            )
        }

        validationFailures
            .filter { it.status == AgentActionExecutor.ActionStatus.Rejected }
            .mapTo(failures) { outcome ->
                RecentFailureMemory(
                    turn = turn,
                    kind = "validation_rejected",
                    summary = outcome.reason,
                    unitId = outcome.unitId,
                    cityX = outcome.cityX,
                    cityY = outcome.cityY,
                    actionType = outcome.actionType ?: outcome.commandType,
                )
            }

        report?.outcomes
            ?.filter { it.status == AgentActionExecutor.ActionStatus.Rejected }
            ?.mapTo(failures) { outcome ->
                RecentFailureMemory(
                    turn = turn,
                    kind = "action_rejected",
                    summary = outcome.reason,
                    unitId = outcome.unitId,
                    cityX = outcome.cityX,
                    cityY = outcome.cityY,
                    actionType = outcome.actionType ?: outcome.commandType,
                )
            }

        return ArrayList(failures.takeLast(maxRecentFailures))
    }

    private fun buildAfterActionNotes(
        turn: Int,
        plan: AgentActionPlan?,
        report: AgentActionExecutor.ExecutionReport?,
        usedLegacyFallback: Boolean,
        fallbackReason: String?,
        intentionalNoOp: Boolean,
    ): List<MemoryNote> {
        val notes = arrayListOf<MemoryNote>()
        if (usedLegacyFallback) {
            notes += MemoryNote(
                topic = "after_action",
                kind = "after_action",
                text = fallbackReason ?: "Legacy fallback handled the turn.",
                firstTurn = turn,
                lastUpdatedTurn = turn,
                staleAfterTurn = turn + recentChangeHorizonTurns,
            )
        } else if (intentionalNoOp) {
            notes += MemoryNote(
                topic = "after_action",
                kind = "after_action",
                text = "The tactician intentionally held actions this turn.",
                firstTurn = turn,
                lastUpdatedTurn = turn,
                staleAfterTurn = turn + recentChangeHorizonTurns,
            )
        } else if (plan != null) {
            plan.notes?.trim()?.takeIf { shouldPersistStrategicFocusNote(it) }?.let { summary ->
                notes += MemoryNote(
                    topic = "after_action",
                    kind = "after_action",
                    text = summary.take(180),
                    firstTurn = turn,
                    lastUpdatedTurn = turn,
                    staleAfterTurn = turn + recentChangeHorizonTurns,
                )
            }
            val declaredWar = plan.actions
                .filterIsInstance<AgentActionCommand.SelectEmpireOption>()
                .any { it.candidateId.startsWith("diplo:war:") }
            if (declaredWar) {
                notes += MemoryNote(
                    topic = "after_action",
                    kind = "after_action",
                    text = "The empire committed to war on this turn.",
                    firstTurn = turn,
                    lastUpdatedTurn = turn,
                    staleAfterTurn = turn + recentChangeHorizonTurns,
                )
            }
        }
        return notes
    }

    private fun mergeRecentChanges(
        existing: List<MemoryNote>,
        additions: List<MemoryNote>,
        turn: Int,
    ): ArrayList<MemoryNote> {
        val merged = linkedMapOf<String, MemoryNote>()
        existing
            .filter { it.staleAfterTurn >= turn }
            .forEach { merged["${it.topic}|${it.text}"] = it.copy() }
        additions.forEach { merged["${it.topic}|${it.text}"] = it.copy() }
        return ArrayList(merged.values.toList().takeLast(maxRecentChanges))
    }

    private fun buildNoteList(
        topic: String,
        kind: String,
        texts: List<String>,
        turn: Int,
        staleAfterTurn: Int,
        maxItems: Int,
    ): ArrayList<MemoryNote> {
        return ArrayList(
            texts
                .mapNotNull { it.trim().takeIf { text -> text.isNotEmpty() } }
                .distinct()
                .take(maxItems)
                .map { text ->
                    MemoryNote(
                        topic = topic,
                        kind = kind,
                        text = text,
                        firstTurn = turn,
                        lastUpdatedTurn = turn,
                        staleAfterTurn = staleAfterTurn,
                    )
                }
        )
    }

    private fun pruneNotes(
        notes: List<MemoryNote>,
        turn: Int,
        maxItems: Int,
    ): ArrayList<MemoryNote> {
        return ArrayList(
            notes
                .filter { it.staleAfterTurn == 0 || it.staleAfterTurn >= turn }
                .takeLast(maxItems)
                .map { it.copy() }
        )
    }

    private fun upsertAnchor(
        anchors: MutableList<MemoryAnchor>,
        anchor: MemoryAnchor,
    ) {
        val index = anchors.indexOfFirst { it.kind == anchor.kind && it.label == anchor.label && it.civName == anchor.civName }
        if (index >= 0) {
            val existing = anchors[index]
            anchors[index] = existing.copy(
                x = anchor.x,
                y = anchor.y,
                lastConfirmedTurn = anchor.lastConfirmedTurn,
                firstSeenTurn = existing.firstSeenTurn.takeIf { it > 0 } ?: anchor.firstSeenTurn,
            )
        } else {
            anchors += anchor
        }
    }

    private fun upsertNote(
        notes: MutableList<MemoryNote>,
        note: MemoryNote,
        maxItems: Int,
    ) {
        val index = notes.indexOfFirst { it.topic == note.topic && it.text == note.text && it.civName == note.civName }
        if (index >= 0) {
            val existing = notes[index]
            notes[index] = existing.copy(
                x = note.x ?: existing.x,
                y = note.y ?: existing.y,
                lastUpdatedTurn = note.lastUpdatedTurn,
                staleAfterTurn = note.staleAfterTurn,
                confidence = note.confidence ?: existing.confidence,
            )
        } else {
            notes += note
            while (notes.size > maxItems) notes.removeAt(0)
        }
    }

    private fun extractPrimaryRivalCiv(
        memory: AgentMemory? = null,
        observation: AgentObservation,
        empireObservation: AgentEmpireObservation,
    ): String? {
        return memory?.campaign?.primaryRivalCiv
            ?: empireObservation.victoryThreats.firstOrNull()?.civName
            ?: observation.visibleThreatsAndTargets.firstOrNull { it.civName != observation.civName }?.civName
    }

    private fun deriveCityIntents(
        observation: AgentObservation,
        plan: AgentActionPlan,
        turn: Int,
    ): ArrayList<CityIntentMemory> {
        val cityByKey = observation.cities.associateBy { cityKey(it.x, it.y) }
        val intentsByKey = linkedMapOf<String, CityIntentMemory>()

        plan.actions
            .filterIsInstance<AgentActionCommand.SelectCityOption>()
            .forEach { action ->
                val parsed = parseCityOptionCandidateId(action.candidateId) ?: return@forEach
                val city = cityByKey[cityKey(parsed.cityX, parsed.cityY)]
                when (parsed.kind) {
                    "citybuild" -> intentsByKey[cityKey(parsed.cityX, parsed.cityY)] = CityIntentMemory(
                        cityX = parsed.cityX,
                        cityY = parsed.cityY,
                        cityName = city?.name ?: "(${parsed.cityX}, ${parsed.cityY})",
                        intent = "develop_city",
                        target = parsed.payload,
                        reasons = ArrayList(listOf("Project selected") + (city?.signals?.take(2) ?: emptyList())),
                        lastProgressTurn = turn,
                        staleAfterTurn = turn + cityIntentHorizonTurns,
                    )
                    "citypurchase" -> intentsByKey[cityKey(parsed.cityX, parsed.cityY)] = CityIntentMemory(
                        cityX = parsed.cityX,
                        cityY = parsed.cityY,
                        cityName = city?.name ?: "(${parsed.cityX}, ${parsed.cityY})",
                        intent = "invest_with_gold",
                        target = parsed.payload,
                        reasons = ArrayList(listOf("Gold purchase selected") + (city?.signals?.take(2) ?: emptyList())),
                        lastProgressTurn = turn,
                        staleAfterTurn = turn + cityIntentHorizonTurns,
                    )
                    "cityfocus" -> intentsByKey[cityKey(parsed.cityX, parsed.cityY)] = CityIntentMemory(
                        cityX = parsed.cityX,
                        cityY = parsed.cityY,
                        cityName = city?.name ?: "(${parsed.cityX}, ${parsed.cityY})",
                        intent = "city_focus",
                        target = parsed.payload,
                        reasons = ArrayList(listOf("City focus adjusted") + (city?.signals?.take(2) ?: emptyList())),
                        lastProgressTurn = turn,
                        staleAfterTurn = turn + cityIntentHorizonTurns,
                    )
                }
            }

        return ArrayList(intentsByKey.values)
    }

    private fun deriveUnitAssignments(
        observation: AgentObservation,
        plan: AgentActionPlan,
        turn: Int,
    ): ArrayList<UnitAssignmentMemory> {
        val unitById = observation.units.associateBy { it.id }
        val unitOptionByCandidateId = observation.units
            .flatMap { it.unitOptionCandidates }
            .associateBy { it.candidateId }
        val assignmentsByUnitId = linkedMapOf<Int, UnitAssignmentMemory>()

        for (action in plan.actions.sortedBy { it.priority }) {
            when (action) {
                is AgentActionCommand.SelectUnitOption -> {
                    val assignment = deriveUnitOptionAssignment(action.candidateId, unitById, unitOptionByCandidateId, turn) ?: continue
                    assignmentsByUnitId[assignment.unitId] = assignment
                }
                else -> Unit
            }
        }

        return ArrayList(assignmentsByUnitId.values)
    }

    private fun shouldPersistStrategicFocusNote(note: String): Boolean {
        val lower = note.lowercase()
        val lowSignalMarkers = listOf(
            "do nothing",
            "no action",
            "no manual actions",
            "next turn",
            "skip this turn",
            "sleep",
            "keep worker",
            "hold worker",
            "hold position",
            "preserve",
            "automate",
            "fortify",
            "garrison",
            "sciencefocus",
            "goldfocus",
            "culturefocus",
            "foodfocus",
            "faithfocus",
        )
        return lowSignalMarkers.none { it in lower }
    }

    private fun shouldPersistUnitAssignment(
        unit: AgentUnitObservation?,
        actionType: String?,
        assignment: UnitAssignmentMemory,
    ): Boolean {
        val lower = actionType?.trim()?.lowercase().orEmpty()
        if (lower == "skip") return false
        if (assignment.assignmentCategory == "one_shot") return false
        if (lower == "sleep") {
            return (unit?.nearbyHostileUnits ?: 0) > 0 ||
                (unit?.nearbyHostileCities ?: 0) > 0 ||
                (unit?.health ?: 100) < 100
        }

        if (assignment.role == "hold_position" &&
            unit != null &&
            (unit.role == "worker" || unit.role == "settler" || unit.role == "great_person") &&
            unit.nearbyHostileUnits == 0 &&
            unit.nearbyHostileCities == 0 &&
            unit.health >= 100
        ) {
            return false
        }

        if (assignment.role == "hold_position" &&
            unit != null &&
            unit.nearbyHostileUnits == 0 &&
            unit.nearbyHostileCities == 0 &&
            unit.health >= 100 &&
            unit.role in setOf("melee", "ranged", "siege", "naval_melee", "naval_ranged") &&
            isPeacefulSnowballWindow(unit, turn = null)
        ) {
            return false
        }

        return true
    }

    private fun deriveUnitOptionAssignment(
        candidateId: String,
        unitById: Map<Int, AgentUnitObservation>,
        candidateObservations: Map<String, UnitOptionCandidateObservation>,
        turn: Int,
    ): UnitAssignmentMemory? {
        val parsed = parseUnitOptionCandidateId(candidateId) ?: return null
        val unit = unitById[parsed.unitId]
        val observation = candidateObservations[candidateId]
        val assignment = when (parsed.kind) {
            "unitattack" -> buildUnitAssignmentMemory(
                unitId = parsed.unitId,
                unitName = unit?.name ?: "",
                role = "attack_target",
                targetX = parsed.targetX,
                targetY = parsed.targetY,
                detail = observation?.detail ?: "Grounded attack option",
                turn = turn,
            )
            "unitsettle" -> buildUnitAssignmentMemory(
                unitId = parsed.unitId,
                unitName = unit?.name ?: "",
                role = "settle_city_site",
                targetX = parsed.targetX,
                targetY = parsed.targetY,
                detail = observation?.detail ?: "Grounded city-site option",
                turn = turn,
            )
            "unitworkerimprove", "unitworkerreposition" -> buildUnitAssignmentMemory(
                unitId = parsed.unitId,
                unitName = unit?.name ?: "",
                role = "improve_tile",
                targetX = parsed.targetX,
                targetY = parsed.targetY,
                detail = observation?.detail ?: observation?.title ?: "Grounded worker job",
                turn = turn,
            )
            "unitstagecity" -> buildUnitAssignmentMemory(
                unitId = parsed.unitId,
                unitName = unit?.name ?: "",
                role = "stage_near_target_city",
                targetX = parsed.targetX,
                targetY = parsed.targetY,
                detail = observation?.detail ?: "Prewar staging assignment",
                turn = turn,
            )
            "unitattackcity" -> buildUnitAssignmentMemory(
                unitId = parsed.unitId,
                unitName = unit?.name ?: "",
                role = "attack_target_city",
                targetX = parsed.targetX,
                targetY = parsed.targetY,
                detail = observation?.detail ?: "Target city attack assignment",
                turn = turn,
            )
            "unitfallbackheal" -> buildUnitAssignmentMemory(
                unitId = parsed.unitId,
                unitName = unit?.name ?: "",
                role = "fallback_and_heal",
                targetX = parsed.targetX,
                targetY = parsed.targetY,
                detail = observation?.detail ?: "Fallback and heal assignment",
                turn = turn,
            )
            "unitautoexplore" -> buildUnitAssignmentMemory(
                unitId = parsed.unitId,
                unitName = unit?.name ?: "",
                role = "auto_explore",
                targetX = unit?.x,
                targetY = unit?.y,
                detail = observation?.detail ?: "Auto-explore assignment",
                turn = turn,
            )
            "unitstopautoexplore" -> buildUnitAssignmentMemory(
                unitId = parsed.unitId,
                unitName = unit?.name ?: "",
                role = "stop_auto_explore",
                targetX = unit?.x,
                targetY = unit?.y,
                detail = observation?.detail ?: "Stop auto-explore assignment",
                turn = turn,
            )
            "unitheal" -> buildUnitAssignmentMemory(
                unitId = parsed.unitId,
                unitName = unit?.name ?: "",
                role = "heal_and_hold",
                targetX = unit?.x,
                targetY = unit?.y,
                detail = observation?.detail ?: "Heal and hold assignment",
                turn = turn,
            )
            "unithold" -> buildUnitAssignmentMemory(
                unitId = parsed.unitId,
                unitName = unit?.name ?: "",
                role = "hold_position",
                targetX = unit?.x,
                targetY = unit?.y,
                detail = observation?.detail ?: "Hold position assignment",
                turn = turn,
            )
            "unitupgrade" -> buildUnitAssignmentMemory(
                unitId = parsed.unitId,
                unitName = unit?.name ?: "",
                role = "upgrade_self",
                targetX = unit?.x,
                targetY = unit?.y,
                detail = observation?.detail ?: "Upgrade self assignment",
                turn = turn,
            )
            "unitpillage" -> buildUnitAssignmentMemory(
                unitId = parsed.unitId,
                unitName = unit?.name ?: "",
                role = "pillage_here",
                targetX = unit?.x,
                targetY = unit?.y,
                detail = observation?.detail ?: "Pillage here assignment",
                turn = turn,
            )
            "unitability" -> buildUnitAssignmentMemory(
                unitId = parsed.unitId,
                unitName = unit?.name ?: "",
                role = "use_special_ability",
                targetX = unit?.x,
                targetY = unit?.y,
                detail = observation?.detail ?: "Use special ability assignment",
                turn = turn,
            )
            else -> return null
        }
        val actionType = parsed.actionType
        return assignment.takeIf { shouldPersistUnitAssignment(unit, actionType, it) }
    }

    private fun buildUnitAssignmentMemory(
        unitId: Int,
        unitName: String,
        role: String,
        targetX: Int?,
        targetY: Int?,
        detail: String?,
        turn: Int,
        assignmentSource: String = "explicit",
    ): UnitAssignmentMemory {
        return UnitAssignmentMemory(
            unitId = unitId,
            unitName = unitName,
            role = role,
            targetX = targetX,
            targetY = targetY,
            detail = detail,
            assignmentSource = assignmentSource,
            assignmentCategory = defaultUnitAssignmentCategory(role),
            executionMode = defaultUnitAssignmentExecutionMode(role),
            completionPolicy = defaultUnitAssignmentCompletionPolicy(role),
            lastProgressTurn = turn,
            staleAfterTurn = turn + unitAssignmentHorizonTurns,
        )
    }

    private fun defaultUnitAssignmentCategory(role: String): String = when (role) {
        "move_to_tile",
        "settle_city_site",
        "improve_tile",
        "attack_target",
        "fallback_and_heal",
        -> "finite"
        "stop_auto_explore",
        "upgrade_self",
        "pillage_here",
        "use_special_ability",
        -> "one_shot"
        else -> "persistent"
    }

    private fun defaultUnitAssignmentExecutionMode(role: String): String = when (role) {
        "auto_explore" -> "engine_auto"
        "move_to_tile",
        "settle_city_site",
        "stage_near_target_city",
        "attack_target_city",
        "fallback_and_heal",
        -> "deferred_heuristic"
        else -> "memory_only"
    }

    private fun defaultUnitAssignmentCompletionPolicy(role: String): String = when (role) {
        "move_to_tile",
        "settle_city_site",
        "improve_tile",
        "attack_target",
        -> "until_arrival"
        "auto_explore",
        "stage_near_target_city",
        "attack_target_city",
        "hold_position",
        "heal_and_hold",
        -> "until_switched"
        "fallback_and_heal" -> "until_recovered"
        else -> "until_stale"
    }

    private fun extractTouchedUnitIds(plan: AgentActionPlan): Set<Int> {
        val touched = linkedSetOf<Int>()
        for (action in plan.actions) {
            when (action) {
                is AgentActionCommand.SelectUnitOption -> parseUnitOptionCandidateId(action.candidateId)?.unitId?.let(touched::add)
                else -> Unit
            }
        }
        return touched
    }

    private fun cityKey(x: Int, y: Int): String = "$x,$y"

    private fun isCanonicalUnitAssignmentRole(role: String): Boolean = role in canonicalUnitAssignmentRoles

    private fun parseCityOptionCandidateId(candidateId: String): ParsedCityOption? {
        val parts = candidateId.split(":", limit = 3)
        if (parts.size != 3) return null
        val coords = parts[1].split(",", limit = 2)
        if (coords.size != 2) return null
        return ParsedCityOption(
            kind = parts[0],
            cityX = coords[0].toIntOrNull() ?: return null,
            cityY = coords[1].toIntOrNull() ?: return null,
            payload = parts[2],
        )
    }

    private data class ParsedCityOption(
        val kind: String,
        val cityX: Int,
        val cityY: Int,
        val payload: String,
    )

    private fun parseUnitOptionCandidateId(candidateId: String): ParsedUnitOption? {
        val parts = candidateId.split(":")
        if (parts.size < 2) return null
        val unitId = parts[1].toIntOrNull() ?: return null
        return when (parts[0]) {
            "unitattack" -> {
                if (parts.size != 4) return null
                val target = parseCoords(parts[3]) ?: return null
                ParsedUnitOption(
                    kind = parts[0],
                    unitId = unitId,
                    targetX = target.first,
                    targetY = target.second,
                )
            }
            "unitsettle" -> {
                if (parts.size != 3) return null
                val target = parseCoords(parts[2]) ?: return null
                ParsedUnitOption(
                    kind = parts[0],
                    unitId = unitId,
                    targetX = target.first,
                    targetY = target.second,
                )
            }
            "unitworkerimprove" -> {
                if (parts.size < 4) return null
                val target = parseCoords(parts[2]) ?: return null
                ParsedUnitOption(
                    kind = parts[0],
                    unitId = unitId,
                    targetX = target.first,
                    targetY = target.second,
                    actionType = parts.getOrNull(3),
                )
            }
            "unitworkerreposition" -> {
                if (parts.size != 3) return null
                val target = parseCoords(parts[2]) ?: return null
                ParsedUnitOption(
                    kind = parts[0],
                    unitId = unitId,
                    targetX = target.first,
                    targetY = target.second,
                )
            }
            "unitstagecity", "unitattackcity", "unitfallbackheal" -> {
                if (parts.size != 3) return null
                val target = parseCoords(parts[2]) ?: return null
                ParsedUnitOption(
                    kind = parts[0],
                    unitId = unitId,
                    targetX = target.first,
                    targetY = target.second,
                )
            }
            "unitautoexplore", "unitstopautoexplore" -> {
                if (parts.size != 2) return null
                ParsedUnitOption(
                    kind = parts[0],
                    unitId = unitId,
                )
            }
            "unitheal", "unithold", "unitupgrade", "unitpillage", "unitability" -> {
                if (parts.size < 3) return null
                ParsedUnitOption(
                    kind = parts[0],
                    unitId = unitId,
                    actionType = parts[2],
                )
            }
            else -> null
        }
    }

    private fun parseCoords(raw: String): Pair<Int, Int>? {
        val coords = raw.split(",", limit = 2)
        if (coords.size != 2) return null
        return Pair(
            coords[0].toIntOrNull() ?: return null,
            coords[1].toIntOrNull() ?: return null,
        )
    }

    private data class ParsedUnitOption(
        val kind: String,
        val unitId: Int,
        val targetX: Int? = null,
        val targetY: Int? = null,
        val actionType: String? = null,
    )

    private fun ObservationFact.looksLikeSettlementOpportunity(): Boolean {
        val haystack = "${headline.lowercase()} ${detail.lowercase()} ${category.lowercase()}"
        return "settle" in haystack || "city site" in haystack || "found city" in haystack
    }

    private fun ObservationFact.looksLikeImprovementOpportunity(): Boolean {
        val haystack = "${headline.lowercase()} ${detail.lowercase()} ${category.lowercase()}"
        return "improve" in haystack || "repair" in haystack || "resource" in haystack || "worker" in haystack
    }

    private fun isPeacefulSnowballWindow(observation: AgentObservation, turn: Int): Boolean {
        if (turn > 45) return false
        if (observation.empireSummary.isAtWar) return false
        if (observation.empireSummary.visibleHostileUnits > 0) return false
        if (observation.empireSummary.visibleForeignCities > 0) return false
        if (observation.empireSummary.cityCount > 1) return false
        if (observation.empireSummary.happiness < 0) return false
        return observation.opportunities.any { it.looksLikeSettlementOpportunity() } ||
            observation.opportunities.any { it.looksLikeImprovementOpportunity() } ||
            observation.cities.any { city ->
                city.actions.chooseProject.any { candidate ->
                    candidate.candidateId.endsWith(":Worker") ||
                        candidate.candidateId.endsWith(":Settler") ||
                        candidate.candidateId.endsWith(":Granary") ||
                        candidate.candidateId.endsWith(":Monument")
                }
            }
    }

    private fun isPeacefulSnowballWindow(unit: AgentUnitObservation, turn: Int?): Boolean {
        if (turn != null && turn > 45) return false
        if (unit.nearbyHostileUnits > 0 || unit.nearbyHostileCities > 0) return false
        return true
    }

    private fun hasStrategistMemo(memo: AgentStrategistMemoMemory): Boolean {
        return memo.campaignStage.isNotBlank() ||
            !memo.thesis.isNullOrBlank() ||
            !memo.currentSituation.isNullOrBlank()
    }

    private fun isSameCampaignThread(
        previous: AgentStrategistMemoMemory,
        current: AgentStrategistMemoDraft,
    ): Boolean {
        val previousObjective = previous.decisiveObjective?.trim().orEmpty()
        val currentObjective = current.decisiveObjective.trim()
        if (!hasStrategistMemo(previous)) return false

        val previousTargetFrame = previous.decisionFrame.targetFrame
            ?: previous.decisiveObjective
            ?: previous.futurePlan
        val currentTargetFrame = current.decisionFrame.targetFrame
            ?: current.decisiveObjective
        val targetFrameMatch = describeSameCampaignAxis(previousTargetFrame, currentTargetFrame)
        val objectiveMatch = describeSameCampaignAxis(previousObjective, currentObjective)
        val previousModeFamily = campaignModeFamily(previous.decisionFrame.decisionMode ?: previous.campaignStage)
        val currentModeFamily = campaignModeFamily(current.decisionFrame.decisionMode ?: current.campaignStage)

        return (targetFrameMatch || objectiveMatch) &&
            previousModeFamily == currentModeFamily
    }

    private fun describeSameCampaignAxis(previous: String?, current: String?): Boolean {
        val previousTokens = canonicalCampaignTokens(previous)
        val currentTokens = canonicalCampaignTokens(current)
        if (previousTokens.isEmpty() || currentTokens.isEmpty()) return false
        if (previousTokens == currentTokens) return true
        val overlap = previousTokens.intersect(currentTokens)
        if (overlap.isEmpty()) return false
        val overlapRatio = overlap.size.toDouble() / minOf(previousTokens.size, currentTokens.size).toDouble()
        return overlapRatio >= 0.5 || overlap.size >= 3
    }

    private fun canonicalCampaignTokens(text: String?): Set<String> {
        if (text.isNullOrBlank()) return emptySet()
        val stopWords = setOf(
            "the", "a", "an", "to", "for", "of", "and", "or", "with", "before", "after",
            "this", "that", "our", "their", "your", "from", "into", "over", "under",
            "while", "still", "now", "next", "real", "current", "main",
        )
        return text
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .split(' ')
            .mapNotNull { token -> token.trim().takeIf { it.length >= 3 && it !in stopWords } }
            .toSet()
    }

    private fun campaignModeFamily(raw: String?): String {
        return when (raw?.trim()?.lowercase()) {
            "scouting", "expand", "expansion" -> "expand"
            "stage_briefly", "staging", "pressure", "launch_now", "launch_window", "assault" -> "military"
            "pivot_recover", "stabilize", "rebuild", "consolidation", "positioning" -> "stabilize"
            else -> raw?.trim()?.lowercase().orEmpty()
        }
    }
}
