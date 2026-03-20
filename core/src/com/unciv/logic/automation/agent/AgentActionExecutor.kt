package com.unciv.logic.automation.agent

import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.models.UnitActionType
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActions

class AgentActionExecutor {
    enum class ActionStatus {
        Executed,
        Rejected,
    }

    data class ActionOutcome(
        val commandType: String,
        val status: ActionStatus,
        val reason: String,
        val candidateId: String? = null,
        val unitId: Int? = null,
        val cityX: Int? = null,
        val cityY: Int? = null,
        val actionType: String? = null,
        val constructionName: String? = null,
        val domain: AgentControlDomain? = AgentControlDomain.fromOutcome(commandType, candidateId),
    )

    data class ExecutionReport(
        val executedActions: Int,
        val rejectedActions: Int,
        val outcomes: List<ActionOutcome>,
    )

    enum class ValidationStatus {
        Valid,
        Invalid,
        NoOp,
    }

    data class ValidationReport(
        val status: ValidationStatus,
        val outcomes: List<ActionOutcome>,
    ) {
        val rejectedOutcomes: List<ActionOutcome> get() = outcomes.filter { it.status == ActionStatus.Rejected }
        val executedActionsPreview: Int get() = outcomes.count { it.status == ActionStatus.Executed && it.commandType != "end_turn" }
        val rejectedActionsPreview: Int get() = rejectedOutcomes.size
    }

    fun validate(civInfo: Civilization, plan: AgentActionPlan): ValidationReport {
        val actionableCommands = plan.actions.filterNot { it is AgentActionCommand.EndTurn }
        if (actionableCommands.isEmpty()) {
            return ValidationReport(
                status = ValidationStatus.NoOp,
                outcomes = emptyList(),
            )
        }

        return runCatching {
            val cloneGame = civInfo.gameInfo.clone().also(GameInfo::setTransients)
            val cloneCiv = cloneGame.getCivilization(civInfo.civID)
            val preview = executeInternal(cloneCiv, plan, recordActionErrors = false)
            ValidationReport(
                status = if (preview.rejectedActions > 0) ValidationStatus.Invalid else ValidationStatus.Valid,
                outcomes = preview.outcomes,
            )
        }.getOrElse { ex ->
            ValidationReport(
                status = ValidationStatus.Invalid,
                outcomes = listOf(
                    ActionOutcome(
                        commandType = "validation",
                        status = ActionStatus.Rejected,
                        reason = "Plan validation failed: ${ex.message ?: ex::class.simpleName.orEmpty()}",
                    )
                ),
            )
        }
    }

    fun execute(civInfo: Civilization, plan: AgentActionPlan): ExecutionReport {
        return executeInternal(civInfo, plan, recordActionErrors = true)
    }

    private fun executeInternal(
        civInfo: Civilization,
        plan: AgentActionPlan,
        recordActionErrors: Boolean,
    ): ExecutionReport {
        var executed = 0
        var rejected = 0
        val outcomes = arrayListOf<ActionOutcome>()
        val sortedActions = plan.actions.sortedBy { it.priority }
        val usedEmpireCandidateIds = hashSetOf<String>()
        val usedCityCandidateIds = hashSetOf<String>()
        val usedUnitCandidateIds = hashSetOf<String>()

        for (action in sortedActions) {
            when (action) {
                is AgentActionCommand.SelectEmpireOption -> {
                    if (!usedEmpireCandidateIds.add(action.candidateId)) {
                        rejected++
                        outcomes += ActionOutcome(
                            commandType = "select_empire_option",
                            status = ActionStatus.Rejected,
                            reason = "Empire option rejected: candidate selected more than once",
                            candidateId = action.candidateId,
                        )
                        continue
                    }

                    val context = AgentEmpireObservationBuilder.build(civInfo, civInfo.agentMemory.clone())
                    val candidate = context.candidates[action.candidateId]
                    if (candidate == null) {
                        rejected++
                        outcomes += ActionOutcome(
                            commandType = "select_empire_option",
                            status = ActionStatus.Rejected,
                            reason = "Empire option rejected: candidate missing",
                            candidateId = action.candidateId,
                        )
                        continue
                    }

                    val invalidReason = candidate.validate(civInfo)
                    if (invalidReason != null) {
                        rejected++
                        outcomes += ActionOutcome(
                            commandType = "select_empire_option",
                            status = ActionStatus.Rejected,
                            reason = invalidReason,
                            candidateId = action.candidateId,
                        )
                        continue
                    }

                    val success = runCatching { candidate.execute(civInfo) }.getOrElse { ex ->
                        if (recordActionErrors) {
                            AgentObservability.record(
                                type = "plan_action_error",
                                message = "Empire action execution failed",
                                civName = civInfo.civName,
                                turn = civInfo.gameInfo.turns,
                                details = mapOf(
                                    "candidateId" to action.candidateId,
                                    "error" to (ex.message ?: ex::class.simpleName.orEmpty()),
                                ),
                            )
                        }
                        false
                    }

                    if (success) {
                        executed++
                        outcomes += ActionOutcome(
                            commandType = "select_empire_option",
                            status = ActionStatus.Executed,
                            reason = candidate.successMessage,
                            candidateId = action.candidateId,
                        )
                    } else {
                        rejected++
                        outcomes += ActionOutcome(
                            commandType = "select_empire_option",
                            status = ActionStatus.Rejected,
                            reason = "Empire option rejected: execution produced no state change",
                            candidateId = action.candidateId,
                        )
                    }
                }

                is AgentActionCommand.SelectCityOption -> {
                    if (!usedCityCandidateIds.add(action.candidateId)) {
                        rejected++
                        outcomes += ActionOutcome(
                            commandType = "select_city_option",
                            status = ActionStatus.Rejected,
                            reason = "City option rejected: candidate selected more than once",
                            candidateId = action.candidateId,
                        )
                        continue
                    }

                    val context = AgentCityOptionBuilder.build(civInfo)
                    val candidate = context.candidates[action.candidateId]
                    if (candidate == null) {
                        rejected++
                        outcomes += ActionOutcome(
                            commandType = "select_city_option",
                            status = ActionStatus.Rejected,
                            reason = "City option rejected: candidate missing",
                            candidateId = action.candidateId,
                        )
                        continue
                    }

                    val invalidReason = candidate.validate(civInfo)
                    if (invalidReason != null) {
                        rejected++
                        outcomes += ActionOutcome(
                            commandType = "select_city_option",
                            status = ActionStatus.Rejected,
                            reason = invalidReason,
                            candidateId = action.candidateId,
                        )
                        continue
                    }

                    val success = runCatching { candidate.execute(civInfo) }.getOrElse { ex ->
                        if (recordActionErrors) {
                            AgentObservability.record(
                                type = "plan_action_error",
                                message = "City option execution failed",
                                civName = civInfo.civName,
                                turn = civInfo.gameInfo.turns,
                                details = mapOf(
                                    "candidateId" to action.candidateId,
                                    "error" to (ex.message ?: ex::class.simpleName.orEmpty()),
                                ),
                            )
                        }
                        false
                    }

                    if (success) {
                        executed++
                        outcomes += ActionOutcome(
                            commandType = "select_city_option",
                            status = ActionStatus.Executed,
                            reason = candidate.successMessage,
                            candidateId = action.candidateId,
                        )
                    } else {
                        rejected++
                        outcomes += ActionOutcome(
                            commandType = "select_city_option",
                            status = ActionStatus.Rejected,
                            reason = "City option rejected: execution produced no state change",
                            candidateId = action.candidateId,
                        )
                    }
                }

                is AgentActionCommand.SelectUnitOption -> {
                    if (!usedUnitCandidateIds.add(action.candidateId)) {
                        rejected++
                        outcomes += ActionOutcome(
                            commandType = "select_unit_option",
                            status = ActionStatus.Rejected,
                            reason = "Unit option rejected: candidate selected more than once",
                            candidateId = action.candidateId,
                        )
                        continue
                    }

                    val context = AgentUnitOptionBuilder.build(civInfo, civInfo.agentMemory)
                    val candidate = context.candidates[action.candidateId]
                    if (candidate == null) {
                        rejected++
                        outcomes += ActionOutcome(
                            commandType = "select_unit_option",
                            status = ActionStatus.Rejected,
                            reason = "Unit option rejected: candidate missing",
                            candidateId = action.candidateId,
                        )
                        continue
                    }

                    val invalidReason = candidate.validate(civInfo)
                    if (invalidReason != null) {
                        rejected++
                        outcomes += ActionOutcome(
                            commandType = "select_unit_option",
                            status = ActionStatus.Rejected,
                            reason = invalidReason,
                            candidateId = action.candidateId,
                        )
                        continue
                    }

                    val success = runCatching { candidate.execute(civInfo) }.getOrElse { ex ->
                        if (recordActionErrors) {
                            AgentObservability.record(
                                type = "plan_action_error",
                                message = "Unit option execution failed",
                                civName = civInfo.civName,
                                turn = civInfo.gameInfo.turns,
                                details = mapOf(
                                    "candidateId" to action.candidateId,
                                    "error" to (ex.message ?: ex::class.simpleName.orEmpty()),
                                ),
                            )
                        }
                        false
                    }

                    if (success) {
                        executed++
                        outcomes += ActionOutcome(
                            commandType = "select_unit_option",
                            status = ActionStatus.Executed,
                            reason = candidate.successMessage,
                            candidateId = action.candidateId,
                        )
                    } else {
                        rejected++
                        outcomes += ActionOutcome(
                            commandType = "select_unit_option",
                            status = ActionStatus.Rejected,
                            reason = "Unit option rejected: execution produced no state change",
                            candidateId = action.candidateId,
                        )
                    }
                }

                is AgentActionCommand.UnitMove -> {
                    val unit = civInfo.units.getCivUnits().firstOrNull { it.id == action.unitId }
                    val destinationCoord = HexCoord(action.destinationX, action.destinationY)
                    val destination = if (destinationCoord in civInfo.gameInfo.tileMap) civInfo.gameInfo.tileMap[destinationCoord] else null
                    if (unit == null || destination == null || !unit.hasMovement() || !unit.movement.canReach(destination)) {
                        rejected++
                        val reason = when {
                            unit == null -> "Unit move rejected: unit missing"
                            destination == null -> "Unit move rejected: destination missing"
                            !unit.hasMovement() -> "Unit move rejected: unit has no movement"
                            else -> "Unit move rejected: destination not reachable"
                        }
                        outcomes += ActionOutcome(
                            commandType = "unit_move",
                            status = ActionStatus.Rejected,
                            reason = reason,
                            unitId = action.unitId,
                        )
                        continue
                    }

                    val before = unit.getTile().position
                    unit.movement.headTowards(destination)
                    val after = unit.getTile().position
                    if (after != before || after == destination.position) {
                        executed++
                        outcomes += ActionOutcome(
                            commandType = "unit_move",
                            status = ActionStatus.Executed,
                            reason = "Unit move executed",
                            unitId = action.unitId,
                        )
                    } else {
                        rejected++
                        outcomes += ActionOutcome(
                            commandType = "unit_move",
                            status = ActionStatus.Rejected,
                            reason = "Unit move rejected: movement produced no position change",
                            unitId = action.unitId,
                        )
                    }
                }

                is AgentActionCommand.UnitAction -> {
                    val unit = civInfo.units.getCivUnits().firstOrNull { it.id == action.unitId }
                    val actionType = runCatching { enumValueOf<UnitActionType>(action.actionType) }.getOrNull()
                    if (unit == null || actionType == null) {
                        rejected++
                        outcomes += ActionOutcome(
                            commandType = "unit_action",
                            status = ActionStatus.Rejected,
                            reason = if (unit == null) "Unit action rejected: unit missing" else "Unit action rejected: unknown action type",
                            unitId = action.unitId,
                            actionType = action.actionType,
                        )
                        continue
                    }

                    val invoked = runCatching {
                        UnitActions.invokeUnitAction(unit, actionType)
                    }.getOrElse { ex ->
                        if (recordActionErrors) {
                            AgentObservability.record(
                                type = "plan_action_error",
                                message = "Unit action execution failed",
                                civName = civInfo.civName,
                                turn = civInfo.gameInfo.turns,
                                details = mapOf(
                                    "unitId" to action.unitId.toString(),
                                    "actionType" to action.actionType,
                                    "error" to (ex.message ?: ex::class.simpleName.orEmpty()),
                                ),
                            )
                        }
                        false
                    }

                    if (invoked) {
                        executed++
                        outcomes += ActionOutcome(
                            commandType = "unit_action",
                            status = ActionStatus.Executed,
                            reason = "Unit action executed",
                            unitId = action.unitId,
                            actionType = action.actionType,
                        )
                    } else {
                        rejected++
                        outcomes += ActionOutcome(
                            commandType = "unit_action",
                            status = ActionStatus.Rejected,
                            reason = "Unit action rejected: invokeUnitAction returned false",
                            unitId = action.unitId,
                            actionType = action.actionType,
                        )
                    }
                }

                is AgentActionCommand.CityChooseConstruction -> {
                    val city = civInfo.cities.firstOrNull { it.location.x == action.cityX && it.location.y == action.cityY }
                    if (city == null) {
                        rejected++
                        outcomes += ActionOutcome(
                            commandType = "city_choose_construction",
                            status = ActionStatus.Rejected,
                            reason = "Construction rejected: city missing",
                            cityX = action.cityX,
                            cityY = action.cityY,
                            constructionName = action.constructionName,
                        )
                        continue
                    }

                    val construction = runCatching { city.cityConstructions.getConstruction(action.constructionName) }.getOrNull()
                    if (construction == null || !construction.isBuildable(city.cityConstructions)) {
                        rejected++
                        outcomes += ActionOutcome(
                            commandType = "city_choose_construction",
                            status = ActionStatus.Rejected,
                            reason = if (construction == null) {
                                "Construction rejected: unknown construction"
                            } else {
                                "Construction rejected: not buildable"
                            },
                            cityX = action.cityX,
                            cityY = action.cityY,
                            constructionName = action.constructionName,
                        )
                        continue
                    }

                    city.cityConstructions.setCurrentConstruction(action.constructionName)
                    executed++
                    outcomes += ActionOutcome(
                        commandType = "city_choose_construction",
                        status = ActionStatus.Executed,
                        reason = "Construction selected",
                        cityX = action.cityX,
                        cityY = action.cityY,
                        constructionName = action.constructionName,
                    )
                }

                is AgentActionCommand.EndTurn -> {
                    // no-op; included to make model output explicit
                    outcomes += ActionOutcome(
                        commandType = "end_turn",
                        status = ActionStatus.Executed,
                        reason = "End turn marker accepted",
                    )
                }
            }
        }

        return ExecutionReport(executedActions = executed, rejectedActions = rejected, outcomes = outcomes)
    }
}
