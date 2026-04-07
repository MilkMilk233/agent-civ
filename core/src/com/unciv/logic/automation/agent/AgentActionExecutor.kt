package com.unciv.logic.automation.agent

import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.models.UnitActionType
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActions

data class ExecutionOptions(
    val stopAfterCityCreation: Boolean = false,
)

class AgentActionExecutor {
    private enum class UnitCommandMode {
        CandidateOption,
        DirectControl,
    }

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
        val haltedForCityCreationBoundary: Boolean = false,
    )

    enum class ValidationStatus {
        Valid,
        Invalid,
        NoOp,
    }

    data class ValidationReport(
        val status: ValidationStatus,
        val outcomes: List<ActionOutcome>,
        val haltedForCityCreationBoundary: Boolean = false,
    ) {
        val rejectedOutcomes: List<ActionOutcome> get() = outcomes.filter { it.status == ActionStatus.Rejected }
        val executedActionsPreview: Int get() = outcomes.count { it.status == ActionStatus.Executed && it.commandType != "end_turn" }
        val rejectedActionsPreview: Int get() = rejectedOutcomes.size
    }

    fun validate(
        civInfo: Civilization,
        plan: AgentActionPlan,
        options: ExecutionOptions = ExecutionOptions(),
    ): ValidationReport {
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
            val preview = executeInternal(
                cloneCiv,
                plan,
                recordActionErrors = false,
                options = options,
            )
            ValidationReport(
                status = if (preview.rejectedActions > 0) ValidationStatus.Invalid else ValidationStatus.Valid,
                outcomes = preview.outcomes,
                haltedForCityCreationBoundary = preview.haltedForCityCreationBoundary,
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

    fun execute(
        civInfo: Civilization,
        plan: AgentActionPlan,
        options: ExecutionOptions = ExecutionOptions(),
    ): ExecutionReport {
        return executeInternal(civInfo, plan, recordActionErrors = true, options = options)
    }

    private fun executeInternal(
        civInfo: Civilization,
        plan: AgentActionPlan,
        recordActionErrors: Boolean,
        options: ExecutionOptions,
    ): ExecutionReport {
        var executed = 0
        var rejected = 0
        var haltedForCityCreationBoundary = false
        val outcomes = arrayListOf<ActionOutcome>()
        val sortedActions = plan.actions.sortedBy { it.priority }
        val usedEmpireCandidateIds = hashSetOf<String>()
        val usedCityCandidateIds = hashSetOf<String>()
        val usedUnitCandidateIds = hashSetOf<String>()
        val unitCommandModes = hashMapOf<Int, UnitCommandMode>()
        val initialCityCount = civInfo.cities.size

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

                    val unitId = parseUnitIdFromCandidateId(action.candidateId)
                    if (unitId == null) {
                        rejected++
                        outcomes += ActionOutcome(
                            commandType = "select_unit_option",
                            status = ActionStatus.Rejected,
                            reason = "Unit option rejected: malformed candidate id",
                            candidateId = action.candidateId,
                        )
                        continue
                    }
                    val commandConflict = registerUnitCommand(unitCommandModes, unitId, UnitCommandMode.CandidateOption)
                    if (commandConflict != null) {
                        rejected++
                        outcomes += ActionOutcome(
                            commandType = "select_unit_option",
                            status = ActionStatus.Rejected,
                            reason = commandConflict,
                            candidateId = action.candidateId,
                            unitId = unitId,
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
                            unitId = unitId,
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
                            unitId = unitId,
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
                            unitId = unitId,
                        )
                    } else {
                        rejected++
                        outcomes += ActionOutcome(
                            commandType = "select_unit_option",
                            status = ActionStatus.Rejected,
                            reason = "Unit option rejected: execution produced no state change",
                            candidateId = action.candidateId,
                            unitId = unitId,
                        )
                    }
                }

                is AgentActionCommand.UnitMove -> {
                    val commandConflict = registerUnitCommand(unitCommandModes, action.unitId, UnitCommandMode.DirectControl)
                    if (commandConflict != null) {
                        rejected++
                        outcomes += ActionOutcome(
                            commandType = "unit_move",
                            status = ActionStatus.Rejected,
                            reason = commandConflict,
                            unitId = action.unitId,
                        )
                        continue
                    }
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
                    val commandConflict = registerUnitCommand(unitCommandModes, action.unitId, UnitCommandMode.DirectControl)
                    if (commandConflict != null) {
                        rejected++
                        outcomes += ActionOutcome(
                            commandType = "unit_action",
                            status = ActionStatus.Rejected,
                            reason = commandConflict,
                            unitId = action.unitId,
                            actionType = action.actionType,
                        )
                        continue
                    }
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

                is AgentActionCommand.EndTurn -> {
                    // no-op; included to make model output explicit
                    outcomes += ActionOutcome(
                        commandType = "end_turn",
                        status = ActionStatus.Executed,
                        reason = "End turn marker accepted",
                    )
                }
            }

            if (options.stopAfterCityCreation && civInfo.cities.size > initialCityCount) {
                haltedForCityCreationBoundary = true
                break
            }
        }

        return ExecutionReport(
            executedActions = executed,
            rejectedActions = rejected,
            outcomes = outcomes,
            haltedForCityCreationBoundary = haltedForCityCreationBoundary,
        )
    }

    private fun registerUnitCommand(
        unitCommandModes: MutableMap<Int, UnitCommandMode>,
        unitId: Int,
        nextMode: UnitCommandMode,
    ): String? {
        val existingMode = unitCommandModes[unitId]
        if (existingMode == null) {
            unitCommandModes[unitId] = nextMode
            return null
        }
        return when {
            existingMode == UnitCommandMode.CandidateOption && nextMode == UnitCommandMode.CandidateOption ->
                "Unit option rejected: only one candidate-selected action is allowed per unit per turn"
            existingMode == UnitCommandMode.CandidateOption || nextMode == UnitCommandMode.CandidateOption ->
                "Unit action rejected: cannot mix select_unit_option with direct unit commands for the same unit in one turn"
            else -> null
        }
    }

    private fun parseUnitIdFromCandidateId(candidateId: String): Int? {
        return candidateId.substringAfter(':', "").substringBefore(':').toIntOrNull()
    }
}
