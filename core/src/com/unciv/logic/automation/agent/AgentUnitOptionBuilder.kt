package com.unciv.logic.automation.agent

import com.unciv.logic.automation.unit.CityLocationTileRanker
import com.unciv.logic.battle.AttackableTile
import com.unciv.logic.battle.Battle
import com.unciv.logic.battle.BattleDamage
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.battle.TargetHelper
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.UnitActionType
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActions
import kotlin.math.roundToInt

object AgentUnitOptionBuilder {
    private const val maxAttackCandidatesPerUnit = 3
    private const val maxSettlerCandidatesPerUnit = 2

    internal fun build(civInfo: Civilization): AgentUnitOptionContext {
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
