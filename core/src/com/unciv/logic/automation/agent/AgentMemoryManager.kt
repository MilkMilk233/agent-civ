package com.unciv.logic.automation.agent

import com.unciv.logic.civilization.Civilization
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object AgentMemoryManager {
    private const val recentFailureWindowTurns = 8
    private const val maxRecentFailures = 8
    private const val cityIntentHorizonTurns = 5
    private const val unitAssignmentHorizonTurns = 4
    private const val doctrineReviewCadenceTurns = 30

    private val json = Json {
        prettyPrint = false
        explicitNulls = false
    }

    fun memoryJson(memory: AgentMemory): String = json.encodeToString(memory)
    fun roadmapJson(roadmap: AgentStrategicRoadmapMemory): String = json.encodeToString(roadmap)

    fun prepareForTurn(
        civInfo: Civilization,
        observation: AgentObservation,
        empireObservation: AgentEmpireObservation,
    ): AgentMemory {
        val turn = civInfo.gameInfo.turns
        val validCities = civInfo.cities.associateBy { cityKey(it.location.x, it.location.y) }
        val validUnits = civInfo.units.getCivUnits().associateBy { it.id }
        val existing = civInfo.agentMemory.clone()

        val prepared = AgentMemory(
            strategicPosture = normalizeStrategicPosture(existing, observation, empireObservation, turn),
            strategicRoadmap = existing.strategicRoadmap.copy(
                midTermGoals = ArrayList(existing.strategicRoadmap.midTermGoals),
                mustMaintain = ArrayList(existing.strategicRoadmap.mustMaintain),
                watchOuts = ArrayList(existing.strategicRoadmap.watchOuts),
                switchTriggers = ArrayList(existing.strategicRoadmap.switchTriggers),
            ),
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
        val strategicPosture = deriveStrategicPosture(startingMemory, observation, empireObservation, plan, turn)
        val recentFailures = buildRecentFailures(
            existing = startingMemory.recentFailures,
            turn = turn,
            plan = plan,
            report = report,
            usedLegacyFallback = usedLegacyFallback,
            fallbackReason = fallbackReason,
            validationFailures = validationFailures,
        )

        val carriedCityIntents = startingMemory.cityIntents
            .filter { it.staleAfterTurn >= turn }
            .map { it.copy(reasons = ArrayList(it.reasons)) }
        val carriedUnitAssignments = startingMemory.unitAssignments
            .filter { it.staleAfterTurn >= turn }
            .map { it.copy() }

        if (plan == null || usedLegacyFallback) {
            val updated = AgentMemory(
                strategicPosture = strategicPosture,
                strategicRoadmap = cloneRoadmap(startingMemory.strategicRoadmap),
                cityIntents = ArrayList(carriedCityIntents),
                unitAssignments = ArrayList(carriedUnitAssignments),
                recentFailures = recentFailures,
            )
            civInfo.agentMemory = updated.clone()
            return updated
        }

        if (intentionalNoOp) {
            val updated = AgentMemory(
                strategicPosture = strategicPosture,
                strategicRoadmap = cloneRoadmap(startingMemory.strategicRoadmap),
                cityIntents = ArrayList(carriedCityIntents),
                unitAssignments = ArrayList(carriedUnitAssignments),
                recentFailures = recentFailures,
            )
            civInfo.agentMemory = updated.clone()
            return updated
        }

        val newCityIntents = deriveCityIntents(observation, plan, turn)
        val newUnitAssignments = deriveUnitAssignments(observation, plan, turn)
        val plannedCityKeys = newCityIntents.map { cityKey(it.cityX, it.cityY) }.toSet()
        val plannedUnitIds = newUnitAssignments.map { it.unitId }.toSet()

        val preservedCityIntents = startingMemory.cityIntents
            .filter { it.staleAfterTurn >= turn && cityKey(it.cityX, it.cityY) !in plannedCityKeys }
            .map { it.copy(reasons = ArrayList(it.reasons)) }
        val preservedUnitAssignments = startingMemory.unitAssignments
            .filter { it.staleAfterTurn >= turn && it.unitId !in plannedUnitIds }
            .map { it.copy() }

        val updated = AgentMemory(
            strategicPosture = strategicPosture,
            strategicRoadmap = cloneRoadmap(startingMemory.strategicRoadmap),
            cityIntents = ArrayList(preservedCityIntents + newCityIntents),
            unitAssignments = ArrayList(preservedUnitAssignments + newUnitAssignments),
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
        val roadmap = memory.strategicRoadmap
        if (roadmap.doctrine.isBlank()) {
            return AgentStrategistRefreshRequest(
                urgency = "initial",
                reason = "No strategic roadmap exists yet. Choose an opening doctrine for this match setup.",
            )
        }
        if (roadmap.reviewAfterTurn > 0 && turn >= roadmap.reviewAfterTurn) {
            return AgentStrategistRefreshRequest(
                urgency = "scheduled",
                reason = "Scheduled roadmap review: reassess doctrine, phase, and next few-turn plan from the current board state.",
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

    fun applyStrategicRoadmap(
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
                strategicPlan.roadmap.reviewInTurns.coerceIn(3, 6)
            else -> strategicPlan.roadmap.reviewInTurns.coerceIn(4, 8)
        }
        val roadmap = AgentStrategicRoadmapMemory(
            gameArchetype = gameContext.archetype,
            doctrine = strategicPlan.roadmap.doctrine.trim(),
            winPath = strategicPlan.roadmap.winPath?.trim().takeUnless { it.isNullOrEmpty() },
            phase = strategicPlan.roadmap.phase.trim(),
            thesis = strategicPlan.roadmap.thesis?.trim().takeUnless { it.isNullOrEmpty() },
            midTermGoals = ArrayList(strategicPlan.roadmap.midTermGoals.map { it.trim() }.filter { it.isNotBlank() }.take(4)),
            mustMaintain = ArrayList(strategicPlan.roadmap.mustMaintain.map { it.trim() }.filter { it.isNotBlank() }.take(4)),
            watchOuts = ArrayList(strategicPlan.roadmap.watchOuts.map { it.trim() }.filter { it.isNotBlank() }.take(3)),
            switchTriggers = ArrayList(strategicPlan.roadmap.switchTriggers.map { it.trim() }.filter { it.isNotBlank() }.take(4)),
            reviewAfterTurn = turn + reviewInTurns,
            createdTurn = memory.strategicRoadmap.createdTurn.takeIf { it > 0 && memory.strategicRoadmap.doctrine == strategicPlan.roadmap.doctrine.trim() }
                ?: turn,
            lastReviewedTurn = turn,
            lastRefreshReason = refreshRequest.reason,
        )
        val updated = memory.copy(
            strategicPosture = roadmapDrivenStrategicPosture(
                previous = memory.strategicPosture,
                roadmap = roadmap,
                observation = observation,
                empireObservation = empireObservation,
                plan = null,
                turn = turn,
            ),
            strategicRoadmap = roadmap,
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
        val roadmap = memory.strategicRoadmap
        if (roadmap.doctrine.isBlank()) return requested
        val primaryThreat = empireObservation.victoryThreats.firstOrNull()
        val requestedReason = requested?.reason?.trim().orEmpty()
        if (observation.empireSummary.isAtWar && !roadmap.doctrine.contains("war", ignoreCase = true)) {
            return AgentStrategistRefreshRequest(
                urgency = "emergency",
                reason = requestedReason.ifBlank { "War or active hostilities invalidate the current peacetime roadmap." },
            )
        }
        if (primaryThreat?.threatLevel == "critical" &&
            !roadmap.doctrine.startsWith("deny_rival_", ignoreCase = true) &&
            !roadmap.doctrine.contains("war", ignoreCase = true)
        ) {
            return AgentStrategistRefreshRequest(
                urgency = "emergency",
                reason = requestedReason.ifBlank { "${primaryThreat.civName} is a critical rival threat and the roadmap should be reconsidered immediately." },
            )
        }
        if (empireObservation.gameContext.contactComplete && roadmap.doctrine.contains("scout", ignoreCase = true)) {
            return AgentStrategistRefreshRequest(
                urgency = "emergency",
                reason = requestedReason.ifBlank { "Full rival contact is already complete, so the scouting-focused roadmap is stale." },
            )
        }
        if (requested != null && requested.urgency.equals("emergency", ignoreCase = true)) {
            return AgentStrategistRefreshRequest(
                urgency = "emergency",
                reason = requestedReason.ifBlank { "The tactical planner detected a strategic emergency that the roadmap does not cover well." },
            )
        }
        return null
    }

    private fun normalizeStrategicPosture(
        memory: AgentMemory,
        observation: AgentObservation,
        empireObservation: AgentEmpireObservation,
        turn: Int,
    ): StrategicPostureMemory {
        val posture = memory.strategicPosture
        val roadmap = memory.strategicRoadmap.takeIf { it.doctrine.isNotBlank() }
        if (roadmap != null) {
            return roadmapDrivenStrategicPosture(
                previous = posture,
                roadmap = roadmap,
                observation = observation,
                empireObservation = empireObservation,
                plan = null,
                turn = turn,
            )
        }
        val primaryThreat = empireObservation.victoryThreats.firstOrNull()
        val doctrineSnapshot = chooseDoctrine(posture, observation, empireObservation, turn)
        val memo = buildStrategicMemo(doctrineSnapshot, observation, empireObservation)
        val focusSource = posture.copy(doctrine = doctrineSnapshot.doctrine)
        if (posture.mode.isNotBlank()) {
            return posture.copy(
                focus = buildStrategicFocus(focusSource, observation, empireObservation, null),
                gameArchetype = empireObservation.gameContext.archetype,
                doctrine = doctrineSnapshot.doctrine,
                phase = doctrineSnapshot.phase,
                victoryGoal = empireObservation.victoryGoal ?: posture.victoryGoal,
                rivalCiv = primaryThreat?.civName ?: posture.rivalCiv,
                rivalVictoryGoal = primaryThreat?.likelyVictoryType ?: posture.rivalVictoryGoal,
                turnThesis = memo.thesis,
                commitments = ArrayList(memo.commitments),
                watchOuts = ArrayList(memo.watchOuts),
                sinceTurn = posture.sinceTurn.takeIf { it > 0 } ?: turn,
                lastUpdatedTurn = turn,
            )
        }
        return deriveStrategicPosture(AgentMemory(strategicPosture = StrategicPostureMemory()), observation, empireObservation, null, turn)
    }

    private fun deriveStrategicPosture(
        previousMemory: AgentMemory,
        observation: AgentObservation,
        empireObservation: AgentEmpireObservation,
        plan: AgentActionPlan?,
        turn: Int,
    ): StrategicPostureMemory {
        val previous = previousMemory.strategicPosture
        val roadmap = previousMemory.strategicRoadmap.takeIf { it.doctrine.isNotBlank() }
        if (roadmap != null) {
            return roadmapDrivenStrategicPosture(
                previous = previous,
                roadmap = roadmap,
                observation = observation,
                empireObservation = empireObservation,
                plan = plan,
                turn = turn,
            )
        }
        val peacefulSnowballWindow = isPeacefulSnowballWindow(observation, turn)
        val primaryThreat = empireObservation.victoryThreats.firstOrNull()
        val lowContact = empireObservation.macroFacts.any { it.category == "contact" && it.severity in setOf("warning", "critical") }
        val smallEmpireForRace = empireObservation.macroFacts.any { it.category == "expansion" && it.severity in setOf("warning", "critical") }
        val weakMilitaryFloor = empireObservation.macroFacts.any { it.category == "war" && it.headline.contains("Military floor", ignoreCase = true) }
        val goldTempoPressure = empireObservation.macroFacts.any { it.category == "economy" && it.headline.contains("gold reserve", ignoreCase = true) }
        val victoryMode = victoryMode(empireObservation.victoryGoal, empireObservation.victoryFocus)
        val mode = when {
            peacefulSnowballWindow -> "peaceful_snowball"
            observation.empireSummary.visibleHostileUnits > 0 ||
                observation.citiesNeedingAttention.any { it.nearbyHostileUnits > 0 || it.nearbyHostileCities > 0 } -> "defend_and_stabilize"
            lowContact -> "expand_contact_and_scout"
            weakMilitaryFloor -> "build_military_floor"
            smallEmpireForRace -> "expand_empire_for_victory"
            goldTempoPressure -> "convert_gold_to_tempo"
            primaryThreat?.threatLevel == "critical" -> "deny_rival_victory"
            victoryMode != null -> victoryMode
            observation.empireSummary.settlersReady > 0 ||
                observation.opportunities.any { it.looksLikeSettlementOpportunity() } -> "expand_safely"
            observation.empireSummary.workersReady > 0 &&
                observation.opportunities.any { it.looksLikeImprovementOpportunity() } -> "improve_infrastructure"
            observation.empireSummary.citiesNeedingProductionChoice > 0 -> "develop_cities"
            else -> previous.mode.ifBlank { "stabilize_empire" }
        }
        val doctrineSnapshot = chooseDoctrine(previous, observation, empireObservation, turn)
        val memo = buildStrategicMemo(doctrineSnapshot, observation, empireObservation)
        val focusSource = previous.copy(doctrine = doctrineSnapshot.doctrine)

        return StrategicPostureMemory(
            mode = mode,
            focus = buildStrategicFocus(focusSource, observation, empireObservation, plan),
            gameArchetype = empireObservation.gameContext.archetype,
            doctrine = doctrineSnapshot.doctrine,
            phase = doctrineSnapshot.phase,
            victoryGoal = empireObservation.victoryGoal ?: previous.victoryGoal,
            rivalCiv = primaryThreat?.civName ?: previous.rivalCiv,
            rivalVictoryGoal = primaryThreat?.likelyVictoryType ?: previous.rivalVictoryGoal,
            turnThesis = memo.thesis,
            commitments = ArrayList(memo.commitments),
            watchOuts = ArrayList(memo.watchOuts),
            sinceTurn = if (mode == previous.mode && previous.sinceTurn > 0) previous.sinceTurn else turn,
            lastUpdatedTurn = turn,
        )
    }

    private fun cloneRoadmap(roadmap: AgentStrategicRoadmapMemory): AgentStrategicRoadmapMemory {
        return roadmap.copy(
            midTermGoals = ArrayList(roadmap.midTermGoals),
            mustMaintain = ArrayList(roadmap.mustMaintain),
            watchOuts = ArrayList(roadmap.watchOuts),
            switchTriggers = ArrayList(roadmap.switchTriggers),
        )
    }

    private fun roadmapDrivenStrategicPosture(
        previous: StrategicPostureMemory,
        roadmap: AgentStrategicRoadmapMemory,
        observation: AgentObservation,
        empireObservation: AgentEmpireObservation,
        plan: AgentActionPlan?,
        turn: Int,
    ): StrategicPostureMemory {
        val primaryThreat = empireObservation.victoryThreats.firstOrNull()
        val mode = when {
            observation.empireSummary.visibleHostileUnits > 0 ||
                observation.citiesNeedingAttention.any { it.nearbyHostileUnits > 0 || it.nearbyHostileCities > 0 } -> "defend_and_stabilize"
            observation.empireSummary.settlersReady > 0 ||
                observation.opportunities.any { it.looksLikeSettlementOpportunity() } -> "expand_safely"
            observation.empireSummary.workersReady > 0 &&
                observation.opportunities.any { it.looksLikeImprovementOpportunity() } -> "improve_infrastructure"
            else -> previous.mode.ifBlank { "stabilize_empire" }
        }
        val focusSource = previous.copy(
            doctrine = roadmap.doctrine,
            victoryGoal = roadmap.winPath,
            turnThesis = roadmap.thesis,
            commitments = ArrayList((roadmap.midTermGoals + roadmap.mustMaintain).take(4)),
            watchOuts = ArrayList(roadmap.watchOuts.take(3)),
        )
        return StrategicPostureMemory(
            mode = mode,
            focus = buildStrategicFocus(focusSource, observation, empireObservation, plan),
            gameArchetype = roadmap.gameArchetype.ifBlank { empireObservation.gameContext.archetype },
            doctrine = roadmap.doctrine,
            phase = roadmap.phase.ifBlank { previous.phase.ifBlank { "opener" } },
            victoryGoal = roadmap.winPath ?: empireObservation.victoryGoal ?: previous.victoryGoal,
            rivalCiv = primaryThreat?.civName ?: previous.rivalCiv,
            rivalVictoryGoal = primaryThreat?.likelyVictoryType ?: previous.rivalVictoryGoal,
            turnThesis = roadmap.thesis ?: previous.turnThesis,
            commitments = ArrayList((roadmap.midTermGoals + roadmap.mustMaintain).take(4)),
            watchOuts = ArrayList(
                (roadmap.watchOuts + listOfNotNull(primaryThreat?.takeIf { it.threatLevel == "critical" }?.let { "${it.civName} is an urgent rival." }))
                    .take(3)
            ),
            sinceTurn = if (previous.doctrine == roadmap.doctrine && previous.sinceTurn > 0) previous.sinceTurn else turn,
            lastUpdatedTurn = turn,
        )
    }

    private fun buildStrategicFocus(
        previous: StrategicPostureMemory,
        observation: AgentObservation,
        empireObservation: AgentEmpireObservation,
        plan: AgentActionPlan?,
    ): ArrayList<String> {
        val focus = linkedSetOf<String>()
        previous.doctrine?.takeIf { it.isNotBlank() }?.let { focus += "Doctrine: $it" }
        empireObservation.victoryGoal?.let { goal ->
            focus += "Pursue $goal victory"
        }
        empireObservation.victoryThreats.firstOrNull()?.let { threat ->
            val prefix = if (threat.threatLevel == "critical") "Urgent" else "Watch"
            focus += "$prefix ${threat.civName} ${threat.likelyVictoryType.lowercase()} push"
        }
        empireObservation.macroFacts
            .filter { it.severity in setOf("warning", "critical") }
            .take(2)
            .forEach { focus += it.headline }
        observation.priorityFacts.take(1).forEach { focus += it.headline }
        observation.opportunities.take(1).forEach { focus += it.headline }
        previous.focus
            .take(2)
            .filter { it.isNotBlank() && focus.size < 4 }
            .forEach { focus += it }
        plan?.notes
            ?.trim()
            ?.takeIf { it.isNotEmpty() && shouldPersistStrategicFocusNote(it) }
            ?.let { focus += it.take(120) }
        return ArrayList(focus.take(4))
    }

    private data class DoctrineSnapshot(
        val doctrine: String,
        val phase: String,
    )

    private data class StrategicMemo(
        val thesis: String?,
        val commitments: List<String>,
        val watchOuts: List<String>,
    )

    private fun chooseDoctrine(
        previous: StrategicPostureMemory,
        observation: AgentObservation,
        empireObservation: AgentEmpireObservation,
        turn: Int,
    ): DoctrineSnapshot {
        val primaryThreat = empireObservation.victoryThreats.firstOrNull()
        val gameContext = empireObservation.gameContext
        val proposed = when {
            observation.empireSummary.isAtWar || observation.empireSummary.visibleHostileUnits > 0 -> "war_execution"
            gameContext.duelLike && gameContext.mapType.equals("Pangaea", ignoreCase = true) && turn < 150 -> "duel_expansion_then_pressure"
            primaryThreat?.threatLevel == "critical" && primaryThreat.likelyVictoryType.equals("Scientific", ignoreCase = true) -> "deny_rival_science"
            primaryThreat?.threatLevel == "critical" && primaryThreat.likelyVictoryType.equals("Cultural", ignoreCase = true) -> "deny_rival_culture"
            primaryThreat?.threatLevel == "critical" && primaryThreat.likelyVictoryType.equals("Domination", ignoreCase = true) -> "deny_rival_domination"
            empireObservation.victoryGoal.equals("Scientific", ignoreCase = true) ->
                if (primaryThreat?.threatLevel == "critical") "science_race_with_military_floor" else "science_race"
            empireObservation.victoryGoal.equals("Cultural", ignoreCase = true) -> "culture_push"
            empireObservation.victoryGoal.equals("Domination", ignoreCase = true) -> "domination_build_up"
            empireObservation.victoryGoal.equals("Diplomatic", ignoreCase = true) -> "diplomatic_setup"
            else -> previous.doctrine ?: previous.mode.ifBlank { "stabilize_empire" }
        }

        val doctrine = when {
            previous.doctrine.isNullOrBlank() -> proposed
            previous.doctrine == proposed -> proposed
            shouldSwitchDoctrine(previous, proposed, observation, empireObservation, turn) -> proposed
            else -> previous.doctrine
        }.orEmpty()

        return DoctrineSnapshot(
            doctrine = doctrine,
            phase = chooseDoctrinePhase(doctrine, observation, empireObservation, turn),
        )
    }

    private fun shouldSwitchDoctrine(
        previous: StrategicPostureMemory,
        proposed: String,
        observation: AgentObservation,
        empireObservation: AgentEmpireObservation,
        turn: Int,
    ): Boolean {
        val current = previous.doctrine.orEmpty()
        if (current.isBlank()) return true
        if (current == proposed) return true
        if (turn - previous.lastUpdatedTurn >= doctrineReviewCadenceTurns) return true
        if (observation.empireSummary.isAtWar && !current.contains("war")) return true
        if (empireObservation.gameContext.contactComplete && current.contains("scout")) return true
        val primaryThreat = empireObservation.victoryThreats.firstOrNull()
        if (primaryThreat?.threatLevel == "critical" && !current.startsWith("deny_rival_") && !current.contains("military")) return true
        return false
    }

    private fun chooseDoctrinePhase(
        doctrine: String,
        observation: AgentObservation,
        empireObservation: AgentEmpireObservation,
        turn: Int,
    ): String {
        val desiredCityFloor = desiredCityFloor(empireObservation.gameContext, turn)
        return when {
            turn < 60 -> "opener"
            observation.empireSummary.cityCount < desiredCityFloor -> "expand"
            empireObservation.victoryThreats.firstOrNull()?.threatLevel == "critical" -> "contest_rival"
            doctrine.contains("war") || doctrine.contains("domination") -> "pressure"
            doctrine.contains("science") || doctrine.contains("culture") -> "conversion"
            else -> "stabilize"
        }
    }

    private fun buildStrategicMemo(
        doctrineSnapshot: DoctrineSnapshot,
        observation: AgentObservation,
        empireObservation: AgentEmpireObservation,
    ): StrategicMemo {
        val primaryThreat = empireObservation.victoryThreats.firstOrNull()
        val gameContext = empireObservation.gameContext
        val commitments = linkedSetOf<String>()
        val watchOuts = linkedSetOf<String>()
        val desiredCityFloor = desiredCityFloor(gameContext, observation.turn)

        if (gameContext.duelLike) {
            commitments += "Treat this as a known-rival duel and prioritize tempo over broad diplomacy."
        }
        if (observation.empireSummary.cityCount < desiredCityFloor) {
            commitments += "Grow toward at least $desiredCityFloor productive cities if land is still available."
        }
        if (empireObservation.macroFacts.any { it.category == "war" && it.headline.contains("Military floor", ignoreCase = true) }) {
            commitments += "Raise the military floor before more worker polish."
        }
        if (empireObservation.macroFacts.any { it.category == "economy" && it.headline.contains("gold reserve", ignoreCase = true) }) {
            commitments += "Convert spare gold into city tempo, upgrades, or other immediate gains."
        }
        if (gameContext.contactComplete) {
            watchOuts += "Contact is already complete; extra scouting has lower value now."
        }
        if (primaryThreat != null) {
            watchOuts += "${primaryThreat.civName} is the main rival on a ${primaryThreat.likelyVictoryType.lowercase()} path."
        }
        if (observation.priorityFacts.count { it.category == "tiles" } >= 3) {
            watchOuts += "Do not let worker upkeep crowd out higher-leverage empire actions."
        }

        val thesis = when (doctrineSnapshot.doctrine) {
            "duel_expansion_then_pressure" -> "Use the small duel map to turn expansion and production into pressure on the only rival."
            "science_race", "science_race_with_military_floor" ->
                "Convert the empire into a consistent science race while keeping enough force to contest the rival."
            "culture_push" -> "Keep the culture race coherent, but do not ignore military or expansion floors."
            "domination_build_up", "deny_rival_domination" -> "Turn production, positioning, and military floor into decisive pressure on the rival."
            "deny_rival_science" -> "Slow the rival science snowball while converting our own cities and gold into catch-up tempo."
            "deny_rival_culture" -> "Contest the rival cultural lead with stronger empire tempo and enough force to punish greed."
            "war_execution" -> "Execute wartime priorities cleanly and stop low-value peacetime upkeep from stealing attention."
            else -> "Keep the empire on a coherent win path instead of reacting only to local upkeep."
        }

        return StrategicMemo(
            thesis = thesis,
            commitments = commitments.take(4),
            watchOuts = watchOuts.take(3),
        )
    }

    private fun desiredCityFloor(gameContext: AgentPublicGameContextObservation, turn: Int): Int {
        return when {
            gameContext.duelLike && gameContext.mapSize == "Tiny" && turn >= 120 -> 4
            gameContext.duelLike && gameContext.mapSize == "Tiny" -> 3
            gameContext.expansionWindow == "narrow" -> 4
            gameContext.expansionWindow == "medium" -> 5
            else -> 6
        }
    }

    private fun victoryMode(victoryGoal: String?, victoryFocus: String?): String? {
        return when (victoryGoal?.lowercase()) {
            "scientific" -> "science_race"
            "cultural" -> "culture_push"
            "domination" -> "domination_build_up"
            "diplomatic" -> "diplomatic_setup"
            else -> when (victoryFocus?.lowercase()) {
                "science" -> "science_race"
                "culture" -> "culture_push"
                "military" -> "domination_build_up"
                "citystates", "gold" -> "diplomatic_setup"
                "faith" -> "faith_push"
                else -> null
            }
        }
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

    private fun deriveCityIntents(
        observation: AgentObservation,
        plan: AgentActionPlan,
        turn: Int,
    ): ArrayList<CityIntentMemory> {
        val cityByKey = observation.citiesNeedingAttention.associateBy { cityKey(it.x, it.y) }
        val intentsByKey = linkedMapOf<String, CityIntentMemory>()

        plan.actions
            .filterIsInstance<AgentActionCommand.CityChooseConstruction>()
            .forEach { action ->
                val city = cityByKey[cityKey(action.cityX, action.cityY)]
                val intent = if (city != null && (city.nearbyHostileUnits > 0 || city.nearbyHostileCities > 0)) {
                    "hold_front_build"
                } else {
                    "develop_city"
                }
                intentsByKey[cityKey(action.cityX, action.cityY)] = CityIntentMemory(
                    cityX = action.cityX,
                    cityY = action.cityY,
                    cityName = city?.name ?: "(${action.cityX}, ${action.cityY})",
                    intent = intent,
                    target = action.constructionName,
                    reasons = ArrayList(city?.reasons?.take(3) ?: emptyList()),
                    lastProgressTurn = turn,
                    staleAfterTurn = turn + cityIntentHorizonTurns,
                )
            }

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
                        reasons = ArrayList(listOf("Production plan selected") + (city?.reasons?.take(2) ?: emptyList())),
                        lastProgressTurn = turn,
                        staleAfterTurn = turn + cityIntentHorizonTurns,
                    )
                    "citypurchase" -> intentsByKey[cityKey(parsed.cityX, parsed.cityY)] = CityIntentMemory(
                        cityX = parsed.cityX,
                        cityY = parsed.cityY,
                        cityName = city?.name ?: "(${parsed.cityX}, ${parsed.cityY})",
                        intent = "invest_with_gold",
                        target = parsed.payload,
                        reasons = ArrayList(listOf("Gold purchase selected") + (city?.reasons?.take(2) ?: emptyList())),
                        lastProgressTurn = turn,
                        staleAfterTurn = turn + cityIntentHorizonTurns,
                    )
                    "cityfocus" -> intentsByKey[cityKey(parsed.cityX, parsed.cityY)] = CityIntentMemory(
                        cityX = parsed.cityX,
                        cityY = parsed.cityY,
                        cityName = city?.name ?: "(${parsed.cityX}, ${parsed.cityY})",
                        intent = "city_focus",
                        target = parsed.payload,
                        reasons = ArrayList(listOf("City focus adjusted") + (city?.reasons?.take(2) ?: emptyList())),
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
        val unitById = observation.actionableUnits.associateBy { it.id }
        val unitOptionByCandidateId = observation.actionableUnits
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
        unit: ActionableUnitObservation?,
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
        unit: ActionableUnitObservation?,
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
        unitById: Map<Int, ActionableUnitObservation>,
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
            observation.citiesNeedingAttention.any { city ->
                city.topConstructionChoices.any { it == "Worker" || it == "Settler" || it == "Granary" || it == "Monument" }
            }
    }

    private fun isPeacefulSnowballWindow(unit: ActionableUnitObservation, turn: Int?): Boolean {
        if (turn != null && turn > 45) return false
        if (unit.nearbyHostileUnits > 0 || unit.nearbyHostileCities > 0) return false
        return true
    }
}
