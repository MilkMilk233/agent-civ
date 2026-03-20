package com.unciv.logic.automation.agent

import com.unciv.logic.automation.unit.CityLocationTileRanker
import com.unciv.logic.automation.city.ConstructionAutomation
import com.unciv.logic.battle.CityCombatant
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.UnitAction
import com.unciv.models.ruleset.PerpetualConstruction
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActions
import kotlin.math.roundToInt

object AgentObservationBuilder {
    private const val maxPriorityFacts = 8
    private const val maxOpportunityFacts = 8
    private const val maxCitiesNeedingAttention = 6
    private const val maxActionableUnits = 12
    private const val maxVisibleTargets = 8
    private const val maxConstructionsPerCity = 10
    private const val maxReachableTilesPerUnit = 14
    private const val maxLocalFactsPerEntity = 4

    fun build(civInfo: Civilization): AgentObservation {
        civInfo.updateStatsForNextTurn()
        civInfo.cities.forEach { it.cityStats.update(updateCivStats = false) }

        val allUnits = civInfo.units.getCivUnits()
            .sortedBy { it.id }
            .toList()
        val visibleTargetCandidates = buildVisibleTargetCandidates(civInfo, allUnits)
        val cityOptionContext = AgentCityOptionBuilder.build(civInfo)
        val cityCandidates = civInfo.cities
            .sortedWith(compareBy<City> { it.name }.thenBy { it.location.toString() })
            .map { buildCityCandidate(it, civInfo, visibleTargetCandidates) }
        val selectedCityCandidates = selectCityCandidates(cityCandidates)
        val selectedCities = selectedCityCandidates.map { candidate ->
            CityAttentionObservation(
                x = candidate.city.location.x,
                y = candidate.city.location.y,
                name = candidate.city.name,
                population = candidate.city.population.population,
                health = candidate.city.health,
                currentConstruction = candidate.city.cityConstructions.currentConstructionName().ifBlank { "None selected" },
                availableConstructions = buildConstructionOptions(candidate.city),
                productionPerTurn = candidate.productionPerTurn,
                foodPerTurn = candidate.foodPerTurn,
                turnsToGrowth = candidate.turnsToGrowth,
                turnsToStarvation = candidate.turnsToStarvation,
                cityStrength = candidate.cityStrength,
                canBombard = candidate.canBombard,
                isCapital = candidate.city.isCapital(),
                isCoastal = candidate.city.isCoastal(),
                isPuppet = candidate.city.isPuppet,
                isGarrisoned = candidate.city.isGarrisoned(),
                cityFocus = candidate.city.getCityFocus().name,
                nearbyHostileUnits = candidate.nearbyHostileUnits,
                nearbyHostileCities = candidate.nearbyHostileCities,
                reasons = candidate.reasons,
                localFacts = candidate.localFacts,
                cityOptionCandidates = cityOptionContext.observationsByCityKey["${candidate.city.location.x},${candidate.city.location.y}"]
                    ?: emptyList(),
            )
        }

        val unitCandidates = allUnits.map { buildUnitCandidate(it, civInfo, visibleTargetCandidates) }
        val selectedUnitCandidates = selectUnitCandidates(unitCandidates)
        val unitOptionContext = AgentUnitOptionBuilder.build(civInfo)
        val selectedUnits = selectedUnitCandidates.map { candidate ->
            val unit = candidate.unit
            val availableActions = collectAvailableUnitActions(unit)
            val unitActionTypes = availableActions
                .map { it.type.name }
                .distinct()
                .sorted()
            ActionableUnitObservation(
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
                unitActions = unitActionTypes,
                legalActionCandidates = buildLegalActionCandidates(
                    unit = unit,
                    role = candidate.role,
                    availableActions = availableActions,
                ),
                unitOptionCandidates = unitOptionContext.observationsByUnitId[unit.id] ?: emptyList(),
                reachableTiles = buildReachableTiles(unit),
                nearbyHostileUnits = candidate.nearbyHostileUnits,
                nearbyHostileCities = candidate.nearbyHostileCities,
                reasons = candidate.reasons,
                localFacts = candidate.localFacts,
            )
        }

        val selectedTargets = visibleTargetCandidates
            .sortedWith(compareByDescending<VisibleTargetCandidate> { it.score }.thenBy { it.observation.name })
            .take(maxVisibleTargets)
            .map { it.observation }

        val opportunityFacts = selectFacts(
            buildOpportunityFacts(
                selectedCityCandidates = selectedCityCandidates,
                selectedCities = selectedCities,
                selectedUnitCandidates = selectedUnitCandidates,
                selectedUnits = selectedUnits,
                visibleTargetCandidates = visibleTargetCandidates,
                selectedTargets = selectedTargets,
            ),
            maxOpportunityFacts,
        )

        val visibleHostileUnits = visibleTargetCandidates.count { it.isHostile && it.observation.kind == "unit" }
        val visibleForeignCities = visibleTargetCandidates.count { it.observation.kind == "city" }
        val empireSummary = EmpireSummaryObservation(
            isAtWar = civInfo.isAtWar(),
            gold = civInfo.gold,
            sciencePerTurn = civInfo.stats.statsForNextTurn.science.toInt(),
            culturePerTurn = civInfo.stats.statsForNextTurn.culture.toInt(),
            faithPerTurn = civInfo.stats.statsForNextTurn.faith.toInt(),
            happiness = civInfo.getHappiness(),
            visibleTiles = civInfo.viewableTiles.size,
            cityCount = civInfo.cities.size,
            unitCount = allUnits.size,
            militaryUnitCount = allUnits.count { it.isMilitary() },
            civilianUnitCount = allUnits.count { it.isCivilian() },
            knownCivs = civInfo.getKnownCivs().count(),
            knownWarOpponents = civInfo.getKnownCivs().count { civInfo.isAtWarWith(it) },
            citiesNeedingProductionChoice = civInfo.cities.count { it.cityConstructions.currentConstructionName().isBlank() },
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
                cityCandidates = selectedCityCandidates,
                unitCandidates = selectedUnitCandidates,
                visibleTargetCandidates = visibleTargetCandidates,
                opportunityFacts = opportunityFacts,
            ),
            maxPriorityFacts,
        )

        return AgentObservation(
            turn = civInfo.gameInfo.turns,
            civName = civInfo.civName,
            empireSummary = empireSummary,
            priorityFacts = priorityFacts,
            citiesNeedingAttention = selectedCities,
            actionableUnits = selectedUnits,
            visibleThreatsAndTargets = selectedTargets,
            opportunities = opportunityFacts,
            omittedSummary = OmittedSummaryObservation(
                quietCities = (civInfo.cities.size - selectedCities.size).coerceAtLeast(0),
                quietUnits = (allUnits.size - selectedUnits.size).coerceAtLeast(0),
                visibleTargetsOmitted = (visibleTargetCandidates.size - selectedTargets.size).coerceAtLeast(0),
                lowerPriorityFactsOmitted = (
                    buildPriorityFacts(civInfo, empireSummary, cityCandidates, unitCandidates, visibleTargetCandidates, opportunityFacts).size -
                        priorityFacts.size
                    ).coerceAtLeast(0),
                lowerPriorityOpportunitiesOmitted = (
                    buildOpportunityFacts(
                        selectedCityCandidates = selectedCityCandidates,
                        selectedCities = selectedCities,
                        selectedUnitCandidates = selectedUnitCandidates,
                        selectedUnits = selectedUnits,
                        visibleTargetCandidates = visibleTargetCandidates,
                        selectedTargets = selectedTargets,
                    ).size - opportunityFacts.size
                    ).coerceAtLeast(0),
            ),
        )
    }

    private fun buildCityCandidate(
        city: City,
        civInfo: Civilization,
        visibleTargetCandidates: List<VisibleTargetCandidate>,
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
            score += 10
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
            val workerJobs = findWorkerJobs(unit)
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

        if (unit.hasMovement() && unit.isIdle()) {
            score += 10
            localFacts += "Idle with movement left."
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
                detail = "${empireSummary.citiesNeedingProductionChoice} cities have no active construction queued.",
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
        selectedCities: List<CityAttentionObservation>,
        selectedUnitCandidates: List<UnitCandidate>,
        selectedUnits: List<ActionableUnitObservation>,
        visibleTargetCandidates: List<VisibleTargetCandidate>,
        selectedTargets: List<VisibleTargetObservation>,
    ): List<ScoredFact> {
        val facts = mutableListOf<ScoredFact>()

        facts += selectedCityCandidates.flatMap { it.opportunities }
        facts += selectedUnitCandidates.flatMap { it.opportunities }

        val targetByCoord = selectedTargets.associateBy { "${it.x},${it.y}" }
        for ((index, unitObservation) in selectedUnits.withIndex()) {
            val hostileReachableTargets = unitObservation.reachableTiles
                .mapNotNull { tileRef -> targetByCoord["${tileRef.x},${tileRef.y}"] }
                .filter { it.relation == "war" || it.relation == "barbarian" }
            if (hostileReachableTargets.isNotEmpty()) {
                val bestTarget = hostileReachableTargets.first()
                facts += fact(
                    priority = 115 - index,
                    category = "war",
                    severity = if (bestTarget.kind == "unit" && bestTarget.facts.any { it == "Civilian" }) "warning" else "info",
                    headline = "${unitObservation.name} #${unitObservation.id} can reach a visible target",
                    detail = "A reachable hostile ${bestTarget.kind} is already in the unit's known movement map at (${bestTarget.x}, ${bestTarget.y}).",
                )
            }
        }

        for (cityObservation in selectedCities) {
            if (cityObservation.canBombard && cityObservation.nearbyHostileUnits > 0) {
                facts += fact(
                    priority = 100,
                    category = "war",
                    severity = "info",
                    headline = "${cityObservation.name} can pressure nearby enemies",
                    detail = "The city can bombard and has ${cityObservation.nearbyHostileUnits} hostile units nearby.",
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

    private fun selectCityCandidates(candidates: List<CityCandidate>): List<CityCandidate> {
        val sorted = candidates.sortedWith(compareByDescending<CityCandidate> { it.score }.thenBy { it.city.name })
        val selected = sorted.filter { it.score > 0 }.take(maxCitiesNeedingAttention).toMutableList()
        if (selected.size >= minOf(2, sorted.size)) return selected

        val fallbacks = sorted
            .sortedWith(
                compareByDescending<CityCandidate> { it.productionPerTurn }
                    .thenByDescending { it.city.isCapital() }
                    .thenBy { it.city.name },
            )
            .filter { it !in selected }
            .take((minOf(2, sorted.size) - selected.size).coerceAtLeast(0))
        selected += fallbacks
        return selected.distinct().take(maxCitiesNeedingAttention)
    }

    private fun selectUnitCandidates(candidates: List<UnitCandidate>): List<UnitCandidate> {
        val movable = candidates.filter { it.unit.hasMovement() }
        val sorted = movable.sortedWith(compareByDescending<UnitCandidate> { it.score }.thenBy { it.unit.id })
        val selected = sorted.take(maxActionableUnits).toMutableList()
        if (selected.size >= minOf(4, sorted.size)) return selected

        val fallbacks = movable
            .sortedWith(compareByDescending<UnitCandidate> { it.unit.getTile().neighbors.count { neighbor -> neighbor.getUnits().any { other -> other.civ != it.unit.civ } } }.thenBy { it.unit.id })
            .filter { it !in selected }
            .take((minOf(4, sorted.size) - selected.size).coerceAtLeast(0))
        selected += fallbacks
        return selected.distinct().take(maxActionableUnits)
    }

    private fun buildConstructionOptions(city: City): List<String> {
        val ranked = ConstructionAutomation(city.cityConstructions)
            .getRankedConstructionChoices(limit = maxConstructionsPerCity)
            .map { it.name }
            .toMutableList()
        val cityConstructions = city.cityConstructions
        val fallbacks = (
            cityConstructions.getBuildableBuildings().map { it.name } +
                cityConstructions.getConstructableUnits().map { it.name } +
                PerpetualConstruction.perpetualConstructionsMap.values.asSequence()
                    .filter { it.isBuildable(cityConstructions) }
                    .map { it.name }
            )
            .distinct()
            .sorted()
        for (name in fallbacks) {
            if (ranked.size >= maxConstructionsPerCity) break
            if (name !in ranked) ranked += name
        }
        return ranked.take(maxConstructionsPerCity)
    }

    private fun collectAvailableUnitActions(unit: MapUnit): List<UnitAction> {
        return UnitActions.getUnitActions(unit)
            .asSequence()
            .filter { it.action != null }
            .toList()
    }

    private fun buildLegalActionCandidates(
        unit: MapUnit,
        role: String,
        availableActions: List<UnitAction>,
    ): List<LegalActionCandidateObservation> {
        if (role != "worker") return emptyList()

        val actionTypes = availableActions.map { it.type.name }.toSet()
        val preferredCurrentAction = preferredCurrentWorkerAction(availableActions)
        val hasAutomate = "Automate" in actionTypes
        val isAutomated = unit.isAutomated() || "StopAutomation" in actionTypes

        return findWorkerJobs(unit)
            .mapNotNull { job ->
                val isCurrentTile = unit.getTile().position.x == job.tileX && unit.getTile().position.y == job.tileY
                when {
                    isCurrentTile && preferredCurrentAction != null -> LegalActionCandidateObservation(
                        actionType = preferredCurrentAction.type.name,
                        title = preferredCurrentAction.title,
                        targetX = job.tileX,
                        targetY = job.tileY,
                        rationale = job.description,
                    )
                    hasAutomate -> LegalActionCandidateObservation(
                        actionType = "Automate",
                        title = "Automate",
                        moveDestinationX = job.tileX.takeIf { !isCurrentTile },
                        moveDestinationY = job.tileY.takeIf { !isCurrentTile },
                        targetX = job.tileX,
                        targetY = job.tileY,
                        rationale = if (isCurrentTile) {
                            "Use Automate on the current tile so the worker can handle: ${job.description}"
                        } else {
                            "Move to (${job.tileX}, ${job.tileY}) then use Automate so the worker can handle: ${job.description}"
                        },
                    )
                    isAutomated && isCurrentTile -> null
                    else -> null
                }
            }
            .distinctBy {
                listOf(
                    it.actionType,
                    it.title,
                    it.moveDestinationX,
                    it.moveDestinationY,
                    it.targetX,
                    it.targetY,
                ).joinToString("|")
            }
            .take(4)
    }

    private fun preferredCurrentWorkerAction(availableActions: List<UnitAction>): UnitAction? {
        val preferredTypes = listOf("Repair", "CreateImprovement", "ConstructImprovement", "ConnectRoad")
        return preferredTypes
            .asSequence()
            .mapNotNull { preferred -> availableActions.firstOrNull { it.type.name == preferred } }
            .firstOrNull()
    }

    private fun buildReachableTiles(unit: MapUnit): List<TileRef> {
        return unit.movement.getDistanceToTiles().keys
            .asSequence()
            .map { TileRef(it.position.x, it.position.y) }
            .distinct()
            .take(maxReachableTilesPerUnit)
            .toList()
    }

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

    private fun findWorkerJobs(unit: MapUnit): List<WorkerJob> {
        if (!unit.cache.hasUniqueToBuildImprovements || !unit.hasMovement()) return emptyList()
        val civInfo = unit.civ
        val candidateTiles = sequenceOf(unit.getTile()) + unit.movement.getDistanceToTiles().keys.asSequence()
        return candidateTiles
            .distinct()
            .mapNotNull { tile -> buildWorkerJob(unit, civInfo, tile) }
            .sortedByDescending { it.priority }
            .take(2)
            .toList()
    }

    private fun buildWorkerJob(unit: MapUnit, civInfo: Civilization, tile: Tile): WorkerJob? {
        if (tile.getOwner() != civInfo) return null

        if (tile.isPillaged()) {
            val repairName = tile.getImprovementToRepair()?.name ?: "tile infrastructure"
            return WorkerJob(
                priority = 90,
                tileX = tile.position.x,
                tileY = tile.position.y,
                description = "$repairName at (${tile.position.x}, ${tile.position.y}) is pillaged and needs repair.",
            )
        }

        val resource = tile.tileResource
        if (resource != null && civInfo.canSeeResource(resource) && !tile.providesResources(civInfo)) {
            val context = GameContext(civInfo = civInfo, unit = unit, tile = tile)
            val improvementName = resource.getImprovingImprovement(tile, context)
            if (improvementName != null) {
                val improvement = civInfo.gameInfo.ruleset.tileImprovements[improvementName]
                if (improvement != null && unit.canBuildImprovement(improvement, tile)) {
                    val priority = when (resource.resourceType) {
                        ResourceType.Luxury -> 85
                        ResourceType.Strategic -> 75
                        ResourceType.Bonus -> 45
                    }
                    return WorkerJob(
                        priority = priority,
                        tileX = tile.position.x,
                        tileY = tile.position.y,
                        description = "${resource.name} at (${tile.position.x}, ${tile.position.y}) is a strong worker target (${improvementName}).",
                    )
                }
            }
        }

        if (tile.isWorked() && !tile.isCityCenter() && tile.getUnpillagedTileImprovement() == null) {
            return WorkerJob(
                priority = 35,
                tileX = tile.position.x,
                tileY = tile.position.y,
                description = "The worked tile at (${tile.position.x}, ${tile.position.y}) is unimproved and worth worker attention.",
            )
        }

        return null
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

    private data class WorkerJob(
        val priority: Int,
        val tileX: Int,
        val tileY: Int,
        val description: String,
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
        val reasons: List<String>,
        val localFacts: List<String>,
        val facts: List<ScoredFact>,
        val opportunities: List<ScoredFact>,
    )

    private data class VisibleTargetCandidate(
        val tile: Tile,
        val isHostile: Boolean,
        val score: Int,
        val observation: VisibleTargetObservation,
    )
}
