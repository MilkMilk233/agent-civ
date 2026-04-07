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

    internal fun build(civInfo: Civilization): AgentCityOptionContext {
        val candidates = LinkedHashMap<String, AgentCityRuntimeCandidate>()
        val observationsByCity = LinkedHashMap<String, AgentCityActionBuckets>()

        for (city in civInfo.cities.sortedWith(compareBy<City> { it.name }.thenBy { it.location.toString() })) {
            val cityKey = cityKey(city.location.x, city.location.y)
            val observations = observationsByCity.getOrPut(cityKey) { AgentCityActionBuckets() }

            appendCandidates(candidates, observations.chooseProject, buildConstructionCandidates(city))
            appendCandidates(candidates, observations.purchase, buildPurchaseCandidates(city))
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
    ): List<RankedConstructionChoice> {
        val automation = ConstructionAutomation(city.cityConstructions)
        val orderedNames = buildList {
            addAll(automation.getRankedConstructionChoices(limit = limit * 3).map { it.name })
            addAll(city.cityConstructions.getBuildableBuildings().map { it.name })
            addAll(city.cityConstructions.getConstructableUnits().map { it.name })
        }.distinct()

        return orderedNames
            .mapIndexedNotNull { index, name ->
                val construction = runCatching { city.cityConstructions.getConstruction(name) }.getOrNull()
                    ?: return@mapIndexedNotNull null
                if (!construction.isBuildable(city.cityConstructions)) return@mapIndexedNotNull null
                val score = scoreConstructionChoice(city, construction, index)
                if (score <= 0) return@mapIndexedNotNull null
                RankedConstructionChoice(
                    name = name,
                    score = score,
                    detail = describeConstructionChoice(city, construction),
                )
            }
            .sortedWith(compareByDescending<RankedConstructionChoice> { it.score }.thenBy { it.name })
            .take(limit)
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
        return rankConstructionChoices(city, maxConstructionCandidatesPerCity)
            .filter { it.name != city.cityConstructions.currentConstructionName() }
            .map { choice ->
                val candidateId = "citybuild:${city.location.x},${city.location.y}:${choice.name}"
                val construction = city.cityConstructions.getConstruction(choice.name)
                AgentCityRuntimeCandidate(
                    observation = AgentCityActionCandidateObservation(
                        candidateId = candidateId,
                        title = "Build ${choice.name}",
                        detail = buildString {
                            append(choice.detail)
                            currentConstructionProgressText(city, choice.name)?.let {
                                append(". ")
                                append(it)
                            }
                        },
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

    private fun buildPurchaseCandidates(city: City): List<AgentCityRuntimeCandidate> {
        val automation = ConstructionAutomation(city.cityConstructions)
        return automation.getRankedConstructionChoices(limit = maxPurchaseCandidatesPerCity * 2)
            .filterIsInstance<INonPerpetualConstruction>()
            .mapNotNull { construction ->
                val cost = construction.getStatBuyCost(city, Stat.Gold) ?: return@mapNotNull null
                if (!city.cityConstructions.isConstructionPurchaseAllowed(construction, Stat.Gold, cost)) return@mapNotNull null
                val detailParts = arrayListOf<String>()
                detailParts += "Cost $cost gold"
                detailParts += when (construction) {
                    is com.unciv.models.ruleset.Building -> "Building purchase"
                    is com.unciv.models.ruleset.unit.BaseUnit -> "Unit purchase"
                    else -> "Purchase"
                }
                val candidateId = "citypurchase:${city.location.x},${city.location.y}:${construction.name}"
                AgentCityRuntimeCandidate(
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
                )
            }
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
    ): Int {
        val civInfo = city.civ
        val peacefulGrowthWindow = isPeacefulGrowthWindow(city)
        val noWorkerExists = civInfo.units.getCivUnits().none { it.cache.hasUniqueToBuildImprovements }
        val singleCity = civInfo.cities.size == 1
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
                if (construction.name == "Scout" && peacefulGrowthWindow && civInfo.units.getCivUnits().count { it.name == construction.name } == 0) {
                    score += 25
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

        return score
    }

    private fun describeConstructionChoice(city: City, construction: IConstruction): String {
        val civInfo = city.civ
        val peacefulGrowthWindow = isPeacefulGrowthWindow(city)
        val noWorkerExists = civInfo.units.getCivUnits().none { it.cache.hasUniqueToBuildImprovements }
        val singleCity = civInfo.cities.size == 1
        val currentProgress = currentConstructionProgressText(city, construction.name)

        val reasons = arrayListOf<String>()
        when (construction) {
            is BaseUnit -> {
                if (construction.hasUnique(UniqueType.BuildImprovements, GameContext.IgnoreConditionals) && noWorkerExists) {
                    reasons += "No worker exists yet; this is a high-priority peaceful opener build"
                }
                if (construction.isCityFounder() && singleCity && peacefulGrowthWindow && city.population.population >= 2) {
                    reasons += "Safe one-city opener; a second city accelerates the snowball"
                }
                if (construction.name == "Scout" && peacefulGrowthWindow) {
                    reasons += "Extra map vision is still useful while the map is quiet"
                }
                if (construction.isMilitary && peacefulGrowthWindow && singleCity) {
                    reasons += "Military value is lower than growth and expansion right now"
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

        if (reasons.isEmpty()) reasons += "Ranked city development option from current game state"
        currentProgress?.let { reasons += it }
        return reasons.joinToString(". ")
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
                    construction.isMilitary -> hints += "military"
                    else -> hints += "unit"
                }
                if (construction.isRanged()) hints += "ranged"
                if (construction.isMilitary && !construction.isRanged()) hints += "frontline"
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
