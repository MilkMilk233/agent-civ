package com.unciv.logic.automation.agent

object AgentStrategistGovernor {
    fun buildBrief(
        memory: AgentMemory,
        observation: AgentObservation,
        empireObservation: AgentEmpireObservation,
        refreshRequest: AgentStrategistRefreshRequest,
    ): AgentStrategistBrief {
        val citySnapshots = observation.cities
            .sortedByDescending { cityStrategistScore(it) }
            .map { city ->
                val projectOptionLimit = if (city.project?.status == "needs_choice") {
                    city.actions.chooseProject.size
                } else {
                    3
                }
                AgentStrategistCitySnapshot(
                    x = city.x,
                    y = city.y,
                    name = city.name,
                    population = city.state.population,
                    focus = city.state.focus,
                    currentProject = city.project?.name,
                    projectStatus = city.project?.status,
                    projectTurnsLeft = city.project?.turnsLeft,
                    projectProductionInvested = city.project?.productionInvested,
                    projectProductionRemaining = city.project?.productionRemaining,
                    productionPerTurn = city.state.productionPerTurn,
                    foodPerTurn = city.state.foodPerTurn,
                    turnsToGrowth = city.state.turnsToGrowth,
                    turnsToStarvation = city.state.turnsToStarvation,
                    cityStrength = city.state.cityStrength,
                    nearbyHostileUnits = city.state.nearbyHostileUnits,
                    nearbyHostileCities = city.state.nearbyHostileCities,
                    signals = city.signals.take(5),
                    projectOptions = city.actions.chooseProject.take(projectOptionLimit).map { option ->
                        AgentStrategistCityProjectOptionSnapshot(
                            title = option.title,
                            estimatedTurns = option.estimatedTurns,
                            goldCost = option.goldCost,
                            switchCost = option.switchCost,
                            yieldHints = option.yieldHints,
                        )
                    },
                )
            }

        val unitSnapshots = observation.units
            .sortedByDescending { unitStrategistScore(it, empireObservation.gameContext.contactComplete) }
            .map { unit ->
                AgentStrategistUnitSnapshot(
                    id = unit.id,
                    x = unit.x,
                    y = unit.y,
                    name = unit.name,
                    role = unit.role,
                    detailLevel = unit.detailLevel,
                    health = unit.health,
                    hasMovement = unit.hasMovement,
                    movementPoints = unit.movementPoints,
                    strength = unit.strength,
                    rangedStrength = unit.rangedStrength,
                    range = unit.range,
                    nearbyHostileUnits = unit.nearbyHostileUnits,
                    nearbyHostileCities = unit.nearbyHostileCities,
                    reasons = unit.reasons.take(4),
                    localFacts = unit.localFacts.take(4),
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
            rivalThreats = empireObservation.victoryThreats,
            stateFacts = empireObservation.stateFacts,
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
        val desiredMilitaryCoverage = desiredMilitaryCoverage(observation, gameContext)
        val primaryThreat = empireObservation.victoryThreats.firstOrNull()

        val currentPhaseReality = when {
            primaryThreat?.threatLevel == "critical" -> "contest_rival"
            gameContext.duelLike && !gameContext.contactComplete && observation.turn >= 20 -> "find_rival"
            observation.empireSummary.cityCount < desiredCityFloor -> "expand"
            desiredMilitaryCoverage > 0 && observation.empireSummary.militaryUnitCount < desiredMilitaryCoverage -> "build_force"
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

        val militaryStatus = if (desiredMilitaryCoverage <= 0) {
            "not_applicable"
        } else if (observation.empireSummary.militaryUnitCount >= desiredMilitaryCoverage) {
            "covered (${observation.empireSummary.militaryUnitCount}/$desiredMilitaryCoverage units)"
        } else {
            "thin (${observation.empireSummary.militaryUnitCount}/$desiredMilitaryCoverage units)"
        }

        val activeGoals = roadmap.immediateObjectives + roadmap.nearTermGoals
        val completedGoals = activeGoals
            .mapNotNull { goal ->
                when (evaluateRoadmapGoal(goal, observation, empireObservation, desiredCityFloor, desiredMilitaryCoverage)) {
                    GoalState.Completed -> goal
                    else -> null
                }
            }

        val staleGoals = buildList {
            if (roadmap.phase.equals("opener", ignoreCase = true) && observation.turn >= 45) {
                add("Roadmap is still in opener phase at turn ${observation.turn}.")
            }
            addAll(
                activeGoals.mapNotNull { goal ->
                    when (evaluateRoadmapGoal(goal, observation, empireObservation, desiredCityFloor, desiredMilitaryCoverage)) {
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
            if (desiredMilitaryCoverage > 0 && observation.empireSummary.militaryUnitCount < desiredMilitaryCoverage) {
                add("Military coverage is still thin for the current cities and contact state.")
            }
            if (observation.empireSummary.happiness <= 1) {
                add("Happiness is low enough to constrain further greed.")
            }
            if (empireObservation.gold >= 1000 &&
                empireObservation.macroCandidates.any { it.candidateId == "macro:gold:auto" }
            ) {
                add("Gold reserve is high relative to currently surfaced spend options.")
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

    private fun cityStrategistScore(city: AgentCityObservation): Int {
        var score = city.state.population * 10 + city.state.cityStrength
        if (city.state.isCapital) score += 40
        if (city.state.nearbyHostileUnits > 0 || city.state.nearbyHostileCities > 0) score += 60
        if ((city.state.turnsToGrowth ?: Int.MAX_VALUE) <= 2) score += 20
        score += city.signals.size * 5
        return score
    }

    private fun unitStrategistScore(unit: AgentUnitObservation, contactComplete: Boolean): Int {
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
        desiredMilitaryCoverage: Int,
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
                desiredMilitaryCoverage > 0 && militaryCount >= desiredMilitaryCoverage -> GoalState.Completed
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

    private fun desiredMilitaryCoverage(
        observation: AgentObservation,
        gameContext: AgentPublicGameContextObservation,
    ): Int {
        val cityCount = observation.empireSummary.cityCount
        if (cityCount <= 0) return 0
        var coverage = cityCount
        if (observation.empireSummary.isAtWar) coverage += 1
        else if (gameContext.contactComplete && cityCount >= 2) coverage += 1
        return coverage
    }
}
