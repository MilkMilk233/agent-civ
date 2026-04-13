package com.unciv.logic.automation.agent

import kotlinx.serialization.Serializable

@Serializable
data class AgentPlannerBrief(
    val gameContext: AgentPublicGameContextObservation,
    val strategy: AgentPlannerStrategyObservation,
    val memoryContext: AgentPlannerMemoryContextObservation? = null,
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
data class AgentPlannerStrategyObservation(
    val gameArchetype: String,
    val winPath: String? = null,
    val phase: String,
    val thesis: String? = null,
    val pastSummary: String? = null,
    val currentSituation: String? = null,
    val futurePlan: String? = null,
    val tacticianHandoff: String? = null,
)

@Serializable
data class AgentPlannerMemoryContextObservation(
    val worldModelSummary: String? = null,
    val worldModelNotes: List<String> = emptyList(),
    val mainRivalCiv: String? = null,
    val mainRivalSummary: String? = null,
    val mainRivalNotes: List<String> = emptyList(),
    val campaignTitle: String? = null,
    val campaignStage: String? = null,
    val campaignObjective: String? = null,
    val campaignSummary: String? = null,
    val reinforcementPlan: String? = null,
    val campaignDoNotDo: List<String> = emptyList(),
    val empirePlanSummary: String? = null,
    val purchaseIntent: String? = null,
    val recentChanges: List<String> = emptyList(),
    val lessons: List<String> = emptyList(),
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
