package com.unciv.logic.automation.agent

import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Civilization

data class ExecutionOptions(
    val stopAfterCityCreation: Boolean = false,
)

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
        val touchedUnitIds = hashSetOf<Int>()
        val initialCityCount = civInfo.cities.size
        val frozenEmpireContext = AgentEmpireObservationBuilder.build(civInfo, civInfo.agentMemory.clone())
        val frozenCityOptionContext = AgentCityOptionBuilder.build(civInfo, civInfo.agentMemory)
        // Freeze unit-option candidates for the duration of this pass so later
        // actions don't invalidate earlier prompt-visible candidate IDs merely
        // because the shortlist was regenerated from a mutated board state.
        val frozenUnitOptionContext = AgentUnitOptionBuilder.build(civInfo, civInfo.agentMemory)

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

                    val candidate = frozenEmpireContext.candidates[action.candidateId]
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

                    val candidate = frozenCityOptionContext.candidates[action.candidateId]
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
                    if (!touchedUnitIds.add(unitId)) {
                        rejected++
                        outcomes += ActionOutcome(
                            commandType = "select_unit_option",
                            status = ActionStatus.Rejected,
                            reason = "Unit option rejected: only one candidate-selected action is allowed per unit per turn",
                            candidateId = action.candidateId,
                            unitId = unitId,
                        )
                        continue
                    }

                    val candidate = frozenUnitOptionContext.candidates[action.candidateId]
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

                    if (success || candidate.allowNoImmediateStateChange) {
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

    private fun parseUnitIdFromCandidateId(candidateId: String): Int? {
        return candidateId.substringAfter(':', "").substringBefore(':').toIntOrNull()
    }
}
