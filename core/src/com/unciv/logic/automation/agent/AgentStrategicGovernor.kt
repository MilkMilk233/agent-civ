package com.unciv.logic.automation.agent

object AgentStrategicGovernor {
    fun buildPlannerBrief(
        memory: AgentMemory,
        observation: AgentObservation,
        empireObservation: AgentEmpireObservation,
    ): AgentPlannerBrief {
        val gameContext = empireObservation.gameContext
        val primaryThreat = empireObservation.victoryThreats.firstOrNull()
        val roadmap = memory.strategicRoadmap.takeIf { it.doctrine.isNotBlank() }
        val attentionFacts = buildAttentionFacts(observation, empireObservation)
        val cityHighlights = selectCityHighlights(observation, gameContext)
        val unitHighlights = selectUnitHighlights(observation, gameContext, primaryThreat)
        val progressInMotion = buildProgressInMotion(observation, empireObservation, cityHighlights, unitHighlights)
        val threatHighlights = observation.visibleThreatsAndTargets.take(if (observation.empireSummary.isAtWar) 3 else 2)

        val workerFacts = observation.priorityFacts.count { isWorkerFact(it) }
        val suppressedContext = buildList {
            val hiddenCities = observation.cities.size - cityHighlights.size
            val hiddenUnits = observation.units.size - unitHighlights.size
            if (hiddenCities > 0) add("$hiddenCities lower-priority city cards were hidden after strategic ranking.")
            if (hiddenUnits > 0) add("$hiddenUnits lower-priority unit cards were hidden after strategic ranking.")
            if (workerFacts > 2 && workerFacts > unitHighlights.count { it.role == "worker" }) {
                add("${workerFacts - unitHighlights.count { it.role == "worker" }} worker facts were collapsed so broader strategy can dominate.")
            }
        }

        return AgentPlannerBrief(
            gameContext = gameContext,
            doctrine = AgentPlannerDoctrineObservation(
                gameArchetype = roadmap?.gameArchetype ?: memory.strategicPosture.gameArchetype ?: gameContext.archetype,
                doctrine = roadmap?.doctrine ?: memory.strategicPosture.doctrine ?: memory.strategicPosture.mode,
                phase = roadmap?.phase ?: memory.strategicPosture.phase.ifBlank { phaseFromTurn(observation.turn) },
                victoryGoal = roadmap?.winPath ?: memory.strategicPosture.victoryGoal ?: empireObservation.victoryGoal,
                rivalCiv = memory.strategicPosture.rivalCiv,
                rivalVictoryGoal = memory.strategicPosture.rivalVictoryGoal,
                thesis = roadmap?.thesis ?: memory.strategicPosture.turnThesis,
                pastSummary = roadmap?.pastSummary,
                currentSituation = roadmap?.currentSituation,
                futurePlan = roadmap?.futurePlan ?: memory.strategicPosture.turnThesis,
            ),
            attentionFacts = attentionFacts,
            progressInMotion = progressInMotion,
            empireChoices = AgentPlannerEmpireChoicesObservation(
                researchChoices = empireObservation.researchCandidates.take(if (empireObservation.currentResearch == null || empireObservation.freeTechs > 0) 3 else 2),
                policyChoices = empireObservation.policyCandidates.take(2),
                macroChoices = empireObservation.macroCandidates.take(2),
                diplomacyChoices = empireObservation.diplomacyCandidates.take(if (gameContext.contactComplete && gameContext.duelLike) 1 else 2),
            ),
            cityHighlights = cityHighlights,
            unitHighlights = unitHighlights,
            threatHighlights = threatHighlights,
            suppressedContext = suppressedContext,
        )
    }

    internal fun buildProgressSummary(
        memory: AgentMemory,
        observation: AgentObservation,
        empireObservation: AgentEmpireObservation,
    ): List<AgentPlannerProgressObservation> {
        val cityHighlights = selectCityHighlights(observation, empireObservation.gameContext)
        val unitHighlights = selectUnitHighlights(observation, empireObservation.gameContext, empireObservation.victoryThreats.firstOrNull())
        return buildProgressInMotion(observation, empireObservation, cityHighlights, unitHighlights)
    }

    private fun buildAttentionFacts(
        observation: AgentObservation,
        empireObservation: AgentEmpireObservation,
    ): List<ObservationFact> {
        val facts = linkedMapOf<String, ObservationFact>()
        fun addFact(fact: ObservationFact) {
            facts.putIfAbsent("${fact.category}|${fact.headline}", fact)
        }

        val threatenedCities = observation.cities
            .filter { it.state.nearbyHostileUnits > 0 || it.state.nearbyHostileCities > 0 }
            .sortedByDescending { it.state.nearbyHostileUnits * 10 + it.state.nearbyHostileCities * 15 + if (it.state.isCapital) 5 else 0 }
        if (threatenedCities.isNotEmpty()) {
            val citySummary = threatenedCities.take(3).joinToString(", ") { city ->
                "${city.name} (${city.state.nearbyHostileUnits} units, ${city.state.nearbyHostileCities} cities)"
            }
            addFact(
                ObservationFact(
                    category = "city",
                    severity = if (threatenedCities.any { it.state.isCapital }) "warning" else "info",
                    headline = "${threatenedCities.size} cities have nearby hostiles",
                    detail = citySummary,
                )
            )
        }

        val citiesNeedingChoice = observation.cities.filter { it.project?.status == "needs_choice" || it.project == null }
        if (citiesNeedingChoice.isNotEmpty()) {
            addFact(
                ObservationFact(
                    category = "city",
                    severity = "warning",
                    headline = "${citiesNeedingChoice.size} cities need a project choice",
                    detail = citiesNeedingChoice.take(3).joinToString(", ") { it.name },
                )
            )
        }

        val finishingProjects = observation.cities
            .filter { (it.project?.turnsLeft ?: Int.MAX_VALUE) <= 1 }
        if (finishingProjects.isNotEmpty()) {
            addFact(
                ObservationFact(
                    category = "city",
                    severity = "info",
                    headline = "${finishingProjects.size} city projects finish within 1 turn",
                    detail = finishingProjects.take(3).joinToString(", ") { city ->
                        "${city.name}: ${city.project?.name ?: "No project"}"
                    },
                )
            )
        }

        val exposedFriendlyCivilians = observation.units.filter { unit ->
            unit.role in setOf("worker", "settler", "great_person", "civilian") &&
                (unit.nearbyHostileUnits > 0 || unit.nearbyHostileCities > 0)
        }
        if (exposedFriendlyCivilians.isNotEmpty()) {
            addFact(
                ObservationFact(
                    category = "unit",
                    severity = "warning",
                    headline = "${exposedFriendlyCivilians.size} civilian units are exposed",
                    detail = exposedFriendlyCivilians.take(3).joinToString(", ") { unit ->
                        "${unit.name} #${unit.id} at (${unit.x}, ${unit.y})"
                    },
                )
            )
        }
        return facts.values.toList()
    }

    private fun selectCityHighlights(
        observation: AgentObservation,
        gameContext: AgentPublicGameContextObservation,
    ): List<AgentCityObservation> {
        val maxCities = if (gameContext.duelLike) 3 else 4
        return observation.cities
            .sortedByDescending { cityScore(it) }
            .take(maxCities)
    }

    private fun selectUnitHighlights(
        observation: AgentObservation,
        gameContext: AgentPublicGameContextObservation,
        primaryThreat: AgentVictoryThreatObservation?,
    ): List<AgentUnitObservation> {
        val maxUnits = when {
            observation.empireSummary.isAtWar -> 6
            observation.turn >= 150 -> 5
            else -> 4
        }
        val workerCap = when {
            primaryThreat?.threatLevel == "critical" -> 1
            gameContext.contactComplete && observation.turn >= 120 -> 1
            observation.turn >= 80 -> 2
            else -> 3
        }

        val candidateUnits = observation.units
            .filter { it.detailLevel == "expanded" }
            .ifEmpty { observation.units }
        val ranked = candidateUnits.sortedByDescending { unitScore(it, observation, gameContext, primaryThreat) }
        val selected = arrayListOf<AgentUnitObservation>()
        var workerCount = 0
        for (unit in ranked) {
            if (selected.size >= maxUnits) break
            if (unit.role == "worker" && workerCount >= workerCap) continue
            selected += unit
            if (unit.role == "worker") workerCount += 1
        }
        if (selected.isEmpty()) return ranked.take(maxUnits)
        return selected
    }

    private fun selectOpportunityHighlights(
        observation: AgentObservation,
        gameContext: AgentPublicGameContextObservation,
        primaryThreat: AgentVictoryThreatObservation?,
    ): List<ObservationFact> {
        return observation.opportunities
            .filterNot { isWorkerFact(it) && (primaryThreat?.threatLevel == "critical" || observation.turn >= 120 || gameContext.contactComplete) }
            .sortedByDescending { alertScore(it, observation.turn, gameContext, primaryThreat) }
            .distinctBy { it.headline }
            .take(3)
    }

    private fun buildProgressInMotion(
        observation: AgentObservation,
        empireObservation: AgentEmpireObservation,
        cityHighlights: List<AgentCityObservation>,
        unitHighlights: List<AgentUnitObservation>,
    ): List<AgentPlannerProgressObservation> {
        val progress = arrayListOf<AgentPlannerProgressObservation>()
        empireObservation.currentResearch?.let { research ->
            progress += AgentPlannerProgressObservation(
                category = "research",
                label = research,
                detail = buildString {
                    empireObservation.currentResearchProgress?.let { append("$it") }
                    empireObservation.currentResearchCost?.let { cost ->
                        if (isNotEmpty()) append("/")
                        append(cost)
                    }
                    empireObservation.currentResearchTurnsLeft?.let { turns ->
                        if (isNotEmpty()) append(" invested")
                        append(" • $turns turns left")
                    }
                    if (isEmpty()) append(empireObservation.currentResearchStatus ?: "Research in motion")
                },
            )
        }
        cityHighlights
            .mapNotNull { city ->
                city.project?.let { project ->
                    AgentPlannerProgressObservation(
                        category = "city",
                        label = "${city.name}: ${project.name ?: "Needs project"}",
                        detail = project.note,
                    )
                }
            }
            .take(3)
            .forEach { progress += it }
        unitHighlights
            .mapNotNull { unit ->
                unit.assignmentProgress?.let { assignment ->
                    AgentPlannerProgressObservation(
                        category = "unit",
                        label = "${unit.name} #${unit.id}",
                        detail = assignment.progressNote,
                    )
                }
            }
            .take(3)
            .forEach { progress += it }
        return progress.take(6)
    }

    private fun alertScore(
        fact: ObservationFact,
        turn: Int,
        gameContext: AgentPublicGameContextObservation,
        primaryThreat: AgentVictoryThreatObservation?,
    ): Int {
        var score = when (fact.severity) {
            "critical" -> 300
            "warning" -> 200
            else -> 100
        }
        score += when (fact.category) {
            "victory" -> 120
            "war" -> 110
            "economy" -> 100
            "expansion" -> 95
            "research", "policy" -> 90
            "contact" -> if (gameContext.contactComplete) -40 else 80
            "tiles" -> if (turn >= 120 || primaryThreat?.threatLevel == "critical") 10 else 50
            else -> 60
        }
        if (isWorkerFact(fact) && (turn >= 120 || primaryThreat?.threatLevel == "critical")) score -= 80
        if (fact.headline.contains("gold reserve", ignoreCase = true)) score += 15
        if (fact.headline.contains("military coverage", ignoreCase = true)) score += 20
        if (fact.headline.contains("main rival", ignoreCase = true)) score += 25
        return score
    }

    private fun hasThinMilitaryCoverage(
        observation: AgentObservation,
        gameContext: AgentPublicGameContextObservation,
    ): Boolean {
        val cityCount = observation.empireSummary.cityCount
        if (cityCount <= 0) return false
        var desiredCoverage = cityCount
        if (observation.empireSummary.isAtWar) desiredCoverage += 1
        else if (gameContext.contactComplete && cityCount >= 2) desiredCoverage += 1
        return observation.empireSummary.militaryUnitCount < desiredCoverage
    }

    private fun hasHighGoldReserve(empireObservation: AgentEmpireObservation): Boolean {
        return empireObservation.gold >= 1000 &&
            empireObservation.macroCandidates.any { it.candidateId == "macro:gold:auto" }
    }

    private fun cityScore(city: AgentCityObservation): Int {
        var score = 100
        score += city.state.nearbyHostileUnits * 20
        score += city.state.nearbyHostileCities * 30
        if (city.state.isCapital) score += 15
        if (city.state.canBombard) score += 5
        if (city.state.turnsToGrowth != null && city.state.turnsToGrowth <= 2) score += 10
        val project = city.project
        if (project != null) {
            score += when (project.switchCost.lowercase()) {
                "high" -> 18
                "medium" -> 10
                else -> 4
            }
            if (project.status in setOf("in_progress", "following_intent", "committed", "nearly_complete")) score += 8
            if ((project.turnsLeft ?: Int.MAX_VALUE) <= 2) score += 10
        }
        return score
    }

    private fun hasMeaningfulImmediateLevers(
        cityHighlights: List<AgentCityObservation>,
        unitHighlights: List<AgentUnitObservation>,
        empireObservation: AgentEmpireObservation,
    ): Boolean {
        val cityLevers = cityHighlights.any { city ->
            city.actions.chooseProject.isNotEmpty() ||
                city.actions.purchase.isNotEmpty() ||
                city.actions.buyTile.isNotEmpty()
        }
        val unitLevers = unitHighlights.any { unit ->
            unit.unitOptionCandidates.isNotEmpty() || unit.legalActionCandidates.isNotEmpty()
        }
        val empireLevers =
            empireObservation.macroCandidates.isNotEmpty() ||
                empireObservation.policyCandidates.isNotEmpty() ||
                empireObservation.diplomacyCandidates.isNotEmpty() ||
                (empireObservation.researchCandidates.isNotEmpty() &&
                    (empireObservation.currentResearch == null || empireObservation.freeTechs > 0))

        return cityLevers || unitLevers || empireLevers
    }

    private fun unitScore(
        unit: AgentUnitObservation,
        observation: AgentObservation,
        gameContext: AgentPublicGameContextObservation,
        primaryThreat: AgentVictoryThreatObservation?,
    ): Int {
        var score = 100
        score += when (unit.role) {
            "settler" -> 90
            "melee", "ranged", "siege", "naval_melee", "naval_ranged" -> 70
            "worker" -> if (observation.turn >= 120 || primaryThreat?.threatLevel == "critical") 10 else 45
            "scout" -> if (gameContext.contactComplete) 5 else 40
            else -> 30
        }
        if (!gameContext.contactComplete && unit.hasMovement && unit.unitOptionCandidates.any { it.candidateId.startsWith("unitexplore:") }) {
            score += if (unit.role == "scout") 70 else 40
        }
        if (unit.unitOptionCandidates.any { it.candidateId.startsWith("unitattack:") }) score += 40
        if (unit.nearbyHostileUnits > 0) score += 25
        if (unit.nearbyHostileCities > 0) score += 35
        if (unit.detailLevel == "expanded") score += 20
        val assignment = unit.assignmentProgress
        if (assignment != null) {
            if (assignment.status == "on_target") score += 20
            if (assignment.switchCost.lowercase() == "high") score += 15
        }
        if (unit.hasMovement && unit.unitActions.any { it == "Explore" } && !gameContext.contactComplete) score += 10
        return score
    }

    private fun isWorkerFact(fact: ObservationFact): Boolean {
        return fact.category == "tiles" && (
            fact.headline.startsWith("Worker #") ||
                fact.detail.contains("worker", ignoreCase = true) ||
                fact.detail.contains("improvement", ignoreCase = true)
            )
    }

    private fun phaseFromTurn(turn: Int): String = when {
        turn < 60 -> "opener"
        turn < 140 -> "expansion"
        turn < 220 -> "conversion"
        else -> "endgame"
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
}
