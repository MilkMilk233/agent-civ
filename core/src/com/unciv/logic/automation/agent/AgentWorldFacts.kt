package com.unciv.logic.automation.agent

import com.unciv.logic.civilization.Civilization
import com.unciv.ui.screens.victoryscreen.RankingType
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class AgentWorldFactsObservation(
    val turn: Int,
    val civs: List<AgentWorldFactCivObservation>,
)

@Serializable
data class AgentWorldFactCivObservation(
    val civName: String,
    val relation: String,
    val isSelf: Boolean,
    val isMajorCiv: Boolean,
    val isCityState: Boolean,
    val isAtWarWithUs: Boolean,
    val capitalName: String? = null,
    val score: Int,
    val force: Int,
    val technologies: Int,
    val cities: Int,
    val population: Int,
    val units: Int,
    val militaryUnits: Int,
    val civilianUnits: Int,
    val gold: Int,
    val happiness: Int,
    val sciencePerTurn: Int,
    val culturePerTurn: Int,
    val faithPerTurn: Int,
    val currentResearch: String? = null,
    val currentResearchTurnsLeft: Int? = null,
)

object AgentWorldFactsBuilder {
    private val json = Json {
        prettyPrint = false
        explicitNulls = false
    }

    fun json(civInfo: Civilization): String = json.encodeToString(build(civInfo))

    fun build(civInfo: Civilization): AgentWorldFactsObservation {
        val civs = civInfo.gameInfo.civilizations
            .asSequence()
            .filterNot { it.isDefeated() || it.isSpectator() || it.isBarbarian }
            .sortedWith(
                compareByDescending<Civilization> { it == civInfo }
                    .thenByDescending { it.isMajorCiv() }
                    .thenBy { it.civName },
            )
            .map { other ->
                other.updateStatsForNextTurn()
                other.cities.forEach { it.cityStats.update(updateCivStats = false) }
                val units = other.units.getCivUnits().toList()
                val currentResearch = other.tech.currentTechnologyName()
                val sciencePerTurn = other.stats.statsForNextTurn.science.toInt()
                val currentResearchTurnsLeft = currentResearch?.let {
                    if (sciencePerTurn <= 0) null else other.tech.turnsToTech(it).toIntOrNull()
                }
                AgentWorldFactCivObservation(
                    civName = other.civName,
                    relation = when {
                        other == civInfo -> "self"
                        other.isCityState -> "city_state"
                        civInfo.isAtWarWith(other) -> "war"
                        else -> "foreign"
                    },
                    isSelf = other == civInfo,
                    isMajorCiv = other.isMajorCiv(),
                    isCityState = other.isCityState,
                    isAtWarWithUs = other != civInfo && civInfo.isAtWarWith(other),
                    capitalName = other.cities.firstOrNull { it.isCapital() }?.name,
                    score = other.getStatForRanking(RankingType.Score),
                    force = other.getStatForRanking(RankingType.Force),
                    technologies = other.getStatForRanking(RankingType.Technologies),
                    cities = other.cities.size,
                    population = other.cities.sumOf { city -> city.population.population },
                    units = units.size,
                    militaryUnits = units.count { it.isMilitary() },
                    civilianUnits = units.count { !it.isMilitary() },
                    gold = other.gold,
                    happiness = other.getHappiness(),
                    sciencePerTurn = sciencePerTurn,
                    culturePerTurn = other.stats.statsForNextTurn.culture.toInt(),
                    faithPerTurn = other.stats.statsForNextTurn.faith.toInt(),
                    currentResearch = currentResearch,
                    currentResearchTurnsLeft = currentResearchTurnsLeft,
                )
            }
            .toList()

        return AgentWorldFactsObservation(
            turn = civInfo.gameInfo.turns,
            civs = civs,
        )
    }
}
