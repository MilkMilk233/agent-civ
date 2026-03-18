package com.unciv.logic.automation.agent

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.models.UnitActionType
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActions

class AgentActionExecutor {
    data class ExecutionReport(
        val executedActions: Int,
        val rejectedActions: Int,
    )

    fun execute(civInfo: Civilization, plan: AgentActionPlan): ExecutionReport {
        var executed = 0
        var rejected = 0

        val sortedActions = plan.actions.sortedBy { it.priority }
        val seenEndTurn = sortedActions.any { it is AgentActionCommand.EndTurn }

        for (action in sortedActions) {
            when (action) {
                is AgentActionCommand.UnitMove -> {
                    val unit = civInfo.units.getCivUnits().firstOrNull { it.id == action.unitId }
                    val destinationCoord = HexCoord(action.destinationX, action.destinationY)
                    val destination = if (destinationCoord in civInfo.gameInfo.tileMap) civInfo.gameInfo.tileMap[destinationCoord] else null
                    if (unit == null || destination == null || !unit.hasMovement() || !unit.movement.canReach(destination)) {
                        rejected++
                        continue
                    }

                    val before = unit.getTile().position
                    unit.movement.headTowards(destination)
                    val after = unit.getTile().position
                    if (after != before || after == destination.position) executed++ else rejected++
                }

                is AgentActionCommand.UnitAction -> {
                    val unit = civInfo.units.getCivUnits().firstOrNull { it.id == action.unitId }
                    val actionType = runCatching { enumValueOf<UnitActionType>(action.actionType) }.getOrNull()
                    if (unit == null || actionType == null) {
                        rejected++
                        continue
                    }

                    val invoked = runCatching {
                        UnitActions.invokeUnitAction(unit, actionType)
                    }.getOrElse { ex ->
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
                        false
                    }

                    if (invoked) executed++ else rejected++
                }

                is AgentActionCommand.CityChooseConstruction -> {
                    val city = civInfo.cities.firstOrNull { it.location.x == action.cityX && it.location.y == action.cityY }
                    if (city == null) {
                        rejected++
                        continue
                    }

                    val construction = runCatching { city.cityConstructions.getConstruction(action.constructionName) }.getOrNull()
                    if (construction == null || !construction.isBuildable(city.cityConstructions)) {
                        rejected++
                        continue
                    }

                    city.cityConstructions.setCurrentConstruction(action.constructionName)
                    executed++
                }

                is AgentActionCommand.EndTurn -> {
                    // no-op; included to make model output explicit
                }
            }
        }

        if (seenEndTurn && executed == 0 && rejected == 0) rejected++

        return ExecutionReport(executedActions = executed, rejectedActions = rejected)
    }
}
