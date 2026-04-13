package com.unciv.logic.automation.agent

object AgentStrategistGovernor {
    fun buildBrief(
        memory: AgentMemory,
        observation: AgentObservation,
        empireObservation: AgentEmpireObservation,
        refreshRequest: AgentStrategistRefreshRequest,
    ): AgentStrategistBrief {
        val lastMemo = memory.lastStrategistMemo.takeIf { it.phase.isNotBlank() }
        val rivalCities = buildRivalCitySnapshots(observation)
        val rivalUnits = buildRivalUnitSnapshots(observation)
        val nearestRivalCity = rivalCities.minWithOrNull(
            compareBy<AgentStrategistRivalCitySnapshot> { it.distanceToClosestUnit ?: Int.MAX_VALUE }
                .thenBy { it.distanceToClosestCity ?: Int.MAX_VALUE }
        )
        val nearestRivalCapital = rivalCities
            .filter { it.isCapital }
            .minWithOrNull(
                compareBy<AgentStrategistRivalCitySnapshot> { it.distanceToClosestUnit ?: Int.MAX_VALUE }
                    .thenBy { it.distanceToClosestCity ?: Int.MAX_VALUE }
            )
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
                    distanceToNearestRivalCity = nearestRivalCity?.let { axialDistance(city.x, city.y, it.x, it.y) },
                    distanceToNearestRivalCapital = nearestRivalCapital?.let { axialDistance(city.x, city.y, it.x, it.y) },
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
                    distanceToNearestRivalCity = nearestRivalCity?.let { axialDistance(unit.x, unit.y, it.x, it.y) },
                    distanceToNearestRivalCapital = nearestRivalCapital?.let { axialDistance(unit.x, unit.y, it.x, it.y) },
                    reasons = unit.reasons.take(4),
                    localFacts = unit.localFacts.take(4),
                    assignmentProgress = unit.assignmentProgress,
                )
            }

        val progressInMotion = AgentStrategicGovernor.buildProgressSummary(memory, observation, empireObservation)
        val lastStrategistMemo = lastMemo?.let(::buildLastStrategistMemo)
        val campaignPicture = buildCampaignPicture(observation, empireObservation, rivalCities, rivalUnits)

        return AgentStrategistBrief(
            refreshRequest = refreshRequest,
            gameContext = empireObservation.gameContext,
            enabledVictoryTypes = empireObservation.enabledVictoryTypes,
            empireSummary = observation.empireSummary,
            currentResearch = empireObservation.currentResearch,
            currentResearchStatus = empireObservation.currentResearchStatus,
            lastStrategistMemo = lastStrategistMemo,
            worldModel = memory.worldModel.takeIf {
                !it.summary.isNullOrBlank() || it.notes.isNotEmpty() || it.anchors.isNotEmpty()
            },
            rivalNotebooks = memory.rivals.sortedBy { it.rivalCiv },
            campaign = memory.campaign.takeIf {
                it.title.isNotBlank() || it.stage.isNotBlank() || !it.summary.isNullOrBlank() || !it.objective.isNullOrBlank()
            },
            empirePlan = memory.empirePlan.takeIf {
                !it.summary.isNullOrBlank() || !it.purchaseIntent.isNullOrBlank() || it.notes.isNotEmpty()
            },
            recentChanges = memory.recentChanges.takeLast(6),
            lessons = memory.lessons.takeLast(6),
            rivalThreats = empireObservation.victoryThreats,
            rivalCities = rivalCities,
            rivalUnits = rivalUnits,
            campaignPicture = campaignPicture,
            progressInMotion = progressInMotion,
            citySnapshots = citySnapshots,
            unitSnapshots = unitSnapshots,
            recentFailures = memory.recentFailures.takeLast(4).map { it.summary },
        )
    }

    private fun buildLastStrategistMemo(
        memo: AgentStrategistMemoMemory,
    ): AgentStrategistReportMemo {
        return AgentStrategistReportMemo(
            phase = memo.phase,
            winPath = memo.winPath,
            thesis = memo.thesis,
            pastSummary = memo.pastSummary,
            currentSituation = memo.currentSituation,
            futurePlan = memo.futurePlan,
            tacticianHandoff = memo.tacticianHandoff,
        )
    }

    private fun buildRivalCitySnapshots(observation: AgentObservation): List<AgentStrategistRivalCitySnapshot> {
        return observation.visibleThreatsAndTargets
            .asSequence()
            .filter { it.kind == "city" && it.civName != observation.civName }
            .map { target ->
                AgentStrategistRivalCitySnapshot(
                    civName = target.civName,
                    name = target.name,
                    relation = target.relation,
                    x = target.x,
                    y = target.y,
                    isCapital = target.facts.any { it.equals("Capital", ignoreCase = true) },
                    health = target.health,
                    combatStrength = target.combatStrength,
                    distanceToClosestCity = target.distanceToClosestCity,
                    distanceToClosestUnit = target.distanceToClosestUnit,
                    facts = target.facts,
                )
            }
            .sortedWith(
                compareBy<AgentStrategistRivalCitySnapshot> { it.distanceToClosestUnit ?: Int.MAX_VALUE }
                    .thenBy { it.distanceToClosestCity ?: Int.MAX_VALUE }
                    .thenByDescending { if (it.isCapital) 1 else 0 }
                    .thenBy { it.name }
            )
            .toList()
    }

    private fun buildRivalUnitSnapshots(observation: AgentObservation): List<AgentStrategistRivalUnitSnapshot> {
        return observation.visibleThreatsAndTargets
            .asSequence()
            .filter { it.kind == "unit" && it.civName != observation.civName }
            .map { target ->
                AgentStrategistRivalUnitSnapshot(
                    civName = target.civName,
                    name = target.name,
                    relation = target.relation,
                    x = target.x,
                    y = target.y,
                    health = target.health,
                    combatStrength = target.combatStrength,
                    distanceToClosestCity = target.distanceToClosestCity,
                    distanceToClosestUnit = target.distanceToClosestUnit,
                    facts = target.facts,
                )
            }
            .sortedWith(
                compareBy<AgentStrategistRivalUnitSnapshot> { it.distanceToClosestUnit ?: Int.MAX_VALUE }
                    .thenBy { it.distanceToClosestCity ?: Int.MAX_VALUE }
                    .thenBy { it.name }
            )
            .toList()
    }

    private fun buildCampaignPicture(
        observation: AgentObservation,
        empireObservation: AgentEmpireObservation,
        rivalCities: List<AgentStrategistRivalCitySnapshot>,
        rivalUnits: List<AgentStrategistRivalUnitSnapshot>,
    ): AgentStrategistCampaignPicture? {
        if (rivalCities.isEmpty() && rivalUnits.isEmpty()) return null

        val primaryRivalCiv = empireObservation.victoryThreats.firstOrNull()?.civName
            ?: rivalCities.firstOrNull()?.civName
            ?: rivalUnits.firstOrNull()?.civName

        val nearestRivalCity = rivalCities.minWithOrNull(
            compareBy<AgentStrategistRivalCitySnapshot> { it.distanceToClosestUnit ?: Int.MAX_VALUE }
                .thenBy { it.distanceToClosestCity ?: Int.MAX_VALUE }
        )
        val nearestRivalCapital = rivalCities
            .filter { it.isCapital }
            .minWithOrNull(
                compareBy<AgentStrategistRivalCitySnapshot> { it.distanceToClosestUnit ?: Int.MAX_VALUE }
                    .thenBy { it.distanceToClosestCity ?: Int.MAX_VALUE }
            )

        val frontlineFriendlyUnits = observation.units.filter { unit ->
            unit.role in setOf("melee", "ranged", "siege", "mounted", "armored", "naval_melee", "naval_ranged") &&
                (unit.nearbyHostileUnits > 0 || unit.nearbyHostileCities > 0)
        }
        val frontlineMeleeUnits = frontlineFriendlyUnits.count { it.role in setOf("melee", "mounted", "armored", "naval_melee") }
        val frontlineRangedUnits = frontlineFriendlyUnits.count { it.role in setOf("ranged", "siege", "naval_ranged") }
        val frontlineDamagedUnits = frontlineFriendlyUnits.count { it.health < 100 }
        val frontlineCityBombards = observation.cities.count { city ->
            city.state.canBombard && (city.state.nearbyHostileUnits > 0 || city.state.nearbyHostileCities > 0)
        }

        val meleeNearNearestCity = nearestRivalCity?.let { target ->
            observation.units.count { unit ->
                unit.role in setOf("melee", "mounted", "armored", "naval_melee") &&
                    manhattanDistance(unit.x, unit.y, target.x, target.y) <= 4
            }
        } ?: 0
        val rangedNearNearestCity = nearestRivalCity?.let { target ->
            observation.units.count { unit ->
                unit.role in setOf("ranged", "siege", "naval_ranged") &&
                    manhattanDistance(unit.x, unit.y, target.x, target.y) <= 4
            }
        } ?: 0

        return AgentStrategistCampaignPicture(
            primaryRivalCiv = primaryRivalCiv,
            visibleRivalCities = rivalCities.size,
            visibleRivalCapitals = rivalCities.count { it.isCapital },
            visibleRivalUnits = rivalUnits.size,
            frontlineFriendlyCombatUnits = frontlineFriendlyUnits.size,
            frontlineMeleeUnits = frontlineMeleeUnits,
            frontlineRangedUnits = frontlineRangedUnits,
            frontlineDamagedUnits = frontlineDamagedUnits,
            frontlineCityBombards = frontlineCityBombards,
            meleeUnitsNearNearestRivalCity = meleeNearNearestCity,
            rangedUnitsNearNearestRivalCity = rangedNearNearestCity,
            nearestRivalCity = nearestRivalCity?.let(::toTargetReference),
            nearestRivalCapital = nearestRivalCapital?.let(::toTargetReference),
        )
    }

    private fun toTargetReference(target: AgentStrategistRivalCitySnapshot): AgentStrategistTargetReference {
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

    private fun manhattanDistance(x1: Int, y1: Int, x2: Int, y2: Int): Int {
        return kotlin.math.abs(x1 - x2) + kotlin.math.abs(y1 - y2)
    }

    private fun axialDistance(x1: Int, y1: Int, x2: Int, y2: Int): Int {
        val dx = x1 - x2
        val dy = y1 - y2
        return (kotlin.math.abs(dx) + kotlin.math.abs(dy) + kotlin.math.abs(dx + dy)) / 2
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

}
