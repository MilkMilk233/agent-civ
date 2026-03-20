package com.unciv.logic.automation.agent

import com.unciv.Constants
import com.unciv.logic.automation.unit.CityLocationTileRanker
import com.unciv.logic.battle.AttackableTile
import com.unciv.logic.battle.Battle
import com.unciv.logic.battle.BattleDamage
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.battle.TargetHelper
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.mapunit.MapUnit
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

    internal fun build(civInfo: Civilization, memory: AgentMemory): AgentUnitOptionContext {
        val candidates = LinkedHashMap<String, AgentUnitRuntimeCandidate>()
        val observationsByUnitId = LinkedHashMap<Int, MutableList<UnitOptionCandidateObservation>>()

        for (unit in civInfo.units.getCivUnits().sortedBy { it.id }) {
            val observations = observationsByUnitId.getOrPut(unit.id) { arrayListOf() }
            buildAttackCandidates(unit).forEach { candidate ->
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
        }

        return AgentUnitOptionContext(
            candidates = candidates,
            observationsByUnitId = observationsByUnitId,
        )
    }

    private fun buildAttackCandidates(unit: MapUnit): List<AgentUnitRuntimeCandidate> {
        if (!unit.canAttack()) return emptyList()
        val combatant = MapUnitCombatant(unit)
        return TargetHelper.getAttackableEnemies(unit, unit.movement.getDistanceToTiles())
            .sortedByDescending { scoreAttackCandidate(combatant, it) }
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

    private fun scoreAttackCandidate(attacker: MapUnitCombatant, attackableTile: AttackableTile): Int {
        val defender = attackableTile.combatant ?: return 0
        var score = 0
        val damageToDefender = BattleDamage.calculateDamageToDefender(attacker, defender, attackableTile.tileToAttackFrom)
        val damageToAttacker = BattleDamage.calculateDamageToAttacker(attacker, defender, attackableTile.tileToAttackFrom)
        score += damageToDefender * 2
        score -= damageToAttacker
        if (defender.isCivilian()) score += 90
        if (defender is com.unciv.logic.battle.CityCombatant) score += 45
        if (defender.getHealth() <= damageToDefender) score += 50
        if (attackableTile.tileToAttack == attackableTile.tileToAttackFrom) score -= 15
        return score
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
}
