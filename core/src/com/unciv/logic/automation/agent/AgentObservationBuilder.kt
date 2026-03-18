package com.unciv.logic.automation.agent

import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.PerpetualConstruction
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActions

object AgentObservationBuilder {
    private const val maxConstructionsPerCity = 14
    private const val maxReachableTilesPerUnit = 20

    fun build(civInfo: Civilization): AgentObservation {
        civInfo.updateStatsForNextTurn()

        val cities = civInfo.cities
            .sortedBy { it.location.toString() }
            .map { city ->
                val cityConstructions = city.cityConstructions
                val constructionOptions = (
                    cityConstructions.getBuildableBuildings().map { it.name } +
                        cityConstructions.getConstructableUnits().map { it.name } +
                        PerpetualConstruction.perpetualConstructionsMap.values.asSequence()
                            .filter { it.isBuildable(cityConstructions) }
                            .map { it.name }
                    )
                    .distinct()
                    .sorted()
                    .take(maxConstructionsPerCity)
                    .toList()

                CityObservation(
                    x = city.location.x,
                    y = city.location.y,
                    name = city.name,
                    population = city.population.population,
                    health = city.health,
                    currentConstruction = cityConstructions.currentConstructionName(),
                    availableConstructions = constructionOptions,
                )
            }

        val units = civInfo.units.getCivUnits()
            .sortedBy { it.id }
            .map { unit ->
                val actions = UnitActions.getUnitActions(unit)
                    .filter { it.action != null }
                    .map { it.type.name }
                    .distinct()
                    .sorted()
                    .toList()

                val reachableTiles = unit.movement.getDistanceToTiles().keys
                    .asSequence()
                    .map { TileRef(it.position.x, it.position.y) }
                    .distinct()
                    .take(maxReachableTilesPerUnit)
                    .toList()

                UnitObservation(
                    id = unit.id,
                    x = unit.getTile().position.x,
                    y = unit.getTile().position.y,
                    name = unit.name,
                    health = unit.health,
                    hasMovement = unit.hasMovement(),
                    movementPoints = unit.getMovementString(),
                    unitActions = actions,
                    reachableTiles = reachableTiles,
                )
            }
            .toList()

        val knownCivs = civInfo.getKnownCivs()
            .sortedBy { it.civName }
            .map { other ->
                val diplomaticStatus = civInfo.getDiplomacyManager(other)?.diplomaticStatus?.name ?: "Unknown"
                KnownCivObservation(
                    civName = other.civName,
                    diplomaticStatus = diplomaticStatus,
                    isAtWar = civInfo.isAtWarWith(other),
                    isCityState = other.isCityState,
                )
            }
            .toList()

        return AgentObservation(
            turn = civInfo.gameInfo.turns,
            civName = civInfo.civName,
            isAtWar = civInfo.isAtWar(),
            gold = civInfo.gold,
            sciencePerTurn = civInfo.stats.statsForNextTurn.science.toInt(),
            culturePerTurn = civInfo.stats.statsForNextTurn.culture.toInt(),
            faithPerTurn = civInfo.stats.statsForNextTurn.faith.toInt(),
            happiness = civInfo.getHappiness(),
            visibleTiles = civInfo.viewableTiles.size,
            cities = cities,
            units = units,
            knownCivs = knownCivs,
        )
    }
}
