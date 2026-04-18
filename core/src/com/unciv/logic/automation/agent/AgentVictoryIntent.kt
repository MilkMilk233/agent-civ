package com.unciv.logic.automation.agent

import com.unciv.logic.IsPartOfGameInfoSerialization
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class AgentVictoryIntentObservation(
    val allowedVictoryTypes: List<String>,
    val forcedVictoryType: String? = null,
    val preferredVictoryTypes: List<String> = emptyList(),
    val heuristicPrimaryVictory: String? = null,
    val heuristicSecondaryVictory: String? = null,
    val primaryVictoryFocus: String? = null,
    val nextVictoryMilestone: String? = null,
    val effectiveWinPath: String? = null,
    val militaryPurpose: String = "deterrence",
    val economicPosture: String = "boom",
    val raceRivalCiv: String? = null,
    val campaignRivalCiv: String? = null,
)

@Serializable
data class AgentVictoryIntentMemory(
    var allowedVictoryTypes: ArrayList<String> = arrayListOf(),
    var forcedVictoryType: String? = null,
    var preferredVictoryTypes: ArrayList<String> = arrayListOf(),
    var heuristicPrimaryVictory: String? = null,
    var heuristicSecondaryVictory: String? = null,
    var primaryVictoryFocus: String? = null,
    var nextVictoryMilestone: String? = null,
    var effectiveWinPath: String? = null,
    var militaryPurpose: String = "deterrence",
    var economicPosture: String = "boom",
    var raceRivalCiv: String? = null,
    var campaignRivalCiv: String? = null,
    var lastUpdatedTurn: Int = 0,
) : IsPartOfGameInfoSerialization {
    constructor() : this(
        arrayListOf(),
        null,
        arrayListOf(),
        null,
        null,
        null,
        null,
        null,
        "deterrence",
        "boom",
        null,
        null,
        0,
    )
}

object AgentVictoryIntentResolver {
    private val json = Json {
        prettyPrint = false
        explicitNulls = false
    }

    fun resolve(
        memory: AgentMemory,
        observation: AgentObservation,
        empireObservation: AgentEmpireObservation,
    ): AgentVictoryIntentObservation {
        val allowedVictoryTypes = empireObservation.enabledVictoryTypes
        val forcedVictoryType = allowedVictoryTypes.singleOrNull()
        val preferredVictoryTypes = empireObservation.preferredVictoryTypes
            .mapNotNull { canonicalVictoryType(it, allowedVictoryTypes) }
            .distinct()
        val heuristicPrimaryVictory = canonicalVictoryType(
            empireObservation.heuristicVictoryGoal,
            allowedVictoryTypes,
        )
        val heuristicSecondaryVictory = preferredVictoryTypes
            .firstOrNull { it != heuristicPrimaryVictory }
        val sanitizedMemoWinPath = sanitizeWinPath(
            rawWinPath = memory.lastStrategistMemo.winPath,
            allowedVictoryTypes = allowedVictoryTypes,
            heuristicPrimaryVictory = heuristicPrimaryVictory,
        )
        val effectiveWinPath = forcedVictoryType
            ?: sanitizedMemoWinPath
            ?: heuristicPrimaryVictory
            ?: preferredVictoryTypes.firstOrNull()
            ?: allowedVictoryTypes.firstOrNull()
        val primaryVictoryFocus = when {
            empireObservation.heuristicVictoryGoal.equals(heuristicPrimaryVictory, ignoreCase = true) ->
                empireObservation.heuristicVictoryFocus
            effectiveWinPath != null ->
                defaultVictoryFocus(effectiveWinPath)
            else -> null
        }
        val warChoiceAvailable = empireObservation.diplomacyCandidates.any { it.candidateId.startsWith("diplo:war:") }
        val visibleRivalCities = observation.visibleThreatsAndTargets.filter {
            it.kind == "city" && it.civName != observation.civName
        }
        val visibleRivalUnits = observation.visibleThreatsAndTargets.filter {
            it.kind == "unit" && it.civName != observation.civName
        }
        val raceRivalCiv = memory.campaign.raceRivalCiv
            ?: empireObservation.victoryThreats.firstOrNull()?.civName
            ?: memory.victoryIntent.raceRivalCiv
            ?: memory.campaign.primaryRivalCiv
            ?: visibleRivalCities.firstOrNull()?.civName
            ?: visibleRivalUnits.firstOrNull()?.civName
        val dominationLine = effectiveWinPath == "Domination"
        val campaignStage = listOf(
            memory.lastStrategistMemo.decisionFrame.decisionMode,
            memory.lastStrategistMemo.campaignStage,
            memory.campaign.stage,
        )
            .firstOrNull { !it.isNullOrBlank() }
        val conquestShaping = dominationLine && (
            observation.empireSummary.isAtWar ||
                isWarLikeStage(campaignStage) ||
                memory.campaignControl.commitmentLevel in setOf("committed", "launch_window") ||
                (empireObservation.gameContext.duelLike &&
                    empireObservation.gameContext.contactComplete &&
                    (warChoiceAvailable || visibleRivalCities.isNotEmpty()))
            )
        val militaryPurpose = when {
            conquestShaping -> "conquest"
            observation.empireSummary.isAtWar -> "defense"
            observation.empireSummary.visibleHostileUnits > 0 -> "defense"
            visibleRivalCities.isNotEmpty() || visibleRivalUnits.isNotEmpty() || warChoiceAvailable -> "deterrence"
            else -> "deterrence"
        }
        val campaignRivalCiv = memory.campaign.campaignRivalCiv
            ?: memory.campaign.primaryRivalCiv
            ?: memory.victoryIntent.campaignRivalCiv
            ?: when {
                militaryPurpose == "conquest" || observation.empireSummary.isAtWar ->
                    raceRivalCiv
                        ?: visibleRivalCities.firstOrNull()?.civName
                        ?: visibleRivalUnits.firstOrNull()?.civName
                else -> null
            }
        val economicPosture = when {
            observation.empireSummary.unitSupplyDeficit > 0 ||
                observation.empireSummary.unitSupplyProductionPenaltyPercent > 0 ||
                observation.empireSummary.gold < 0 ||
                observation.empireSummary.happiness < 0 -> "recover"
            militaryPurpose == "conquest" -> "convert"
            observation.empireSummary.cityCount < 2 || observation.empireSummary.settlersReady > 0 -> "expand"
            effectiveWinPath == "Science" -> "boom"
            else -> "boom"
        }

        return AgentVictoryIntentObservation(
            allowedVictoryTypes = allowedVictoryTypes,
            forcedVictoryType = forcedVictoryType,
            preferredVictoryTypes = preferredVictoryTypes,
            heuristicPrimaryVictory = heuristicPrimaryVictory,
            heuristicSecondaryVictory = heuristicSecondaryVictory,
            primaryVictoryFocus = primaryVictoryFocus,
            nextVictoryMilestone = empireObservation.victoryNextMilestone,
            effectiveWinPath = effectiveWinPath,
            militaryPurpose = militaryPurpose,
            economicPosture = economicPosture,
            raceRivalCiv = raceRivalCiv,
            campaignRivalCiv = campaignRivalCiv,
        )
    }

    fun toMemory(
        observation: AgentVictoryIntentObservation,
        turn: Int,
    ): AgentVictoryIntentMemory {
        return AgentVictoryIntentMemory(
            allowedVictoryTypes = ArrayList(observation.allowedVictoryTypes),
            forcedVictoryType = observation.forcedVictoryType,
            preferredVictoryTypes = ArrayList(observation.preferredVictoryTypes),
            heuristicPrimaryVictory = observation.heuristicPrimaryVictory,
            heuristicSecondaryVictory = observation.heuristicSecondaryVictory,
            primaryVictoryFocus = observation.primaryVictoryFocus,
            nextVictoryMilestone = observation.nextVictoryMilestone,
            effectiveWinPath = observation.effectiveWinPath,
            militaryPurpose = observation.militaryPurpose,
            economicPosture = observation.economicPosture,
            raceRivalCiv = observation.raceRivalCiv,
            campaignRivalCiv = observation.campaignRivalCiv,
            lastUpdatedTurn = turn,
        )
    }

    fun json(intent: AgentVictoryIntentObservation): String = json.encodeToString(intent)

    fun sanitizeWinPath(
        rawWinPath: String?,
        allowedVictoryTypes: List<String>,
        heuristicPrimaryVictory: String? = null,
    ): String? {
        val forcedVictoryType = allowedVictoryTypes.singleOrNull()
        if (forcedVictoryType != null) return forcedVictoryType
        return canonicalVictoryType(rawWinPath, allowedVictoryTypes)
            ?: canonicalVictoryType(heuristicPrimaryVictory, allowedVictoryTypes)
    }

    private fun canonicalVictoryType(
        rawVictoryType: String?,
        allowedVictoryTypes: List<String>,
    ): String? {
        val normalized = rawVictoryType
            ?.trim()
            ?.lowercase()
            ?.replace('_', ' ')
            ?.takeUnless { it.isEmpty() }
            ?: return null
        val alias = when (normalized) {
            "science", "scientific" -> "Science"
            "culture", "cultural" -> "Cultural"
            "diplomacy", "diplomatic" -> "Diplomatic"
            "domination", "military" -> "Domination"
            "time", "score" -> allowedVictoryTypes.firstOrNull {
                it.equals("Time", ignoreCase = true) || it.equals("Score", ignoreCase = true)
            }
            else -> allowedVictoryTypes.firstOrNull { it.equals(normalized, ignoreCase = true) }
        }
        return alias?.takeIf { candidate ->
            allowedVictoryTypes.any { it.equals(candidate, ignoreCase = true) }
        }
    }

    private fun defaultVictoryFocus(winPath: String): String = when (winPath.lowercase()) {
        "science" -> "Science"
        "cultural" -> "Culture"
        "domination" -> "Military"
        "diplomatic" -> "CityStates"
        else -> "Score"
    }

    private fun isWarLikeStage(stage: String?): Boolean {
        return when (stage?.trim()?.lowercase()) {
            "stage_briefly", "staging", "pressure", "launch_now", "launch_window", "assault", "rebuild" -> true
            else -> false
        }
    }
}
