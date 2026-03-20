package com.unciv.logic.automation.agent

import com.unciv.Constants
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.UnitAction
import com.unciv.models.UnitActionType
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.unique.GameContext

object AgentWorkerJobPlanner {
    private val preferredActionTypes = listOf(
        UnitActionType.Repair,
        UnitActionType.CreateImprovement,
        UnitActionType.ConstructImprovement,
        UnitActionType.ConnectRoad,
    )

    internal fun findWorkerJobs(unit: MapUnit, currentAssignment: UnitAssignmentMemory? = null): List<WorkerJob> {
        if (!unit.cache.hasUniqueToBuildImprovements || !unit.hasMovement()) return emptyList()
        val civInfo = unit.civ
        val candidateTiles = sequenceOf(unit.getTile()) + unit.movement.getDistanceToTiles().keys.asSequence()
        val jobsByTile = candidateTiles
            .distinct()
            .mapNotNull { tile -> buildWorkerJob(unit, civInfo, tile) }
            .associateBy { "${it.tileX},${it.tileY}" }
            .toMutableMap()

        val assignmentTarget = currentAssignment
            ?.takeIf { it.role == "improve_tile" && it.targetX != null && it.targetY != null }
        if (assignmentTarget != null) {
            val targetTile = civInfo.gameInfo.tileMap.values.firstOrNull {
                it.position.x == assignmentTarget.targetX && it.position.y == assignmentTarget.targetY
            }
            if (targetTile != null && targetTile.getOwner() == civInfo) {
                val stickyJob = jobsByTile["${targetTile.position.x},${targetTile.position.y}"]
                    ?: WorkerJob(
                        priority = 65,
                        tileX = targetTile.position.x,
                        tileY = targetTile.position.y,
                        description = buildString {
                            append("Continue the current worker assignment")
                            assignmentTarget.detail?.let { append(": $it") }
                            append(".")
                        },
                        intentLabel = assignmentTarget.detail,
                        isCommittedAssignment = true,
                    )
                jobsByTile["${targetTile.position.x},${targetTile.position.y}"] = boostCommittedJob(unit, stickyJob, assignmentTarget)
            }
        }

        return jobsByTile.values
            .sortedByDescending { it.priority }
            .take(2)
            .toList()
    }

    internal fun preferredCurrentAction(availableActions: List<UnitAction>): UnitAction? {
        return preferredActionTypes
            .asSequence()
            .mapNotNull { preferred -> availableActions.firstOrNull { it.type == preferred && it.action != null } }
            .firstOrNull()
    }

    private fun buildWorkerJob(unit: MapUnit, civInfo: Civilization, tile: Tile): WorkerJob? {
        if (tile.getOwner() != civInfo) return null
        val context = GameContext(civInfo = civInfo, unit = unit, tile = tile)

        if (tile.improvementInProgress != null) {
            val improvementName = tile.improvementInProgress!!
            if (improvementName == Constants.repair) {
                val repairName = tile.getImprovementToRepair()?.name ?: "tile infrastructure"
                return WorkerJob(
                    priority = 95,
                    tileX = tile.position.x,
                    tileY = tile.position.y,
                    description = "$repairName at (${tile.position.x}, ${tile.position.y}) is already being repaired and should be finished.",
                    intentLabel = "repair $repairName",
                    improvementName = Constants.repair,
                    isRepair = true,
                    isInProgress = true,
                    isCommittedAssignment = true,
                )
            }

            val inProgressImprovement = civInfo.gameInfo.ruleset.tileImprovements[improvementName]
            if (inProgressImprovement != null && unit.canBuildImprovement(inProgressImprovement, tile)) {
                return WorkerJob(
                    priority = 95,
                    tileX = tile.position.x,
                    tileY = tile.position.y,
                    description = "$improvementName at (${tile.position.x}, ${tile.position.y}) is already in progress and should be finished before switching jobs.",
                    intentLabel = "continue $improvementName",
                    improvementName = improvementName,
                    isInProgress = true,
                    isCommittedAssignment = true,
                )
            }
        }

        if (tile.isPillaged()) {
            val repairName = tile.getImprovementToRepair()?.name ?: "tile infrastructure"
            return WorkerJob(
                priority = 90,
                tileX = tile.position.x,
                tileY = tile.position.y,
                description = "$repairName at (${tile.position.x}, ${tile.position.y}) is pillaged and needs repair.",
                intentLabel = "repair $repairName",
                improvementName = Constants.repair,
                isRepair = true,
            )
        }

        val resource = tile.tileResource
        if (resource != null && civInfo.canSeeResource(resource) && !tile.providesResources(civInfo)) {
            val improvementName = resource.getImprovingImprovement(tile, context)
            if (improvementName != null) {
                val improvement = civInfo.gameInfo.ruleset.tileImprovements[improvementName]
                if (improvement != null &&
                    unit.canBuildImprovement(improvement, tile) &&
                    tile.improvementFunctions.canBuildImprovement(improvement, context)
                ) {
                    val priority = when (resource.resourceType) {
                        ResourceType.Luxury -> 85
                        ResourceType.Strategic -> 75
                        ResourceType.Bonus -> 45
                    }
                    return WorkerJob(
                        priority = priority,
                        tileX = tile.position.x,
                        tileY = tile.position.y,
                        description = "${resource.name} at (${tile.position.x}, ${tile.position.y}) is a strong worker target (${improvementName}).",
                        intentLabel = "build $improvementName for ${resource.name}",
                        improvementName = improvementName,
                    )
                }
            }
        }

        if (tile.isWorked() && !tile.isCityCenter() && tile.getUnpillagedTileImprovement() == null) {
            return WorkerJob(
                priority = 35,
                tileX = tile.position.x,
                tileY = tile.position.y,
                description = "The worked tile at (${tile.position.x}, ${tile.position.y}) is unimproved and worth worker attention.",
                intentLabel = "improve worked tile",
            )
        }

        return null
    }

    private fun boostCommittedJob(
        unit: MapUnit,
        job: WorkerJob,
        assignment: UnitAssignmentMemory,
    ): WorkerJob {
        val onTargetTile = unit.getTile().position.x == job.tileX && unit.getTile().position.y == job.tileY
        val stickyPrefix = if (onTargetTile) {
            "This worker is already on its assigned tile and should finish the job before switching."
        } else {
            "This worker is already committed to the target tile and should keep moving unless a much stronger job appears."
        }
        return job.copy(
            priority = job.priority + if (onTargetTile) 45 else 25,
            description = "$stickyPrefix ${job.description}",
            intentLabel = assignment.detail ?: job.intentLabel,
            isCommittedAssignment = true,
        )
    }

    internal data class WorkerJob(
        val priority: Int,
        val tileX: Int,
        val tileY: Int,
        val description: String,
        val intentLabel: String? = null,
        val improvementName: String? = null,
        val isRepair: Boolean = false,
        val isInProgress: Boolean = false,
        val isCommittedAssignment: Boolean = false,
    )
}
