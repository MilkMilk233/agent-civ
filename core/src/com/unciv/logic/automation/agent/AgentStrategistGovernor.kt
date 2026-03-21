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
            .sortedByDescending { unitStrategistScore(it, empireObservation.gameContext.contactComplete) }
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
        val roadmapReality = buildRoadmapReality(memory, observation, empireObservation)

        return AgentStrategistBrief(
            refreshRequest = refreshRequest,
            gameContext = empireObservation.gameContext,
            enabledVictoryTypes = empireObservation.enabledVictoryTypes,
            empireSummary = observation.empireSummary,
            currentResearch = empireObservation.currentResearch,
            currentResearchStatus = empireObservation.currentResearchStatus,
            currentRoadmap = memory.strategicRoadmap.takeIf { it.doctrine.isNotBlank() },
            roadmapReality = roadmapReality,
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

    private fun buildRoadmapReality(
        memory: AgentMemory,
        observation: AgentObservation,
        empireObservation: AgentEmpireObservation,
    ): AgentStrategistRoadmapRealityObservation? {
        val roadmap = memory.strategicRoadmap.takeIf { it.doctrine.isNotBlank() } ?: return null
        val gameContext = empireObservation.gameContext
        val desiredCityFloor = desiredCityFloor(gameContext, observation.turn)
        val desiredMilitaryFloor = desiredMilitaryFloor(gameContext, observation.turn)
        val primaryThreat = empireObservation.victoryThreats.firstOrNull()

        val currentPhaseReality = when {
            primaryThreat?.threatLevel == "critical" -> "contest_rival"
            gameContext.duelLike && !gameContext.contactComplete && observation.turn >= 20 -> "find_rival"
            observation.empireSummary.cityCount < desiredCityFloor -> "expand"
            observation.empireSummary.militaryUnitCount < desiredMilitaryFloor -> "build_force"
            else -> "convert_advantage"
        }

        val expansionStatus = if (observation.empireSummary.cityCount >= desiredCityFloor) {
            "met (${observation.empireSummary.cityCount}/$desiredCityFloor cities)"
        } else {
            "behind (${observation.empireSummary.cityCount}/$desiredCityFloor cities)"
        }

        val contactStatus = when {
            gameContext.contactComplete -> "complete"
            gameContext.duelLike && observation.turn >= 20 -> "urgent_missing"
            else -> "incomplete"
        }

        val militaryStatus = if (observation.empireSummary.militaryUnitCount >= desiredMilitaryFloor) {
            "met (${observation.empireSummary.militaryUnitCount}/$desiredMilitaryFloor units)"
        } else {
            "below_floor (${observation.empireSummary.militaryUnitCount}/$desiredMilitaryFloor units)"
        }

        val completedGoals = roadmap.midTermGoals
            .mapNotNull { goal ->
                when (evaluateRoadmapGoal(goal, observation, empireObservation, desiredCityFloor, desiredMilitaryFloor)) {
                    GoalState.Completed -> goal
                    else -> null
                }
            }

        val staleGoals = buildList {
            if (roadmap.phase.equals("opener", ignoreCase = true) && observation.turn >= 45) {
                add("Roadmap is still in opener phase at turn ${observation.turn}.")
            }
            addAll(
                roadmap.midTermGoals.mapNotNull { goal ->
                    when (evaluateRoadmapGoal(goal, observation, empireObservation, desiredCityFloor, desiredMilitaryFloor)) {
                        GoalState.Stale -> "Stale goal: $goal"
                        else -> null
                    }
                }
            )
        }.take(4)

        val urgentProblems = buildList {
            if (gameContext.duelLike && !gameContext.contactComplete && observation.turn >= 20) {
                add("You still have not found the only rival in this duel.")
            }
            if (observation.empireSummary.militaryUnitCount < desiredMilitaryFloor) {
                add("Military floor is still below the roadmap target.")
            }
            if (observation.empireSummary.happiness <= 1) {
                add("Happiness is low enough to constrain further greed.")
            }
            if (empireObservation.macroFacts.any { it.category == "economy" && it.headline.contains("gold reserve", ignoreCase = true) }) {
                add("Large gold reserves still need to be converted into tempo.")
            }
        }.take(4)

        return AgentStrategistRoadmapRealityObservation(
            currentPhaseReality = currentPhaseReality,
            expansionStatus = expansionStatus,
            contactStatus = contactStatus,
            militaryStatus = militaryStatus,
            completedGoals = completedGoals,
            staleGoals = staleGoals,
            urgentProblems = urgentProblems,
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

    private fun unitStrategistScore(unit: ActionableUnitObservation, contactComplete: Boolean): Int {
        var score = when (unit.role) {
            "settler" -> 120
            "melee", "ranged", "siege", "naval_melee", "naval_ranged" -> 80
            "worker" -> 55
            "scout" -> 40
            else -> 30
        }
        if (!contactComplete && unit.hasMovement && unit.unitOptionCandidates.any { it.candidateId.startsWith("unitexplore:") }) {
            score += if (unit.role == "scout") 80 else 45
        }
        if (unit.nearbyHostileUnits > 0 || unit.nearbyHostileCities > 0) score += 40
        if (unit.assignmentProgress != null) score += 15
        return score
    }

    private enum class GoalState {
        Active,
        Completed,
        Stale,
    }

    private fun evaluateRoadmapGoal(
        goal: String,
        observation: AgentObservation,
        empireObservation: AgentEmpireObservation,
        desiredCityFloor: Int,
        desiredMilitaryFloor: Int,
    ): GoalState {
        val lower = goal.lowercase()
        val cityCount = observation.empireSummary.cityCount
        val militaryCount = observation.empireSummary.militaryUnitCount
        val contactComplete = empireObservation.gameContext.contactComplete
        return when {
            "second city" in lower && cityCount >= 2 -> GoalState.Completed
            "third city" in lower && cityCount >= 3 -> GoalState.Completed
            "fourth city" in lower && cityCount >= 4 -> GoalState.Completed
            "expand" in lower && cityCount >= desiredCityFloor -> GoalState.Completed
            ("contact" in lower || "find rival" in lower || "locate rival" in lower || "scout" in lower) && contactComplete -> GoalState.Completed
            ("spearmen" in lower || "archer" in lower || "military" in lower || "combat unit" in lower || "force" in lower) &&
                militaryCount >= desiredMilitaryFloor -> GoalState.Completed
            "second city" in lower && cityCount >= 3 -> GoalState.Stale
            "third city" in lower && cityCount >= 4 -> GoalState.Stale
            ("contact" in lower || "find rival" in lower || "locate rival" in lower || "scout" in lower) && contactComplete -> GoalState.Stale
            else -> GoalState.Active
        }
    }

    private fun desiredCityFloor(gameContext: AgentPublicGameContextObservation, turn: Int): Int {
        return when {
            gameContext.duelLike && gameContext.mapSize == "Tiny" && turn >= 120 -> 4
            gameContext.duelLike && gameContext.mapSize == "Tiny" -> 3
            gameContext.expansionWindow == "narrow" -> 4
            gameContext.expansionWindow == "medium" -> 5
            else -> 6
        }
    }

    private fun desiredMilitaryFloor(gameContext: AgentPublicGameContextObservation, turn: Int): Int {
        return when {
            gameContext.duelLike && turn < 35 -> 2
            gameContext.duelLike && turn < 70 -> 4
            gameContext.duelLike -> 6
            turn < 70 -> 3
            turn < 140 -> 5
            else -> 7
        }
    }
}
