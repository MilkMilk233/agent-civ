package com.unciv.logic.automation.agent

import com.unciv.logic.automation.city.ConstructionAutomation
import com.unciv.logic.city.City
import com.unciv.logic.city.CityFocus
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.IConstruction
import com.unciv.models.ruleset.INonPerpetualConstruction
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.stats.Stat
import kotlin.math.roundToInt

object AgentCityOptionBuilder {
    private const val maxConstructionCandidatesPerCity = 4
    private const val maxPurchaseCandidatesPerCity = 3
    private const val maxTilePurchaseCandidatesPerCity = 3
    private const val maxGrowthCandidatesPerCity = 1
    private const val maxFocusCandidatesPerCity = 4

    internal fun build(civInfo: Civilization, memory: AgentMemory = civInfo.agentMemory): AgentCityOptionContext {
        val candidates = LinkedHashMap<String, AgentCityRuntimeCandidate>()
        val observationsByCity = LinkedHashMap<String, AgentCityActionBuckets>()
        val conversionContext = buildConversionContext(memory)

        for (city in civInfo.cities.sortedWith(compareBy<City> { it.name }.thenBy { it.location.toString() })) {
            val cityKey = cityKey(city.location.x, city.location.y)
            val observations = observationsByCity.getOrPut(cityKey) { AgentCityActionBuckets() }

            appendCandidates(candidates, observations.chooseProject, buildConstructionCandidates(city))
            appendCandidates(candidates, observations.purchase, buildPurchaseCandidates(city, conversionContext))
            appendCandidates(candidates, observations.buyTile, buildTilePurchaseCandidates(city))
            appendCandidates(candidates, observations.growthMode, buildGrowthCandidates(city))
            appendCandidates(candidates, observations.focus, buildFocusCandidates(city))
        }

        return AgentCityOptionContext(
            candidates = candidates,
            observationsByCityKey = observationsByCity.mapValues { (_, buckets) -> buckets.freeze() },
        )
    }

    internal fun rankConstructionChoices(
        city: City,
        limit: Int = maxConstructionCandidatesPerCity,
        memory: AgentMemory = city.civ.agentMemory,
    ): List<RankedConstructionChoice> {
        val conversionContext = buildConversionContext(memory)
        val needsExplicitChoice = AgentCityProjectPolicy.needsExplicitProjectChoice(city)
        val orderedNames: List<String> = if (needsExplicitChoice || conversionContext.objectivePressure) {
            val names = mutableListOf<String>()
            names += city.cityConstructions.getBuildableBuildings().map { it.name }
            names += city.cityConstructions.getConstructableUnits().map { it.name }
            names.distinct().sorted()
        } else {
            val automation = ConstructionAutomation(city.cityConstructions)
            buildList<String> {
                addAll(automation.getRankedConstructionChoices(limit = limit * 3).map { it.name })
                addAll(city.cityConstructions.getBuildableBuildings().map { it.name })
                addAll(city.cityConstructions.getConstructableUnits().map { it.name })
            }.distinct()
        }

        val choices = orderedNames
            .mapIndexedNotNull { index, name ->
                val construction = runCatching { city.cityConstructions.getConstruction(name) }.getOrNull()
                    ?: return@mapIndexedNotNull null
                if (!construction.isBuildable(city.cityConstructions)) return@mapIndexedNotNull null
                val score = scoreConstructionChoice(city, construction, index, conversionContext)
                if (!needsExplicitChoice && score <= 0) return@mapIndexedNotNull null
                RankedConstructionChoice(
                    name = name,
                    score = score,
                    detail = describeConstructionChoice(city, construction, conversionContext),
                )
            }

        return if (needsExplicitChoice) {
            choices
                .sortedWith(compareByDescending<RankedConstructionChoice> { it.score }.thenBy { it.name })
        } else {
            choices
                .sortedWith(compareByDescending<RankedConstructionChoice> { it.score }.thenBy { it.name })
                .take(limit)
        }
    }

    internal fun isPeacefulGrowthWindow(city: City): Boolean {
        val civInfo = city.civ
        if (civInfo.gameInfo.turns > 45) return false
        if (civInfo.isAtWar()) return false
        if (civInfo.getHappiness() < 0) return false
        if (civInfo.getKnownCivs().any { !it.isBarbarian && !it.isCityState }) return false
        return civInfo.cities.size <= 2
    }

    private fun buildConstructionCandidates(city: City): List<AgentCityRuntimeCandidate> {
        val needsExplicitChoice = AgentCityProjectPolicy.needsExplicitProjectChoice(city)
        val currentName = city.cityConstructions.currentConstructionName()
        val choiceLimit = if (needsExplicitChoice) {
            maxConstructionCandidatesPerCity
        } else {
            maxConstructionCandidatesPerCity + 1
        }
        val rankedChoices = rankConstructionChoices(city, choiceLimit, city.civ.agentMemory)
            .filter { needsExplicitChoice || it.name != currentName }
        val surfacedChoices = if (needsExplicitChoice) rankedChoices else rankedChoices.take(maxConstructionCandidatesPerCity)
        return surfacedChoices
            .map { choice ->
                val candidateId = "citybuild:${city.location.x},${city.location.y}:${choice.name}"
                val construction = city.cityConstructions.getConstruction(choice.name)
                AgentCityRuntimeCandidate(
                    observation = AgentCityActionCandidateObservation(
                        candidateId = candidateId,
                        title = if (needsExplicitChoice && choice.name == currentName) {
                            "Keep ${choice.name}"
                        } else {
                            "Build ${choice.name}"
                        },
                        detail = choice.detail,
                        estimatedTurns = city.cityConstructions.turnsToConstruction(choice.name),
                        switchCost = buildProjectSwitchCost(city, choice.name),
                        yieldHints = yieldHintsForConstruction(construction),
                    ),
                    validate = { currentCiv ->
                        val liveCity = currentCiv.cities.firstOrNull { it.location == city.location }
                            ?: return@AgentCityRuntimeCandidate "City option rejected: city missing"
                        val liveConstruction = runCatching {
                            liveCity.cityConstructions.getConstruction(choice.name)
                        }.getOrNull()
                            ?: return@AgentCityRuntimeCandidate "City option rejected: construction is no longer available"
                        if (!liveConstruction.isBuildable(liveCity.cityConstructions)) {
                            return@AgentCityRuntimeCandidate "City option rejected: construction is no longer buildable"
                        }
                        null
                    },
                    execute = { currentCiv ->
                        val liveCity = currentCiv.cities.firstOrNull { it.location == city.location } ?: return@AgentCityRuntimeCandidate false
                        val liveConstruction = runCatching { liveCity.cityConstructions.getConstruction(choice.name) }.getOrNull()
                            ?: return@AgentCityRuntimeCandidate false
                        if (!liveConstruction.isBuildable(liveCity.cityConstructions)) return@AgentCityRuntimeCandidate false
                        AgentCityProjectPolicy.selectProject(liveCity, choice.name)
                    },
                    successMessage = "${city.name} switched active project to ${choice.name}",
                )
            }
    }

    private fun buildPurchaseCandidates(
        city: City,
        conversionContext: ConversionContext,
    ): List<AgentCityRuntimeCandidate> {
        val candidateNames = if (conversionContext.objectivePressure) {
            buildList {
                addAll(city.cityConstructions.getBuildableBuildings().map { it.name })
                addAll(city.cityConstructions.getConstructableUnits().map { it.name })
            }
        } else {
            val automation = ConstructionAutomation(city.cityConstructions)
            buildList {
                addAll(automation.getRankedConstructionChoices(limit = maxPurchaseCandidatesPerCity * 2).map { it.name })
                addAll(city.cityConstructions.getConstructableUnits().map { it.name })
            }
        }
        return candidateNames
            .distinct()
            .mapNotNull { name -> runCatching { city.cityConstructions.getConstruction(name) }.getOrNull() }
            .filterIsInstance<INonPerpetualConstruction>()
            .mapNotNull { construction ->
                val cost = construction.getStatBuyCost(city, Stat.Gold) ?: return@mapNotNull null
                if (!city.cityConstructions.isConstructionPurchaseAllowed(construction, Stat.Gold, cost)) return@mapNotNull null
                val purchaseScore = scorePurchaseCandidate(city, construction, cost, conversionContext)
                if (purchaseScore <= 0) return@mapNotNull null
                val detailParts = arrayListOf<String>()
                detailParts += "Cost $cost gold"
                detailParts += when (construction) {
                    is com.unciv.models.ruleset.Building -> "Building purchase"
                    is com.unciv.models.ruleset.unit.BaseUnit -> "Unit purchase"
                    else -> "Purchase"
                }
                if (conversionContext.objectivePressure) {
                    detailParts += describePurchasePriority(construction, conversionContext)
                }
                val candidateId = "citypurchase:${city.location.x},${city.location.y}:${construction.name}"
                PurchaseRuntimeCandidate(
                    runtime = AgentCityRuntimeCandidate(
                    observation = AgentCityActionCandidateObservation(
                        candidateId = candidateId,
                        title = "Buy ${construction.name}",
                        detail = detailParts.joinToString(", "),
                        goldCost = cost,
                        effectTiming = "immediate",
                        yieldHints = yieldHintsForConstruction(construction),
                    ),
                    validate = { currentCiv ->
                        val liveCity = currentCiv.cities.firstOrNull { it.location == city.location }
                            ?: return@AgentCityRuntimeCandidate "City option rejected: city missing"
                        val liveConstruction = runCatching {
                            liveCity.cityConstructions.getConstruction(construction.name)
                        }.getOrNull() as? INonPerpetualConstruction
                            ?: return@AgentCityRuntimeCandidate "City option rejected: construction is no longer available"
                        val liveCost = liveConstruction.getStatBuyCost(liveCity, Stat.Gold)
                            ?: return@AgentCityRuntimeCandidate "City option rejected: construction cannot be bought with gold"
                        if (!liveCity.cityConstructions.isConstructionPurchaseAllowed(liveConstruction, Stat.Gold, liveCost)) {
                            return@AgentCityRuntimeCandidate "City option rejected: purchase is no longer allowed"
                        }
                        null
                    },
                    execute = { currentCiv ->
                        val liveCity = currentCiv.cities.firstOrNull { it.location == city.location } ?: return@AgentCityRuntimeCandidate false
                        AgentCityProjectPolicy.purchaseWithGold(liveCity, construction.name)
                    },
                    successMessage = "Purchased ${construction.name} in ${city.name}",
                    ),
                    score = purchaseScore,
                )
            }
            .sortedWith(compareByDescending<PurchaseRuntimeCandidate> { it.score }.thenBy { it.runtime.observation.title })
            .map { it.runtime }
            .distinctBy { it.observation.candidateId }
            .take(maxPurchaseCandidatesPerCity)
    }

    private fun buildTilePurchaseCandidates(city: City): List<AgentCityRuntimeCandidate> {
        return city.expansion.getChoosableTiles()
            .asSequence()
            .filter { city.expansion.canBuyTile(it) && canAffordTilePurchase(city, it) }
            .map { tile -> TilePurchaseCandidate(tile, scoreTileForPurchase(city, tile), describeTileForPurchase(city, tile)) }
            .filter { it.score > 0 }
            .sortedWith(compareByDescending<TilePurchaseCandidate> { it.score }.thenBy { it.tile.position.toString() })
            .take(maxTilePurchaseCandidatesPerCity)
            .map { candidate ->
                val tile = candidate.tile
                val candidateId = "citytile:${city.location.x},${city.location.y}:${tile.position.x},${tile.position.y}"
                val goldCost = city.expansion.getGoldCostOfTile(tile)
                AgentCityRuntimeCandidate(
                    observation = AgentCityActionCandidateObservation(
                        candidateId = candidateId,
                        title = "Buy tile (${tile.position.x}, ${tile.position.y})",
                        detail = candidate.detail,
                        goldCost = goldCost,
                        effectTiming = "immediate",
                        tileSummary = summarizeTileForPurchase(city, tile),
                        yieldHints = yieldHintsForTile(city, tile),
                    ),
                    validate = { currentCiv ->
                        val liveCity = currentCiv.cities.firstOrNull { it.location == city.location }
                            ?: return@AgentCityRuntimeCandidate "City option rejected: city missing"
                        val liveTile = currentCiv.gameInfo.tileMap[tile.position]
                        if (!liveCity.expansion.canBuyTile(liveTile)) {
                            return@AgentCityRuntimeCandidate "City option rejected: tile can no longer be bought"
                        }
                        if (!canAffordTilePurchase(liveCity, liveTile)) {
                            return@AgentCityRuntimeCandidate "City option rejected: not enough gold to buy tile"
                        }
                        null
                    },
                    execute = { currentCiv ->
                        val liveCity = currentCiv.cities.firstOrNull { it.location == city.location } ?: return@AgentCityRuntimeCandidate false
                        val liveTile = currentCiv.gameInfo.tileMap[tile.position]
                        liveCity.expansion.buyTile(liveTile)
                        true
                    },
                    successMessage = "${city.name} bought tile (${tile.position.x}, ${tile.position.y})",
                )
            }
            .toList()
    }

    private fun buildFocusCandidates(city: City): List<AgentCityRuntimeCandidate> {
        val currentFocus = city.getCityFocus()
        val candidates = linkedSetOf<CityFocus>()
        val peacefulGrowthWindow = isPeacefulGrowthWindow(city)

        if (city.population.getNumTurnsToStarvation() != null || city.foodForNextTurn() < 0) candidates += CityFocus.FoodFocus
        if (city.cityConstructions.currentConstructionName().isBlank() || city.getThreatScore() > 0) candidates += CityFocus.ProductionFocus
        if (!peacefulGrowthWindow && city.civ.gold < 100) candidates += CityFocus.GoldFocus
        if (city.cityStats.currentCityStats.science > 0f) candidates += CityFocus.ScienceFocus
        if (!peacefulGrowthWindow && city.cityStats.currentCityStats.culture > 0f) candidates += CityFocus.CultureFocus
        candidates += CityFocus.NoFocus

        return candidates
            .filter { it != CityFocus.Manual && it != currentFocus }
            .take(if (peacefulGrowthWindow) 2 else maxFocusCandidatesPerCity)
            .map { focus ->
                val candidateId = "cityfocus:${city.location.x},${city.location.y}:${focus.name}"
                AgentCityRuntimeCandidate(
                    observation = AgentCityActionCandidateObservation(
                        candidateId = candidateId,
                        title = "Set ${city.name} to ${focus.name}",
                        detail = "Current focus is ${currentFocus.name}. Reassign citizens using ${focus.label}.",
                        effectTiming = "immediate",
                        yieldHints = yieldHintsForFocus(focus),
                    ),
                    validate = { currentCiv ->
                        val liveCity = currentCiv.cities.firstOrNull { it.location == city.location }
                            ?: return@AgentCityRuntimeCandidate "City option rejected: city missing"
                        if (liveCity.getCityFocus() == focus) {
                            return@AgentCityRuntimeCandidate "City option rejected: city focus is already ${focus.name}"
                        }
                        null
                    },
                    execute = { currentCiv ->
                        val liveCity = currentCiv.cities.firstOrNull { it.location == city.location } ?: return@AgentCityRuntimeCandidate false
                        if (liveCity.getCityFocus() == focus) return@AgentCityRuntimeCandidate false
                        liveCity.setCityFocus(focus)
                        liveCity.reassignPopulationDeferred()
                        true
                    },
                    successMessage = "${city.name} focus set to ${focus.name}",
                )
            }
    }

    private fun buildGrowthCandidates(city: City): List<AgentCityRuntimeCandidate> {
        val avoidGrowth = city.avoidGrowth
        val candidateId = if (avoidGrowth) {
            "citygrowth:${city.location.x},${city.location.y}:allow"
        } else {
            "citygrowth:${city.location.x},${city.location.y}:avoid"
        }
        val title = if (avoidGrowth) {
            "Allow growth in ${city.name}"
        } else {
            "Enable Avoid Growth in ${city.name}"
        }
        val detail = if (avoidGrowth) {
            "City is currently preventing growth. Re-enable normal food storage and citizen allocation."
        } else {
            "Prevent growth and reassign citizens toward non-food yields when that better fits the current plan."
        }
        return listOf(
            AgentCityRuntimeCandidate(
                observation = AgentCityActionCandidateObservation(
                    candidateId = candidateId,
                    title = title,
                    detail = detail,
                    effectTiming = "immediate",
                    yieldHints = if (avoidGrowth) listOf("food", "growth") else listOf("production", "gold", "stability"),
                ),
                validate = { currentCiv ->
                    val liveCity = currentCiv.cities.firstOrNull { it.location == city.location }
                        ?: return@AgentCityRuntimeCandidate "City option rejected: city missing"
                    if (liveCity.avoidGrowth == avoidGrowth) null else "City option rejected: growth mode already changed"
                },
                execute = { currentCiv ->
                    val liveCity = currentCiv.cities.firstOrNull { it.location == city.location } ?: return@AgentCityRuntimeCandidate false
                    if (liveCity.avoidGrowth != avoidGrowth) return@AgentCityRuntimeCandidate false
                    liveCity.avoidGrowth = !avoidGrowth
                    liveCity.reassignPopulation()
                    true
                },
                successMessage = if (avoidGrowth) {
                    "${city.name} can grow again"
                } else {
                    "${city.name} is now avoiding growth"
                },
            )
        ).take(maxGrowthCandidatesPerCity)
    }

    private fun scoreTileForPurchase(city: City, tile: Tile): Int {
        var score = 0
        if (tile.naturalWonder != null) score += 120
        val resource = tile.tileResource
        if (resource != null && city.civ.canSeeResource(resource)) {
            score += when (resource.resourceType) {
                ResourceType.Luxury -> 90
                ResourceType.Strategic -> 70
                ResourceType.Bonus -> 30
            }
        }
        val stats = tile.stats.getTileStats(city.civ)
        score += (stats.food + stats.production * 1.4f + stats.gold * 0.6f).roundToInt()
        if (tile.isHill()) score += 10
        if (tile.isAdjacentToRiver()) score += 8
        if (tile.getUnpillagedTileImprovement() == null) score += 4
        score -= city.expansion.getGoldCostOfTile(tile) / 40
        return score
    }

    private fun describeTileForPurchase(city: City, tile: Tile): String {
        val pieces = arrayListOf<String>()
        pieces += "Cost ${city.expansion.getGoldCostOfTile(tile)} gold"
        tile.tileResource?.takeIf { city.civ.canSeeResource(it) }?.let { resource ->
            pieces += resource.name
        }
        val stats = tile.stats.getTileStats(city.civ)
        pieces += "Yields ${stats.food.roundToInt()} food / ${stats.production.roundToInt()} prod / ${stats.gold.roundToInt()} gold"
        if (tile.naturalWonder != null) pieces += "Natural wonder"
        if (tile.isAdjacentToRiver()) pieces += "River"
        if (tile.isHill()) pieces += "Hill"
        return pieces.joinToString(", ")
    }

    private fun summarizeTileForPurchase(city: City, tile: Tile): String {
        val summary = arrayListOf<String>()
        tile.tileResource?.takeIf { city.civ.canSeeResource(it) }?.let { summary += it.name }
        if (tile.naturalWonder != null) summary += "Natural wonder"
        if (tile.isHill()) summary += "Hill"
        if (tile.isAdjacentToRiver()) summary += "River"
        if (summary.isEmpty()) summary += "Workable tile upgrade"
        return summary.joinToString(", ")
    }

    private fun canAffordTilePurchase(city: City, tile: Tile): Boolean {
        return city.civ.gameInfo.gameParameters.godMode || city.civ.gold >= city.expansion.getGoldCostOfTile(tile)
    }

    private fun yieldHintsForTile(city: City, tile: Tile): List<String> {
        val hints = linkedSetOf<String>()
        val stats = tile.stats.getTileStats(city.civ)
        if (stats.food.roundToInt() > 0) hints += "food"
        if (stats.production.roundToInt() > 0) hints += "production"
        if (stats.gold.roundToInt() > 0) hints += "gold"
        tile.tileResource?.takeIf { city.civ.canSeeResource(it) }?.let { resource ->
            hints += when (resource.resourceType) {
                ResourceType.Luxury -> "happiness"
                ResourceType.Strategic -> "strategic_resource"
                ResourceType.Bonus -> "tile_yield"
            }
        }
        return hints.toList()
    }

    private fun scoreConstructionChoice(
        city: City,
        construction: IConstruction,
        baseIndex: Int,
        conversionContext: ConversionContext,
    ): Int {
        val civInfo = city.civ
        val peacefulGrowthWindow = isPeacefulGrowthWindow(city)
        val noWorkerExists = civInfo.units.getCivUnits().none { it.cache.hasUniqueToBuildImprovements }
        val singleCity = civInfo.cities.size == 1
        val scoutCount = civInfo.units.getCivUnits().count { it.name == "Scout" }
        val contactComplete = civInfo.getKnownCivs().any { !it.isBarbarian && !it.isCityState }
        val currentName = city.cityConstructions.currentConstructionName()
        val currentTurnsLeft = currentName.takeIf { it.isNotBlank() }?.let { city.cityConstructions.turnsToConstruction(it) }
        val currentWorkDone = currentName.takeIf { it.isNotBlank() }?.let { city.cityConstructions.getWorkDone(it) } ?: 0

        var score = 220 - baseIndex * 18
        if (construction is Building && construction.isAnyWonder() && peacefulGrowthWindow && noWorkerExists) score -= 80
        if (construction is BaseUnit && construction.isMilitary && peacefulGrowthWindow && singleCity) score -= 15
        if (construction.name == currentName) score -= 40
        if (currentName.isNotBlank() && construction.name != currentName) {
            if (currentWorkDone > 0) score -= 30
            if (currentTurnsLeft != null && currentTurnsLeft <= 2) score -= 95
            else if (currentTurnsLeft != null && currentTurnsLeft <= 5) score -= 45
            if (peacefulGrowthWindow && currentName in setOf("Worker", "Settler", "Granary", "Monument")) {
                score -= 35
            }
        }

        when (construction) {
            is BaseUnit -> {
                if (construction.hasUnique(UniqueType.BuildImprovements, GameContext.IgnoreConditionals)) {
                    score += if (noWorkerExists) 180 else 30
                    if (peacefulGrowthWindow) score += 50
                }
                if (construction.isCityFounder()) {
                    if (singleCity && peacefulGrowthWindow && city.population.population >= 2 && civInfo.getHappiness() > 0) {
                        score += 150
                    } else {
                        score += 20
                    }
                }
                if (construction.name == "Scout" && peacefulGrowthWindow) {
                    score += when {
                        scoutCount == 0 -> 55
                        scoutCount == 1 && !contactComplete -> 15
                        scoutCount >= 2 -> -140
                        else -> 0
                    }
                }
            }
            is Building -> {
                when (construction.name) {
                    "Granary" -> if (peacefulGrowthWindow && city.population.population <= 3) score += 95
                    "Monument" -> if (civInfo.gameInfo.turns <= 35) score += 70
                    "Library" -> if (civInfo.gameInfo.turns >= 20) score += 45
                    "Shrine" -> if (peacefulGrowthWindow) score -= 10
                }
            }
        }

        if (conversionContext.objectivePressure) {
            score += conversionPressureAdjustment(city, construction, conversionContext)
            if (currentName.isNotBlank() && construction.name != currentName) {
                val currentConstruction = runCatching { city.cityConstructions.getConstruction(currentName) }.getOrNull()
                if (currentConstruction != null &&
                    isPassivePeacetimeConstruction(currentConstruction) &&
                    isFrontlineMilitaryConstruction(construction)
                ) {
                    score += if ((currentTurnsLeft ?: Int.MAX_VALUE) <= 2) 35 else 75
                }
            }
        }

        return score
    }

    private fun describeConstructionChoice(
        city: City,
        construction: IConstruction,
        conversionContext: ConversionContext,
    ): String {
        val civInfo = city.civ
        val peacefulGrowthWindow = isPeacefulGrowthWindow(city)
        val noWorkerExists = civInfo.units.getCivUnits().none { it.cache.hasUniqueToBuildImprovements }
        val singleCity = civInfo.cities.size == 1
        val scoutCount = civInfo.units.getCivUnits().count { it.name == "Scout" }
        val contactComplete = civInfo.getKnownCivs().any { !it.isBarbarian && !it.isCityState }
        val needsExplicitChoice = AgentCityProjectPolicy.needsExplicitProjectChoice(city)
        val currentName = city.cityConstructions.currentConstructionName()
        val currentProgress = currentConstructionProgressText(city, construction.name)

        val reasons = arrayListOf<String>()
        if (needsExplicitChoice && construction.name == currentName) {
            reasons += if (currentName.isBlank()) {
                "This city still needs an explicit first project choice"
            } else {
                "$currentName is only a provisional project with no invested production yet"
            }
        }
        when (construction) {
            is BaseUnit -> {
                if (construction.hasUnique(UniqueType.BuildImprovements, GameContext.IgnoreConditionals) && noWorkerExists) {
                    reasons += "No worker exists yet; this is a high-priority peaceful opener build"
                }
                if (construction.isCityFounder() && singleCity && peacefulGrowthWindow && city.population.population >= 2) {
                    reasons += "Safe one-city opener; a second city accelerates the snowball"
                }
                if (construction.name == "Scout" && peacefulGrowthWindow) {
                    when {
                        scoutCount == 0 ->
                            reasons += "First Scout is still a normal opener tool for contact and site certainty"
                        scoutCount == 1 && !contactComplete ->
                            reasons += "A second Scout can still be justified while contact remains unresolved, but recon should stop after that"
                        else ->
                            reasons += "Recon is already sufficient here; another Scout is drift unless the board shows an unusual reason"
                    }
                }
                if (construction.isMilitary && peacefulGrowthWindow && singleCity && scoutCount < 2 && !contactComplete) {
                    reasons += "A slower combat unit is less urgent than fast recon and growth tempo right now"
                }
            }
            is Building -> {
                when (construction.name) {
                    "Granary" -> reasons += "Improves early growth and helps the capital scale"
                    "Monument" -> reasons += "Speeds early culture and policy tempo"
                    "Library" -> reasons += "Improves early science once the opener is stable"
                }
                if (construction.isAnyWonder() && peacefulGrowthWindow) {
                    reasons += "Wonders are lower priority than worker/settler tempo in this opener"
                }
            }
        }

        if (conversionContext.objectivePressure) {
            when {
                isFrontlineMilitaryConstruction(construction) && conversionContext.frontlineShortage ->
                    reasons += "Current campaign needs more healthy capture-capable frontline units to convert pressure into a city take"
                isFrontlineMilitaryConstruction(construction) ->
                    reasons += "Frontline military production feeds the decisive objective more directly than passive city development"
                construction is BaseUnit && construction.isRanged() && conversionContext.rangedShortage ->
                    reasons += "The current campaign still needs more ranged support to soften the objective safely"
                construction is BaseUnit && construction.isRanged() ->
                    reasons += "Extra ranged support helps sustain pressure, but frontline units matter more if capture capability is thin"
                construction is BaseUnit && construction.name == "Scout" ->
                    reasons += if (conversionContext.expansionCheckpointLive) {
                        "Additional recon is lower value than securing the live second-city checkpoint"
                    } else {
                        "Additional recon is lower value than objective conversion during this campaign"
                    }
                construction is BaseUnit && construction.isCityFounder() ->
                    reasons += if (conversionContext.expansionCheckpointLive) {
                        "The second city is the live checkpoint that unlocks the rest of the plan"
                    } else {
                        "Expansion is not the current bottleneck; the decisive objective needs conversion first"
                    }
                construction is Building && isPassivePeacetimeConstruction(construction) ->
                    reasons += "Passive infrastructure slows the current conversion window unless the city is under real threat"
            }
        }

        if (reasons.isEmpty()) reasons += "Ranked city development option from current game state"
        currentProgress?.let { reasons += it }
        return reasons.joinToString(". ")
    }

    private fun scorePurchaseCandidate(
        city: City,
        construction: INonPerpetualConstruction,
        goldCost: Int,
        conversionContext: ConversionContext,
    ): Int {
        var score = 120 - goldCost / 12
        if (!conversionContext.objectivePressure) {
            if (construction is BaseUnit && construction.isMilitary) score += 20
            if (construction is Building && construction.name == "Library") score += 15
            return score
        }

        score += when {
            isFrontlineMilitaryConstruction(construction) && conversionContext.frontlineShortage -> 220
            isFrontlineMilitaryConstruction(construction) -> 170
            construction is BaseUnit && construction.isRanged() && conversionContext.rangedShortage -> 150
            construction is BaseUnit && construction.isRanged() -> 105
            construction is Building && isMilitaryDefenseBuilding(construction) && city.getThreatScore() > 0 -> 55
            construction is Building && isMilitaryInfrastructureBuilding(construction) -> 20
            construction is BaseUnit && construction.name == "Scout" -> -180
            construction is BaseUnit && construction.isCityFounder() -> -220
            construction is BaseUnit && construction.hasUnique(UniqueType.BuildImprovements, GameContext.IgnoreConditionals) -> -150
            construction is Building && isPassivePeacetimeConstruction(construction) -> -165
            else -> -90
        }
        return score
    }

    private fun describePurchasePriority(
        construction: INonPerpetualConstruction,
        conversionContext: ConversionContext,
    ): String {
        return when {
            isFrontlineMilitaryConstruction(construction) && conversionContext.frontlineShortage -> "High-value frontline conversion buy"
            isFrontlineMilitaryConstruction(construction) -> "Frontline reinforcement buy"
            construction is BaseUnit && construction.isRanged() && conversionContext.rangedShortage -> "Needed ranged support buy"
            construction is BaseUnit && construction.isRanged() -> "Supportive pressure buy"
            construction is Building && isMilitaryDefenseBuilding(construction) -> "Defensive emergency buy"
            else -> "Lower-priority purchase during the current campaign"
        }
    }

    private fun conversionPressureAdjustment(
        city: City,
        construction: IConstruction,
        conversionContext: ConversionContext,
    ): Int {
        return when {
            isFrontlineMilitaryConstruction(construction) && conversionContext.frontlineShortage -> 210
            isFrontlineMilitaryConstruction(construction) -> 150
            construction is BaseUnit && construction.isRanged() && conversionContext.rangedShortage -> 155
            construction is BaseUnit && construction.isRanged() -> 110
            construction is BaseUnit && construction.name == "Scout" -> -170
            construction is BaseUnit && construction.isCityFounder() && conversionContext.expansionCheckpointLive -> 170
            construction is BaseUnit && construction.isCityFounder() -> -210
            construction is BaseUnit && construction.hasUnique(UniqueType.BuildImprovements, GameContext.IgnoreConditionals) -> -130
            construction is Building && isMilitaryDefenseBuilding(construction) && city.getThreatScore() > 0 -> 45
            construction is Building && isMilitaryInfrastructureBuilding(construction) -> 25
            construction is Building && isPassivePeacetimeConstruction(construction) -> -145
            else -> -70
        }
    }

    private fun currentConstructionProgressText(city: City, candidateName: String): String? {
        val currentName = city.cityConstructions.currentConstructionName()
        if (currentName.isBlank() || currentName == candidateName) return null
        val turnsLeft = city.cityConstructions.turnsToConstruction(currentName)
        val workDone = city.cityConstructions.getWorkDone(currentName)
        val remaining = city.cityConstructions.getRemainingWork(currentName)
        return when {
            turnsLeft <= 2 -> "Switching away would abandon $currentName with only $turnsLeft turns left ($workDone invested, $remaining remaining)"
            workDone > 0 -> "Switching away would reset progress on $currentName ($workDone invested, $remaining remaining)"
            else -> null
        }
    }

    private fun buildProjectSwitchCost(city: City, candidateName: String): String {
        val currentName = city.cityConstructions.currentConstructionName()
        if (currentName.isBlank() || currentName == candidateName) return "low"
        val turnsLeft = city.cityConstructions.turnsToConstruction(currentName)
        val workDone = city.cityConstructions.getWorkDone(currentName)
        return when {
            turnsLeft <= 2 -> "high"
            workDone > 0 -> "medium"
            else -> "low"
        }
    }

    private fun yieldHintsForConstruction(construction: IConstruction): List<String> {
        val hints = linkedSetOf<String>()
        when (construction) {
            is Building -> {
                val lower = construction.name.lowercase()
                if ("granary" in lower || "water mill" in lower || "aqueduct" in lower) hints += "food"
                if ("library" in lower || "university" in lower || "public school" in lower) hints += "science"
                if ("monument" in lower || "amphitheater" in lower || "opera house" in lower) hints += "culture"
                if ("market" in lower || "bank" in lower || "mint" in lower) hints += "gold"
                if ("wall" in lower || "castle" in lower || "arsenal" in lower) hints += "defense"
                if (construction.isAnyWonder()) hints += "wonder"
                if (hints.isEmpty()) hints += "city_development"
            }
            is BaseUnit -> {
                when {
                    construction.isCityFounder() -> hints += "expansion"
                    construction.hasUnique(UniqueType.BuildImprovements, GameContext.IgnoreConditionals) -> hints += "tile_improvement"
                    construction.name == "Scout" -> hints += "recon"
                    construction.isMilitary -> hints += "military"
                    else -> hints += "unit"
                }
                if (construction.isRanged()) hints += "ranged"
                if (construction.isMilitary && !construction.isRanged() && construction.name != "Scout") hints += "frontline"
                if (construction.name == "Scout") hints += "tempo"
            }
            else -> hints += "tempo"
        }
        return hints.toList()
    }

    private fun yieldHintsForFocus(focus: CityFocus): List<String> = when (focus) {
        CityFocus.FoodFocus -> listOf("food", "growth")
        CityFocus.ProductionFocus -> listOf("production")
        CityFocus.GoldFocus -> listOf("gold")
        CityFocus.ScienceFocus -> listOf("science")
        CityFocus.CultureFocus -> listOf("culture")
        else -> listOf("balanced")
    }

    private fun buildConversionContext(memory: AgentMemory): ConversionContext {
        val stage = memory.campaign.stage.lowercase()
        val decisiveObjective = memory.campaign.decisiveObjective?.lowercase().orEmpty()
        val conversionBlocker = memory.campaign.conversionBlocker?.lowercase().orEmpty()
        val objectivePressure = stage in setOf("pressure", "staging", "assault", "rebuild", "declaration") ||
            decisiveObjective.contains("capture") ||
            decisiveObjective.contains("take") ||
            decisiveObjective.contains("assault") ||
            decisiveObjective.contains("war") ||
            decisiveObjective.contains("frontier")
        val expansionCheckpointLive =
            decisiveObjective.contains("second city") ||
                decisiveObjective.contains("found the second city") ||
                decisiveObjective.contains("found a second city") ||
                decisiveObjective.contains("city founded") ||
                decisiveObjective.contains("settler")
        return ConversionContext(
            objectivePressure = objectivePressure,
            expansionCheckpointLive = expansionCheckpointLive,
            frontlineShortage = conversionBlocker.contains("melee") ||
                conversionBlocker.contains("frontline") ||
                conversionBlocker.contains("capture"),
            rangedShortage = conversionBlocker.contains("ranged") ||
                conversionBlocker.contains("bombard") ||
                conversionBlocker.contains("siege") ||
                conversionBlocker.contains("support"),
        )
    }

    private fun isFrontlineMilitaryConstruction(construction: IConstruction): Boolean {
        return construction is BaseUnit &&
            construction.isMilitary &&
            !construction.isRanged() &&
            construction.name != "Scout"
    }

    private fun isMilitaryInfrastructureBuilding(construction: IConstruction): Boolean {
        if (construction !is Building) return false
        val lower = construction.name.lowercase()
        return lower.contains("barracks") || lower.contains("armory") || lower.contains("arsenal")
    }

    private fun isMilitaryDefenseBuilding(construction: IConstruction): Boolean {
        if (construction !is Building) return false
        val lower = construction.name.lowercase()
        return lower.contains("wall") || lower.contains("castle")
    }

    private fun isPassivePeacetimeConstruction(construction: IConstruction): Boolean {
        return when (construction) {
            is BaseUnit -> construction.name in setOf("Scout", "Settler") ||
                construction.hasUnique(UniqueType.BuildImprovements, GameContext.IgnoreConditionals)
            is Building -> !isMilitaryInfrastructureBuilding(construction) && !isMilitaryDefenseBuilding(construction)
            else -> false
        }
    }

    private fun City.getThreatScore(): Int {
        val cityTile = getCenterTile()
        return civ.viewableTiles.count { tile ->
            tile.getUnits().any { unit -> unit.civ != civ && cityTile.aerialDistanceTo(tile) <= 4 }
        }
    }

    private fun cityKey(x: Int, y: Int): String = "$x,$y"

    internal data class AgentCityOptionContext(
        val candidates: Map<String, AgentCityRuntimeCandidate>,
        val observationsByCityKey: Map<String, AgentCityActionsObservation>,
    )

    internal data class AgentCityRuntimeCandidate(
        val observation: AgentCityActionCandidateObservation,
        val validate: (Civilization) -> String?,
        val execute: (Civilization) -> Boolean,
        val successMessage: String,
    )

    internal data class RankedConstructionChoice(
        val name: String,
        val score: Int,
        val detail: String,
    )

    private data class TilePurchaseCandidate(
        val tile: Tile,
        val score: Int,
        val detail: String,
    )

    private data class PurchaseRuntimeCandidate(
        val runtime: AgentCityRuntimeCandidate,
        val score: Int,
    )

    private data class ConversionContext(
        val objectivePressure: Boolean,
        val expansionCheckpointLive: Boolean,
        val frontlineShortage: Boolean,
        val rangedShortage: Boolean,
    )

    private data class AgentCityActionBuckets(
        val chooseProject: MutableList<AgentCityActionCandidateObservation> = arrayListOf(),
        val purchase: MutableList<AgentCityActionCandidateObservation> = arrayListOf(),
        val buyTile: MutableList<AgentCityActionCandidateObservation> = arrayListOf(),
        val focus: MutableList<AgentCityActionCandidateObservation> = arrayListOf(),
        val growthMode: MutableList<AgentCityActionCandidateObservation> = arrayListOf(),
    ) {
        fun freeze(): AgentCityActionsObservation = AgentCityActionsObservation(
            chooseProject = chooseProject.toList(),
            purchase = purchase.toList(),
            buyTile = buyTile.toList(),
            focus = focus.toList(),
            growthMode = growthMode.toList(),
        )
    }

    private fun appendCandidates(
        candidatesById: MutableMap<String, AgentCityRuntimeCandidate>,
        target: MutableList<AgentCityActionCandidateObservation>,
        runtimeCandidates: List<AgentCityRuntimeCandidate>,
    ) {
        runtimeCandidates.forEach { candidate ->
            candidatesById[candidate.observation.candidateId] = candidate
            target += candidate.observation
        }
    }
}
