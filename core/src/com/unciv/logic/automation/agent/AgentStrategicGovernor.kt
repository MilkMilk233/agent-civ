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
        val criticalAlerts = buildCriticalAlerts(observation, empireObservation, roadmap)
        val cityHighlights = selectCityHighlights(observation, gameContext)
        val unitHighlights = selectUnitHighlights(observation, gameContext, primaryThreat)
        val progressInMotion = buildProgressInMotion(observation, empireObservation, cityHighlights, unitHighlights)
        val threatHighlights = observation.visibleThreatsAndTargets.take(if (observation.empireSummary.isAtWar) 3 else 2)
        val opportunityHighlights = selectOpportunityHighlights(observation, gameContext, primaryThreat)

        val workerFacts = observation.priorityFacts.count { isWorkerFact(it) }
        val suppressedContext = buildList {
            val hiddenCities = observation.citiesNeedingAttention.size - cityHighlights.size
            val hiddenUnits = observation.actionableUnits.size - unitHighlights.size
            val hiddenOpportunities = observation.opportunities.size - opportunityHighlights.size
            if (hiddenCities > 0) add("$hiddenCities lower-priority city cards were hidden after strategic ranking.")
            if (hiddenUnits > 0) add("$hiddenUnits lower-priority unit cards were hidden after strategic ranking.")
            if (workerFacts > 2 && workerFacts > unitHighlights.count { it.role == "worker" }) {
                add("${workerFacts - unitHighlights.count { it.role == "worker" }} worker facts were collapsed so broader strategy can dominate.")
            }
            if (hiddenOpportunities > 0) add("$hiddenOpportunities quieter opportunities were hidden from the planner brief.")
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
                commitments = ((roadmap?.midTermGoals ?: emptyList()) + (roadmap?.mustMaintain ?: emptyList()))
                    .ifEmpty { memory.strategicPosture.commitments }
                    .take(4),
                watchOuts = (roadmap?.watchOuts ?: memory.strategicPosture.watchOuts).take(3),
            ),
            criticalAlerts = criticalAlerts,
            progressInMotion = progressInMotion,
            empireChoices = AgentPlannerEmpireChoicesObservation(
                researchChoices = empireObservation.researchCandidates.take(if (empireObservation.currentResearch == null || empireObservation.freeTechs > 0) 3 else 2),
                policyChoices = empireObservation.policyCandidates.take(2),
                macroChoices = empireObservation.macroCandidates.take(2),
                diplomacyChoices = empireObservation.diplomacyCandidates.take(if (gameContext.contactComplete && gameContext.duelLike) 1 else 2),
                spyChoices = empireObservation.spyCandidates.take(2),
            ),
            cityHighlights = cityHighlights,
            unitHighlights = unitHighlights,
            threatHighlights = threatHighlights,
            opportunityHighlights = opportunityHighlights,
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

    private fun buildCriticalAlerts(
        observation: AgentObservation,
        empireObservation: AgentEmpireObservation,
        roadmap: AgentStrategicRoadmapMemory?,
    ): List<ObservationFact> {
        val workerFacts = observation.priorityFacts.filter(::isWorkerFact)
        val roadmapAlerts = roadmap?.let { buildRoadmapAlerts(it) }.orEmpty()
        val pool = buildList {
            addAll(roadmapAlerts)
            addAll(
                empireObservation.macroFacts.filterNot { fact ->
                    (fact.category == "contact" && empireObservation.gameContext.contactComplete) ||
                        (fact.category == "victory" &&
                            fact.headline.startsWith("Current best victory path:", ignoreCase = true) &&
                            roadmap?.winPath != null &&
                            !fact.headline.contains(roadmap.winPath ?: "", ignoreCase = true))
                }
            )
            if (workerFacts.size >= 3) {
                add(
                    ObservationFact(
                        category = "tiles",
                        severity = if (workerFacts.any { it.severity == "warning" }) "warning" else "info",
                        headline = "${workerFacts.size} worker jobs are already in motion",
                        detail = "Finish on-target jobs before switching; worker detail was compressed so broader strategy can dominate this turn.",
                    )
                )
            }
            addAll(observation.priorityFacts.filterNot(::isWorkerFact))
            addAll(observation.opportunities.filterNot(::isWorkerFact))
            if (workerFacts.size < 3) addAll(workerFacts)
        }

        return pool
            .distinctBy { it.headline }
            .sortedByDescending { alertScore(it, observation.turn, empireObservation.gameContext, empireObservation.victoryThreats.firstOrNull()) }
            .take(6)
    }

    private fun buildRoadmapAlerts(roadmap: AgentStrategicRoadmapMemory): List<ObservationFact> {
        val alerts = arrayListOf<ObservationFact>()
        roadmap.winPath?.takeIf { it.isNotBlank() }?.let { winPath ->
            alerts += ObservationFact(
                category = "roadmap",
                severity = "info",
                headline = "Current roadmap aims for $winPath",
                detail = roadmap.thesis ?: "Follow the current strategist roadmap unless the board creates an emergency.",
            )
        }
        roadmap.mustMaintain.firstOrNull()?.let { mustMaintain ->
            alerts += ObservationFact(
                category = "roadmap",
                severity = "warning",
                headline = "Roadmap non-negotiable",
                detail = mustMaintain,
            )
        }
        return alerts
    }

    private fun selectCityHighlights(
        observation: AgentObservation,
        gameContext: AgentPublicGameContextObservation,
    ): List<CityAttentionObservation> {
        val maxCities = if (gameContext.duelLike) 3 else 4
        return observation.citiesNeedingAttention
            .sortedByDescending { cityScore(it) }
            .take(maxCities)
    }

    private fun selectUnitHighlights(
        observation: AgentObservation,
        gameContext: AgentPublicGameContextObservation,
        primaryThreat: AgentVictoryThreatObservation?,
    ): List<ActionableUnitObservation> {
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

        val ranked = observation.actionableUnits.sortedByDescending { unitScore(it, observation, gameContext, primaryThreat) }
        val selected = arrayListOf<ActionableUnitObservation>()
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
        cityHighlights: List<CityAttentionObservation>,
        unitHighlights: List<ActionableUnitObservation>,
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
                city.constructionProgress?.let { progressObservation ->
                    AgentPlannerProgressObservation(
                        category = "city",
                        label = "${city.name}: ${city.currentConstruction}",
                        detail = progressObservation.progressNote,
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
        if (fact.headline.contains("military floor", ignoreCase = true)) score += 20
        if (fact.headline.contains("main rival", ignoreCase = true)) score += 25
        return score
    }

    private fun cityScore(city: CityAttentionObservation): Int {
        var score = 100
        score += city.nearbyHostileUnits * 20
        score += city.nearbyHostileCities * 30
        if (city.isCapital) score += 15
        if (city.canBombard) score += 5
        if (city.turnsToGrowth != null && city.turnsToGrowth <= 2) score += 10
        val progress = city.constructionProgress
        if (progress != null) {
            score += when (progress.switchCost.lowercase()) {
                "high" -> 18
                "medium" -> 10
                else -> 4
            }
            if (progress.status.contains("progress", ignoreCase = true)) score += 8
            if ((progress.turnsLeft ?: Int.MAX_VALUE) <= 2) score += 10
        }
        return score
    }

    private fun unitScore(
        unit: ActionableUnitObservation,
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
        if (unit.unitOptionCandidates.any { it.candidateId.startsWith("unitattack:") }) score += 40
        if (unit.nearbyHostileUnits > 0) score += 25
        if (unit.nearbyHostileCities > 0) score += 35
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
}
