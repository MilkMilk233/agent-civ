package com.unciv.logic.automation.agent

import kotlinx.serialization.Serializable

@Serializable
data class AgentPlannerBrief(
    val gameContext: AgentPublicGameContextObservation,
    val doctrine: AgentPlannerDoctrineObservation,
    val campaignContext: AgentPlannerCampaignContextObservation? = null,
    val mustActNow: List<AgentPlannerMustActObservation> = emptyList(),
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
    val tacticianHandoff: String? = null,
)

@Serializable
data class AgentPlannerCampaignContextObservation(
    val primaryRivalCiv: String? = null,
    val atWar: Boolean,
    val warChoiceAvailable: Boolean,
    val visibleRivalCities: Int,
    val visibleRivalUnits: Int,
    val visibleTarget: AgentStrategistTargetReference? = null,
    val visibleCapital: AgentStrategistTargetReference? = null,
    val lastKnownTarget: AgentStrategistTargetReference? = null,
    val lastKnownCapital: AgentStrategistTargetReference? = null,
    val frontlineFriendlyCombatUnits: Int = 0,
    val meleeUnitsNearObjective: Int = 0,
    val rangedUnitsNearObjective: Int = 0,
)

@Serializable
data class AgentPlannerMustActObservation(
    val kind: String,
    val headline: String,
    val detail: String,
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
