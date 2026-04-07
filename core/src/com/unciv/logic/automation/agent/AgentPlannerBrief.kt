package com.unciv.logic.automation.agent

import kotlinx.serialization.Serializable

@Serializable
data class AgentPlannerBrief(
    val gameContext: AgentPublicGameContextObservation,
    val doctrine: AgentPlannerDoctrineObservation,
    val tacticalPressure: AgentPlannerTacticalPressureObservation,
    val criticalAlerts: List<ObservationFact>,
    val progressInMotion: List<AgentPlannerProgressObservation>,
    val empireChoices: AgentPlannerEmpireChoicesObservation,
    val cityHighlights: List<AgentCityObservation>,
    val unitHighlights: List<AgentUnitObservation>,
    val threatHighlights: List<VisibleTargetObservation>,
    val opportunityHighlights: List<ObservationFact>,
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
    val commitments: List<String> = emptyList(),
    val watchOuts: List<String> = emptyList(),
)

@Serializable
data class AgentPlannerTacticalPressureObservation(
    val noOpPolicy: String,
    val noOpReason: String? = null,
    val mustActReasons: List<String> = emptyList(),
    val priorityThisTurn: List<String> = emptyList(),
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
