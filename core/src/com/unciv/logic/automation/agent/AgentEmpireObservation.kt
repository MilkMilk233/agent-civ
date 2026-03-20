package com.unciv.logic.automation.agent

import com.unciv.logic.civilization.Civilization
import kotlinx.serialization.Serializable

@Serializable
data class AgentEmpireObservation(
    val turn: Int,
    val civName: String,
    val strategicPosture: String,
    val isAtWar: Boolean,
    val currentResearch: String? = null,
    val freeTechs: Int,
    val storedCulture: Int,
    val freePolicies: Int,
    val storedFaith: Int,
    val religionState: String,
    val gold: Int,
    val happiness: Int,
    val macroFacts: List<ObservationFact>,
    val researchCandidates: List<AgentEmpireChoiceCandidateObservation>,
    val policyCandidates: List<AgentEmpireChoiceCandidateObservation>,
    val macroCandidates: List<AgentEmpireChoiceCandidateObservation>,
    val diplomacyCandidates: List<AgentEmpireChoiceCandidateObservation>,
    val spyCandidates: List<AgentEmpireChoiceCandidateObservation>,
)

@Serializable
data class AgentEmpireChoiceCandidateObservation(
    val candidateId: String,
    val category: String,
    val title: String,
    val detail: String,
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
