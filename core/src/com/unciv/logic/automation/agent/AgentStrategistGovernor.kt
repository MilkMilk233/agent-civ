package com.unciv.logic.automation.agent

object AgentStrategistGovernor {
    fun buildBrief(
        memory: AgentMemory,
        observation: AgentObservation,
        empireObservation: AgentEmpireObservation,
        refreshRequest: AgentStrategistRefreshRequest,
    ): AgentStrategistBrief {
        val citySnapshots = observation.citiesNeedingAttention
            .sortedByDescending { cityStrategistScore(it) }
            .take(4)
            .map { city ->
                AgentStrategistCitySnapshot(
                    name = city.name,
                    population = city.population,
                    currentConstruction = city.currentConstruction,
                    turnsToGrowth = city.turnsToGrowth,
                    cityStrength = city.cityStrength,
                    reasons = city.reasons.take(4),
                    topConstructionChoices = city.topConstructionChoices.take(4),
                )
            }

        val unitSnapshots = observation.actionableUnits
            .sortedByDescending { unitStrategistScore(it) }
            .take(5)
            .map { unit ->
                AgentStrategistUnitSnapshot(
                    id = unit.id,
                    name = unit.name,
                    role = unit.role,
                    health = unit.health,
                    hasMovement = unit.hasMovement,
                    nearbyHostileUnits = unit.nearbyHostileUnits,
                    nearbyHostileCities = unit.nearbyHostileCities,
                    reasons = unit.reasons.take(4),
                    assignmentProgress = unit.assignmentProgress,
                )
            }

        val progressInMotion = AgentStrategicGovernor.buildProgressSummary(memory, observation, empireObservation)

        return AgentStrategistBrief(
            refreshRequest = refreshRequest,
            gameContext = empireObservation.gameContext,
            enabledVictoryTypes = empireObservation.enabledVictoryTypes,
            empireSummary = observation.empireSummary,
            currentResearch = empireObservation.currentResearch,
            currentResearchStatus = empireObservation.currentResearchStatus,
            currentRoadmap = memory.strategicRoadmap.takeIf { it.doctrine.isNotBlank() },
            rivalThreats = empireObservation.victoryThreats.take(2),
            macroFacts = empireObservation.macroFacts
                .filterNot { it.category == "victory" && it.headline.startsWith("Current best victory path:", ignoreCase = true) }
                .take(6),
            progressInMotion = progressInMotion,
            citySnapshots = citySnapshots,
            unitSnapshots = unitSnapshots,
            recentFailures = memory.recentFailures.takeLast(4).map { it.summary },
        )
    }

    private fun cityStrategistScore(city: CityAttentionObservation): Int {
        var score = city.population * 10 + city.cityStrength
        if (city.isCapital) score += 40
        if (city.nearbyHostileUnits > 0 || city.nearbyHostileCities > 0) score += 60
        if ((city.turnsToGrowth ?: Int.MAX_VALUE) <= 2) score += 20
        score += city.reasons.size * 5
        return score
    }

    private fun unitStrategistScore(unit: ActionableUnitObservation): Int {
        var score = when (unit.role) {
            "settler" -> 120
            "melee", "ranged", "siege", "naval_melee", "naval_ranged" -> 80
            "worker" -> 55
            "scout" -> 40
            else -> 30
        }
        if (unit.nearbyHostileUnits > 0 || unit.nearbyHostileCities > 0) score += 40
        if (unit.assignmentProgress != null) score += 15
        return score
    }
}
