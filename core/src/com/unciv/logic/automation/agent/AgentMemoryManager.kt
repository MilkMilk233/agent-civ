package com.unciv.logic.automation.agent

import com.unciv.logic.civilization.Civilization
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object AgentMemoryManager {
    private const val recentFailureWindowTurns = 8
    private const val maxRecentFailures = 8
    private const val cityIntentHorizonTurns = 5
    private const val unitAssignmentHorizonTurns = 4
    private const val recentChangeHorizonTurns = 12
    private const val sightingHorizonTurns = 12
    private const val maxRecentChanges = 10
    private const val maxLessons = 8
    private const val maxTacticianTurnLogEntries = 8
    private const val maxRivalNotes = 10
    private const val maxRivalAnchors = 12

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

        val prepared = existing.copy(
            worldModel = pruneWorldModel(existing.worldModel, turn),
            rivals = mergeRivalNotebooks(existing.rivals, observation, empireObservation, turn),
            campaign = pruneCampaign(existing.campaign, empireObservation, turn),
            empirePlan = pruneEmpirePlan(existing.empirePlan, turn),
            recentChanges = pruneNotes(existing.recentChanges, turn, maxRecentChanges),
            lessons = ArrayList(existing.lessons.takeLast(maxLessons).map { it.copy() }),
            lastStrategistMemo = existing.lastStrategistMemo.copy(
                reviewCityNames = ArrayList(existing.lastStrategistMemo.reviewCityNames),
            ),
            tacticianTurnLog = pruneTacticianTurnLog(existing.tacticianTurnLog, turn),
            cityIntents = ArrayList(
                existing.cityIntents
                    .filter { it.staleAfterTurn >= turn && cityKey(it.cityX, it.cityY) in validCities }
                    .map { it.copy(reasons = ArrayList(it.reasons)) }
            ),
            unitAssignments = ArrayList(
                existing.unitAssignments
                    .filter { it.staleAfterTurn >= turn && it.unitId in validUnits }
                    .map { it.copy() }
            ),
            recentFailures = ArrayList(
                existing.recentFailures
                    .filter { turn - it.turn <= recentFailureWindowTurns }
                    .takeLast(maxRecentFailures)
                    .map { it.copy() }
            ),
        )

        civInfo.agentMemory = prepared.clone()
        return prepared
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
                carryForward = intentReconciliation.carryForward,
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
            civInfo.agentMemory = updated.clone()
            return updated
        }

        val newCityIntents = deriveCityIntents(observation, plan, turn)
        val newUnitAssignments = deriveUnitAssignments(observation, plan, turn)
        val plannedCityKeys = newCityIntents.map { cityKey(it.cityX, it.cityY) }.toSet()
        val plannedUnitIds = newUnitAssignments.map { it.unitId }.toSet()

        val preservedCityIntents = intentReconciliation.cityIntents
            .filter { it.staleAfterTurn >= turn && cityKey(it.cityX, it.cityY) !in plannedCityKeys }
            .map { it.copy(reasons = ArrayList(it.reasons)) }
        val preservedUnitAssignments = intentReconciliation.unitAssignments
            .filter { it.staleAfterTurn >= turn && it.unitId !in plannedUnitIds }
            .map { it.copy() }

        val carryForward = buildCarryForwardNotes(
            cityIntents = newCityIntents.ifEmpty { preservedCityIntents },
            unitAssignments = newUnitAssignments.ifEmpty { preservedUnitAssignments },
        )
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
            carryForward = carryForward,
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
        civInfo.agentMemory = updated.clone()
        return updated
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
            )
        }
        if (memo.reviewAfterTurn > 0 && turn >= memo.reviewAfterTurn) {
            return AgentStrategistRefreshRequest(
                urgency = "scheduled",
                reason = "Scheduled strategist review: refresh the game notebook and next few-turn handoff from the current board state.",
            )
        }
        return tacticalRefreshIfEmergency(memory, observation, empireObservation, requested = null)
    }

    fun shouldHonorTacticalStrategistRefresh(
        memory: AgentMemory,
        observation: AgentObservation,
        empireObservation: AgentEmpireObservation,
        requested: AgentStrategistRefreshRequest,
    ): AgentStrategistRefreshRequest? {
        return tacticalRefreshIfEmergency(memory, observation, empireObservation, requested)
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
        val reviewInTurns = when {
            gameContext.duelLike && gameContext.gameSpeed.equals("Quick", ignoreCase = true) ->
                strategicPlan.memo.reviewInTurns.coerceIn(3, 6)
            else -> strategicPlan.memo.reviewInTurns.coerceIn(4, 8)
        }

        val memo = AgentStrategistMemoMemory(
            gameArchetype = gameContext.archetype,
            winPath = strategicPlan.memo.winPath?.trim().takeUnless { it.isNullOrEmpty() },
            campaignStage = strategicPlan.memo.campaignStage.trim(),
            decisiveObjective = strategicPlan.memo.decisiveObjective.trim().takeUnless { it.isEmpty() },
            conversionBlocker = strategicPlan.memo.conversionBlocker?.trim().takeUnless { it.isNullOrEmpty() },
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
            reviewVisibleRivalUnits = observation.visibleThreatsAndTargets.count { it.kind == "unit" && it.civName != observation.civName },
            reviewPrimaryRivalCiv = extractPrimaryRivalCiv(memory, observation, empireObservation),
            reviewCityNames = ArrayList(observation.cities.map { it.name }.sorted()),
            reviewAfterTurn = turn + reviewInTurns,
            createdTurn = memory.lastStrategistMemo.createdTurn.takeIf {
                it > 0 && isSameCampaignThread(memory.lastStrategistMemo, strategicPlan.memo)
            }
                ?: turn,
            lastReviewedTurn = turn,
            lastRefreshReason = refreshRequest.reason,
        )

        val updated = memory.copy(
            worldModel = buildWorldModel(memory.worldModel, strategicPlan.memo, turn),
            rivals = applyStrategistRivalUpdates(
                current = mergeRivalNotebooks(memory.rivals, observation, empireObservation, turn),
                drafts = strategicPlan.memo.rivals,
                turn = turn,
            ),
            campaign = buildCampaignMemory(memory.campaign, strategicPlan.memo, observation, empireObservation, turn),
            empirePlan = buildEmpirePlan(memory.empirePlan, strategicPlan.memo, turn),
            recentChanges = buildNoteList("recent_change", "change", strategicPlan.memo.recentChanges, turn, recentChangeHorizonTurns, maxRecentChanges),
            lessons = buildNoteList("lesson", "lesson", strategicPlan.memo.lessons, turn, turn + 200, maxLessons),
            lastStrategistMemo = memo,
            tacticianTurnLog = pruneTacticianTurnLog(memory.tacticianTurnLog, turn),
            cityIntents = ArrayList(memory.cityIntents.map { it.copy(reasons = ArrayList(it.reasons)) }),
            unitAssignments = ArrayList(memory.unitAssignments.map { it.copy() }),
            recentFailures = ArrayList(memory.recentFailures.map { it.copy() }),
        )
        return updated
    }

    private fun tacticalRefreshIfEmergency(
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
            )
        }
        if (primaryThreat?.threatLevel == "critical" &&
            memory.campaign.primaryRivalCiv != null &&
            memory.campaign.primaryRivalCiv != primaryThreat.civName
        ) {
            return AgentStrategistRefreshRequest(
                urgency = "emergency",
                reason = requestedReason.ifBlank { "${primaryThreat.civName} is now the critical rival, so the strategist notebook should be refreshed immediately." },
            )
        }
        if (requested != null && requested.urgency.equals("emergency", ignoreCase = true)) {
            return AgentStrategistRefreshRequest(
                urgency = "emergency",
                reason = requestedReason.ifBlank { "The tactician reported a strategic break in the current notebook." },
            )
        }
        return null
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
            worldModel = pruneWorldModel(memory.worldModel, turn),
            rivals = mergeRivalNotebooks(memory.rivals, observation, empireObservation, turn),
            campaign = pruneCampaign(memory.campaign, empireObservation, turn),
            empirePlan = pruneEmpirePlan(memory.empirePlan, turn),
            recentChanges = pruneNotes(memory.recentChanges, turn, maxRecentChanges),
            lessons = ArrayList(memory.lessons.takeLast(maxLessons).map { it.copy() }),
            lastStrategistMemo = memory.lastStrategistMemo.copy(
                reviewCityNames = ArrayList(memory.lastStrategistMemo.reviewCityNames),
            ),
            tacticianTurnLog = pruneTacticianTurnLog(memory.tacticianTurnLog, turn),
            cityIntents = ArrayList(memory.cityIntents.map { it.copy(reasons = ArrayList(it.reasons)) }),
            unitAssignments = ArrayList(memory.unitAssignments.map { it.copy() }),
            recentFailures = ArrayList(memory.recentFailures.map { it.copy() }),
        )
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
                        carryForward = ArrayList(entry.carryForward),
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
                    carryForward = ArrayList(existingEntry.carryForward),
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
            if (targetReached) {
                completed += "${assignment.unitName.ifBlank { "Unit #${assignment.unitId}" }} reached (${assignment.targetX}, ${assignment.targetY}), so that carry-over is complete."
                continue
            }
            activeUnitAssignments += assignment.copy(staleAfterTurn = turn + unitAssignmentHorizonTurns)
        }

        return IntentCarryoverReconciliation(
            cityIntents = activeCityIntents,
            unitAssignments = activeUnitAssignments,
            completed = completed,
            obsolete = obsolete,
            carryForward = buildCarryForwardNotes(activeCityIntents, activeUnitAssignments),
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
        carryForward: List<String>,
    ): TacticianTurnLogEntry? {
        val turn = civInfo.gameInfo.turns
        val whatChanged = buildWhatChangedNotes(civInfo, observation, empireObservation, plan, report)
        val stillBlocked = summarizeCurrentBlockers(civInfo, observation, empireObservation, startingMemory)
        val planNotes = plan?.notes?.trim().takeUnless { it.isNullOrEmpty() }
        val summary = when {
            usedLegacyFallback -> fallbackReason ?: "Legacy fallback handled the turn after the tactical plan broke."
            intentionalNoOp -> "The tactician intentionally held actions this turn."
            planNotes != null -> planNotes.take(220)
            report != null -> "Executed ${report.executedActions} tactical actions with ${report.rejectedActions} rejected."
            else -> "Turn completed without a recorded tactical delta."
        }
        if (
            summary.isBlank() &&
            whatChanged.isEmpty() &&
            completed.isEmpty() &&
            obsolete.isEmpty() &&
            stillBlocked.isEmpty() &&
            carryForward.isEmpty()
        ) {
            return null
        }
        return TacticianTurnLogEntry(
            turn = turn,
            basedOnStrategistTurn = startingMemory.lastStrategistMemo.lastReviewedTurn.takeIf { it > 0 },
            campaignStage = startingMemory.campaign.stage.takeIf { it.isNotBlank() },
            decisiveObjective = startingMemory.campaign.decisiveObjective,
            summary = summary,
            whatChanged = ArrayList(whatChanged.take(4)),
            completed = ArrayList(completed.take(4)),
            stillBlocked = ArrayList(stillBlocked.take(3)),
            obsolete = ArrayList(obsolete.take(3)),
            carryForward = ArrayList(carryForward.take(4)),
        )
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

    private fun buildCarryForwardNotes(
        cityIntents: List<CityIntentMemory>,
        unitAssignments: List<UnitAssignmentMemory>,
    ): List<String> {
        val notes = linkedSetOf<String>()
        cityIntents.take(2).forEach { intent ->
            val target = intent.target?.takeIf { it.isNotBlank() } ?: intent.intent
            notes += "${intent.cityName} still carries $target."
        }
        unitAssignments.take(2).forEach { assignment ->
            val unitName = assignment.unitName.ifBlank { "Unit #${assignment.unitId}" }
            notes += "$unitName still carries ${describeUnitAssignment(assignment)}."
        }
        return notes.toList()
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
        val carryForward: List<String>,
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

    private fun mergeRivalNotebooks(
        existing: List<RivalNotebookMemory>,
        observation: AgentObservation,
        empireObservation: AgentEmpireObservation,
        turn: Int,
    ): ArrayList<RivalNotebookMemory> {
        val notebooks = linkedMapOf<String, RivalNotebookMemory>()
        existing.forEach { rival ->
            notebooks[rival.rivalCiv] = rival.copy(
                notes = pruneNotes(rival.notes, turn, maxRivalNotes),
                anchors = ArrayList(rival.anchors.map { it.copy() }),
            )
        }

        val knownRivals = linkedSetOf<String>()
        empireObservation.victoryThreats.mapTo(knownRivals) { it.civName }
        observation.visibleThreatsAndTargets
            .filter { it.civName != observation.civName }
            .mapTo(knownRivals) { it.civName }

        knownRivals.forEach { rivalCiv ->
            notebooks.putIfAbsent(rivalCiv, RivalNotebookMemory(rivalCiv = rivalCiv, lastUpdatedTurn = turn))
        }

        observation.visibleThreatsAndTargets
            .filter { it.civName != observation.civName }
            .forEach { target ->
                val notebook = notebooks.getOrPut(target.civName) { RivalNotebookMemory(rivalCiv = target.civName) }
                notebook.lastUpdatedTurn = turn
                if (target.kind == "city") {
                    upsertAnchor(
                        notebook.anchors,
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
                } else if (target.kind == "unit") {
                    upsertNote(
                        notebook.notes,
                        MemoryNote(
                            topic = "rival",
                            kind = "fact",
                            text = "Saw ${target.name} near (${target.x}, ${target.y}).",
                            civName = target.civName,
                            x = target.x,
                            y = target.y,
                            confidence = "high",
                            firstTurn = turn,
                            lastUpdatedTurn = turn,
                            staleAfterTurn = turn + sightingHorizonTurns,
                        ),
                        maxRivalNotes,
                    )
                }
            }

        return ArrayList(
            notebooks.values
                .map { rival ->
                    rival.copy(
                        notes = ArrayList(rival.notes.takeLast(maxRivalNotes).map { it.copy() }),
                        anchors = ArrayList(rival.anchors.sortedByDescending { it.lastConfirmedTurn }.take(maxRivalAnchors).map { it.copy() }),
                    )
                }
                .sortedBy { it.rivalCiv }
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
            primaryRivalCiv = memo.rivals.firstOrNull()?.rivalCiv
                ?: current.primaryRivalCiv
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

    private fun applyStrategistRivalUpdates(
        current: List<RivalNotebookMemory>,
        drafts: List<AgentStrategistRivalNotebookDraft>,
        turn: Int,
    ): ArrayList<RivalNotebookMemory> {
        val byCiv = linkedMapOf<String, RivalNotebookMemory>()
        current.forEach { byCiv[it.rivalCiv] = it.copy(
            notes = ArrayList(it.notes.map { note -> note.copy() }),
            anchors = ArrayList(it.anchors.map { anchor -> anchor.copy() }),
        ) }
        drafts.forEach { draft ->
            val existing = byCiv[draft.rivalCiv] ?: RivalNotebookMemory(rivalCiv = draft.rivalCiv)
            byCiv[draft.rivalCiv] = existing.copy(
                summary = draft.summary?.trim().takeUnless { it.isNullOrEmpty() } ?: existing.summary,
                notes = buildNoteList("rival", "inference", draft.notes, turn, turn + 60, maxRivalNotes),
                anchors = ArrayList(existing.anchors.map { it.copy() }),
                lastUpdatedTurn = turn,
            )
        }
        return ArrayList(byCiv.values.sortedBy { it.rivalCiv })
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
        val commandsByUnit = linkedMapOf<Int, MutableList<AgentActionCommand>>()

        for (action in plan.actions.sortedBy { it.priority }) {
            when (action) {
                is AgentActionCommand.SelectUnitOption -> {
                    val assignment = deriveUnitOptionAssignment(action.candidateId, unitById, unitOptionByCandidateId, turn) ?: continue
                    assignmentsByUnitId[assignment.unitId] = assignment
                }
                is AgentActionCommand.UnitMove -> commandsByUnit.getOrPut(action.unitId) { arrayListOf() }.add(action)
                is AgentActionCommand.UnitAction -> commandsByUnit.getOrPut(action.unitId) { arrayListOf() }.add(action)
                else -> Unit
            }
        }

        for ((unitId, commands) in commandsByUnit) {
            if (unitId in assignmentsByUnitId) continue
            val unit = unitById[unitId]
            val lastMove = commands.filterIsInstance<AgentActionCommand.UnitMove>().lastOrNull()
            val lastAction = commands.filterIsInstance<AgentActionCommand.UnitAction>().lastOrNull()
            val targetX = lastMove?.destinationX ?: unit?.x
            val targetY = lastMove?.destinationY ?: unit?.y
            val assignment = classifyAssignment(unit, lastAction?.actionType, targetX, targetY, turn)
            if (!shouldPersistUnitAssignment(unit, lastAction?.actionType, assignment)) continue
            assignmentsByUnitId[unitId] = assignment.copy(unitId = unitId, unitName = unit?.name ?: assignment.unitName)
        }

        return ArrayList(assignmentsByUnitId.values)
    }

    private fun classifyAssignment(
        unit: AgentUnitObservation?,
        actionType: String?,
        targetX: Int?,
        targetY: Int?,
        turn: Int,
    ): UnitAssignmentMemory {
        val normalized = actionType?.trim().orEmpty()
        val lower = normalized.lowercase()
        val role = when {
            normalized.isBlank() -> "reposition"
            lower == "skip" -> "transient_wait"
            lower == "foundcity" -> "settle_city_site"
            "build" in lower || "repair" in lower || "remove" in lower || "create" in lower -> "improve_tile"
            "automate" in lower -> "automation_change"
            "fortify" in lower || "sleep" in lower || "guard" in lower ->
                if ((unit?.health ?: 100) < 100) "heal_and_hold" else "hold_position"
            "explore" in lower || "recon" in lower -> "explore"
            "attack" in lower || "bombard" in lower -> "attack_target"
            else -> "execute_action"
        }

        return UnitAssignmentMemory(
            unitId = unit?.id ?: 0,
            unitName = unit?.name ?: "",
            role = role,
            targetX = targetX,
            targetY = targetY,
            detail = normalized.ifBlank { if (targetX != null && targetY != null) "Move to ($targetX, $targetY)" else null },
            lastProgressTurn = turn,
            staleAfterTurn = turn + unitAssignmentHorizonTurns,
        )
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
        val normalized = actionType?.trim().orEmpty()
        val lower = normalized.lowercase()

        if (assignment.role == "transient_wait") return false
        if (lower == "automate" || lower == "stopautomation") return false
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
            "unitattack" -> UnitAssignmentMemory(
                unitId = parsed.unitId,
                unitName = unit?.name ?: "",
                role = "attack_target",
                targetX = parsed.targetX,
                targetY = parsed.targetY,
                detail = observation?.detail ?: "Grounded attack option",
                lastProgressTurn = turn,
                staleAfterTurn = turn + unitAssignmentHorizonTurns,
            )
            "unitsettle" -> UnitAssignmentMemory(
                unitId = parsed.unitId,
                unitName = unit?.name ?: "",
                role = "settle_city_site",
                targetX = parsed.targetX,
                targetY = parsed.targetY,
                detail = observation?.detail ?: "Grounded city-site option",
                lastProgressTurn = turn,
                staleAfterTurn = turn + unitAssignmentHorizonTurns,
            )
            "unitworkerimprove", "unitworkerreposition" -> UnitAssignmentMemory(
                unitId = parsed.unitId,
                unitName = unit?.name ?: "",
                role = "improve_tile",
                targetX = parsed.targetX,
                targetY = parsed.targetY,
                detail = observation?.detail ?: observation?.title ?: "Grounded worker job",
                lastProgressTurn = turn,
                staleAfterTurn = turn + unitAssignmentHorizonTurns,
            )
            "unitspecial" -> classifyAssignment(unit, parsed.actionType, unit?.x, unit?.y, turn)
                .copy(unitId = parsed.unitId, unitName = unit?.name ?: "")
            else -> return null
        }
        val actionType = parsed.actionType
        return assignment.takeIf { shouldPersistUnitAssignment(unit, actionType, it) }
    }

    private fun cityKey(x: Int, y: Int): String = "$x,$y"

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
        if (parts.size < 3) return null
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
            "unitspecial" -> {
                if (parts.size != 3) return null
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
        val previousStage = previous.campaignStage.trim()
        val currentStage = current.campaignStage.trim()
        val previousObjective = previous.decisiveObjective?.trim().orEmpty()
        val currentObjective = current.decisiveObjective.trim()
        return previousStage.isNotBlank() &&
            previousStage == currentStage &&
            previousObjective == currentObjective
    }
}
