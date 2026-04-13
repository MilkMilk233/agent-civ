package com.unciv.logic.automation.agent

object AgentStrategicGovernor {
    fun buildPlannerBrief(
        memory: AgentMemory,
        observation: AgentObservation,
        empireObservation: AgentEmpireObservation,
    ): AgentPlannerBrief {
        val gameContext = empireObservation.gameContext
        val primaryThreat = empireObservation.victoryThreats.firstOrNull()
        val memo = memory.lastStrategistMemo.takeIf { it.phase.isNotBlank() }
        val attentionFacts = buildAttentionFacts(observation, empireObservation)
        val mustActNow = buildMustActNow(observation, empireObservation)
        val cityHighlights = selectCityHighlights(observation, gameContext)
        val campaignContext = buildCampaignContext(memory, observation, empireObservation)
        val memoryContext = buildMemoryContext(memory, observation, empireObservation)
        val unitHighlights = selectUnitHighlights(observation, gameContext, primaryThreat, campaignContext)
        val progressInMotion = buildProgressInMotion(observation, empireObservation, cityHighlights, unitHighlights, campaignContext)
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
            strategy = AgentPlannerStrategyObservation(
                gameArchetype = memo?.gameArchetype?.ifBlank { null } ?: gameContext.archetype,
                winPath = memo?.winPath ?: empireObservation.victoryGoal,
                phase = memo?.phase ?: phaseFromTurn(observation.turn),
                thesis = memo?.thesis,
                pastSummary = memo?.pastSummary,
                currentSituation = memo?.currentSituation,
                futurePlan = memo?.futurePlan,
                tacticianHandoff = memo?.tacticianHandoff ?: memo?.futurePlan,
            ),
            memoryContext = memoryContext,
            campaignContext = campaignContext,
            mustActNow = mustActNow,
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

    private fun buildMemoryContext(
        memory: AgentMemory,
        observation: AgentObservation,
        empireObservation: AgentEmpireObservation,
    ): AgentPlannerMemoryContextObservation? {
        val primaryRivalCiv = memory.campaign.primaryRivalCiv
            ?: empireObservation.victoryThreats.firstOrNull()?.civName
            ?: observation.visibleThreatsAndTargets.firstOrNull { it.civName != observation.civName }?.civName
        val rivalNotebook = primaryRivalCiv?.let { civ ->
            memory.rivals.firstOrNull { it.rivalCiv == civ }
        }
        if (
            memory.worldModel.summary.isNullOrBlank() &&
            memory.worldModel.notes.isEmpty() &&
            rivalNotebook == null &&
            memory.campaign.summary.isNullOrBlank() &&
            memory.empirePlan.summary.isNullOrBlank() &&
            memory.recentChanges.isEmpty() &&
            memory.lessons.isEmpty()
        ) return null

        return AgentPlannerMemoryContextObservation(
            worldModelSummary = memory.worldModel.summary,
            worldModelNotes = memory.worldModel.notes.takeLast(3).map { it.text },
            mainRivalCiv = rivalNotebook?.rivalCiv ?: primaryRivalCiv,
            mainRivalSummary = rivalNotebook?.summary,
            mainRivalNotes = rivalNotebook?.notes?.takeLast(3)?.map { it.text } ?: emptyList(),
            campaignTitle = memory.campaign.title.takeIf { it.isNotBlank() },
            campaignStage = memory.campaign.stage.takeIf { it.isNotBlank() },
            campaignObjective = memory.campaign.objective,
            campaignSummary = memory.campaign.summary,
            reinforcementPlan = memory.campaign.reinforcementPlan,
            campaignDoNotDo = memory.campaign.doNotDo.take(4),
            empirePlanSummary = memory.empirePlan.summary,
            purchaseIntent = memory.empirePlan.purchaseIntent,
            recentChanges = memory.recentChanges.takeLast(4).map { it.text },
            lessons = memory.lessons.takeLast(4).map { it.text },
        )
    }

    private fun buildMustActNow(
        observation: AgentObservation,
        empireObservation: AgentEmpireObservation,
    ): List<AgentPlannerMustActObservation> {
        val items = arrayListOf<AgentPlannerMustActObservation>()

        val citiesNeedingChoice = observation.cities
            .filter { it.project?.status == "needs_choice" || it.project == null }
        if (citiesNeedingChoice.isNotEmpty()) {
            items += AgentPlannerMustActObservation(
                kind = "city_project_choice",
                headline = "${citiesNeedingChoice.size} cities still need an explicit project choice",
                detail = citiesNeedingChoice.take(3).joinToString(", ") { city ->
                    "${city.name}${city.project?.name?.let { " ($it placeholder)" } ?: ""}"
                },
            )
        }

        val settlersReadyToFound = observation.units
            .filter { unit ->
                unit.role == "settler" &&
                    (unit.unitActions.contains("FoundCity") ||
                        unit.unitOptionCandidates.any { candidate ->
                            candidate.candidateId.startsWith("unitsettle:") &&
                                candidate.title.contains("found city here", ignoreCase = true)
                        })
            }
        if (settlersReadyToFound.isNotEmpty()) {
            items += AgentPlannerMustActObservation(
                kind = "settler_can_found_now",
                headline = "${settlersReadyToFound.size} settler units can found immediately",
                detail = settlersReadyToFound.take(2).joinToString(", ") { unit ->
                    "${unit.name} #${unit.id} at (${unit.x}, ${unit.y})"
                },
            )
        }

        if (empireObservation.currentResearch == null && empireObservation.researchCandidates.isNotEmpty()) {
            items += AgentPlannerMustActObservation(
                kind = "research_choice_missing",
                headline = "Research choice is still unresolved",
                detail = empireObservation.researchCandidates.take(3).joinToString(", ") { it.title },
            )
        }

        return items
    }

    internal fun buildProgressSummary(
        memory: AgentMemory,
        observation: AgentObservation,
        empireObservation: AgentEmpireObservation,
    ): List<AgentPlannerProgressObservation> {
        val cityHighlights = selectCityHighlights(observation, empireObservation.gameContext)
        val campaignContext = buildCampaignContext(memory, observation, empireObservation)
        val unitHighlights = selectUnitHighlights(
            observation,
            empireObservation.gameContext,
            empireObservation.victoryThreats.firstOrNull(),
            campaignContext,
        )
        return buildProgressInMotion(observation, empireObservation, cityHighlights, unitHighlights, campaignContext)
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
        campaignContext: AgentPlannerCampaignContextObservation?,
    ): List<AgentUnitObservation> {
        val warLikeContext = isPressureContext(observation, campaignContext)
        val maxUnits = when {
            observation.empireSummary.isAtWar -> 10
            warLikeContext -> 8
            observation.turn >= 150 -> 5
            else -> 4
        }
        val workerCap = when {
            warLikeContext -> 1
            primaryThreat?.threatLevel == "critical" -> 1
            gameContext.contactComplete && observation.turn >= 120 -> 1
            observation.turn >= 80 -> 2
            else -> 3
        }

        val candidateUnits = observation.units
            .filter { it.detailLevel == "expanded" }
            .ifEmpty { observation.units }
        val ranked = candidateUnits.sortedByDescending { unitScore(it, observation, gameContext, primaryThreat, campaignContext) }
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

    private fun buildProgressInMotion(
        observation: AgentObservation,
        empireObservation: AgentEmpireObservation,
        cityHighlights: List<AgentCityObservation>,
        unitHighlights: List<AgentUnitObservation>,
        campaignContext: AgentPlannerCampaignContextObservation?,
    ): List<AgentPlannerProgressObservation> {
        val warLikeContext = isPressureContext(observation, campaignContext)
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
                    if (warLikeContext && assignment.role in setOf("explore", "reposition")) return@let null
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

    private fun unitScore(
        unit: AgentUnitObservation,
        observation: AgentObservation,
        gameContext: AgentPublicGameContextObservation,
        primaryThreat: AgentVictoryThreatObservation?,
        campaignContext: AgentPlannerCampaignContextObservation?,
    ): Int {
        val warLikeContext = isPressureContext(observation, campaignContext)
        var score = 100
        score += when (unit.role) {
            "settler" -> 90
            "melee", "ranged", "siege", "naval_melee", "naval_ranged" -> if (warLikeContext) 95 else 70
            "worker" -> if (observation.turn >= 120 || primaryThreat?.threatLevel == "critical") 10 else 45
            "scout" -> if (gameContext.contactComplete) if (warLikeContext) -10 else 5 else 40
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
            if (warLikeContext && assignment.role in setOf("explore", "reposition")) {
                score -= 25
            } else {
                if (assignment.status == "on_target") score += 20
                if (assignment.switchCost.lowercase() == "high") score += 15
            }
        }
        val objective = campaignContext?.visibleTarget ?: campaignContext?.visibleCapital ?: campaignContext?.lastKnownTarget ?: campaignContext?.lastKnownCapital
        if (warLikeContext && objective != null && unit.role in setOf("melee", "ranged", "siege", "naval_melee", "naval_ranged")) {
            val distance = axialDistance(unit.x, unit.y, objective.x, objective.y)
            score += (40 - distance * 4).coerceAtLeast(0)
            if (unit.hasMovement) score += 10
        }
        if (unit.hasMovement && unit.unitActions.any { it == "Explore" } && !gameContext.contactComplete) score += 10
        return score
    }

    private fun buildCampaignContext(
        memory: AgentMemory,
        observation: AgentObservation,
        empireObservation: AgentEmpireObservation,
    ): AgentPlannerCampaignContextObservation? {
        val visibleRivalCities = observation.visibleThreatsAndTargets
            .filter { it.kind == "city" && it.civName != observation.civName }
        val visibleRivalUnits = observation.visibleThreatsAndTargets
            .filter { it.kind == "unit" && it.civName != observation.civName }
        val visibleTarget = visibleRivalCities.minWithOrNull(
            compareBy<VisibleTargetObservation> { it.distanceToClosestUnit ?: Int.MAX_VALUE }
                .thenBy { it.distanceToClosestCity ?: Int.MAX_VALUE }
                .thenBy { it.name }
        )
        val visibleCapital = visibleRivalCities
            .filter { it.facts.any { fact -> fact.equals("Capital", ignoreCase = true) } }
            .minWithOrNull(
                compareBy<VisibleTargetObservation> { it.distanceToClosestUnit ?: Int.MAX_VALUE }
                    .thenBy { it.distanceToClosestCity ?: Int.MAX_VALUE }
                    .thenBy { it.name }
            )
        val primaryRivalCiv = memory.campaign.primaryRivalCiv
            ?: empireObservation.victoryThreats.firstOrNull()?.civName
            ?: visibleTarget?.civName
            ?: visibleCapital?.civName
        val lastKnownTarget = lastKnownAnchor(memory, primaryRivalCiv, setOf("city"))
        val lastKnownCapital = lastKnownAnchor(memory, primaryRivalCiv, setOf("capital"))
        val objective = visibleTarget?.let(::toTargetReference)
            ?: visibleCapital?.let(::toTargetReference)
            ?: lastKnownTarget
            ?: lastKnownCapital
        val frontlineCombatUnits = objective?.let { target ->
            observation.units.count { unit ->
                unit.role in setOf("melee", "ranged", "siege", "naval_melee", "naval_ranged") &&
                    axialDistance(unit.x, unit.y, target.x, target.y) <= 6
            }
        } ?: 0
        val meleeUnitsNearObjective = objective?.let { target ->
            observation.units.count { unit ->
                unit.role in setOf("melee", "naval_melee") &&
                    axialDistance(unit.x, unit.y, target.x, target.y) <= 5
            }
        } ?: 0
        val rangedUnitsNearObjective = objective?.let { target ->
            observation.units.count { unit ->
                unit.role in setOf("ranged", "siege", "naval_ranged") &&
                    axialDistance(unit.x, unit.y, target.x, target.y) <= 5
            }
        } ?: 0
        val warChoiceAvailable = empireObservation.diplomacyCandidates.any { it.candidateId.startsWith("diplo:war:") }
        if (
            visibleRivalCities.isEmpty() &&
            visibleRivalUnits.isEmpty() &&
            lastKnownTarget == null &&
            lastKnownCapital == null &&
            !warChoiceAvailable &&
            !observation.empireSummary.isAtWar
        ) return null
        return AgentPlannerCampaignContextObservation(
            primaryRivalCiv = primaryRivalCiv,
            atWar = observation.empireSummary.isAtWar,
            warChoiceAvailable = warChoiceAvailable,
            visibleRivalCities = visibleRivalCities.size,
            visibleRivalUnits = visibleRivalUnits.size,
            visibleTarget = visibleTarget?.let(::toTargetReference),
            visibleCapital = visibleCapital?.let(::toTargetReference),
            lastKnownTarget = lastKnownTarget,
            lastKnownCapital = lastKnownCapital,
            frontlineFriendlyCombatUnits = frontlineCombatUnits,
            meleeUnitsNearObjective = meleeUnitsNearObjective,
            rangedUnitsNearObjective = rangedUnitsNearObjective,
        )
    }

    private fun lastKnownAnchor(
        memory: AgentMemory,
        rivalCiv: String?,
        kinds: Set<String>,
    ): AgentStrategistTargetReference? {
        val notebook = rivalCiv?.let { civ -> memory.rivals.firstOrNull { it.rivalCiv == civ } }
            ?: memory.rivals.firstOrNull()
            ?: return null
        val anchor = notebook.anchors
            .filter { it.kind in kinds }
            .maxByOrNull { it.lastConfirmedTurn }
            ?: return null
        return AgentStrategistTargetReference(
            civName = anchor.civName ?: notebook.rivalCiv,
            name = anchor.label,
            x = anchor.x ?: return null,
            y = anchor.y ?: return null,
        )
    }

    private fun toTargetReference(target: VisibleTargetObservation): AgentStrategistTargetReference {
        return AgentStrategistTargetReference(
            civName = target.civName,
            name = target.name,
            x = target.x,
            y = target.y,
            health = target.health,
            combatStrength = target.combatStrength,
            distanceToClosestCity = target.distanceToClosestCity,
            distanceToClosestUnit = target.distanceToClosestUnit,
        )
    }

    private fun isPressureContext(
        observation: AgentObservation,
        campaignContext: AgentPlannerCampaignContextObservation?,
    ): Boolean {
        if (observation.empireSummary.isAtWar) return true
        campaignContext ?: return false
        return campaignContext.warChoiceAvailable ||
            (observation.empireSummary.militaryUnitCount >= 8 && campaignContext.primaryRivalCiv != null) ||
            campaignContext.visibleTarget != null ||
            campaignContext.lastKnownTarget != null ||
            campaignContext.lastKnownCapital != null
    }

    private fun axialDistance(x1: Int, y1: Int, x2: Int, y2: Int): Int {
        val dx = x1 - x2
        val dy = y1 - y2
        return (kotlin.math.abs(dx) + kotlin.math.abs(dy) + kotlin.math.abs(dx + dy)) / 2
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
