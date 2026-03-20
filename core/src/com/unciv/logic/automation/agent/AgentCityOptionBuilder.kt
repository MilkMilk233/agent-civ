package com.unciv.logic.automation.agent

import com.unciv.logic.automation.city.ConstructionAutomation
import com.unciv.logic.city.City
import com.unciv.logic.city.CityFocus
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.INonPerpetualConstruction
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.stats.Stat
import kotlin.math.roundToInt

object AgentCityOptionBuilder {
    private const val maxPurchaseCandidatesPerCity = 3
    private const val maxTilePurchaseCandidatesPerCity = 3
    private const val maxFocusCandidatesPerCity = 4

    internal fun build(civInfo: Civilization): AgentCityOptionContext {
        val candidates = LinkedHashMap<String, AgentCityRuntimeCandidate>()
        val observationsByCity = LinkedHashMap<String, MutableList<CityOptionCandidateObservation>>()

        for (city in civInfo.cities.sortedWith(compareBy<City> { it.name }.thenBy { it.location.toString() })) {
            val cityKey = cityKey(city.location.x, city.location.y)
            val observations = observationsByCity.getOrPut(cityKey) { arrayListOf() }

            buildPurchaseCandidates(city).forEach { candidate ->
                candidates[candidate.observation.candidateId] = candidate
                observations += candidate.observation
            }
            buildTilePurchaseCandidates(city).forEach { candidate ->
                candidates[candidate.observation.candidateId] = candidate
                observations += candidate.observation
            }
            buildFocusCandidates(city).forEach { candidate ->
                candidates[candidate.observation.candidateId] = candidate
                observations += candidate.observation
            }
        }

        return AgentCityOptionContext(
            candidates = candidates,
            observationsByCityKey = observationsByCity,
        )
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
                    observation = CityOptionCandidateObservation(
                        candidateId = candidateId,
                        category = "purchase",
                        title = "Buy ${construction.name}",
                        detail = detailParts.joinToString(", "),
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
                        liveCity.cityConstructions.purchaseConstruction(construction.name, -1, true, Stat.Gold)
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
            .filter { city.expansion.canBuyTile(it) }
            .map { tile -> TilePurchaseCandidate(tile, scoreTileForPurchase(city, tile), describeTileForPurchase(city, tile)) }
            .filter { it.score > 0 }
            .sortedWith(compareByDescending<TilePurchaseCandidate> { it.score }.thenBy { it.tile.position.toString() })
            .take(maxTilePurchaseCandidatesPerCity)
            .map { candidate ->
                val tile = candidate.tile
                val candidateId = "citytile:${city.location.x},${city.location.y}:${tile.position.x},${tile.position.y}"
                AgentCityRuntimeCandidate(
                    observation = CityOptionCandidateObservation(
                        candidateId = candidateId,
                        category = "tile",
                        title = "Buy tile (${tile.position.x}, ${tile.position.y})",
                        detail = candidate.detail,
                    ),
                    validate = { currentCiv ->
                        val liveCity = currentCiv.cities.firstOrNull { it.location == city.location }
                            ?: return@AgentCityRuntimeCandidate "City option rejected: city missing"
                        val liveTile = currentCiv.gameInfo.tileMap[tile.position]
                            ?: return@AgentCityRuntimeCandidate "City option rejected: tile missing"
                        if (!liveCity.expansion.canBuyTile(liveTile)) {
                            return@AgentCityRuntimeCandidate "City option rejected: tile can no longer be bought"
                        }
                        null
                    },
                    execute = { currentCiv ->
                        val liveCity = currentCiv.cities.firstOrNull { it.location == city.location } ?: return@AgentCityRuntimeCandidate false
                        val liveTile = currentCiv.gameInfo.tileMap[tile.position] ?: return@AgentCityRuntimeCandidate false
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

        if (city.population.getNumTurnsToStarvation() != null || city.foodForNextTurn() < 0) candidates += CityFocus.FoodFocus
        if (city.cityConstructions.currentConstructionName().isBlank() || city.getThreatScore() > 0) candidates += CityFocus.ProductionFocus
        if (city.civ.gold < 100) candidates += CityFocus.GoldFocus
        if (city.civ.gameInfo.isReligionEnabled() && city.cityStats.currentCityStats.faith > 0f) candidates += CityFocus.FaithFocus
        if (city.cityStats.currentCityStats.science > 0f) candidates += CityFocus.ScienceFocus
        if (city.cityStats.currentCityStats.culture > 0f) candidates += CityFocus.CultureFocus
        candidates += CityFocus.NoFocus

        return candidates
            .filter { it != CityFocus.Manual && it != currentFocus }
            .take(maxFocusCandidatesPerCity)
            .map { focus ->
                val candidateId = "cityfocus:${city.location.x},${city.location.y}:${focus.name}"
                AgentCityRuntimeCandidate(
                    observation = CityOptionCandidateObservation(
                        candidateId = candidateId,
                        category = "focus",
                        title = "Set ${city.name} to ${focus.name}",
                        detail = "Current focus is ${currentFocus.name}. Reassign citizens using ${focus.label}.",
                    ),
                    validate = { currentCiv ->
                        val liveCity = currentCiv.cities.firstOrNull { it.location == city.location }
                            ?: return@AgentCityRuntimeCandidate "City option rejected: city missing"
                        if (liveCity.getCityFocus() == focus) {
                            return@AgentCityRuntimeCandidate "City option rejected: city focus is already ${focus.name}"
                        }
                        if (focus == CityFocus.FaithFocus && !currentCiv.gameInfo.isReligionEnabled()) {
                            return@AgentCityRuntimeCandidate "City option rejected: faith focus is unavailable without religion"
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

    private fun City.getThreatScore(): Int {
        val cityTile = getCenterTile()
        return civ.viewableTiles.count { tile ->
            tile.getUnits().any { unit -> unit.civ != civ && cityTile.aerialDistanceTo(tile) <= 4 }
        }
    }

    private fun cityKey(x: Int, y: Int): String = "$x,$y"

    internal data class AgentCityOptionContext(
        val candidates: Map<String, AgentCityRuntimeCandidate>,
        val observationsByCityKey: Map<String, List<CityOptionCandidateObservation>>,
    )

    internal data class AgentCityRuntimeCandidate(
        val observation: CityOptionCandidateObservation,
        val validate: (Civilization) -> String?,
        val execute: (Civilization) -> Boolean,
        val successMessage: String,
    )

    private data class TilePurchaseCandidate(
        val tile: Tile,
        val score: Int,
        val detail: String,
    )
}
