package com.unciv.logic.automation.agent

import kotlinx.serialization.Serializable

@Serializable
data class AgentPlannerBrief(
    val gameContext: AgentPublicGameContextObservation,
    val doctrine: AgentPlannerDoctrineObservation,
    val attentionFacts: List<ObservationFact>,
    val progressInMotion: List<AgentPlannerProgressObservation>,
    val empireChoices: AgentPlannerEmpireChoicesObservation,
    val cityHighlights: List<AgentCityObservation>,
    val unitHighlights: List<AgentUnitObservation>,
    val threatHighlights: List<VisibleTargetObservation>,
    val suppressedContext: List<String>,
)

@Serializable
data class AgentPlannerDoctrineObservation(
    val gameArchetype: String,
    val doctrine: String,
    val phase: String,
    val victoryGoal: String? = null,
    val rivalCiv: String? = null,
    val rivalVictoryGoal: String? = null,
    val thesis: String? = null,
    val pastSummary: String? = null,
    val currentSituation: String? = null,
    val futurePlan: String? = null,
)

@Serializable
data class AgentPlannerProgressObservation(
    val category: String,
    val label: String,
    val detail: String,
)

@Serializable
data class AgentPlannerEmpireChoicesObservation(
    val researchChoices: List<AgentEmpireChoiceCandidateObservation> = emptyList(),
    val policyChoices: List<AgentEmpireChoiceCandidateObservation> = emptyList(),
    val macroChoices: List<AgentEmpireChoiceCandidateObservation> = emptyList(),
    val diplomacyChoices: List<AgentEmpireChoiceCandidateObservation> = emptyList(),
)
