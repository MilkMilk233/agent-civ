package com.unciv.logic.automation.agent

import com.unciv.logic.automation.unit.CityLocationTileRanker
import com.unciv.logic.automation.city.ConstructionAutomation
import com.unciv.logic.battle.CityCombatant
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.UnitActionType
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActions
import kotlin.math.roundToInt

object AgentObservationBuilder {
    private const val maxPriorityFacts = 8
    private const val maxOpportunityFacts = 8
    private const val maxVisibleTargets = 8
    private const val maxLocalFactsPerEntity = 4
    private const val maxCompactFactsPerEntity = 2

    fun build(civInfo: Civilization, memory: AgentMemory = civInfo.agentMemory): AgentObservation {
        civInfo.updateStatsForNextTurn()
        civInfo.cities.forEach { it.cityStats.update(updateCivStats = false) }

        val allUnits = civInfo.units.getCivUnits()
            .sortedBy { it.id }
            .toList()
        val visibleTargetCandidates = buildVisibleTargetCandidates(civInfo, allUnits)
        val objectiveTheaterHint = buildObjectiveTheaterHint(memory, civInfo, visibleTargetCandidates)
        val peacefulGrowthWindow = isPeacefulGrowthWindow(civInfo, visibleTargetCandidates)
        val cityOptionContext = AgentCityOptionBuilder.build(civInfo, memory)
        val cityCandidates = civInfo.cities
            .sortedWith(compareBy<City> { it.name }.thenBy { it.location.toString() })
            .map { buildCityCandidate(it, civInfo, visibleTargetCandidates, peacefulGrowthWindow, memory) }
        val cities = cityCandidates.map { candidate ->
            val project = buildProjectObservation(candidate.city, memory)
            val actions = cityOptionContext.observationsByCityKey["${candidate.city.location.x},${candidate.city.location.y}"]
                ?: AgentCityActionsObservation()
            AgentCityObservation(
                x = candidate.city.location.x,
                y = candidate.city.location.y,
                name = candidate.city.name,
                state = AgentCityStateObservation(
                    population = candidate.city.population.population,
                    health = candidate.city.health,
                    isCapital = candidate.city.isCapital(),
                    isCoastal = candidate.city.isCoastal(),
                    isPuppet = candidate.city.isPuppet,
                    isGarrisoned = candidate.city.isGarrisoned(),
                    canBombard = candidate.canBombard,
                    focus = candidate.city.getCityFocus().name,
                    productionPerTurn = candidate.productionPerTurn,
                    foodPerTurn = candidate.foodPerTurn,
                    turnsToGrowth = candidate.turnsToGrowth,
                    turnsToStarvation = candidate.turnsToStarvation,
                    cityStrength = candidate.cityStrength,
                    nearbyHostileUnits = candidate.nearbyHostileUnits,
                    nearbyHostileCities = candidate.nearbyHostileCities,
                ),
                project = project,
                signals = buildCitySignals(candidate, project),
                actions = actions,
            )
        }

        val unitOptionContext = AgentUnitOptionBuilder.build(civInfo, memory)
        val unitCandidates = allUnits.map { unit ->
            buildUnitCandidate(
                unit = unit,
                civInfo = civInfo,
                visibleTargetCandidates = visibleTargetCandidates,
                objectiveTheaterHint = objectiveTheaterHint,
                peacefulGrowthWindow = peacefulGrowthWindow,
                memory = memory,
                unitOptionCandidates = unitOptionContext.observationsByUnitId[unit.id] ?: emptyList(),
            )
        }
        val units = unitCandidates.map { candidate ->
            val unit = candidate.unit
            val unitOptionCandidates = unitOptionContext.observationsByUnitId[unit.id] ?: emptyList()
            val assignmentProgress = buildUnitAssignmentProgress(
                unit = unit,
                memory = memory,
                unitOptionCandidates = unitOptionCandidates,
            )
            val detailReasons = buildUnitDetailReasons(
                candidate = candidate,
                unitOptionCandidates = unitOptionCandidates,
                assignmentProgress = assignmentProgress,
            )
            val expanded = detailReasons.isNotEmpty()
            AgentUnitObservation(
                detailLevel = if (expanded) "expanded" else "compact",
                detailReasons = detailReasons,
                id = unit.id,
                x = unit.getTile().position.x,
                y = unit.getTile().position.y,
                name = unit.name,
                role = candidate.role,
                health = unit.health,
                hasMovement = unit.hasMovement(),
                movementPoints = unit.getMovementString(),
                strength = candidate.strength,
                rangedStrength = candidate.rangedStrength,
                range = candidate.range,
                unitOptionCandidates = if (expanded) unitOptionCandidates else emptyList(),
                nearbyHostileUnits = candidate.nearbyHostileUnits,
                nearbyHostileCities = candidate.nearbyHostileCities,
                reasons = candidate.reasons.take(if (expanded) maxLocalFactsPerEntity else maxCompactFactsPerEntity),
                localFacts = candidate.localFacts.take(if (expanded) maxLocalFactsPerEntity else maxCompactFactsPerEntity),
                assignmentProgress = assignmentProgress,
            )
        }
        val expandedUnits = units.filter { it.detailLevel == "expanded" }

        val visibleTargets = visibleTargetCandidates
            .sortedWith(compareByDescending<VisibleTargetCandidate> { it.score }.thenBy { it.observation.name })
            .map { it.observation }
        val selectedTargets = visibleTargets.take(maxVisibleTargets)

        val opportunityFacts = selectFacts(
            buildOpportunityFacts(
                selectedCityCandidates = cityCandidates,
                selectedCities = cities,
                selectedUnitCandidates = unitCandidates.filter { candidate -> expandedUnits.any { it.id == candidate.unit.id } },
                selectedUnits = expandedUnits,
                visibleTargetCandidates = visibleTargetCandidates,
                selectedTargets = selectedTargets,
            ),
            maxOpportunityFacts,
        )

        val visibleHostileUnits = visibleTargetCandidates.count { it.isHostile && it.observation.kind == "unit" }
        val visibleForeignCities = visibleTargetCandidates.count { it.observation.kind == "city" }
        val unitSupply = civInfo.stats.getUnitSupply()
        val unitSupplyDeficit = civInfo.stats.getUnitSupplyDeficit()
        val unitSupplyProductionPenaltyPercent = (-civInfo.stats.getUnitSupplyProductionPenalty()).roundToInt()
        val empireSummary = EmpireSummaryObservation(
            isAtWar = civInfo.isAtWar(),
            gold = civInfo.gold,
            sciencePerTurn = civInfo.stats.statsForNextTurn.science.toInt(),
            culturePerTurn = civInfo.stats.statsForNextTurn.culture.toInt(),
            faithPerTurn = civInfo.stats.statsForNextTurn.faith.toInt(),
            happiness = civInfo.getHappiness(),
            unitSupply = unitSupply,
            unitSupplyDeficit = unitSupplyDeficit,
            unitSupplyProductionPenaltyPercent = unitSupplyProductionPenaltyPercent,
            visibleTiles = civInfo.viewableTiles.size,
            cityCount = civInfo.cities.size,
            unitCount = allUnits.size,
            militaryUnitCount = allUnits.count { it.isMilitary() },
            civilianUnitCount = allUnits.count { it.isCivilian() },
            knownCivs = civInfo.getKnownCivs().count(),
            knownWarOpponents = civInfo.getKnownCivs().count { civInfo.isAtWarWith(it) },
            citiesNeedingProductionChoice = civInfo.cities.count { AgentCityProjectPolicy.needsExplicitProjectChoice(it) },
            settlersReady = allUnits.count { it.baseUnit.isCityFounder() && it.hasMovement() },
            workersReady = allUnits.count { it.cache.hasUniqueToBuildImprovements && it.hasMovement() },
            damagedUnits = allUnits.count { it.health < 100 },
            visibleHostileUnits = visibleHostileUnits,
            visibleForeignCities = visibleForeignCities,
        )

        val priorityFacts = selectFacts(
            buildPriorityFacts(
                civInfo = civInfo,
                empireSummary = empireSummary,
                cityCandidates = cityCandidates,
                unitCandidates = unitCandidates,
                visibleTargetCandidates = visibleTargetCandidates,
                opportunityFacts = opportunityFacts,
                peacefulGrowthWindow = peacefulGrowthWindow,
            ),
            maxPriorityFacts,
        )

        return AgentObservation(
            turn = civInfo.gameInfo.turns,
            civName = civInfo.civName,
            empireSummary = empireSummary,
            priorityFacts = priorityFacts,
            cities = cities,
            units = units,
            visibleThreatsAndTargets = visibleTargets,
            opportunities = opportunityFacts,
            perceptionSummary = PerceptionSummaryObservation(
                totalCities = cities.size,
                expandedCities = cities.size,
                totalUnits = units.size,
                expandedUnits = expandedUnits.size,
                visibleTargets = visibleTargets.size,
            ),
        )
    }

    private fun buildCityCandidate(
        city: City,
        civInfo: Civilization,
        visibleTargetCandidates: List<VisibleTargetCandidate>,
        peacefulGrowthWindow: Boolean,
        memory: AgentMemory,
    ): CityCandidate {
        val cityTile = city.getCenterTile()
        val productionPerTurn = city.cityStats.currentCityStats.production.roundToInt()
        val foodPerTurn = city.foodForNextTurn()
        val turnsToGrowth = city.population.getNumTurnsToNewPopulation()
        val turnsToStarvation = city.population.getNumTurnsToStarvation()
        val nearbyHostileUnits = visibleTargetCandidates.count {
            it.isHostile && it.observation.kind == "unit" && cityTile.aerialDistanceTo(it.tile) <= 4
        }
        val nearbyHostileCities = visibleTargetCandidates.count {
            it.isHostile && it.observation.kind == "city" && cityTile.aerialDistanceTo(it.tile) <= 6
        }
        val localFacts = mutableListOf<String>()
        localFacts += collectCityResourceAlerts(city)
        if (city.isBlockaded()) localFacts += "City is blockaded."
        if (city.canBombard() && nearbyHostileUnits > 0) localFacts += "Can bombard nearby hostiles this turn."
        if (!city.isGarrisoned() && nearbyHostileUnits > 0) localFacts += "No garrison is protecting the city center."
        if (city.health < city.getMaxHealth()) {
            localFacts += "City health is ${city.health}/${city.getMaxHealth()}."
        }

        val reasons = mutableListOf<String>()
        val facts = mutableListOf<ScoredFact>()
        val opportunities = mutableListOf<ScoredFact>()
        var score = 0
        val rankedConstructionChoices = AgentCityOptionBuilder.rankConstructionChoices(city, 4)
        val noWorkerExists = civInfo.units.getCivUnits().none { it.cache.hasUniqueToBuildImprovements }

        if (city.cityConstructions.currentConstructionName().isBlank()) {
            score += 95
            reasons += "Needs a production choice"
            facts += fact(
                priority = 140,
                category = "city",
                severity = "warning",
                headline = "${city.name} needs a production choice",
                detail = "The city is not currently building anything and should be reviewed this turn.",
            )
            opportunities += fact(
                priority = 110,
                category = "city",
                severity = "info",
                headline = "${city.name} can choose a new construction",
                detail = "Pick a build from the curated construction options for this city.",
            )
        }

        if (peacefulGrowthWindow && nearbyHostileUnits == 0 && nearbyHostileCities == 0) {
            rankedConstructionChoices.firstOrNull()?.let { topChoice ->
                score += 55
                reasons += "Quiet opener window"
                opportunities += fact(
                    priority = 135,
                    category = "expand",
                    severity = "info",
                    headline = "${city.name} has a calm development window",
                    detail = "No local hostiles are visible, and ${topChoice.name} is a strong current build option.",
                )
            }

            if (noWorkerExists && rankedConstructionChoices.any { it.name == "Worker" }) {
                score += 80
                reasons += "Needs first worker"
                facts += fact(
                    priority = 155,
                    category = "tiles",
                    severity = "warning",
                    headline = "${city.name} still needs the first worker",
                    detail = "The capital has workable resource tiles, so getting a worker online quickly should be a high priority.",
                )
            }

            if (civInfo.cities.size == 1 &&
                city.population.population >= 2 &&
                civInfo.getHappiness() > 0 &&
                rankedConstructionChoices.any { it.name == "Settler" }
            ) {
                score += 75
                reasons += "Can expand safely"
                opportunities += fact(
                    priority = 145,
                    category = "expand",
                    severity = "warning",
                    headline = "${city.name} can expand to a second city soon",
                    detail = "The opener is still calm, happiness is positive, and a settler is available to accelerate the empire.",
                )
            }
        }

        if (nearbyHostileUnits > 0 || nearbyHostileCities > 0) {
            val threatScore = 70 + nearbyHostileUnits * 10 + nearbyHostileCities * 8
            score += threatScore
            reasons += "Under visible threat"
            facts += fact(
                priority = 180 + nearbyHostileUnits * 12 + nearbyHostileCities * 8,
                category = "war",
                severity = "critical",
                headline = "${city.name} is under visible pressure",
                detail = "${city.name} has $nearbyHostileUnits hostile units and $nearbyHostileCities hostile cities in local view.",
            )
            if (city.canBombard()) {
                opportunities += fact(
                    priority = 120 + nearbyHostileUnits * 8,
                    category = "war",
                    severity = "info",
                    headline = "${city.name} can respond to nearby enemies",
                    detail = "The city can bombard and already has hostile units in local range.",
                )
            }
        }

        if (turnsToStarvation != null) {
            score += 80
            reasons += "Starvation risk"
            facts += fact(
                priority = 170,
                category = "growth",
                severity = "critical",
                headline = "${city.name} is starving",
                detail = "Food is negative and the city will starve in $turnsToStarvation turns if nothing changes.",
            )
        } else if (turnsToGrowth != null && turnsToGrowth <= 2) {
            score += 18
            reasons += "Rapid growth soon"
            facts += fact(
                priority = 70,
                category = "growth",
                severity = "info",
                headline = "${city.name} is about to grow",
                detail = "Growth is expected in $turnsToGrowth turns, so food and production priorities matter now.",
            )
        }

        if (localFacts.isNotEmpty()) {
            score += 20 + localFacts.size * 3
            reasons += "Local tile work nearby"
            val topLocalFact = localFacts.first()
            facts += fact(
                priority = 95,
                category = "tiles",
                severity = "info",
                headline = "${city.name} has nearby tile work",
                detail = topLocalFact,
            )
        }

        if (city.isCapital()) {
            score += 8
            reasons += "Capital oversight"
        }

        buildProjectObservation(city, memory)?.let { project ->
            if (project.status == "needs_choice") score += 24
            if (project.status == "nearly_complete") score += 28
            if (project.status == "following_intent") score += 18
            localFacts += project.note
            reasons += when (project.status) {
                "needs_choice" -> "Needs explicit project choice"
                "nearly_complete" -> "Finish current build"
                "following_intent" -> "Continuing city plan"
                else -> "City progress in flight"
            }
        }

        return CityCandidate(
            city = city,
            score = score,
            productionPerTurn = productionPerTurn,
            foodPerTurn = foodPerTurn,
            turnsToGrowth = turnsToGrowth,
            turnsToStarvation = turnsToStarvation,
            cityStrength = CityCombatant(city).getCityStrength(),
            canBombard = city.canBombard(),
            nearbyHostileUnits = nearbyHostileUnits,
            nearbyHostileCities = nearbyHostileCities,
            reasons = reasons.distinct().take(maxLocalFactsPerEntity),
            localFacts = localFacts.distinct().take(maxLocalFactsPerEntity),
            facts = facts,
            opportunities = opportunities,
        )
    }

    private fun buildUnitCandidate(
        unit: MapUnit,
        civInfo: Civilization,
        visibleTargetCandidates: List<VisibleTargetCandidate>,
        objectiveTheaterHint: ObjectiveTheaterHint?,
        peacefulGrowthWindow: Boolean,
        memory: AgentMemory,
        unitOptionCandidates: List<UnitOptionCandidateObservation>,
    ): UnitCandidate {
        val role = classifyUnitRole(unit)
        val threatRadius = if (unit.isMilitary()) 3 else 2
        val nearbyHostileUnits = visibleTargetCandidates.count {
            it.isHostile && it.observation.kind == "unit" && unit.getTile().aerialDistanceTo(it.tile) <= threatRadius
        }
        val nearbyHostileCities = visibleTargetCandidates.count {
            it.isHostile && it.observation.kind == "city" && unit.getTile().aerialDistanceTo(it.tile) <= 4
        }

        val localFacts = mutableListOf<String>()
        val reasons = mutableListOf<String>()
        val facts = mutableListOf<ScoredFact>()
        val opportunities = mutableListOf<ScoredFact>()
        var score = if (unit.hasMovement()) 12 else 0

        if (unit.health < 100) {
            localFacts += "Unit is damaged at ${unit.health} HP."
            if (unit.canHealInCurrentTile()) {
                localFacts += "Can heal ${unit.getHealAmountForCurrentTile()} HP here."
            }
        }

        if (unit.isExploring()) {
            score += if (peacefulGrowthWindow && unit.isMilitary() && nearbyHostileUnits == 0 && nearbyHostileCities == 0) 2 else 10
            reasons += "Exploring"
        }

        if (role == "settler" && unit.hasMovement()) {
            score += 45
            reasons += "Expansion unit"
            val settleFact = buildSettlerFact(unit)
            if (settleFact != null) {
                localFacts += settleFact.observation.detail
                facts += settleFact
                opportunities += settleFact
                score += 45
            }
        }

        if (role == "worker" && unit.hasMovement()) {
            score += 40
            reasons += "Improvement unit"
            val workerJobs = AgentWorkerJobPlanner.findWorkerJobs(unit, currentAssignment = findUnitAssignment(memory, unit.id))
            if (workerJobs.isNotEmpty()) {
                localFacts += workerJobs.map { it.description }
                val topJob = workerJobs.first()
                val severity = if (topJob.priority >= 80) "warning" else "info"
                val workerFact = fact(
                    priority = 105 + topJob.priority,
                    category = "tiles",
                    severity = severity,
                    headline = "${unit.name} #${unit.id} has a useful worker job",
                    detail = topJob.description,
                )
                facts += workerFact
                opportunities += workerFact
                score += 35 + topJob.priority
            }
        }

        buildUnitAssignmentProgress(unit, memory, unitOptionCandidates)?.let { progress ->
            localFacts += progress.progressNote
            if (progress.status == "ready_to_finish") {
                score += 32
                reasons += "Ready to finish assignment"
            } else if (progress.status == "moving_to_target") {
                score += 18
                reasons += "Committed assignment in progress"
            }
        }

        if (unit.isMilitary() && nearbyHostileUnits > 0) {
            score += 40 + nearbyHostileUnits * 8
            reasons += "Enemy contact nearby"
            facts += fact(
                priority = 115 + nearbyHostileUnits * 8,
                category = "war",
                severity = if (unit.health < 70) "warning" else "info",
                headline = "${unit.name} #${unit.id} has local enemy contact",
                detail = "$nearbyHostileUnits hostile units and $nearbyHostileCities hostile cities are near this unit.",
            )
        }

        if (unit.health < 70) {
            score += 18
            reasons += "Damaged"
        }

        if (peacefulGrowthWindow &&
            unit.isMilitary() &&
            nearbyHostileUnits == 0 &&
            nearbyHostileCities == 0
        ) {
            score -= 18
            localFacts += "No local hostiles are visible around this military unit."
        }

        if (unit.hasMovement() && unit.isIdle()) {
            score += 10
            localFacts += "Idle with movement left."
        }

        if (objectiveTheaterHint != null && isTheaterRelevantUnit(unit, role)) {
            val distanceToObjective = axialDistance(
                unit.getTile().position.x,
                unit.getTile().position.y,
                objectiveTheaterHint.x,
                objectiveTheaterHint.y,
            )
            val assignment = findUnitAssignment(memory, unit.id)
            val assignmentTargetX = assignment?.targetX
            val assignmentTargetY = assignment?.targetY
            val assignmentNearObjective = assignmentTargetX != null &&
                assignmentTargetY != null &&
                axialDistance(assignmentTargetX, assignmentTargetY, objectiveTheaterHint.x, objectiveTheaterHint.y) <= 4
            val theaterRadius = if (civInfo.isAtWar()) 8 else 6
            if (distanceToObjective <= theaterRadius || nearbyHostileUnits > 0 || nearbyHostileCities > 0 || assignmentNearObjective) {
                score += if (unit.isMilitary()) 32 else 16
                reasons += "Objective theater"
                localFacts += "This unit can materially affect the current objective theater around ${objectiveTheaterHint.label}."
            }
        }

        if (unit.isEmbarked()) {
            localFacts += "Embarked on water."
        }

        return UnitCandidate(
            unit = unit,
            score = score,
            role = role,
            strength = unit.baseUnit.strength.takeIf { it > 0 },
            rangedStrength = unit.baseUnit.rangedStrength.takeIf { it > 0 },
            range = unit.getRange().takeIf { it > 1 },
            nearbyHostileUnits = nearbyHostileUnits,
            nearbyHostileCities = nearbyHostileCities,
            objectiveTheater = reasons.any { it == "Objective theater" },
            reasons = reasons.distinct().take(maxLocalFactsPerEntity),
            localFacts = localFacts.distinct().take(maxLocalFactsPerEntity),
            facts = facts,
            opportunities = opportunities,
        )
    }

    private fun buildVisibleTargetCandidates(
        civInfo: Civilization,
        allUnits: List<MapUnit>,
    ): List<VisibleTargetCandidate> {
        val seenCityCoords = HashSet<String>()
        val seenUnitIds = HashSet<Int>()
        val candidates = mutableListOf<VisibleTargetCandidate>()

        for (tile in civInfo.viewableTiles.sortedBy { it.position.toString() }) {
            val city = tile.getCity()
            if (tile.isCityCenter() && city != null && city.civ != civInfo) {
                val key = "${tile.position.x},${tile.position.y}"
                if (seenCityCoords.add(key)) {
                    candidates += buildVisibleCityCandidate(civInfo, city, tile, allUnits)
                }
            }

            for (unit in tile.getUnits()) {
                if (unit.civ == civInfo) continue
                if (!seenUnitIds.add(unit.id)) continue
                candidates += buildVisibleUnitCandidate(civInfo, unit, tile, allUnits)
            }
        }

        return candidates
    }

    private fun buildObjectiveTheaterHint(
        memory: AgentMemory,
        civInfo: Civilization,
        visibleTargetCandidates: List<VisibleTargetCandidate>,
    ): ObjectiveTheaterHint? {
        val currentStage = memory.campaign.stage.lowercase()
        val loweredObjective = memory.campaign.decisiveObjective?.lowercase().orEmpty()
        val shouldTrackTheater = civInfo.isAtWar() ||
            loweredObjective.isNotBlank() ||
            currentStage in setOf("staging", "pressure", "assault", "rebuild")
        if (!shouldTrackTheater) return null
        val visibleCities = visibleTargetCandidates
            .filter { it.observation.kind == "city" && it.observation.civName != civInfo.civName }
        val visibleCapital = visibleCities.firstOrNull { candidate ->
            candidate.observation.facts.any { it.equals("Capital", ignoreCase = true) }
        }
        if ("capital" in loweredObjective) {
            visibleCapital?.observation?.let { city ->
                return ObjectiveTheaterHint(city.civName, city.name, city.x, city.y)
            }
        }
        visibleCities.firstOrNull()?.observation?.let { city ->
            return ObjectiveTheaterHint(city.civName, city.name, city.x, city.y)
        }
        val primaryRival = memory.campaign.primaryRivalCiv ?: return null
        val preferredKinds = if ("capital" in loweredObjective) listOf("capital", "city") else listOf("city", "capital")
        val anchor = preferredKinds
            .asSequence()
            .mapNotNull { kind ->
                memory.worldModel.anchors
                    .filter { it.kind == kind && (it.civName == null || it.civName == primaryRival) }
                    .maxByOrNull { it.lastConfirmedTurn }
            }
            .firstOrNull()
            ?: return null
        val x = anchor.x ?: return null
        val y = anchor.y ?: return null
        return ObjectiveTheaterHint(anchor.civName ?: primaryRival, anchor.label, x, y)
    }

    private fun isTheaterRelevantUnit(unit: MapUnit, role: String): Boolean {
        if (unit.isMilitary()) return true
        return role in setOf("worker", "settler", "scout")
    }

    private fun axialDistance(x1: Int, y1: Int, x2: Int, y2: Int): Int {
        val dx = x1 - x2
        val dy = y1 - y2
        return (kotlin.math.abs(dx) + kotlin.math.abs(dy) + kotlin.math.abs(dx + dy)) / 2
    }

    private fun buildVisibleCityCandidate(
        civInfo: Civilization,
        city: City,
        tile: Tile,
        allUnits: List<MapUnit>,
    ): VisibleTargetCandidate {
        val relation = relationToCiv(civInfo, city.civ)
        val distanceToClosestCity = civInfo.cities.minOfOrNull { it.getCenterTile().aerialDistanceTo(tile) }
        val distanceToClosestUnit = allUnits.minOfOrNull { it.getTile().aerialDistanceTo(tile) }
        val facts = mutableListOf<String>()
        facts += "Population ${city.population.population}"
        facts += "Strength ${CityCombatant(city).getCityStrength()}"
        if (city.isCapital()) facts += "Capital"
        if (city.isCoastal()) facts += "Coastal"

        val score = scoreVisibleTarget(
            isHostile = isHostileRelation(relation),
            isCity = true,
            isCivilian = false,
            distanceToClosestCity = distanceToClosestCity,
            distanceToClosestUnit = distanceToClosestUnit,
        )

        return VisibleTargetCandidate(
            tile = tile,
            isHostile = isHostileRelation(relation),
            score = score,
            observation = VisibleTargetObservation(
                kind = "city",
                civName = city.civ.civName,
                name = city.name,
                relation = relation,
                x = tile.position.x,
                y = tile.position.y,
                health = city.health,
                combatStrength = CityCombatant(city).getCityStrength(),
                distanceToClosestCity = distanceToClosestCity,
                distanceToClosestUnit = distanceToClosestUnit,
                facts = facts.take(maxLocalFactsPerEntity),
            ),
        )
    }

    private fun buildVisibleUnitCandidate(
        civInfo: Civilization,
        unit: MapUnit,
        tile: Tile,
        allUnits: List<MapUnit>,
    ): VisibleTargetCandidate {
        val relation = relationToCiv(civInfo, unit.civ)
        val distanceToClosestCity = civInfo.cities.minOfOrNull { it.getCenterTile().aerialDistanceTo(tile) }
        val distanceToClosestUnit = allUnits.minOfOrNull { it.getTile().aerialDistanceTo(tile) }
        val facts = mutableListOf<String>()
        facts += if (unit.isCivilian()) "Civilian" else "Military"
        unit.baseUnit.strength.takeIf { it > 0 }?.let { facts += "Strength $it" }
        unit.baseUnit.rangedStrength.takeIf { it > 0 }?.let { facts += "Ranged $it" }
        if (unit.health < 100) facts += "Health ${unit.health}"

        val score = scoreVisibleTarget(
            isHostile = isHostileRelation(relation),
            isCity = false,
            isCivilian = unit.isCivilian(),
            distanceToClosestCity = distanceToClosestCity,
            distanceToClosestUnit = distanceToClosestUnit,
        )

        return VisibleTargetCandidate(
            tile = tile,
            isHostile = isHostileRelation(relation),
            score = score,
            observation = VisibleTargetObservation(
                kind = "unit",
                civName = unit.civ.civName,
                name = unit.name,
                relation = relation,
                x = tile.position.x,
                y = tile.position.y,
                health = unit.health,
                combatStrength = unit.baseUnit.rangedStrength.takeIf { it > 0 } ?: unit.baseUnit.strength.takeIf { it > 0 },
                distanceToClosestCity = distanceToClosestCity,
                distanceToClosestUnit = distanceToClosestUnit,
                facts = facts.take(maxLocalFactsPerEntity),
            ),
        )
    }

    private fun buildPriorityFacts(
        civInfo: Civilization,
        empireSummary: EmpireSummaryObservation,
        cityCandidates: List<CityCandidate>,
        unitCandidates: List<UnitCandidate>,
        visibleTargetCandidates: List<VisibleTargetCandidate>,
        opportunityFacts: List<ObservationFact>,
        peacefulGrowthWindow: Boolean,
    ): List<ScoredFact> {
        val facts = mutableListOf<ScoredFact>()

        if (empireSummary.happiness < 0) {
            facts += fact(
                priority = 175,
                category = "empire",
                severity = "warning",
                headline = "Empire happiness is negative",
                detail = "Current happiness is ${empireSummary.happiness}, so growth and combat pressure may worsen the turn.",
            )
        }

        if (empireSummary.unitSupplyDeficit > 0) {
            facts += fact(
                priority = 185 + empireSummary.unitSupplyDeficit * 12,
                category = "empire",
                severity = "warning",
                headline = "The army is over the unit supply cap",
                detail = "Unit count is over supply by ${empireSummary.unitSupplyDeficit}, causing a ${empireSummary.unitSupplyProductionPenaltyPercent}% production penalty until the roster shrinks or supply grows.",
            )
        }

        if (empireSummary.isAtWar) {
            facts += fact(
                priority = 120 + empireSummary.visibleHostileUnits * 8,
                category = "war",
                severity = if (empireSummary.visibleHostileUnits > 0) "warning" else "info",
                headline = "The empire is currently at war",
                detail = "Known war opponents: ${empireSummary.knownWarOpponents}. Visible hostile units: ${empireSummary.visibleHostileUnits}.",
            )
        }

        if (empireSummary.citiesNeedingProductionChoice > 0) {
            facts += fact(
                priority = 150,
                category = "city",
                severity = "warning",
                headline = "Some cities still need production choices",
                detail = "${empireSummary.citiesNeedingProductionChoice} cities still need explicit project choices.",
            )
        }

        if (peacefulGrowthWindow && empireSummary.cityCount <= 1) {
            facts += fact(
                priority = 160,
                category = "expand",
                severity = "info",
                headline = "The opening remains quiet",
                detail = "No visible local pressure is interrupting early expansion, growth, or worker tempo.",
            )
        }

        facts += cityCandidates.flatMap { it.facts }
        facts += unitCandidates.flatMap { it.facts }

        val hostileTargetsInRange = visibleTargetCandidates.count {
            it.isHostile && ((it.observation.distanceToClosestCity ?: Int.MAX_VALUE) <= 4 || (it.observation.distanceToClosestUnit ?: Int.MAX_VALUE) <= 3)
        }
        if (hostileTargetsInRange > 0) {
            facts += fact(
                priority = 110 + hostileTargetsInRange * 6,
                category = "war",
                severity = "warning",
                headline = "Visible hostile targets are close to our forces",
                detail = "$hostileTargetsInRange visible hostile targets are within immediate response distance of a city or unit.",
            )
        }

        facts += opportunityFacts.mapIndexed { index, opportunity ->
            fact(
                priority = 80 - index,
                category = opportunity.category,
                severity = opportunity.severity,
                headline = opportunity.headline,
                detail = opportunity.detail,
            )
        }

        return facts
    }

    private fun buildOpportunityFacts(
        selectedCityCandidates: List<CityCandidate>,
        selectedCities: List<AgentCityObservation>,
        selectedUnitCandidates: List<UnitCandidate>,
        selectedUnits: List<AgentUnitObservation>,
        visibleTargetCandidates: List<VisibleTargetCandidate>,
        selectedTargets: List<VisibleTargetObservation>,
    ): List<ScoredFact> {
        val facts = mutableListOf<ScoredFact>()

        facts += selectedCityCandidates.flatMap { it.opportunities }
        facts += selectedUnitCandidates.flatMap { it.opportunities }

        val targetByCoord = selectedTargets.associateBy { "${it.x},${it.y}" }
        for ((index, unitObservation) in selectedUnits.withIndex()) {
            val hostileReachableTargets = unitObservation.unitOptionCandidates
                .mapNotNull { candidate ->
                    val targetCoord = extractAttackTargetCoord(candidate.candidateId) ?: return@mapNotNull null
                    targetByCoord["${targetCoord.first},${targetCoord.second}"]
                }
                .filter { it.relation == "war" || it.relation == "barbarian" }
            if (hostileReachableTargets.isNotEmpty()) {
                val bestTarget = hostileReachableTargets.first()
                facts += fact(
                    priority = 115 - index,
                    category = "war",
                    severity = if (bestTarget.kind == "unit" && bestTarget.facts.any { it == "Civilian" }) "warning" else "info",
                    headline = "${unitObservation.name} #${unitObservation.id} has a grounded attack option",
                    detail = "A surfaced attack-target assignment already points at the visible hostile ${bestTarget.kind} at (${bestTarget.x}, ${bestTarget.y}).",
                )
            }
        }

        for (cityObservation in selectedCities) {
            if (cityObservation.state.canBombard && cityObservation.state.nearbyHostileUnits > 0) {
                facts += fact(
                    priority = 100,
                    category = "war",
                    severity = "info",
                    headline = "${cityObservation.name} can pressure nearby enemies",
                    detail = "The city can bombard and has ${cityObservation.state.nearbyHostileUnits} hostile units nearby.",
                )
            }
        }

        val exposedCivilianTarget = visibleTargetCandidates
            .map { it.observation }
            .firstOrNull { (it.relation == "war" || it.relation == "barbarian") && it.kind == "unit" && it.facts.any { fact -> fact == "Civilian" } }
        if (exposedCivilianTarget != null) {
            facts += fact(
                priority = 125,
                category = "war",
                severity = "warning",
                headline = "An exposed civilian is visible",
                detail = "${exposedCivilianTarget.civName}'s civilian unit is visible at (${exposedCivilianTarget.x}, ${exposedCivilianTarget.y}).",
            )
        }

        return facts
    }

    private fun buildUnitDetailReasons(
        candidate: UnitCandidate,
        unitOptionCandidates: List<UnitOptionCandidateObservation>,
        assignmentProgress: UnitAssignmentProgressObservation?,
    ): List<String> {
        val reasons = linkedSetOf<String>()
        val unit = candidate.unit
        if (unit.hasMovement()) reasons += "free_to_act"
        if (unitOptionCandidates.isNotEmpty()) reasons += "has_grounded_options"
        if (candidate.nearbyHostileUnits > 0 || candidate.nearbyHostileCities > 0) reasons += "local_threat"
        if (assignmentProgress?.status in setOf("ready_to_finish", "on_target", "assignment_at_risk")) {
            reasons += "assignment_requires_review"
        }
        if (candidate.role == "settler") reasons += "strategic_civilian"
        if (candidate.role == "worker" && unit.hasMovement()) reasons += "worker_decision"
        if (unit.health < 70 && unit.isMilitary()) reasons += "damaged_frontline"
        if (candidate.objectiveTheater) reasons += "objective_theater"
        if (unit.isCivilian() && (candidate.nearbyHostileUnits > 0 || candidate.nearbyHostileCities > 0)) {
            reasons += "exposed_civilian"
        }
        return reasons.take(4)
    }

    private fun buildCitySignals(
        candidate: CityCandidate,
        project: AgentCityProjectObservation?,
    ): List<String> {
        return buildList {
            addAll(candidate.reasons)
            addAll(candidate.localFacts)
            project?.note
                ?.takeIf {
                    candidate.localFacts.none { fact -> fact == it } &&
                        candidate.reasons.none { reason -> reason == it }
                }
                ?.let(::add)
        }
            .distinct()
            .take(6)
    }

    private fun isPeacefulGrowthWindow(
        civInfo: Civilization,
        visibleTargetCandidates: List<VisibleTargetCandidate>,
    ): Boolean {
        if (civInfo.gameInfo.turns > 45) return false
        if (civInfo.isAtWar()) return false
        if (civInfo.getHappiness() < 0) return false
        if (visibleTargetCandidates.any { it.isHostile }) return false
        if (civInfo.getKnownCivs().any { !it.isBarbarian && !it.isCityState }) return false
        return civInfo.cities.size <= 2
    }

    private fun findCityIntent(memory: AgentMemory, city: City): CityIntentMemory? {
        return memory.cityIntents.firstOrNull { it.cityX == city.location.x && it.cityY == city.location.y }
    }

    private fun findUnitAssignment(memory: AgentMemory, unitId: Int): UnitAssignmentMemory? {
        return memory.unitAssignments.firstOrNull { it.unitId == unitId }
    }

    private fun extractAttackTargetCoord(candidateId: String): Pair<Int, Int>? {
        if (!candidateId.startsWith("unitattack:")) return null
        val target = candidateId.substringAfterLast(':', "")
        val coords = target.split(",", limit = 2)
        if (coords.size != 2) return null
        return Pair(
            coords[0].toIntOrNull() ?: return null,
            coords[1].toIntOrNull() ?: return null,
        )
    }

    private fun buildProjectObservation(
        city: City,
        memory: AgentMemory,
    ): AgentCityProjectObservation? {
        val currentName = city.cityConstructions.currentConstructionName()
        val cityIntent = findCityIntent(memory, city)
        val projectIntent = cityIntent?.takeIf { it.intent == "develop_city" }
        if (currentName.isBlank() && projectIntent == null) return null
        if (currentName.isBlank()) {
            return AgentCityProjectObservation(
                name = null,
                status = "needs_choice",
                note = projectIntent?.target?.let { "No active project is selected even though memory was carrying $it." }
                    ?: "No active project is selected for this city.",
                switchCost = "low",
            )
        }

        val turnsLeft = city.cityConstructions.turnsToConstruction(currentName)
        val workDone = city.cityConstructions.getWorkDone(currentName)
        val workRemaining = city.cityConstructions.getRemainingWork(currentName)
        if (AgentCityProjectPolicy.needsExplicitProjectChoice(city)) {
            val note = if (projectIntent?.target == currentName) {
                "$currentName is selected, but no production has been invested yet. Treat this as a fresh project choice."
            } else {
                "$currentName is only the current placeholder project with no invested production yet. This city still needs an explicit project choice."
            }
            return AgentCityProjectObservation(
                name = currentName,
                turnsLeft = turnsLeft,
                productionInvested = workDone,
                productionRemaining = workRemaining,
                status = "needs_choice",
                note = note,
                switchCost = "low",
            )
        }
        val intentMatches = projectIntent?.target == currentName
        val status = when {
            turnsLeft <= 2 -> if (intentMatches) "nearly_complete" else "committed"
            intentMatches -> "following_intent"
            projectIntent?.target != null && projectIntent.target != currentName -> "drifted_from_intent"
            else -> "in_progress"
        }
        val progressNote = when (status) {
            "nearly_complete" -> "$currentName is already underway and should finish in $turnsLeft turns."
            "following_intent" -> "$currentName matches the carried city intent and is still in progress."
            "drifted_from_intent" -> "Memory expected ${projectIntent?.target}, but the city is currently building $currentName."
            else -> "$currentName is the active build with $workDone production invested and $workRemaining remaining."
        }
        val switchCost = when {
            turnsLeft <= 2 -> "high"
            workDone > 0 || intentMatches -> "medium"
            else -> "low"
        }
        return AgentCityProjectObservation(
            name = currentName,
            turnsLeft = turnsLeft,
            productionInvested = workDone,
            productionRemaining = workRemaining,
            status = status,
            note = progressNote,
            switchCost = switchCost,
        )
    }

    private fun buildUnitAssignmentProgress(
        unit: MapUnit,
        memory: AgentMemory,
        unitOptionCandidates: List<UnitOptionCandidateObservation>,
    ): UnitAssignmentProgressObservation? {
        val assignment = findUnitAssignment(memory, unit.id) ?: return null
        val onTarget = AgentUnitOptionBuilder.isAssignmentOnTarget(unit, assignment)
        val matchingCandidate = unitOptionCandidates.any { candidateMatchesAssignment(it.candidateId, assignment) }
        val groundedStep = AgentUnitOptionBuilder.hasGroundedAssignmentStep(unit, assignment)
        val readyToFinish = unitOptionCandidates.any { candidate ->
            candidate.candidateId.startsWith("unitworkerimprove:${unit.id}:${assignment.targetX},${assignment.targetY}:") ||
                candidate.candidateId == "unitsettle:${unit.id}:${assignment.targetX},${assignment.targetY}" ||
                candidateMatchesAssignment(candidate.candidateId, assignment)
        } || (assignment.role == "fallback_and_heal" && unit.health >= 85)
        val status = when {
            assignment.role == "auto_explore" && unit.isExploring() -> "automation_active"
            assignment.role == "fallback_and_heal" && unit.health >= 85 -> "ready_to_finish"
            onTarget && readyToFinish -> "ready_to_finish"
            onTarget -> "on_target"
            groundedStep && assignment.executionMode != "memory_only" -> "moving_to_target"
            matchingCandidate -> "moving_to_target"
            else -> "assignment_at_risk"
        }
        val progressNote = assignmentProgressNote(status, assignment)
        val switchCost = when {
            assignment.role in setOf("improve_tile", "settle_city_site") && status != "assignment_at_risk" -> "high"
            assignment.role in setOf(
                "auto_explore",
                "move_to_tile",
                "attack_target",
                "heal_and_hold",
                "hold_position",
                "stage_near_target_city",
                "attack_target_city",
                "fallback_and_heal",
            ) -> "medium"
            else -> "low"
        }
        return UnitAssignmentProgressObservation(
            role = publicAssignmentRole(assignment.role),
            targetX = assignment.targetX,
            targetY = assignment.targetY,
            detail = assignment.detail,
            assignmentSource = assignment.assignmentSource,
            assignmentCategory = assignment.assignmentCategory,
            executionMode = assignment.executionMode,
            completionPolicy = assignment.completionPolicy,
            lastProgressTurn = assignment.lastProgressTurn,
            staleAfterTurn = assignment.staleAfterTurn,
            status = status,
            progressNote = progressNote,
            switchCost = switchCost,
        )
    }

    private fun candidateMatchesAssignment(candidateId: String, assignment: UnitAssignmentMemory): Boolean {
        return when {
            candidateId.startsWith("unitworkerimprove:${assignment.unitId}:${assignment.targetX},${assignment.targetY}:") -> true
            candidateId == "unitworkerreposition:${assignment.unitId}:${assignment.targetX},${assignment.targetY}" -> true
            candidateId == "unitsettle:${assignment.unitId}:${assignment.targetX},${assignment.targetY}" -> true
            candidateId.startsWith("unitattack:${assignment.unitId}:") &&
                assignment.targetX != null &&
                assignment.targetY != null &&
                candidateId.endsWith(":${assignment.targetX},${assignment.targetY}") -> true
            candidateId == "unitautoexplore:${assignment.unitId}" -> assignment.role == "auto_explore"
            candidateId == "unitstopautoexplore:${assignment.unitId}" -> assignment.role == "stop_auto_explore"
            candidateId == "unitstagecity:${assignment.unitId}:${assignment.targetX},${assignment.targetY}" ->
                assignment.role == "stage_near_target_city"
            candidateId == "unitattackcity:${assignment.unitId}:${assignment.targetX},${assignment.targetY}" ->
                assignment.role == "attack_target_city"
            candidateId.startsWith("unitfallbackheal:${assignment.unitId}:") ->
                assignment.role == "fallback_and_heal"
            candidateId.startsWith("unitheal:${assignment.unitId}:") -> assignment.role == "heal_and_hold"
            candidateId.startsWith("unithold:${assignment.unitId}:") -> assignment.role == "hold_position"
            candidateId.startsWith("unitupgrade:${assignment.unitId}:") -> assignment.role == "upgrade_self"
            candidateId.startsWith("unitpillage:${assignment.unitId}:") -> assignment.role == "pillage_here"
            candidateId.startsWith("unitability:${assignment.unitId}:") -> assignment.role == "use_special_ability"
            else -> false
        }
    }

    private fun assignmentProgressNote(
        status: String,
        assignment: UnitAssignmentMemory,
    ): String {
        return when (assignment.role) {
            "auto_explore" -> when (status) {
                "automation_active" -> "This unit is currently auto-exploring and will keep scouting unless the tactician retasks it."
                "moving_to_target" -> "This unit still has a grounded way to resume auto-explore if left alone."
                else -> "This unit used to be on auto-explore, but that automation no longer looks healthy."
            }
            "stage_near_target_city" -> when (status) {
                "on_target" -> "This unit is already staged near the target city and should hold that pre-attack slot unless you retask it."
                "moving_to_target" -> "This unit is gathering near the target city and still has a grounded way to improve staging."
                else -> "This unit was supposed to stage near the target city, but the current turn no longer shows a clean matching route."
            }
            "attack_target_city" -> when (status) {
                "on_target" -> "This unit is already in a useful position to keep pressuring the target city."
                "moving_to_target" -> "This unit is committed to approaching and attacking the target city."
                else -> "This unit was supposed to attack the target city, but the current turn no longer shows a clean matching route."
            }
            "fallback_and_heal" -> when (status) {
                "ready_to_finish" -> "This unit has recovered enough that the fallback-and-heal job can end and the tactician can assign a fresh combat role."
                "on_target" -> "This unit is already on a safer fallback tile and should finish healing before taking a new combat job."
                "moving_to_target" -> "This unit is disengaging toward a safer healing tile."
                else -> "This unit was supposed to fall back and heal, but the current turn no longer shows a clean matching route."
            }
            else -> when (status) {
                "ready_to_finish" -> "This unit is already in position to finish its carried assignment."
                "on_target" -> "This unit is on the assigned target tile; prefer finishing the current job over switching away."
                "moving_to_target" -> "This unit is already committed to a carried assignment and still has a grounded route toward it."
                else -> "This unit had a carried assignment, but the current turn no longer surfaces a matching grounded option."
            }
        }
    }

    private fun publicAssignmentRole(role: String): String = role

    private fun collectCityResourceAlerts(city: City): List<String> {
        return city.getWorkableTiles()
            .filter { it.getOwner() == city.civ }
            .mapNotNull { tile -> buildCityTileAlert(city.civ, tile) }
            .take(4)
            .toList()
    }

    private fun buildCityTileAlert(civInfo: Civilization, tile: Tile): String? {
        if (tile.isPillaged()) {
            val improvementName = tile.getImprovementToRepair()?.name ?: "tile infrastructure"
            return "Repair $improvementName at (${tile.position.x}, ${tile.position.y})."
        }

        val resource = tile.tileResource
        if (resource != null && civInfo.canSeeResource(resource) && !tile.providesResources(civInfo)) {
            return "Connect ${resource.name} at (${tile.position.x}, ${tile.position.y})."
        }

        if (tile.isWorked() && !tile.isCityCenter() && tile.getUnpillagedTileImprovement() == null) {
            return "A worked tile at (${tile.position.x}, ${tile.position.y}) is still unimproved."
        }

        return null
    }

    private fun buildSettlerFact(unit: MapUnit): ScoredFact? {
        if (!unit.baseUnit.isCityFounder() || !unit.hasMovement()) return null
        val bestTiles = CityLocationTileRanker.getBestTilesToFoundCity(unit, minimumValue = 15f)
        val bestTile = bestTiles.bestTile ?: return null

        val siteFacts = mutableListOf<String>()
        if (bestTile.isAdjacentToRiver()) siteFacts += "river"
        if (bestTile.isCoastalTile()) siteFacts += "coast"
        if (bestTile.isHill()) siteFacts += "hill"
        val nearbyLuxuries = bestTile.getTilesInDistanceRange(0..2)
            .mapNotNull { it.tileResource }
            .filter { resource -> unit.civ.canSeeResource(resource) && resource.resourceType == ResourceType.Luxury }
            .distinctBy { it.name }
            .count()
        if (nearbyLuxuries > 0) siteFacts += "$nearbyLuxuries nearby luxuries"

        return fact(
            priority = 145,
            category = "expand",
            severity = "info",
            headline = "${unit.name} #${unit.id} has a viable city site",
            detail = "Best visible site is (${bestTile.position.x}, ${bestTile.position.y}) with rank ${bestTiles.bestTileRank.roundToInt()}${if (siteFacts.isNotEmpty()) " (${siteFacts.joinToString(", ")})" else ""}.",
        )
    }

    private fun classifyUnitRole(unit: MapUnit): String {
        return when {
            unit.baseUnit.isCityFounder() -> "settler"
            unit.cache.hasUniqueToBuildImprovements -> "worker"
            unit.isGreatPerson() -> "great_person"
            unit.baseUnit.isAirUnit() -> "air"
            unit.baseUnit.isWaterUnit && unit.baseUnit.isRanged() -> "naval_ranged"
            unit.baseUnit.isWaterUnit -> "naval_melee"
            unit.baseUnit.isProbablySiegeUnit() -> "siege"
            unit.baseUnit.isRanged() -> "ranged"
            unit.isCivilian() -> "civilian"
            else -> "melee"
        }
    }

    private fun relationToCiv(civInfo: Civilization, otherCiv: Civilization): String {
        return when {
            otherCiv.isBarbarian -> "barbarian"
            civInfo.isAtWarWith(otherCiv) -> "war"
            otherCiv.isCityState && otherCiv.allyCiv == civInfo -> "allied city-state"
            otherCiv.isCityState -> "city-state"
            else -> "peace"
        }
    }

    private fun isHostileRelation(relation: String): Boolean {
        return relation == "war" || relation == "barbarian"
    }

    private fun scoreVisibleTarget(
        isHostile: Boolean,
        isCity: Boolean,
        isCivilian: Boolean,
        distanceToClosestCity: Int?,
        distanceToClosestUnit: Int?,
    ): Int {
        var score = when {
            isHostile && isCivilian -> 90
            isHostile && isCity -> 82
            isHostile -> 78
            isCity -> 28
            else -> 22
        }

        val nearestDistance = listOfNotNull(distanceToClosestCity, distanceToClosestUnit).minOrNull()
        if (nearestDistance != null) {
            score += (12 - nearestDistance.coerceAtMost(12)) * 4
        }
        return score
    }

    private fun selectFacts(candidates: List<ScoredFact>, limit: Int): List<ObservationFact> {
        return candidates
            .sortedWith(compareByDescending<ScoredFact> { it.priority }.thenBy { it.observation.headline })
            .distinctBy { "${it.observation.headline}|${it.observation.detail}" }
            .take(limit)
            .map { it.observation }
    }

    private fun fact(
        priority: Int,
        category: String,
        severity: String,
        headline: String,
        detail: String,
    ) = ScoredFact(
        priority = priority,
        observation = ObservationFact(
            category = category,
            severity = severity,
            headline = headline,
            detail = detail,
        ),
    )

    private data class ScoredFact(
        val priority: Int,
        val observation: ObservationFact,
    )

    private data class CityCandidate(
        val city: City,
        val score: Int,
        val productionPerTurn: Int,
        val foodPerTurn: Int,
        val turnsToGrowth: Int?,
        val turnsToStarvation: Int?,
        val cityStrength: Int,
        val canBombard: Boolean,
        val nearbyHostileUnits: Int,
        val nearbyHostileCities: Int,
        val reasons: List<String>,
        val localFacts: List<String>,
        val facts: List<ScoredFact>,
        val opportunities: List<ScoredFact>,
    )

    private data class UnitCandidate(
        val unit: MapUnit,
        val score: Int,
        val role: String,
        val strength: Int?,
        val rangedStrength: Int?,
        val range: Int?,
        val nearbyHostileUnits: Int,
        val nearbyHostileCities: Int,
        val objectiveTheater: Boolean,
        val reasons: List<String>,
        val localFacts: List<String>,
        val facts: List<ScoredFact>,
        val opportunities: List<ScoredFact>,
    )

    private data class ObjectiveTheaterHint(
        val civName: String,
        val label: String,
        val x: Int,
        val y: Int,
    )

    private data class VisibleTargetCandidate(
        val tile: Tile,
        val isHostile: Boolean,
        val score: Int,
        val observation: VisibleTargetObservation,
    )
}
