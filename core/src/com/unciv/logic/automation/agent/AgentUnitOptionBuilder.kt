package com.unciv.logic.automation.agent

import com.unciv.Constants
import com.unciv.logic.automation.unit.CityLocationTileRanker
import com.unciv.logic.battle.AttackableTile
import com.unciv.logic.battle.Battle
import com.unciv.logic.battle.BattleDamage
import com.unciv.logic.battle.CityCombatant
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.battle.TargetHelper
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.tile.TileImprovement
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.UnitAction
import com.unciv.models.UnitActionType
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActions
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActionsFromUniques
import kotlin.math.roundToInt

object AgentUnitOptionBuilder {
    private const val maxAttackCandidatesPerUnit = 3
    private const val maxSettlerCandidatesPerUnit = 2
    private const val maxWorkerCandidatesPerUnit = 3
    private const val maxDirectActionCandidatesPerUnit = 3

    internal fun build(civInfo: Civilization, memory: AgentMemory): AgentUnitOptionContext {
        val candidates = LinkedHashMap<String, AgentUnitRuntimeCandidate>()
        val observationsByUnitId = LinkedHashMap<Int, MutableList<UnitOptionCandidateObservation>>()
        val attackContext = buildAttackConversionContext(civInfo, memory)

        for (unit in civInfo.units.getCivUnits().sortedBy { it.id }) {
            val observations = observationsByUnitId.getOrPut(unit.id) { arrayListOf() }
            val availableActions = UnitActions.getUnitActions(unit).filter { it.action != null }.toList()
            buildAttackCandidates(unit, attackContext).forEach { candidate ->
                candidates[candidate.observation.candidateId] = candidate
                observations += candidate.observation
            }
            buildOperationalCandidates(unit, civInfo, attackContext).forEach { candidate ->
                candidates[candidate.observation.candidateId] = candidate
                observations += candidate.observation
            }
            buildSettlerCandidates(unit).forEach { candidate ->
                candidates[candidate.observation.candidateId] = candidate
                observations += candidate.observation
            }
            buildWorkerCandidates(unit, memory.unitAssignments.firstOrNull { it.unitId == unit.id }).forEach { candidate ->
                candidates[candidate.observation.candidateId] = candidate
                observations += candidate.observation
            }
            buildRecoveryCandidates(unit).forEach { candidate ->
                candidates[candidate.observation.candidateId] = candidate
                observations += candidate.observation
            }
            buildDirectActionCandidates(unit, availableActions).forEach { candidate ->
                candidates[candidate.observation.candidateId] = candidate
                observations += candidate.observation
            }
        }

        return AgentUnitOptionContext(
            candidates = candidates,
            observationsByUnitId = observationsByUnitId,
        )
    }

    internal fun isAssignmentOnTarget(
        unit: MapUnit,
        assignment: UnitAssignmentMemory,
    ): Boolean {
        val targetX = assignment.targetX
        val targetY = assignment.targetY
        return when (assignment.role) {
            "auto_explore" -> unit.isExploring()
            "stage_outside_border" -> {
                resolveAssignmentObjective(unit, assignment)?.let { currentTileMatchesOperationalRole(unit, it, assignment.role) } == true
            }
            "reinforce_assault" -> {
                resolveAssignmentObjective(unit, assignment)?.let { currentTileMatchesOperationalRole(unit, it, assignment.role) } == true
            }
            "assault_city_ring" -> {
                resolveAssignmentObjective(unit, assignment)?.let { currentTileMatchesOperationalRole(unit, it, assignment.role) } == true
            }
            "recover_then_rejoin" -> {
                resolveAssignmentObjective(unit, assignment)?.let { currentTileMatchesOperationalRole(unit, it, assignment.role) } == true
            }
            "preserve_capture_unit" -> {
                resolveAssignmentObjective(unit, assignment)?.let { currentTileMatchesOperationalRole(unit, it, assignment.role) } == true
            }
            else -> targetX != null &&
                targetY != null &&
                unit.getTile().position.x == targetX &&
                unit.getTile().position.y == targetY
        }
    }

    internal fun hasGroundedAssignmentStep(
        unit: MapUnit,
        assignment: UnitAssignmentMemory,
    ): Boolean {
        val activeAssignment = refreshOperationalAssignment(unit, assignment) ?: return false
        return when (activeAssignment.role) {
            "auto_explore" -> unit.isExploring() || canInvokeAction(unit, UnitActionType.Explore)
            "move_to_tile" -> findAssignmentTargetTile(unit, activeAssignment)?.let { canAdvanceToward(unit, it) } == true
            "settle_city_site" -> {
                val targetTile = findAssignmentTargetTile(unit, activeAssignment) ?: return false
                if (unit.getTile() == targetTile) canInvokeAction(unit, UnitActionType.FoundCity) else canAdvanceToward(unit, targetTile)
            }
            "stage_outside_border" -> resolveAssignmentObjective(unit, activeAssignment)?.let { findStageOutsideBorderDestination(unit, it) != null } == true
            "reinforce_assault" -> resolveAssignmentObjective(unit, activeAssignment)?.let { hasReinforceAssaultStep(unit, it) } == true
            "assault_city_ring" -> resolveAssignmentObjective(unit, activeAssignment)?.let { hasAssaultCityStep(unit, it) } == true
            "recover_then_rejoin" -> resolveAssignmentObjective(unit, activeAssignment)?.let { hasRecoverThenRejoinStep(unit, it) } == true
            "preserve_capture_unit" -> resolveAssignmentObjective(unit, activeAssignment)?.let { hasPreserveCaptureStep(unit, it) } == true
            else -> false
        }
    }

    internal fun executeDeferredAssignmentStep(
        unit: MapUnit,
        assignment: UnitAssignmentMemory,
        reservationTracker: TheaterReservationTracker? = null,
    ): Boolean {
        if (!unit.hasMovement()) return false
        val activeAssignment = refreshOperationalAssignment(unit, assignment) ?: return false
        return when (activeAssignment.role) {
            "auto_explore" -> advanceAutoExploreAssignment(unit)
            "move_to_tile" -> {
                val targetTile = findAssignmentTargetTile(unit, activeAssignment) ?: return false
                moveTowardTile(unit, targetTile)
            }
            "settle_city_site" -> {
                val targetTile = findAssignmentTargetTile(unit, activeAssignment) ?: return false
                if (unit.getTile() == targetTile) {
                    UnitActions.invokeUnitAction(unit, UnitActionType.FoundCity)
                } else {
                    moveTowardTile(unit, targetTile)
                }
            }
            "stage_outside_border" -> resolveAssignmentObjective(unit, activeAssignment)?.let { objective ->
                findStageOutsideBorderDestination(unit, objective, reservationTracker)?.let { moveTowardTile(unit, it) }
            } ?: false
            "reinforce_assault" -> resolveAssignmentObjective(unit, activeAssignment)?.let { objective ->
                executeReinforceAssaultStep(unit, objective, reservationTracker)
            } ?: false
            "assault_city_ring" -> resolveAssignmentObjective(unit, activeAssignment)?.let { objective ->
                executeAssaultCityStep(unit, objective, preserveCaptor = false, reservationTracker = reservationTracker)
            } ?: false
            "recover_then_rejoin" -> resolveAssignmentObjective(unit, activeAssignment)?.let { objective ->
                executeRecoverThenRejoinStep(unit, objective, reservationTracker)
            } ?: false
            "preserve_capture_unit" -> resolveAssignmentObjective(unit, activeAssignment)?.let { objective ->
                executePreserveCaptureStep(unit, objective, reservationTracker)
            } ?: false
            else -> false
        }
    }

    private fun buildAttackCandidates(
        unit: MapUnit,
        attackContext: AttackConversionContext,
    ): List<AgentUnitRuntimeCandidate> {
        if (!unit.canAttack()) return emptyList()
        val combatant = MapUnitCombatant(unit)
        return TargetHelper.getAttackableEnemies(unit, unit.movement.getDistanceToTiles())
            .sortedByDescending { scoreAttackCandidate(combatant, it, attackContext) }
            .take(maxAttackCandidatesPerUnit)
            .mapNotNull { attackableTile ->
                val defender = attackableTile.combatant ?: return@mapNotNull null
                val targetTile = attackableTile.tileToAttack
                val fromTile = attackableTile.tileToAttackFrom
                val candidateId = buildString {
                    append("unitattack:")
                    append(unit.id)
                    append(':')
                    append(fromTile.position.x)
                    append(',')
                    append(fromTile.position.y)
                    append(':')
                    append(targetTile.position.x)
                    append(',')
                    append(targetTile.position.y)
                }
                val damageToDefender = BattleDamage.calculateDamageToDefender(combatant, defender, fromTile)
                val damageToAttacker = BattleDamage.calculateDamageToAttacker(combatant, defender, fromTile)
                AgentUnitRuntimeCandidate(
                    observation = UnitOptionCandidateObservation(
                        candidateId = candidateId,
                        category = "attack",
                        title = "${unit.name} #${unit.id} attack ${defender.getName()}",
                        detail = "Attack from (${fromTile.position.x}, ${fromTile.position.y}) into (${targetTile.position.x}, ${targetTile.position.y}), expected damage ${damageToDefender}/${damageToAttacker}.",
                    ),
                    validate = { currentCiv ->
                        val liveUnit = currentCiv.units.getCivUnits().firstOrNull { it.id == unit.id }
                            ?: return@AgentUnitRuntimeCandidate "Unit option rejected: unit missing"
                        if (!liveUnit.canAttack()) return@AgentUnitRuntimeCandidate "Unit option rejected: unit can no longer attack"
                        val liveAttackable = findAttackableTile(
                            liveUnit,
                            fromTile.position.x,
                            fromTile.position.y,
                            targetTile.position.x,
                            targetTile.position.y,
                        ) ?: return@AgentUnitRuntimeCandidate "Unit option rejected: attack is no longer available"
                        if (liveAttackable.combatant == null) return@AgentUnitRuntimeCandidate "Unit option rejected: target is gone"
                        null
                    },
                    execute = { currentCiv ->
                        val liveUnit = currentCiv.units.getCivUnits().firstOrNull { it.id == unit.id } ?: return@AgentUnitRuntimeCandidate false
                        val beforeAttacks = liveUnit.attacksThisTurn
                        val beforePosition = liveUnit.getTile().position
                        val liveAttackable = findAttackableTile(
                            liveUnit,
                            fromTile.position.x,
                            fromTile.position.y,
                            targetTile.position.x,
                            targetTile.position.y,
                        ) ?: return@AgentUnitRuntimeCandidate false
                        Battle.moveAndAttack(MapUnitCombatant(liveUnit), liveAttackable)
                        liveUnit.isDestroyed || liveUnit.attacksThisTurn > beforeAttacks || liveUnit.getTile().position != beforePosition
                    },
                    successMessage = "${unit.name} attacked ${defender.getName()}",
                )
            }
    }

    private fun buildOperationalCandidates(
        unit: MapUnit,
        civInfo: Civilization,
        attackContext: AttackConversionContext,
    ): List<AgentUnitRuntimeCandidate> {
        if (!unit.hasMovement() || !isOperationalCombatUnit(unit)) return emptyList()
        val objective = attackContext.objective ?: return emptyList()
        val objectiveTile = civInfo.gameInfo.tileMap[HexCoord(objective.x, objective.y)]
        val objectiveOwner = objectiveTile.getOwner()
        if (objectiveOwner == civInfo) return emptyList()

        val candidates = arrayListOf<AgentUnitRuntimeCandidate>()
        val currentDistance = axialDistance(unit.getTile().position.x, unit.getTile().position.y, objective.x, objective.y)
        val wartimeObjective = objectiveOwner != null && civInfo.isAtWarWith(objectiveOwner)
        if (objectiveOwner != null && !civInfo.isAtWarWith(objectiveOwner)) {
            buildStageOutsideBorderCandidate(unit, objective)?.let(candidates::add)
        }
        if (civInfo.isAtWar() || attackContext.objectivePressure) {
            buildReinforceAssaultCandidate(unit, objective)?.let(candidates::add)
        }
        if (wartimeObjective && currentDistance <= 5) {
            if (unit.health < 80) {
                buildRecoverThenRejoinCandidate(unit, objective)?.let(candidates::add)
            } else {
                buildAssaultCityRingCandidate(unit, objective)?.let(candidates::add)
                if (unit.baseUnit.isMelee() && unit.health >= 70) {
                    buildPreserveCaptureUnitCandidate(unit, objective)?.let(candidates::add)
                }
            }
        }
        return candidates
    }

    private fun buildStageOutsideBorderCandidate(
        unit: MapUnit,
        objective: ResolvedObjective,
    ): AgentUnitRuntimeCandidate? {
        val candidateId = "unitstage:${unit.id}:${objective.x},${objective.y}"
        val objectiveLabel = objective.cityName ?: "target city"
        return AgentUnitRuntimeCandidate(
            observation = UnitOptionCandidateObservation(
                candidateId = candidateId,
                category = "operation",
                title = "${unit.name} #${unit.id} assign: stage outside $objectiveLabel",
                detail = "Set an ongoing prewar staging assignment outside $objectiveLabel. If left alone, this unit will keep moving toward a safe border-ring slot until switched or war changes the mode.",
            ),
            validate = { currentCiv ->
                val liveUnit = currentCiv.units.getCivUnits().firstOrNull { it.id == unit.id }
                    ?: return@AgentUnitRuntimeCandidate "Unit option rejected: unit missing"
                val destination = findStageOutsideBorderDestination(liveUnit, objective)
                    ?: return@AgentUnitRuntimeCandidate "Unit option rejected: no safe prewar staging tile is reachable this turn"
                if (liveUnit.getTile() == destination) {
                    return@AgentUnitRuntimeCandidate "Unit option rejected: unit is already in a viable staging position"
                }
                null
            },
            execute = { currentCiv ->
                val liveUnit = currentCiv.units.getCivUnits().firstOrNull { it.id == unit.id } ?: return@AgentUnitRuntimeCandidate false
                executeOperationalAssignmentStep(liveUnit, buildOperationalAssignment("stage_outside_border", objective, unit), allowAutoTransitions = true)
            },
            successMessage = "${unit.name} assigned to prewar staging outside $objectiveLabel",
        )
    }

    private fun buildReinforceAssaultCandidate(
        unit: MapUnit,
        objective: ResolvedObjective,
    ): AgentUnitRuntimeCandidate? {
        val candidateId = "unitreinforce:${unit.id}:${objective.x},${objective.y}"
        val objectiveLabel = objective.cityName ?: "target city"
        return AgentUnitRuntimeCandidate(
            observation = UnitOptionCandidateObservation(
                candidateId = candidateId,
                category = "operation",
                title = "${unit.name} #${unit.id} assign: reinforce $objectiveLabel assault",
                detail = "Set an ongoing reinforcement assignment toward $objectiveLabel. If left alone, this unit will keep marching to the assault axis and fill the line when it can.",
            ),
            validate = { currentCiv ->
                val liveUnit = currentCiv.units.getCivUnits().firstOrNull { it.id == unit.id }
                    ?: return@AgentUnitRuntimeCandidate "Unit option rejected: unit missing"
                val destination = findReinforceAssaultDestination(liveUnit, objective)
                    ?: return@AgentUnitRuntimeCandidate "Unit option rejected: no useful reinforcement step is reachable this turn"
                if (liveUnit.getTile() == destination) {
                    return@AgentUnitRuntimeCandidate "Unit option rejected: unit is already close enough to the target to reinforce directly"
                }
                null
            },
            execute = { currentCiv ->
                val liveUnit = currentCiv.units.getCivUnits().firstOrNull { it.id == unit.id } ?: return@AgentUnitRuntimeCandidate false
                executeOperationalAssignmentStep(liveUnit, buildOperationalAssignment("reinforce_assault", objective, unit), allowAutoTransitions = true)
            },
            successMessage = "${unit.name} assigned to reinforce the $objectiveLabel assault",
        )
    }

    private fun buildAssaultCityRingCandidate(
        unit: MapUnit,
        objective: ResolvedObjective,
    ): AgentUnitRuntimeCandidate? {
        val candidateId = "unitassault:${unit.id}:${objective.x},${objective.y}"
        val objectiveLabel = objective.cityName ?: "target city"
        return AgentUnitRuntimeCandidate(
            observation = UnitOptionCandidateObservation(
                candidateId = candidateId,
                category = "operation",
                title = "${unit.name} #${unit.id} assign: assault $objectiveLabel",
                detail = "Set an ongoing city-assault assignment on $objectiveLabel. If left alone, this unit will keep stepping into useful ring tiles and attack when the heuristic finds a good trade.",
            ),
            validate = { currentCiv ->
                val liveUnit = currentCiv.units.getCivUnits().firstOrNull { it.id == unit.id }
                    ?: return@AgentUnitRuntimeCandidate "Unit option rejected: unit missing"
                val destination = findAssaultCityRingDestination(liveUnit, objective)
                    ?: return@AgentUnitRuntimeCandidate "Unit option rejected: no useful assault-ring step is reachable this turn"
                if (liveUnit.getTile() == destination) {
                    return@AgentUnitRuntimeCandidate "Unit option rejected: unit is already in a viable assault position"
                }
                null
            },
            execute = { currentCiv ->
                val liveUnit = currentCiv.units.getCivUnits().firstOrNull { it.id == unit.id } ?: return@AgentUnitRuntimeCandidate false
                executeOperationalAssignmentStep(liveUnit, buildOperationalAssignment("assault_city_ring", objective, unit), allowAutoTransitions = true)
            },
            successMessage = "${unit.name} assigned to assault $objectiveLabel",
        )
    }

    private fun buildRecoverThenRejoinCandidate(
        unit: MapUnit,
        objective: ResolvedObjective,
    ): AgentUnitRuntimeCandidate? {
        val candidateId = "unitrecoverrejoin:${unit.id}:${objective.x},${objective.y}"
        val objectiveLabel = objective.cityName ?: "target city"
        return AgentUnitRuntimeCandidate(
            observation = UnitOptionCandidateObservation(
                candidateId = candidateId,
                category = "operation",
                title = "${unit.name} #${unit.id} assign: recover then rejoin $objectiveLabel",
                detail = "Set an ongoing recovery assignment near $objectiveLabel. If left alone, this unit will fall back, heal, and return toward the assault once healthy enough.",
            ),
            validate = { currentCiv ->
                val liveUnit = currentCiv.units.getCivUnits().firstOrNull { it.id == unit.id }
                    ?: return@AgentUnitRuntimeCandidate "Unit option rejected: unit missing"
                val destination = findRecoverThenRejoinDestination(liveUnit, objective)
                    ?: return@AgentUnitRuntimeCandidate "Unit option rejected: no useful recovery step is reachable this turn"
                if (liveUnit.getTile() == destination) {
                    return@AgentUnitRuntimeCandidate "Unit option rejected: unit is already on a viable recovery tile"
                }
                null
            },
            execute = { currentCiv ->
                val liveUnit = currentCiv.units.getCivUnits().firstOrNull { it.id == unit.id } ?: return@AgentUnitRuntimeCandidate false
                executeOperationalAssignmentStep(liveUnit, buildOperationalAssignment("recover_then_rejoin", objective, unit), allowAutoTransitions = true)
            },
            successMessage = "${unit.name} assigned to recover then rejoin near $objectiveLabel",
        )
    }

    private fun buildPreserveCaptureUnitCandidate(
        unit: MapUnit,
        objective: ResolvedObjective,
    ): AgentUnitRuntimeCandidate? {
        val candidateId = "unitcaptorpreserve:${unit.id}:${objective.x},${objective.y}"
        val objectiveLabel = objective.cityName ?: "target city"
        return AgentUnitRuntimeCandidate(
            observation = UnitOptionCandidateObservation(
                candidateId = candidateId,
                category = "operation",
                title = "${unit.name} #${unit.id} assign: preserve as $objectiveLabel captor",
                detail = "Set an ongoing captor-preserve assignment near $objectiveLabel. If left alone, this unit will stay healthy and close enough to take the city when the heuristic sees a safe capture window.",
            ),
            validate = { currentCiv ->
                val liveUnit = currentCiv.units.getCivUnits().firstOrNull { it.id == unit.id }
                    ?: return@AgentUnitRuntimeCandidate "Unit option rejected: unit missing"
                val destination = findPreserveCaptureDestination(liveUnit, objective)
                    ?: return@AgentUnitRuntimeCandidate "Unit option rejected: no useful capture-ready tile is reachable this turn"
                if (liveUnit.getTile() == destination) {
                    return@AgentUnitRuntimeCandidate "Unit option rejected: unit is already in a viable capture-ready position"
                }
                null
            },
            execute = { currentCiv ->
                val liveUnit = currentCiv.units.getCivUnits().firstOrNull { it.id == unit.id } ?: return@AgentUnitRuntimeCandidate false
                executeOperationalAssignmentStep(liveUnit, buildOperationalAssignment("preserve_capture_unit", objective, unit), allowAutoTransitions = true)
            },
            successMessage = "${unit.name} assigned as the preserved captor for $objectiveLabel",
        )
    }

    private fun buildSettlerCandidates(unit: MapUnit): List<AgentUnitRuntimeCandidate> {
        if (!unit.baseUnit.isCityFounder() || !unit.hasMovement()) return emptyList()
        val bestTiles = CityLocationTileRanker.getBestTilesToFoundCity(unit, minimumValue = 15f)
        return bestTiles.tileRankMap.entries
            .sortedByDescending { it.value }
            .take(maxSettlerCandidatesPerUnit)
            .map { (targetTile, rank) ->
                val candidateId = "unitsettle:${unit.id}:${targetTile.position.x},${targetTile.position.y}"
                AgentUnitRuntimeCandidate(
                    observation = UnitOptionCandidateObservation(
                        candidateId = candidateId,
                        category = "settle",
                        title = if (unit.getTile() == targetTile) {
                            "${unit.name} #${unit.id} found city here"
                        } else {
                            "${unit.name} #${unit.id} move toward city site"
                        },
                        detail = "Target visible city site (${targetTile.position.x}, ${targetTile.position.y}) ranked ${rank.roundToInt()}.",
                    ),
                    validate = { currentCiv ->
                        val liveUnit = currentCiv.units.getCivUnits().firstOrNull { it.id == unit.id }
                            ?: return@AgentUnitRuntimeCandidate "Unit option rejected: unit missing"
                        val liveTarget = currentCiv.gameInfo.tileMap[HexCoord(targetTile.position.x, targetTile.position.y)]
                        if (liveUnit.getTile() == liveTarget) {
                            val canFound = UnitActions.getUnitActions(liveUnit, UnitActionType.FoundCity).any { it.action != null }
                            if (!canFound) return@AgentUnitRuntimeCandidate "Unit option rejected: city cannot be founded on the target tile"
                        } else if (!liveUnit.movement.canReach(liveTarget) && liveUnit.movement.getShortestPath(liveTarget).isEmpty()) {
                            return@AgentUnitRuntimeCandidate "Unit option rejected: path to the city site is blocked"
                        }
                        null
                    },
                    execute = { currentCiv ->
                        val liveUnit = currentCiv.units.getCivUnits().firstOrNull { it.id == unit.id } ?: return@AgentUnitRuntimeCandidate false
                        val liveTarget = currentCiv.gameInfo.tileMap[HexCoord(targetTile.position.x, targetTile.position.y)]
                        val beforePosition = liveUnit.getTile().position
                        if (liveUnit.getTile() != liveTarget) {
                            liveUnit.movement.headTowards(liveTarget)
                        }
                        if (liveUnit.getTile() == liveTarget) {
                            val found = UnitActions.invokeUnitAction(liveUnit, UnitActionType.FoundCity)
                            if (found) return@AgentUnitRuntimeCandidate true
                        }
                        liveUnit.getTile().position != beforePosition
                    },
                    successMessage = if (unit.getTile() == targetTile) {
                        "${unit.name} founded a city"
                    } else {
                        "${unit.name} moved toward a city site"
                    },
                )
            }
    }

    private fun buildRecoveryCandidates(unit: MapUnit): List<AgentUnitRuntimeCandidate> {
        if (unit.health >= 100 || !unit.hasMovement()) return emptyList()
        val recoveryAction = when {
            unit.canFortify() && unit.canHealInCurrentTile() -> UnitActionType.FortifyUntilHealed
            unit.canFortify() -> UnitActionType.Fortify
            unit.canHealInCurrentTile() -> UnitActionType.SleepUntilHealed
            else -> UnitActionType.Sleep
        }
        val candidateId = "unitspecial:${unit.id}:${recoveryAction.name}"
        return listOf(
            AgentUnitRuntimeCandidate(
                observation = UnitOptionCandidateObservation(
                    candidateId = candidateId,
                    category = "recovery",
                    title = "${unit.name} #${unit.id} recover",
                    detail = "Use ${recoveryAction.name} on the current tile at ${unit.health} HP.",
                ),
                validate = { currentCiv ->
                    val liveUnit = currentCiv.units.getCivUnits().firstOrNull { it.id == unit.id }
                        ?: return@AgentUnitRuntimeCandidate "Unit option rejected: unit missing"
                    if (!liveUnit.hasMovement()) return@AgentUnitRuntimeCandidate "Unit option rejected: unit has no movement left"
                    val actionAvailable = UnitActions.getUnitActions(liveUnit, recoveryAction).any { it.action != null }
                    if (!actionAvailable) return@AgentUnitRuntimeCandidate "Unit option rejected: recovery action is no longer available"
                    null
                },
                execute = { currentCiv ->
                    val liveUnit = currentCiv.units.getCivUnits().firstOrNull { it.id == unit.id } ?: return@AgentUnitRuntimeCandidate false
                    UnitActions.invokeUnitAction(liveUnit, recoveryAction)
                },
                successMessage = "${unit.name} set to ${recoveryAction.name}",
            )
        )
    }

    private fun buildDirectActionCandidates(
        unit: MapUnit,
        availableActions: List<UnitAction>,
    ): List<AgentUnitRuntimeCandidate> {
        return availableActions
            .asSequence()
            .filter { it.action != null }
            .filter { isSurfacedDirectActionType(it.type) }
            .filterNot { action ->
                action.type in setOf(UnitActionType.Fortify, UnitActionType.FortifyUntilHealed, UnitActionType.Sleep, UnitActionType.SleepUntilHealed) &&
                    unit.health < 100
            }
            .distinctBy { "${it.type.name}:${it.title}" }
            .sortedByDescending { scoreDirectAction(unit, it) }
            .take(maxDirectActionCandidatesPerUnit)
            .map { action ->
                val titleToken = action.title.hashCode().toUInt().toString(16)
                val candidateId = "unitspecial:${unit.id}:${action.type.name}:$titleToken"
                AgentUnitRuntimeCandidate(
                    observation = UnitOptionCandidateObservation(
                        candidateId = candidateId,
                        category = directActionCategory(action.type),
                        title = "${unit.name} #${unit.id} ${agentFacingActionTitle(action)}",
                        detail = describeDirectAction(unit, action),
                    ),
                    validate = { currentCiv ->
                        val liveUnit = currentCiv.units.getCivUnits().firstOrNull { it.id == unit.id }
                            ?: return@AgentUnitRuntimeCandidate "Unit option rejected: unit missing"
                        val liveAction = findMatchingAction(liveUnit, action.type, action.title)
                            ?: return@AgentUnitRuntimeCandidate "Unit option rejected: action is no longer available"
                        if (liveAction.action == null) return@AgentUnitRuntimeCandidate "Unit option rejected: action cannot execute right now"
                        null
                    },
                    execute = { currentCiv ->
                        val liveUnit = currentCiv.units.getCivUnits().firstOrNull { it.id == unit.id } ?: return@AgentUnitRuntimeCandidate false
                        val beforePosition = liveUnit.getTile().position
                        val beforeHealth = liveUnit.health
                        val beforeMovement = liveUnit.currentMovement
                        val beforeGold = liveUnit.civ.gold
                        val beforeResearch = liveUnit.civ.tech.currentTechnologyName()
                        val beforeResearchProgress = liveUnit.civ.tech.researchOfTech(beforeResearch)
                        val liveAction = findMatchingAction(liveUnit, action.type, action.title) ?: return@AgentUnitRuntimeCandidate false
                        liveAction.action?.invoke()
                        val actionStillAvailable = !liveUnit.isDestroyed && findMatchingAction(liveUnit, action.type, action.title) != null
                        liveUnit.isDestroyed ||
                            liveUnit.getTile().position != beforePosition ||
                            liveUnit.health != beforeHealth ||
                            liveUnit.currentMovement != beforeMovement ||
                            liveUnit.civ.gold != beforeGold ||
                            liveUnit.civ.tech.currentTechnologyName() != beforeResearch ||
                            liveUnit.civ.tech.researchOfTech(liveUnit.civ.tech.currentTechnologyName()) != beforeResearchProgress ||
                            isActionNowCurrentState(liveUnit, action.type) ||
                            !actionStillAvailable
                    },
                    successMessage = "${unit.name} used ${action.title}",
                )
            }
            .toList()
    }

    private fun buildWorkerCandidates(
        unit: MapUnit,
        currentAssignment: UnitAssignmentMemory?,
    ): List<AgentUnitRuntimeCandidate> {
        if (!unit.cache.hasUniqueToBuildImprovements || !unit.hasMovement()) return emptyList()
        val availableActions = UnitActions.getUnitActions(unit).filter { it.action != null }.toList()
        return AgentWorkerJobPlanner.findWorkerJobs(unit, currentAssignment = currentAssignment)
            .mapNotNull { job ->
                val isCurrentTile = unit.getTile().position.x == job.tileX && unit.getTile().position.y == job.tileY
                if (isCurrentTile) {
                    buildCurrentWorkerCandidate(unit, job, availableActions)
                } else {
                    val candidateId = "unitworkerreposition:${unit.id}:${job.tileX},${job.tileY}"
                    AgentUnitRuntimeCandidate(
                        observation = UnitOptionCandidateObservation(
                            candidateId = candidateId,
                            category = "worker",
                            title = "${unit.name} #${unit.id} move to worker job",
                            detail = job.intentLabel?.replaceFirstChar { it.uppercase() }?.let {
                                "$it at (${job.tileX}, ${job.tileY})."
                            } ?: "Move to (${job.tileX}, ${job.tileY}) for worker tempo.",
                        ),
                        validate = { currentCiv ->
                            val liveUnit = currentCiv.units.getCivUnits().firstOrNull { it.id == unit.id }
                                ?: return@AgentUnitRuntimeCandidate "Unit option rejected: unit missing"
                            val liveTarget = currentCiv.gameInfo.tileMap[HexCoord(job.tileX, job.tileY)]
                            if (liveUnit.getTile() == liveTarget) return@AgentUnitRuntimeCandidate null
                            if (!liveUnit.hasMovement()) return@AgentUnitRuntimeCandidate "Unit option rejected: unit has no movement left"
                            if (!liveUnit.movement.canReach(liveTarget) && liveUnit.movement.getShortestPath(liveTarget).isEmpty()) {
                                return@AgentUnitRuntimeCandidate "Unit option rejected: path to worker job is blocked"
                            }
                            null
                        },
                        execute = { currentCiv ->
                            val liveUnit = currentCiv.units.getCivUnits().firstOrNull { it.id == unit.id } ?: return@AgentUnitRuntimeCandidate false
                            val liveTarget = currentCiv.gameInfo.tileMap[HexCoord(job.tileX, job.tileY)]
                            val beforePosition = liveUnit.getTile().position
                            liveUnit.movement.headTowards(liveTarget)
                            liveUnit.getTile().position != beforePosition
                        },
                        successMessage = "${unit.name} moved toward a worker job",
                    )
                }
            }
            .take(maxWorkerCandidatesPerUnit)
    }

    private fun buildCurrentWorkerCandidate(
        unit: MapUnit,
        job: AgentWorkerJobPlanner.WorkerJob,
        availableActions: List<UnitAction>,
    ): AgentUnitRuntimeCandidate? {
        val directImprovementCandidate = when {
            job.isRepair -> buildRepairCandidate(unit, job)
            job.improvementName != null -> buildImprovementCandidate(unit, job)
            else -> null
        }
        if (directImprovementCandidate != null) return directImprovementCandidate

        val workerAction = AgentWorkerJobPlanner.preferredCurrentAction(availableActions) ?: return null
        val titleToken = workerAction.title.hashCode().toUInt().toString(16)
        val candidateId = buildString {
            append("unitworkerimprove:")
            append(unit.id)
            append(':')
            append(job.tileX)
            append(',')
            append(job.tileY)
            append(':')
            append(workerAction.type.name)
            append(':')
            append(titleToken)
        }
        return AgentUnitRuntimeCandidate(
            observation = UnitOptionCandidateObservation(
                candidateId = candidateId,
                category = "worker",
                title = "${unit.name} #${unit.id} ${workerAction.title}",
                detail = job.description,
            ),
            validate = { currentCiv ->
                val liveUnit = currentCiv.units.getCivUnits().firstOrNull { it.id == unit.id }
                    ?: return@AgentUnitRuntimeCandidate "Unit option rejected: unit missing"
                if (liveUnit.getTile().position.x != job.tileX || liveUnit.getTile().position.y != job.tileY) {
                    return@AgentUnitRuntimeCandidate "Unit option rejected: worker is no longer on the target tile"
                }
                val liveAction = findMatchingWorkerAction(liveUnit, workerAction.type, workerAction.title)
                    ?: return@AgentUnitRuntimeCandidate "Unit option rejected: worker action is no longer available"
                if (liveAction.action == null) return@AgentUnitRuntimeCandidate "Unit option rejected: worker action cannot execute right now"
                null
            },
            execute = { currentCiv ->
                val liveUnit = currentCiv.units.getCivUnits().firstOrNull { it.id == unit.id } ?: return@AgentUnitRuntimeCandidate false
                val liveAction = findMatchingWorkerAction(liveUnit, workerAction.type, workerAction.title)
                    ?: return@AgentUnitRuntimeCandidate false
                liveAction.action?.invoke()
                true
            },
            successMessage = "${unit.name} executed ${workerAction.title}",
        )
    }

    private fun findStageOutsideBorderDestination(
        unit: MapUnit,
        objective: ResolvedObjective,
        reservationTracker: TheaterReservationTracker? = null,
    ): Tile? {
        return selectTheaterDestination(
            unit,
            objective,
            preferredKinds = setOf(BattleTheaterSlotKind.Staging),
            reservationTracker = reservationTracker,
        )
    }

    private fun findReinforceAssaultDestination(
        unit: MapUnit,
        objective: ResolvedObjective,
        reservationTracker: TheaterReservationTracker? = null,
    ): Tile? {
        return selectTheaterDestination(
            unit,
            objective,
            preferredKinds = setOf(BattleTheaterSlotKind.Reserve),
            fallbackKinds = setOf(primaryAssaultSlotKind(unit, objective)),
            reservationTracker = reservationTracker,
        )
    }

    private fun findAssaultCityRingDestination(
        unit: MapUnit,
        objective: ResolvedObjective,
        reservationTracker: TheaterReservationTracker? = null,
    ): Tile? {
        return selectTheaterDestination(
            unit,
            objective,
            preferredKinds = setOf(primaryAssaultSlotKind(unit, objective)),
            fallbackKinds = setOf(BattleTheaterSlotKind.Reserve),
            reservationTracker = reservationTracker,
        )
    }

    private fun findRecoverThenRejoinDestination(
        unit: MapUnit,
        objective: ResolvedObjective,
        reservationTracker: TheaterReservationTracker? = null,
    ): Tile? {
        return selectTheaterDestination(
            unit,
            objective,
            preferredKinds = setOf(BattleTheaterSlotKind.Recovery),
            fallbackKinds = setOf(BattleTheaterSlotKind.Reserve),
            reservationTracker = reservationTracker,
        )
    }

    private fun findPreserveCaptureDestination(
        unit: MapUnit,
        objective: ResolvedObjective,
        reservationTracker: TheaterReservationTracker? = null,
    ): Tile? {
        if (!unit.baseUnit.isMelee()) return null
        return selectTheaterDestination(
            unit,
            objective,
            preferredKinds = setOf(BattleTheaterSlotKind.Captor),
            fallbackKinds = setOf(BattleTheaterSlotKind.MeleeAssault, BattleTheaterSlotKind.Reserve),
            reservationTracker = reservationTracker,
        )
    }

    private fun selectTheaterDestination(
        unit: MapUnit,
        objective: ResolvedObjective,
        preferredKinds: Set<BattleTheaterSlotKind>,
        fallbackKinds: Set<BattleTheaterSlotKind> = emptySet(),
        reservationTracker: TheaterReservationTracker? = null,
    ): Tile? {
        val allSlots = buildBattleTheaterSlots(unit, objective)
        val candidateSlots = allSlots.filter { it.kind in preferredKinds }
            .ifEmpty { allSlots.filter { it.kind in fallbackKinds } }
        if (candidateSlots.isEmpty()) return null

        val currentTile = unit.getTile()
        val reachableTiles = unit.movement.getDistanceToTiles().keys
            .asSequence()
            .filter { tile -> tile != currentTile }
            .filter { tile -> !tile.isCityCenter() }
            .filter { tile -> unit.getDamageFromTerrain(tile) <= 0 }
            .toList()
        val orderedSlots = candidateSlots.sortedByDescending { slot ->
            scoreTargetTheaterSlot(unit, objective, slot, reservationTracker)
        }

        for (slot in orderedSlots) {
            if (slot.tile == currentTile) {
                reservationTracker?.reserve(objective.x, objective.y, currentTile)
                return null
            }
            if (slot.tile in reachableTiles) {
                reservationTracker?.reserve(objective.x, objective.y, slot.tile)
                return slot.tile
            }
            val bestStep = reachableTiles
                .maxByOrNull { step -> scoreStepTowardTheaterSlot(unit, objective, step, slot, reservationTracker) }
                ?.takeIf { step -> scoreStepTowardTheaterSlot(unit, objective, step, slot, reservationTracker) > 0 }
            if (bestStep != null) {
                reservationTracker?.reserve(objective.x, objective.y, slot.tile)
                return bestStep
            }
        }
        return null
    }

    private fun buildBattleTheaterSlots(
        unit: MapUnit,
        objective: ResolvedObjective,
    ): List<BattleTheaterSlot> {
        val objectiveTile = unit.civ.gameInfo.tileMap[HexCoord(objective.x, objective.y)]
        return objectiveTile.getTilesInDistance(5)
            .flatMap { tile -> buildBattleTheaterSlotsForTile(unit, objective, tile) }
            .toList()
    }

    private fun buildBattleTheaterSlotsForTile(
        unit: MapUnit,
        objective: ResolvedObjective,
        tile: Tile,
    ): List<BattleTheaterSlot> {
        if (tile.position.x == objective.x && tile.position.y == objective.y) return emptyList()
        if (tile.isCityCenter()) return emptyList()
        if (unit.getDamageFromTerrain(tile) > 0) return emptyList()

        val slots = arrayListOf<BattleTheaterSlot>()
        val objectiveTile = unit.civ.gameInfo.tileMap[HexCoord(objective.x, objective.y)]
        val objectiveOwner = objectiveTile.getOwner()
        val targetCity = objectiveTile.getCity()
        val distance = axialDistance(tile.position.x, tile.position.y, objective.x, objective.y)
        val threatDistance = unit.civ.threatManager.getDistanceToClosestEnemyUnit(tile, 4)
        val owner = tile.getOwner()
        val preferSecondRingForMelee = targetCity != null &&
            (unit.health < 85 || targetCity.health > targetCity.getMaxHealth() / 2)
        val cityWeak = targetCity != null && targetCity.health <= targetCity.getMaxHealth() / 3

        if (distance in 2..4 && (objectiveOwner == null || owner != objectiveOwner) && threatDistance > 2) {
            var score = when (distance) {
                2 -> 100
                3 -> 82
                4 -> 60
                else -> 0
            }
            if (owner == unit.civ) score += 18
            if (owner == null) score += 10
            score += tile.tileHeight * 3
            slots += BattleTheaterSlot(tile, BattleTheaterSlotKind.Staging, score)
        }

        val reserveCenter = if (unit.baseUnit.isRanged()) 3 else 2
        if (distance in 2..4 && (owner == unit.civ || owner == null || threatDistance > 1)) {
            var score = 110 - kotlin.math.abs(distance - reserveCenter) * 18
            if (owner == unit.civ) score += 12
            if (owner == null) score += 6
            score += tile.tileHeight * if (unit.baseUnit.isRanged()) 2 else 1
            slots += BattleTheaterSlot(tile, BattleTheaterSlotKind.Reserve, score)
        }

        if (distance in 3..5 && (threatDistance > 2 || owner == unit.civ)) {
            var score = when (distance) {
                3 -> 115
                4 -> 96
                5 -> 78
                else -> 0
            }
            if (owner == unit.civ) score += 20
            if (owner == null) score += 8
            score += tile.tileHeight * 2
            score += threatDistance * 4
            slots += BattleTheaterSlot(tile, BattleTheaterSlotKind.Recovery, score)
        }

        if (unit.baseUnit.isRanged() && distance in 2..3) {
            var score = if (distance == 2) 160 else 135
            if (owner == unit.civ) score += 8
            if (owner == null) score += 4
            score += tile.tileHeight * 4
            slots += BattleTheaterSlot(tile, BattleTheaterSlotKind.RangedAssault, score)
        }

        if (unit.baseUnit.isMelee()) {
            if ((!preferSecondRingForMelee && distance in 1..2) || (preferSecondRingForMelee && distance == 2)) {
                var score = when {
                    !preferSecondRingForMelee && distance == 1 -> 170
                    distance == 2 -> 145
                    else -> 120
                }
                if (owner == unit.civ) score += 6
                if (owner == null) score += 3
                score += tile.tileHeight
                slots += BattleTheaterSlot(tile, BattleTheaterSlotKind.MeleeAssault, score)
            }

            val captorRange = if (cityWeak && unit.health >= 85) 1..2 else 2..3
            if (unit.health >= 70 && distance in captorRange) {
                var score = when {
                    cityWeak && distance == 1 -> 175
                    cityWeak && distance == 2 -> 150
                    distance == 2 -> 160
                    distance == 3 -> 132
                    else -> 100
                }
                if (owner == unit.civ) score += 12
                if (owner == null) score += 8
                score += tile.tileHeight
                slots += BattleTheaterSlot(tile, BattleTheaterSlotKind.Captor, score)
            }
        }

        return slots
    }

    private fun scoreDirectTheaterSlot(
        unit: MapUnit,
        objective: ResolvedObjective,
        slot: BattleTheaterSlot,
    ): Int {
        var score = slot.score
        val currentDistanceToObjective = axialDistance(unit.getTile().position.x, unit.getTile().position.y, objective.x, objective.y)
        val slotDistanceToObjective = axialDistance(slot.tile.position.x, slot.tile.position.y, objective.x, objective.y)
        score += (currentDistanceToObjective - slotDistanceToObjective) * 14
        score -= unit.getTile().aerialDistanceTo(slot.tile) * 3
        return score
    }

    private fun scoreTargetTheaterSlot(
        unit: MapUnit,
        objective: ResolvedObjective,
        slot: BattleTheaterSlot,
        reservationTracker: TheaterReservationTracker?,
    ): Int {
        val currentTile = unit.getTile()
        var score = scoreDirectTheaterSlot(unit, objective, slot)
        val currentDistToSlot = axialDistance(currentTile.position.x, currentTile.position.y, slot.tile.position.x, slot.tile.position.y)
        score -= currentDistToSlot * 7
        if (reservationTracker?.isReserved(objective.x, objective.y, slot.tile) == true) score -= 220
        return score
    }

    private fun scoreStepTowardTheaterSlot(
        unit: MapUnit,
        objective: ResolvedObjective,
        step: Tile,
        slot: BattleTheaterSlot,
        reservationTracker: TheaterReservationTracker?,
    ): Int {
        val currentTile = unit.getTile()
        val currentDistanceToObjective = axialDistance(currentTile.position.x, currentTile.position.y, objective.x, objective.y)
        val nextDistanceToObjective = axialDistance(step.position.x, step.position.y, objective.x, objective.y)
        val currentDistToSlot = axialDistance(currentTile.position.x, currentTile.position.y, slot.tile.position.x, slot.tile.position.y)
        val nextDistToSlot = axialDistance(step.position.x, step.position.y, slot.tile.position.x, slot.tile.position.y)
        var score = slot.score
        score += (currentDistToSlot - nextDistToSlot) * 40
        score -= nextDistToSlot * 9
        score -= currentTile.aerialDistanceTo(step) * 4
        if (step.getOwner() == unit.civ) score += 8
        if (step.getOwner() == null) score += 4
        score += step.tileHeight * if (unit.baseUnit.isRanged()) 2 else 1
        score += when {
            slot.kind == BattleTheaterSlotKind.Recovery && nextDistanceToObjective >= currentDistanceToObjective -> 16
            slot.kind != BattleTheaterSlotKind.Recovery && nextDistanceToObjective < currentDistanceToObjective -> 18
            else -> -12
        }
        if (reservationTracker?.isReserved(objective.x, objective.y, slot.tile) == true) score -= 180
        return score
    }

    private fun currentTileMatchesOperationalRole(
        unit: MapUnit,
        objective: ResolvedObjective,
        role: String,
    ): Boolean {
        val slotKinds = buildBattleTheaterSlotsForTile(unit, objective, unit.getTile()).map { it.kind }.toSet()
        return when (role) {
            "stage_outside_border" -> BattleTheaterSlotKind.Staging in slotKinds
            "reinforce_assault" -> BattleTheaterSlotKind.Reserve in slotKinds || primaryAssaultSlotKind(unit, objective) in slotKinds
            "assault_city_ring" -> primaryAssaultSlotKind(unit, objective) in slotKinds
            "recover_then_rejoin" -> BattleTheaterSlotKind.Recovery in slotKinds
            "preserve_capture_unit" -> BattleTheaterSlotKind.Captor in slotKinds
            else -> false
        }
    }

    private fun primaryAssaultSlotKind(
        unit: MapUnit,
        objective: ResolvedObjective,
    ): BattleTheaterSlotKind {
        if (unit.baseUnit.isRanged()) return BattleTheaterSlotKind.RangedAssault
        val objectiveTile = unit.civ.gameInfo.tileMap[HexCoord(objective.x, objective.y)]
        val targetCity = objectiveTile.getCity()
        val preferSecondRingForMelee = targetCity != null &&
            (unit.health < 85 || targetCity.health > targetCity.getMaxHealth() / 2)
        return if (preferSecondRingForMelee) BattleTheaterSlotKind.Reserve else BattleTheaterSlotKind.MeleeAssault
    }

    private fun buildOperationalAssignment(
        role: String,
        objective: ResolvedObjective,
        sourceUnit: MapUnit,
    ): UnitAssignmentMemory {
        return UnitAssignmentMemory(
            unitId = sourceUnit.id,
            unitName = sourceUnit.name,
            role = role,
            targetX = objective.x,
            targetY = objective.y,
            detail = objective.cityName?.let { "$role around $it" } ?: role,
            executionMode = "deferred_heuristic",
            completionPolicy = "until_switched",
            lastProgressTurn = sourceUnit.civ.gameInfo.turns,
            staleAfterTurn = sourceUnit.civ.gameInfo.turns + 6,
        )
    }

    private fun refreshOperationalAssignment(
        unit: MapUnit,
        assignment: UnitAssignmentMemory,
    ): UnitAssignmentMemory? {
        val objective = resolveAssignmentObjective(unit, assignment) ?: return null
        val objectiveTile = unit.civ.gameInfo.tileMap[HexCoord(objective.x, objective.y)]
        val objectiveOwner = objectiveTile.getOwner()
        if (objectiveOwner == unit.civ) return null

        val atWarWithObjective = objectiveOwner != null && unit.civ.isAtWarWith(objectiveOwner)
        val currentDistance = axialDistance(unit.getTile().position.x, unit.getTile().position.y, objective.x, objective.y)

        return when (assignment.role) {
            "stage_outside_border" -> {
                if (atWarWithObjective) {
                    assignment.copy(role = "reinforce_assault", detail = "Converted from staging into reinforcement")
                } else {
                    assignment
                }
            }
            "reinforce_assault" -> when {
                !atWarWithObjective -> assignment.copy(role = "stage_outside_border", detail = "Converted back to prewar staging")
                unit.health < 45 -> assignment.copy(role = "recover_then_rejoin", detail = "Fallback to recovery because the frontline unit is damaged")
                currentDistance <= if (unit.baseUnit.isRanged()) 3 else 2 ->
                    assignment.copy(role = "assault_city_ring", detail = "Close enough to join the assault ring")
                else -> assignment
            }
            "assault_city_ring" -> when {
                !atWarWithObjective -> assignment.copy(role = "stage_outside_border", detail = "War context ended; revert to prewar staging")
                unit.health < 45 -> assignment.copy(role = "recover_then_rejoin", detail = "Fall back to recover before rejoining the assault")
                unit.baseUnit.isMelee() && unit.health >= 80 && shouldPreserveHealthyCaptor(unit, objective) ->
                    assignment.copy(role = "preserve_capture_unit", detail = "Healthy melee promoted into preserved captor role")
                else -> assignment
            }
            "recover_then_rejoin" -> when {
                !atWarWithObjective -> assignment.copy(role = "stage_outside_border", detail = "War context ended while recovering")
                unit.health >= 85 -> assignment.copy(role = "reinforce_assault", detail = "Recovered enough to rejoin the assault")
                else -> assignment
            }
            "preserve_capture_unit" -> when {
                !atWarWithObjective -> assignment.copy(role = "stage_outside_border", detail = "War context ended; captor reserve no longer needed")
                unit.health < 60 -> assignment.copy(role = "recover_then_rejoin", detail = "Preserved captor is too damaged and must recover")
                else -> assignment
            }
            else -> assignment
        }
    }

    private fun executeOperationalAssignmentStep(
        unit: MapUnit,
        assignment: UnitAssignmentMemory,
        allowAutoTransitions: Boolean,
        reservationTracker: TheaterReservationTracker? = null,
    ): Boolean {
        if (!unit.hasMovement()) return false
        val activeAssignment = (if (allowAutoTransitions) refreshOperationalAssignment(unit, assignment) else assignment)
            ?: return false
        return when (activeAssignment.role) {
            "stage_outside_border" -> resolveAssignmentObjective(unit, activeAssignment)?.let { objective ->
                val destination = findStageOutsideBorderDestination(unit, objective, reservationTracker)
                when {
                    destination != null -> moveTowardTile(unit, destination)
                    isAssignmentOnTarget(unit, activeAssignment) -> executeHoldAssignmentStep(unit)
                    else -> false
                }
            } ?: false
            "reinforce_assault" -> resolveAssignmentObjective(unit, activeAssignment)?.let { objective ->
                executeReinforceAssaultStep(unit, objective, reservationTracker)
            } ?: false
            "assault_city_ring" -> resolveAssignmentObjective(unit, activeAssignment)?.let { objective ->
                executeAssaultCityStep(unit, objective, preserveCaptor = false, reservationTracker = reservationTracker)
            } ?: false
            "recover_then_rejoin" -> resolveAssignmentObjective(unit, activeAssignment)?.let { objective ->
                executeRecoverThenRejoinStep(unit, objective, reservationTracker)
            } ?: false
            "preserve_capture_unit" -> resolveAssignmentObjective(unit, activeAssignment)?.let { objective ->
                executePreserveCaptureStep(unit, objective, reservationTracker)
            } ?: false
            else -> executeDeferredAssignmentStep(unit, activeAssignment)
        }
    }

    private fun hasReinforceAssaultStep(
        unit: MapUnit,
        objective: ResolvedObjective,
    ): Boolean {
        return findBestOperationalAttack(unit, objective, preserveCaptor = false) != null ||
            findReinforceAssaultDestination(unit, objective) != null ||
            findAssaultCityRingDestination(unit, objective) != null ||
            isWithinObjectiveBand(unit, objective, if (unit.baseUnit.isRanged()) 2..3 else 1..2)
    }

    private fun hasAssaultCityStep(
        unit: MapUnit,
        objective: ResolvedObjective,
    ): Boolean {
        return findBestOperationalAttack(unit, objective, preserveCaptor = false) != null ||
            findAssaultCityRingDestination(unit, objective) != null ||
            isWithinObjectiveBand(unit, objective, if (unit.baseUnit.isRanged()) 2..3 else 1..2)
    }

    private fun hasRecoverThenRejoinStep(
        unit: MapUnit,
        objective: ResolvedObjective,
    ): Boolean {
        return if (unit.health >= 85) {
            hasReinforceAssaultStep(unit, objective)
        } else {
            findRecoverThenRejoinDestination(unit, objective) != null ||
                canInvokeAction(unit, UnitActionType.FortifyUntilHealed) ||
                canInvokeAction(unit, UnitActionType.SleepUntilHealed) ||
                canInvokeAction(unit, UnitActionType.Fortify) ||
                canInvokeAction(unit, UnitActionType.Sleep)
        }
    }

    private fun hasPreserveCaptureStep(
        unit: MapUnit,
        objective: ResolvedObjective,
    ): Boolean {
        return if (unit.health < 70) {
            hasRecoverThenRejoinStep(unit, objective)
        } else {
            findBestOperationalAttack(unit, objective, preserveCaptor = true) != null ||
                findPreserveCaptureDestination(unit, objective) != null ||
                isWithinObjectiveBand(unit, objective, 1..3)
        }
    }

    private fun executeReinforceAssaultStep(
        unit: MapUnit,
        objective: ResolvedObjective,
        reservationTracker: TheaterReservationTracker? = null,
    ): Boolean {
        if (unit.health < 45) return executeRecoverThenRejoinStep(unit, objective, reservationTracker)
        findBestOperationalAttack(unit, objective, preserveCaptor = false)?.let { attack ->
            return executeOperationalAttack(unit, attack)
        }
        val destination = findReinforceAssaultDestination(unit, objective, reservationTracker)
            ?: findAssaultCityRingDestination(unit, objective, reservationTracker)
        return when {
            destination != null -> moveTowardTile(unit, destination)
            isWithinObjectiveBand(unit, objective, if (unit.baseUnit.isRanged()) 2..3 else 1..2) ->
                executeHoldAssignmentStep(unit)
            else -> false
        }
    }

    private fun executeAssaultCityStep(
        unit: MapUnit,
        objective: ResolvedObjective,
        preserveCaptor: Boolean,
        reservationTracker: TheaterReservationTracker? = null,
    ): Boolean {
        findBestOperationalAttack(unit, objective, preserveCaptor)?.let { attack ->
            return executeOperationalAttack(unit, attack)
        }
        val destination = if (preserveCaptor) {
            findPreserveCaptureDestination(unit, objective, reservationTracker) ?: findAssaultCityRingDestination(unit, objective, reservationTracker)
        } else {
            findAssaultCityRingDestination(unit, objective, reservationTracker)
        }
        return when {
            destination != null -> moveTowardTile(unit, destination)
            isWithinObjectiveBand(unit, objective, if (unit.baseUnit.isRanged()) 2..3 else 1..3) ->
                executeHoldAssignmentStep(unit)
            else -> false
        }
    }

    private fun executeRecoverThenRejoinStep(
        unit: MapUnit,
        objective: ResolvedObjective,
        reservationTracker: TheaterReservationTracker? = null,
    ): Boolean {
        if (unit.health >= 85) return executeReinforceAssaultStep(unit, objective, reservationTracker)
        val destination = findRecoverThenRejoinDestination(unit, objective, reservationTracker)
        return when {
            destination != null -> moveTowardTile(unit, destination)
            else -> executeRecoveryAssignmentStep(unit)
        }
    }

    private fun executePreserveCaptureStep(
        unit: MapUnit,
        objective: ResolvedObjective,
        reservationTracker: TheaterReservationTracker? = null,
    ): Boolean {
        if (unit.health < 70) return executeRecoverThenRejoinStep(unit, objective, reservationTracker)
        findBestOperationalAttack(unit, objective, preserveCaptor = true)?.let { attack ->
            return executeOperationalAttack(unit, attack)
        }
        val destination = findPreserveCaptureDestination(unit, objective, reservationTracker)
            ?: findAssaultCityRingDestination(unit, objective, reservationTracker)
        return when {
            destination != null -> moveTowardTile(unit, destination)
            isWithinObjectiveBand(unit, objective, 1..3) -> executeHoldAssignmentStep(unit)
            else -> false
        }
    }

    private fun findBestOperationalAttack(
        unit: MapUnit,
        objective: ResolvedObjective,
        preserveCaptor: Boolean,
    ): AttackableTile? {
        val attacker = MapUnitCombatant(unit)
        val attackContext = AttackConversionContext(
            objectivePressure = true,
            frontlineShortage = preserveCaptor || unit.baseUnit.isMelee(),
            objective = objective,
        )
        return TargetHelper.getAttackableEnemies(unit, unit.movement.getDistanceToTiles())
            .filter { attack ->
                val defender = attack.combatant ?: return@filter false
                val distanceToObjective = axialDistance(
                    attack.tileToAttack.position.x,
                    attack.tileToAttack.position.y,
                    objective.x,
                    objective.y,
                )
                distanceToObjective <= 2 || defender is CityCombatant
            }
            .maxByOrNull { attack ->
                scoreOperationalAttackCandidate(attacker, attack, attackContext, preserveCaptor)
            }
            ?.takeIf {
                scoreOperationalAttackCandidate(attacker, it, attackContext, preserveCaptor) > 30
            }
    }

    private fun scoreOperationalAttackCandidate(
        attacker: MapUnitCombatant,
        attackableTile: AttackableTile,
        attackContext: AttackConversionContext,
        preserveCaptor: Boolean,
    ): Int {
        val defender = attackableTile.combatant ?: return Int.MIN_VALUE
        val damageToDefender = BattleDamage.calculateDamageToDefender(attacker, defender, attackableTile.tileToAttackFrom)
        val damageToAttacker = BattleDamage.calculateDamageToAttacker(attacker, defender, attackableTile.tileToAttackFrom)
        var score = scoreAttackCandidate(attacker, attackableTile, attackContext)

        if (attacker.isMelee()) {
            if (damageToAttacker >= attacker.getHealth()) score -= 500
            if (attacker.getHealth() - damageToAttacker < 25) score -= 140
        }

        val objective = attackContext.objective
        if (objective != null) {
            val distanceToObjective = axialDistance(
                attackableTile.tileToAttack.position.x,
                attackableTile.tileToAttack.position.y,
                objective.x,
                objective.y,
            )
            score += when {
                attackableTile.tileToAttack.position.x == objective.x &&
                    attackableTile.tileToAttack.position.y == objective.y -> 180
                distanceToObjective == 1 -> 55
                distanceToObjective == 2 -> 20
                else -> 0
            }
        }

        if (!preserveCaptor) return score

        if (!attacker.isMelee()) return score - 180

        return when {
            defender is CityCombatant -> {
                val safeCapture = defender.getHealth() <= damageToDefender && attacker.getHealth() - damageToAttacker >= 35
                if (safeCapture) score + 240 else score - 220
            }
            defender.isCivilian() -> {
                val safeCapture = attacker.getHealth() - damageToAttacker >= 45
                if (safeCapture) score + 120 else score - 120
            }
            else -> score - 180
        }
    }

    private fun executeOperationalAttack(
        unit: MapUnit,
        attackableTile: AttackableTile,
    ): Boolean {
        val beforeAttacks = unit.attacksThisTurn
        val beforePosition = unit.getTile().position
        val beforeHealth = unit.health
        Battle.moveAndAttack(MapUnitCombatant(unit), attackableTile)
        return unit.isDestroyed ||
            unit.attacksThisTurn > beforeAttacks ||
            unit.getTile().position != beforePosition ||
            unit.health != beforeHealth
    }

    private fun executeRecoveryAssignmentStep(unit: MapUnit): Boolean {
        return when {
            canInvokeAction(unit, UnitActionType.FortifyUntilHealed) -> UnitActions.invokeUnitAction(unit, UnitActionType.FortifyUntilHealed)
            canInvokeAction(unit, UnitActionType.SleepUntilHealed) -> UnitActions.invokeUnitAction(unit, UnitActionType.SleepUntilHealed)
            canInvokeAction(unit, UnitActionType.Fortify) -> UnitActions.invokeUnitAction(unit, UnitActionType.Fortify)
            canInvokeAction(unit, UnitActionType.Sleep) -> UnitActions.invokeUnitAction(unit, UnitActionType.Sleep)
            else -> false
        }
    }

    private fun executeHoldAssignmentStep(unit: MapUnit): Boolean {
        return when {
            canInvokeAction(unit, UnitActionType.Fortify) -> UnitActions.invokeUnitAction(unit, UnitActionType.Fortify)
            canInvokeAction(unit, UnitActionType.Sleep) -> UnitActions.invokeUnitAction(unit, UnitActionType.Sleep)
            else -> false
        }
    }

    private fun shouldPreserveHealthyCaptor(
        unit: MapUnit,
        objective: ResolvedObjective,
    ): Boolean {
        if (!unit.baseUnit.isMelee() || unit.health < 80) return false
        val objectiveTile = unit.civ.gameInfo.tileMap[HexCoord(objective.x, objective.y)]
        val targetCity = objectiveTile.getCity() ?: return false
        return targetCity.health <= targetCity.getMaxHealth() / 2
    }

    private fun isWithinObjectiveBand(
        unit: MapUnit,
        objective: ResolvedObjective,
        desiredRange: IntRange,
    ): Boolean {
        val distance = axialDistance(unit.getTile().position.x, unit.getTile().position.y, objective.x, objective.y)
        return distance in desiredRange
    }

    private fun resolveAssignmentObjective(
        unit: MapUnit,
        assignment: UnitAssignmentMemory,
    ): ResolvedObjective? {
        val targetX = assignment.targetX ?: return null
        val targetY = assignment.targetY ?: return null
        val targetCoord = HexCoord(targetX, targetY)
        if (targetCoord !in unit.civ.gameInfo.tileMap) return null
        val targetTile = unit.civ.gameInfo.tileMap[targetCoord]
        val targetCity = targetTile.getCity()
        return ResolvedObjective(
            x = targetX,
            y = targetY,
            cityName = targetCity?.name,
            preferCapital = targetCity?.isCapital() == true,
        )
    }

    private fun findAssignmentTargetTile(
        unit: MapUnit,
        assignment: UnitAssignmentMemory,
    ): Tile? {
        val targetX = assignment.targetX ?: return null
        val targetY = assignment.targetY ?: return null
        val targetCoord = HexCoord(targetX, targetY)
        if (targetCoord !in unit.civ.gameInfo.tileMap) return null
        return unit.civ.gameInfo.tileMap[targetCoord]
    }

    private fun canAdvanceToward(unit: MapUnit, targetTile: Tile): Boolean {
        if (unit.getTile() == targetTile) return true
        return unit.movement.canReach(targetTile) || unit.movement.getShortestPath(targetTile).isNotEmpty()
    }

    private fun moveTowardTile(unit: MapUnit, targetTile: Tile): Boolean {
        if (unit.getTile() == targetTile) return false
        if (!canAdvanceToward(unit, targetTile)) return false
        val before = unit.getTile().position
        unit.movement.headTowards(targetTile)
        return unit.getTile().position != before
    }

    private fun canInvokeAction(unit: MapUnit, actionType: UnitActionType): Boolean {
        return UnitActions.getUnitActions(unit, actionType).any { it.action != null } ||
            UnitActions.getUnitActions(unit).any { it.action != null && it.type == actionType }
    }

    private fun advanceAutoExploreAssignment(unit: MapUnit): Boolean {
        val beforePosition = unit.getTile().position
        val beforeAction = unit.action
        if (unit.isExploring()) {
            unit.doAction()
        } else {
            if (!UnitActions.invokeUnitAction(unit, UnitActionType.Explore)) return false
        }
        if (unit.isDestroyed) return true
        val afterPosition = unit.getTile().position
        return afterPosition != beforePosition || beforeAction != unit.action
    }

    private fun buildRepairCandidate(
        unit: MapUnit,
        job: AgentWorkerJobPlanner.WorkerJob,
    ): AgentUnitRuntimeCandidate {
        val candidateId = "unitworkerimprove:${unit.id}:${job.tileX},${job.tileY}:${Constants.repair}"
        val title = if (job.isInProgress) {
            "${unit.name} #${unit.id} continue repair"
        } else {
            "${unit.name} #${unit.id} repair current tile"
        }
        return AgentUnitRuntimeCandidate(
            observation = UnitOptionCandidateObservation(
                candidateId = candidateId,
                category = "worker",
                title = title,
                detail = job.description,
            ),
            validate = { currentCiv ->
                val liveUnit = currentCiv.units.getCivUnits().firstOrNull { it.id == unit.id }
                    ?: return@AgentUnitRuntimeCandidate "Unit option rejected: unit missing"
                val liveTile = liveUnit.getTile()
                if (liveTile.position.x != job.tileX || liveTile.position.y != job.tileY) {
                    return@AgentUnitRuntimeCandidate "Unit option rejected: worker is no longer on the target tile"
                }
                if (liveTile.improvementInProgress == Constants.repair) return@AgentUnitRuntimeCandidate null
                val repairAction = UnitActionsFromUniques.getRepairAction(liveUnit)
                    ?: return@AgentUnitRuntimeCandidate "Unit option rejected: repair action is no longer available"
                if (repairAction.action == null) return@AgentUnitRuntimeCandidate "Unit option rejected: repair action cannot execute right now"
                null
            },
            execute = { currentCiv ->
                val liveUnit = currentCiv.units.getCivUnits().firstOrNull { it.id == unit.id } ?: return@AgentUnitRuntimeCandidate false
                val liveTile = liveUnit.getTile()
                if (liveTile.improvementInProgress == Constants.repair) return@AgentUnitRuntimeCandidate true
                val repairAction = UnitActionsFromUniques.getRepairAction(liveUnit) ?: return@AgentUnitRuntimeCandidate false
                repairAction.action?.invoke()
                liveTile.improvementInProgress == Constants.repair
            },
            successMessage = if (job.isInProgress) {
                "${unit.name} continued a repair job"
            } else {
                "${unit.name} started repairing the tile"
            },
        )
    }

    private fun buildImprovementCandidate(
        unit: MapUnit,
        job: AgentWorkerJobPlanner.WorkerJob,
    ): AgentUnitRuntimeCandidate {
        val improvementName = job.improvementName!!
        val candidateId = "unitworkerimprove:${unit.id}:${job.tileX},${job.tileY}:${improvementName}"
        val title = when {
            job.isInProgress -> "${unit.name} #${unit.id} continue $improvementName"
            else -> "${unit.name} #${unit.id} start $improvementName"
        }
        return AgentUnitRuntimeCandidate(
            observation = UnitOptionCandidateObservation(
                candidateId = candidateId,
                category = "worker",
                title = title,
                detail = job.description,
            ),
            validate = { currentCiv ->
                val liveUnit = currentCiv.units.getCivUnits().firstOrNull { it.id == unit.id }
                    ?: return@AgentUnitRuntimeCandidate "Unit option rejected: unit missing"
                val liveTile = liveUnit.getTile()
                if (liveTile.position.x != job.tileX || liveTile.position.y != job.tileY) {
                    return@AgentUnitRuntimeCandidate "Unit option rejected: worker is no longer on the target tile"
                }
                if (liveTile.improvementInProgress == improvementName) return@AgentUnitRuntimeCandidate null
                val improvement = resolveBuildableImprovement(currentCiv, liveUnit, improvementName)
                    ?: return@AgentUnitRuntimeCandidate "Unit option rejected: worker improvement is no longer available"
                if (!canStartImprovementNow(liveUnit, liveTile, improvement)) {
                    return@AgentUnitRuntimeCandidate "Unit option rejected: worker improvement cannot start on this tile right now"
                }
                null
            },
            execute = { currentCiv ->
                val liveUnit = currentCiv.units.getCivUnits().firstOrNull { it.id == unit.id } ?: return@AgentUnitRuntimeCandidate false
                val liveTile = liveUnit.getTile()
                if (liveTile.improvementInProgress == improvementName) return@AgentUnitRuntimeCandidate true
                val improvement = resolveBuildableImprovement(currentCiv, liveUnit, improvementName) ?: return@AgentUnitRuntimeCandidate false
                if (!canStartImprovementNow(liveUnit, liveTile, improvement)) return@AgentUnitRuntimeCandidate false
                liveTile.startWorkingOnImprovement(improvement, currentCiv, liveUnit)
                liveTile.improvementInProgress == improvement.name
            },
            successMessage = if (job.isInProgress) {
                "${unit.name} continued $improvementName"
            } else {
                "${unit.name} started $improvementName"
            },
        )
    }

    private fun scoreAttackCandidate(
        attacker: MapUnitCombatant,
        attackableTile: AttackableTile,
        attackContext: AttackConversionContext,
    ): Int {
        val defender = attackableTile.combatant ?: return 0
        var score = 0
        val damageToDefender = BattleDamage.calculateDamageToDefender(attacker, defender, attackableTile.tileToAttackFrom)
        val damageToAttacker = BattleDamage.calculateDamageToAttacker(attacker, defender, attackableTile.tileToAttackFrom)
        score += damageToDefender * 2
        score -= damageToAttacker
        if (defender.isCivilian()) {
            score += if (attacker.isMelee()) 135 else 25
            if (attacker.isMelee() && damageToAttacker <= 25) score += 35
            if (!attacker.isMelee() && attackContext.objectivePressure) score -= 55
        }
        if (defender is com.unciv.logic.battle.CityCombatant) {
            score += 45
            if (attacker.isMelee()) score += 20
        }
        if (defender.getHealth() <= damageToDefender) score += 50
        if (attackableTile.tileToAttack == attackableTile.tileToAttackFrom) score -= 15
        val objective = attackContext.objective
        if (objective != null) {
            val distanceToObjective = axialDistance(
                attackableTile.tileToAttack.position.x,
                attackableTile.tileToAttack.position.y,
                objective.x,
                objective.y,
            )
            if (defender is com.unciv.logic.battle.CityCombatant) {
                score += when {
                    objective.matches(defender.city.name, defender.city.isCapital()) -> 150
                    distanceToObjective <= 1 -> 45
                    attackContext.objectivePressure -> -35
                    else -> 0
                }
            } else {
                score += when {
                    defender.isCivilian() && attackContext.objectivePressure && distanceToObjective <= 2 -> 40
                    distanceToObjective <= 1 -> 30
                    attackContext.objectivePressure -> -20
                    else -> 0
                }
            }
        }
        if (attackContext.frontlineShortage && attacker.isMelee() && defender is com.unciv.logic.battle.CityCombatant) {
            score += 35
        }
        return score
    }

    private fun isOperationalCombatUnit(unit: MapUnit): Boolean {
        if (!unit.isMilitary()) return false
        if (unit.baseUnit.movesLikeAirUnits || unit.baseUnit.isWaterUnit) return false
        if (unit.name == "Scout") return false
        return unit.baseUnit.isMelee() || unit.baseUnit.isRanged()
    }

    private fun findAttackableTile(
        unit: MapUnit,
        fromX: Int,
        fromY: Int,
        targetX: Int,
        targetY: Int,
    ): AttackableTile? {
        return TargetHelper.getAttackableEnemies(unit, unit.movement.getDistanceToTiles())
            .firstOrNull {
                it.tileToAttackFrom.position.x == fromX &&
                    it.tileToAttackFrom.position.y == fromY &&
                    it.tileToAttack.position.x == targetX &&
                    it.tileToAttack.position.y == targetY
            }
    }

    private fun findMatchingWorkerAction(
        unit: MapUnit,
        actionType: UnitActionType,
        actionTitle: String,
    ): UnitAction? {
        return findMatchingAction(unit, actionType, actionTitle)
    }

    private fun findMatchingAction(
        unit: MapUnit,
        actionType: UnitActionType,
        actionTitle: String,
    ): UnitAction? {
        return UnitActions.getUnitActions(unit)
            .firstOrNull { it.action != null && it.type == actionType && it.title == actionTitle }
            ?: UnitActions.getUnitActions(unit)
                .firstOrNull { it.action != null && it.type == actionType }
    }

    private fun resolveBuildableImprovement(
        civInfo: Civilization,
        unit: MapUnit,
        improvementName: String,
    ): TileImprovement? {
        val improvement = civInfo.gameInfo.ruleset.tileImprovements[improvementName] ?: return null
        return improvement.takeIf { unit.canBuildImprovement(it, unit.getTile()) }
    }

    private fun canStartImprovementNow(
        unit: MapUnit,
        tile: com.unciv.logic.map.tile.Tile,
        improvement: TileImprovement,
    ): Boolean {
        val context = GameContext(civInfo = unit.civ, unit = unit, tile = tile)
        return unit.canBuildImprovement(improvement, tile) &&
            tile.improvementFunctions.canBuildImprovement(improvement, context)
    }

    private fun buildAttackConversionContext(
        civInfo: Civilization,
        memory: AgentMemory,
    ): AttackConversionContext {
        val objectiveText = memory.campaign.decisiveObjective?.lowercase().orEmpty()
        val blockerText = memory.campaign.conversionBlocker?.lowercase().orEmpty()
        val primaryRival = memory.campaign.primaryRivalCiv
        val objectivePressure = memory.campaign.stage.lowercase() in setOf("pressure", "staging", "assault", "rebuild", "declaration") ||
            objectiveText.contains("capture") ||
            objectiveText.contains("take") ||
            objectiveText.contains("assault") ||
            objectiveText.contains("war")

        val visibleObjective = civInfo.viewableTiles
            .filter { it.isCityCenter() }
            .mapNotNull { it.getCity() }
            .filter { city -> city.civ != civInfo && (primaryRival == null || city.civ.civName == primaryRival) }
            .let { visibleCities ->
                when {
                    objectiveText.contains("capital") ->
                        visibleCities.firstOrNull { it.isCapital() } ?: visibleCities.firstOrNull()
                    else -> visibleCities.firstOrNull()
                }
            }
        val anchor = if (visibleObjective == null) {
            memory.worldModel.anchors
                .filter { it.x != null && it.y != null && (primaryRival == null || it.civName == null || it.civName == primaryRival) }
                .let { anchors ->
                    if (objectiveText.contains("capital")) {
                        anchors.maxByOrNull { anchorCandidate ->
                            (if (anchorCandidate.kind == "capital") 1000 else 0) + anchorCandidate.lastConfirmedTurn
                        }
                    } else {
                        anchors.maxByOrNull { anchorCandidate -> anchorCandidate.lastConfirmedTurn }
                    }
                }
        } else {
            null
        }

        val objective = when {
            visibleObjective != null -> ResolvedObjective(
                x = visibleObjective.location.x,
                y = visibleObjective.location.y,
                cityName = visibleObjective.name,
                preferCapital = objectiveText.contains("capital") || visibleObjective.isCapital(),
            )
            anchor?.x != null && anchor.y != null -> ResolvedObjective(
                x = anchor.x!!,
                y = anchor.y!!,
                cityName = anchor.label,
                preferCapital = objectiveText.contains("capital") || anchor.kind == "capital",
            )
            else -> null
        }
        return AttackConversionContext(
            objectivePressure = objectivePressure,
            frontlineShortage = blockerText.contains("melee") ||
                blockerText.contains("frontline") ||
                blockerText.contains("capture"),
            objective = objective,
        )
    }

    private fun axialDistance(x1: Int, y1: Int, x2: Int, y2: Int): Int {
        val dx = x1 - x2
        val dy = y1 - y2
        return (kotlin.math.abs(dx) + kotlin.math.abs(dy) + kotlin.math.abs(dx + dy)) / 2
    }

    private fun isSurfacedDirectActionType(actionType: UnitActionType): Boolean {
        return actionType in setOf(
            UnitActionType.Upgrade,
            UnitActionType.Pillage,
            UnitActionType.Explore,
            UnitActionType.StopExploration,
            UnitActionType.HurryResearch,
            UnitActionType.HurryPolicy,
            UnitActionType.HurryWonder,
            UnitActionType.HurryBuilding,
            UnitActionType.ConductTradeMission,
            UnitActionType.CreateImprovement,
            UnitActionType.TriggerUnique,
            UnitActionType.Fortify,
            UnitActionType.FortifyUntilHealed,
            UnitActionType.Sleep,
            UnitActionType.SleepUntilHealed,
        )
    }

    private fun directActionCategory(actionType: UnitActionType): String {
        return when (actionType) {
            UnitActionType.Upgrade -> "upgrade"
            UnitActionType.Pillage -> "combat"
            UnitActionType.Explore,
            UnitActionType.StopExploration -> "explore"
            UnitActionType.HurryResearch,
            UnitActionType.HurryPolicy,
            UnitActionType.HurryWonder,
            UnitActionType.HurryBuilding,
            UnitActionType.ConductTradeMission,
            UnitActionType.CreateImprovement,
            UnitActionType.TriggerUnique,
                -> "great_person"
            else -> "special"
        }
    }

    private fun describeDirectAction(unit: MapUnit, action: UnitAction): String {
        return when (action.type) {
            UnitActionType.Upgrade -> "Spend gold to upgrade this unit immediately if the upgrade is available."
            UnitActionType.Pillage -> "Pillage the current tile for immediate war tempo, healing, or disruption."
            UnitActionType.Explore -> "Enable automated exploration. The unit will keep scouting on its own, heal if needed, and stop only when exploration is exhausted or you cancel it."
            UnitActionType.StopExploration -> "Stop automated exploration and return the unit to direct control."
            UnitActionType.HurryResearch -> "Consume this great person for an immediate science burst."
            UnitActionType.HurryPolicy -> "Consume this great person for an immediate culture burst."
            UnitActionType.HurryWonder -> "Consume this great person to rush the current wonder in this city."
            UnitActionType.HurryBuilding -> "Consume this great person to rush the current construction in this city."
            UnitActionType.ConductTradeMission -> "Use this great merchant for gold and city-state influence."
            UnitActionType.CreateImprovement -> "Consume this unit to create a permanent great-person improvement on this tile."
            UnitActionType.TriggerUnique -> "Use the unit's special one-shot ability now."
            UnitActionType.Fortify -> "Fortify this unit in place."
            UnitActionType.FortifyUntilHealed -> "Fortify and keep healing until healthy."
            UnitActionType.Sleep -> "Put this unit to sleep without moving."
            UnitActionType.SleepUntilHealed -> "Sleep and keep healing until healthy."
            else -> "${unit.name} can use ${action.title}."
        }
    }

    private fun agentFacingActionTitle(action: UnitAction): String {
        return when (action.type) {
            UnitActionType.Explore -> "start auto-explore"
            UnitActionType.StopExploration -> "stop auto-explore"
            else -> action.title
        }
    }

    private fun scoreDirectAction(unit: MapUnit, action: UnitAction): Int {
        var score = when (action.type) {
            UnitActionType.HurryResearch -> 120
            UnitActionType.HurryPolicy -> 105
            UnitActionType.HurryWonder,
            UnitActionType.HurryBuilding -> 110
            UnitActionType.ConductTradeMission -> 100
            UnitActionType.CreateImprovement -> 95
            UnitActionType.TriggerUnique -> 90
            UnitActionType.Upgrade -> 85
            UnitActionType.Pillage -> 75
            UnitActionType.Explore -> if (unit.name == "Scout") 72 else 52
            UnitActionType.StopExploration -> 45
            UnitActionType.FortifyUntilHealed,
            UnitActionType.SleepUntilHealed -> 40
            UnitActionType.Fortify,
            UnitActionType.Sleep -> 25
            else -> 20
        }
        if (unit.health < 60 && action.type in setOf(UnitActionType.FortifyUntilHealed, UnitActionType.SleepUntilHealed)) score += 25
        if (unit.health == 100 && action.type in setOf(UnitActionType.FortifyUntilHealed, UnitActionType.SleepUntilHealed)) score -= 20
        return score
    }

    private fun isActionNowCurrentState(unit: MapUnit, actionType: UnitActionType): Boolean {
        return when (actionType) {
            UnitActionType.Explore -> unit.isExploring()
            UnitActionType.StopExploration -> !unit.isExploring()
            UnitActionType.Fortify -> unit.isFortified() && !unit.isActionUntilHealed()
            UnitActionType.FortifyUntilHealed -> unit.isFortifyingUntilHealed()
            UnitActionType.Sleep -> unit.isSleeping() && !unit.isActionUntilHealed()
            UnitActionType.SleepUntilHealed -> unit.isSleepingUntilHealed()
            else -> false
        }
    }

    internal data class AgentUnitOptionContext(
        val candidates: Map<String, AgentUnitRuntimeCandidate>,
        val observationsByUnitId: Map<Int, List<UnitOptionCandidateObservation>>,
    )

    internal data class AgentUnitRuntimeCandidate(
        val observation: UnitOptionCandidateObservation,
        val validate: (Civilization) -> String?,
        val execute: (Civilization) -> Boolean,
        val successMessage: String,
    )

    private data class AttackConversionContext(
        val objectivePressure: Boolean,
        val frontlineShortage: Boolean,
        val objective: ResolvedObjective? = null,
    )

    private enum class BattleTheaterSlotKind {
        Staging,
        Reserve,
        Recovery,
        RangedAssault,
        MeleeAssault,
        Captor,
    }

    private data class BattleTheaterSlot(
        val tile: Tile,
        val kind: BattleTheaterSlotKind,
        val score: Int,
    )

    internal class TheaterReservationTracker {
        private val reservedTilesByObjective = linkedMapOf<String, LinkedHashSet<HexCoord>>()

        internal fun reserve(objectiveX: Int, objectiveY: Int, tile: Tile) {
            val reservations = reservedTilesByObjective.getOrPut("$objectiveX,$objectiveY") { linkedSetOf() }
            reservations += HexCoord(tile.position.x, tile.position.y)
        }

        internal fun isReserved(objectiveX: Int, objectiveY: Int, tile: Tile): Boolean {
            return HexCoord(tile.position.x, tile.position.y) in (reservedTilesByObjective["$objectiveX,$objectiveY"] ?: return false)
        }

        internal fun reservedObjectivesCount(): Int = reservedTilesByObjective.size

        internal fun reservedSlotsCount(): Int = reservedTilesByObjective.values.sumOf { it.size }
    }

    private data class ResolvedObjective(
        val x: Int,
        val y: Int,
        val cityName: String? = null,
        val preferCapital: Boolean = false,
    ) {
        fun matches(name: String, isCapital: Boolean): Boolean {
            if (preferCapital && isCapital) return true
            return cityName != null && cityName.equals(name, ignoreCase = true)
        }
    }
}
