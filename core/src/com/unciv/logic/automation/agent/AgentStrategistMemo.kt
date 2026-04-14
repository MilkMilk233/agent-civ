package com.unciv.logic.automation.agent

import com.unciv.logic.IsPartOfGameInfoSerialization
import kotlinx.serialization.Serializable

@Serializable
data class AgentStrategistMemoMemory(
    var gameArchetype: String = "",
    var winPath: String? = null,
    var campaignStage: String = "",
    var decisiveObjective: String? = null,
    var conversionBlocker: String? = null,
    var decisionFrame: AgentStrategistDecisionFrame = AgentStrategistDecisionFrame(),
    var planHealth: AgentPlanHealthLabels = AgentPlanHealthLabels(),
    var campaignControl: AgentCampaignControlLabels = AgentCampaignControlLabels(),
    var thesis: String? = null,
    var pastSummary: String? = null,
    var currentSituation: String? = null,
    var futurePlan: String? = null,
    var tacticianHandoff: String? = null,
    var reviewCityCount: Int = 0,
    var reviewMilitaryUnitCount: Int = 0,
    var reviewIsAtWar: Boolean = false,
    var reviewContactComplete: Boolean = false,
    var reviewResearch: String? = null,
    var reviewVisibleRivalCities: Int = 0,
    var reviewVisibleRivalUnits: Int = 0,
    var reviewPrimaryRivalCiv: String? = null,
    var reviewCityNames: ArrayList<String> = arrayListOf(),
    var reviewAfterTurn: Int = 0,
    var createdTurn: Int = 0,
    var lastReviewedTurn: Int = 0,
    var lastRefreshReason: String? = null,
) : IsPartOfGameInfoSerialization {
    constructor() : this("", null, "", null, null, AgentStrategistDecisionFrame(), AgentPlanHealthLabels(), AgentCampaignControlLabels(), null, null, null, null, null, 0, 0, false, false, null, 0, 0, null, arrayListOf(), 0, 0, 0, null)
}

@Serializable
data class AgentStrategicPlan(
    val memo: AgentStrategistMemoDraft,
    val notes: String? = null,
)

@Serializable
data class AgentStrategistMemoDraft(
    val winPath: String? = null,
    val campaignStage: String,
    val decisiveObjective: String,
    val conversionBlocker: String? = null,
    val decisionFrame: AgentStrategistDecisionFrame = AgentStrategistDecisionFrame(),
    val planHealth: AgentPlanHealthLabels = AgentPlanHealthLabels(),
    val campaignControl: AgentCampaignControlLabels = AgentCampaignControlLabels(),
    val thesis: String? = null,
    val pastSummary: String? = null,
    val currentSituation: String? = null,
    val futurePlan: String? = null,
    val tacticianHandoff: String? = null,
    val worldModelSummary: String? = null,
    val worldModelNotes: List<String> = emptyList(),
    val rivals: List<AgentStrategistRivalNotebookDraft> = emptyList(),
    val campaignTitle: String? = null,
    val campaignSummary: String? = null,
    val reinforcementPlan: String? = null,
    val campaignDoNotDo: List<String> = emptyList(),
    val empirePlanSummary: String? = null,
    val purchaseIntent: String? = null,
    val empirePlanNotes: List<String> = emptyList(),
    val recentChanges: List<String> = emptyList(),
    val lessons: List<String> = emptyList(),
    val reviewInTurns: Int = 5,
)

@Serializable
data class AgentStrategistRivalNotebookDraft(
    val rivalCiv: String,
    val summary: String? = null,
    val notes: List<String> = emptyList(),
)

@Serializable
data class AgentStrategistRefreshRequest(
    val urgency: String = "scheduled",
    val reason: String,
)
