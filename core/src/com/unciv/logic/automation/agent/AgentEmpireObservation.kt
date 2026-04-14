package com.unciv.logic.automation.agent

import com.unciv.logic.civilization.Civilization
import kotlinx.serialization.Serializable

@Serializable
data class AgentEmpireObservation(
    val turn: Int,
    val civName: String,
    val campaignStage: String,
    val isAtWar: Boolean,
    val gameContext: AgentPublicGameContextObservation,
    val enabledVictoryTypes: List<String>,
    val preferredVictoryTypes: List<String>,
    val victoryGoal: String? = null,
    val victoryFocus: String? = null,
    val heuristicVictoryGoal: String? = null,
    val heuristicVictoryFocus: String? = null,
    val victoryNextMilestone: String? = null,
    val victoryProgressCompleted: Int = 0,
    val victoryProgressTotal: Int = 0,
    val victoryThreats: List<AgentVictoryThreatObservation> = emptyList(),
    val currentResearch: String? = null,
    val currentResearchTurnsLeft: Int? = null,
    val currentResearchProgress: Int? = null,
    val currentResearchCost: Int? = null,
    val currentResearchStatus: String? = null,
    val freeTechs: Int,
    val storedCulture: Int,
    val freePolicies: Int,
    val gold: Int,
    val happiness: Int,
    val researchCandidates: List<AgentEmpireChoiceCandidateObservation>,
    val policyCandidates: List<AgentEmpireChoiceCandidateObservation>,
    val macroCandidates: List<AgentEmpireChoiceCandidateObservation>,
    val diplomacyCandidates: List<AgentEmpireChoiceCandidateObservation>,
)

@Serializable
data class AgentPublicGameContextObservation(
    val rulesetName: String,
    val mapSize: String,
    val mapType: String,
    val mapShape: String,
    val gameSpeed: String,
    val majorCivCount: Int,
    val cityStateCount: Int,
    val barbariansEnabled: Boolean,
    val ruinsEnabled: Boolean,
    val strategicBalance: Boolean,
    val legendaryStart: Boolean,
    val duelLike: Boolean,
    val contactComplete: Boolean,
    val explorationValue: String,
    val expansionWindow: String,
    val archetype: String,
)

@Serializable
data class AgentEmpireChoiceCandidateObservation(
    val candidateId: String,
    val category: String,
    val title: String,
    val detail: String,
)

@Serializable
data class AgentVictoryThreatObservation(
    val civName: String,
    val likelyVictoryType: String,
    val focus: String,
    val nextMilestone: String? = null,
    val completedMilestones: Int = 0,
    val totalMilestones: Int = 0,
    val scoreDeltaVsUs: Int? = null,
    val forceDeltaVsUs: Int? = null,
    val technologyDeltaVsUs: Int? = null,
    val threatLevel: String = "info",
    val detail: String = "",
)

internal data class AgentEmpirePlanningContext(
    val observation: AgentEmpireObservation,
    val candidates: Map<String, AgentEmpireRuntimeCandidate>,
)

internal data class AgentEmpireRuntimeCandidate(
    val observation: AgentEmpireChoiceCandidateObservation,
    val validate: (Civilization) -> String?,
    val execute: (Civilization) -> Boolean,
    val successMessage: String,
)
