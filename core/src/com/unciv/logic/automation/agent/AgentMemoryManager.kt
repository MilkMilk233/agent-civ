package com.unciv.logic.automation.agent

import com.unciv.logic.civilization.Civilization
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object AgentMemoryManager {
    private const val recentFailureWindowTurns = 8
    private const val maxRecentFailures = 8
    private const val cityIntentHorizonTurns = 5
    private const val unitAssignmentHorizonTurns = 4

    private val json = Json {
        prettyPrint = false
        explicitNulls = false
    }

    fun memoryJson(memory: AgentMemory): String = json.encodeToString(memory)

    fun prepareForTurn(civInfo: Civilization, observation: AgentObservation): AgentMemory {
        val turn = civInfo.gameInfo.turns
        val validCities = civInfo.cities.associateBy { cityKey(it.location.x, it.location.y) }
        val validUnits = civInfo.units.getCivUnits().associateBy { it.id }
        val existing = civInfo.agentMemory.clone()

        val prepared = AgentMemory(
            strategicPosture = normalizeStrategicPosture(existing.strategicPosture, observation, turn),
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
        startingMemory: AgentMemory,
        plan: AgentActionPlan?,
        report: AgentActionExecutor.ExecutionReport?,
        usedLegacyFallback: Boolean,
        fallbackReason: String? = null,
        validationFailures: List<AgentActionExecutor.ActionOutcome> = emptyList(),
        intentionalNoOp: Boolean = false,
    ): AgentMemory {
        val turn = civInfo.gameInfo.turns
        val strategicPosture = deriveStrategicPosture(startingMemory.strategicPosture, observation, plan, turn)
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
            cityIntents = ArrayList(preservedCityIntents + newCityIntents),
            unitAssignments = ArrayList(preservedUnitAssignments + newUnitAssignments),
            recentFailures = recentFailures,
        )
        civInfo.agentMemory = updated.clone()
        return updated
    }

    private fun normalizeStrategicPosture(
        posture: StrategicPostureMemory,
        observation: AgentObservation,
        turn: Int,
    ): StrategicPostureMemory {
        if (posture.mode.isNotBlank()) {
            return posture.copy(
                focus = ArrayList(posture.focus),
                sinceTurn = posture.sinceTurn.takeIf { it > 0 } ?: turn,
                lastUpdatedTurn = posture.lastUpdatedTurn.takeIf { it > 0 } ?: turn,
            )
        }
        return deriveStrategicPosture(StrategicPostureMemory(), observation, null, turn)
    }

    private fun deriveStrategicPosture(
        previous: StrategicPostureMemory,
        observation: AgentObservation,
        plan: AgentActionPlan?,
        turn: Int,
    ): StrategicPostureMemory {
        val mode = when {
            observation.empireSummary.visibleHostileUnits > 0 ||
                observation.citiesNeedingAttention.any { it.nearbyHostileUnits > 0 || it.nearbyHostileCities > 0 } -> "defend_and_stabilize"
            observation.empireSummary.settlersReady > 0 ||
                observation.opportunities.any { it.looksLikeSettlementOpportunity() } -> "expand_safely"
            observation.empireSummary.workersReady > 0 &&
                observation.opportunities.any { it.looksLikeImprovementOpportunity() } -> "improve_infrastructure"
            observation.empireSummary.citiesNeedingProductionChoice > 0 -> "develop_cities"
            else -> previous.mode.ifBlank { "stabilize_empire" }
        }

        val focus = linkedSetOf<String>()
        observation.priorityFacts.take(2).forEach { focus += it.headline }
        observation.opportunities.take(1).forEach { focus += it.headline }
        plan?.notes?.trim()?.takeIf { it.isNotEmpty() }?.let { focus += it.take(120) }

        return StrategicPostureMemory(
            mode = mode,
            focus = ArrayList(focus.take(3)),
            sinceTurn = if (mode == previous.mode && previous.sinceTurn > 0) previous.sinceTurn else turn,
            lastUpdatedTurn = turn,
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
                    staleAfterTurn = turn + cityIntentHorizonTurns,
                )
            }

        return ArrayList(intentsByKey.values)
    }

    private fun deriveUnitAssignments(
        observation: AgentObservation,
        plan: AgentActionPlan,
        turn: Int,
    ): ArrayList<UnitAssignmentMemory> {
        val unitById = observation.actionableUnits.associateBy { it.id }
        val commandsByUnit = linkedMapOf<Int, MutableList<AgentActionCommand>>()

        for (action in plan.actions.sortedBy { it.priority }) {
            when (action) {
                is AgentActionCommand.UnitMove -> commandsByUnit.getOrPut(action.unitId) { arrayListOf() }.add(action)
                is AgentActionCommand.UnitAction -> commandsByUnit.getOrPut(action.unitId) { arrayListOf() }.add(action)
                else -> Unit
            }
        }

        val assignments = arrayListOf<UnitAssignmentMemory>()
        for ((unitId, commands) in commandsByUnit) {
            val unit = unitById[unitId]
            val lastMove = commands.filterIsInstance<AgentActionCommand.UnitMove>().lastOrNull()
            val lastAction = commands.filterIsInstance<AgentActionCommand.UnitAction>().lastOrNull()
            val targetX = lastMove?.destinationX ?: unit?.x
            val targetY = lastMove?.destinationY ?: unit?.y
            val assignment = classifyAssignment(unit, lastAction?.actionType, targetX, targetY, turn)
            assignments += assignment.copy(unitId = unitId, unitName = unit?.name ?: assignment.unitName)
        }

        return assignments
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
            staleAfterTurn = turn + unitAssignmentHorizonTurns,
        )
    }

    private fun cityKey(x: Int, y: Int): String = "$x,$y"

    private fun ObservationFact.looksLikeSettlementOpportunity(): Boolean {
        val haystack = "${headline.lowercase()} ${detail.lowercase()} ${category.lowercase()}"
        return "settle" in haystack || "city site" in haystack || "found city" in haystack
    }

    private fun ObservationFact.looksLikeImprovementOpportunity(): Boolean {
        val haystack = "${headline.lowercase()} ${detail.lowercase()} ${category.lowercase()}"
        return "improve" in haystack || "repair" in haystack || "resource" in haystack || "worker" in haystack
    }
}
