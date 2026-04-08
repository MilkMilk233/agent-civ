package com.unciv.logic.automation.agent

object AgentStrategistGovernor {
    fun buildBrief(
        memory: AgentMemory,
        observation: AgentObservation,
        empireObservation: AgentEmpireObservation,
        refreshRequest: AgentStrategistRefreshRequest,
    ): AgentStrategistBrief {
        val currentRoadmap = memory.strategicRoadmap.takeIf { it.doctrine.isNotBlank() }
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
        val lastStrategistReport = currentRoadmap?.let(::buildLastStrategistReport)
        val sinceLastReviewFacts = buildSinceLastReviewFacts(currentRoadmap, observation, empireObservation)

        return AgentStrategistBrief(
            refreshRequest = refreshRequest,
            gameContext = empireObservation.gameContext,
            enabledVictoryTypes = empireObservation.enabledVictoryTypes,
            empireSummary = observation.empireSummary,
            currentResearch = empireObservation.currentResearch,
            currentResearchStatus = empireObservation.currentResearchStatus,
            currentRoadmap = currentRoadmap,
            lastStrategistReport = lastStrategistReport,
            sinceLastReviewFacts = sinceLastReviewFacts,
            roadmapReality = roadmapReality,
            rivalThreats = empireObservation.victoryThreats,
            stateFacts = empireObservation.stateFacts,
            progressInMotion = progressInMotion,
            citySnapshots = citySnapshots,
            unitSnapshots = unitSnapshots,
            recentFailures = memory.recentFailures.takeLast(4).map { it.summary },
        )
    }

    private fun buildLastStrategistReport(
        roadmap: AgentStrategicRoadmapMemory,
    ): AgentStrategistReportMemo {
        return AgentStrategistReportMemo(
            doctrine = roadmap.doctrine,
            phase = roadmap.phase,
            winPath = roadmap.winPath,
            thesis = roadmap.thesis,
            pastSummary = roadmap.pastSummary,
            currentSituation = roadmap.currentSituation,
            futurePlan = roadmap.futurePlan,
        )
    }

    private fun buildSinceLastReviewFacts(
        roadmap: AgentStrategicRoadmapMemory?,
        observation: AgentObservation,
        empireObservation: AgentEmpireObservation,
    ): List<String> {
        roadmap ?: return emptyList()
        if (roadmap.lastReviewedTurn <= 0) return emptyList()

        val facts = buildList {
            val turnsSinceReview = observation.turn - roadmap.lastReviewedTurn
            if (turnsSinceReview > 0) {
                add("Last strategist review was $turnsSinceReview turn${if (turnsSinceReview == 1) "" else "s"} ago.")
            }

            if (roadmap.reviewCityCount != observation.empireSummary.cityCount) {
                add("City count changed from ${roadmap.reviewCityCount} to ${observation.empireSummary.cityCount}.")
            }

            val currentCityNames = observation.cities.map { it.name }.sorted()
            val foundedCities = currentCityNames - roadmap.reviewCityNames.toSet()
            val lostCities = roadmap.reviewCityNames.filter { it !in currentCityNames.toSet() }
            if (foundedCities.isNotEmpty()) {
                add("Cities founded since review: ${foundedCities.joinToString(", ")}.")
            }
            if (lostCities.isNotEmpty()) {
                add("Cities lost since review: ${lostCities.joinToString(", ")}.")
            }

            if (roadmap.reviewMilitaryUnitCount != observation.empireSummary.militaryUnitCount) {
                add("Military unit count changed from ${roadmap.reviewMilitaryUnitCount} to ${observation.empireSummary.militaryUnitCount}.")
            }

            val contactChanged = roadmap.reviewContactComplete != empireObservation.gameContext.contactComplete
            if (contactChanged) {
                add(
                    if (empireObservation.gameContext.contactComplete)
                        "Rival contact became complete since the last review."
                    else
                        "Rival contact regressed from complete to incomplete since the last review."
                )
            }

            val previousResearch = roadmap.reviewResearch
            val currentResearch = empireObservation.currentResearch
            if (previousResearch != currentResearch) {
                add("Research changed from ${previousResearch ?: "none"} to ${currentResearch ?: "none"}.")
            }
        }
        return facts.ifEmpty { listOf("No major factual changes were recorded since the last strategist review.") }
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

        val notableDrift = buildList {
            if (roadmap.phase.equals("opener", ignoreCase = true) && observation.turn >= 45) {
                add("Roadmap is still in opener phase at turn ${observation.turn}.")
            }
            if (roadmap.doctrine.contains("scout", ignoreCase = true) && gameContext.contactComplete) {
                add("Roadmap doctrine is still scouting-heavy even though rival contact is already complete.")
            }
            if ((roadmap.phase.equals("expand", ignoreCase = true) || roadmap.doctrine.contains("expand", ignoreCase = true)) &&
                observation.empireSummary.cityCount < desiredCityFloor
            ) {
                add("Expansion doctrine is still behind the desired city count for this map state.")
            }
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
            notableDrift = notableDrift,
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
