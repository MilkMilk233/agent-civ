package com.unciv.logic.automation.agent

import com.unciv.logic.IsPartOfGameInfoSerialization
import kotlinx.serialization.Serializable

@Serializable
data class AgentStrategicRoadmapMemory(
    var gameArchetype: String = "",
    var doctrine: String = "",
    var winPath: String? = null,
    var phase: String = "",
    var thesis: String? = null,
    var pastSummary: String? = null,
    var currentSituation: String? = null,
    var futurePlan: String? = null,
    var reviewCityCount: Int = 0,
    var reviewMilitaryUnitCount: Int = 0,
    var reviewContactComplete: Boolean = false,
    var reviewResearch: String? = null,
    var reviewCityNames: ArrayList<String> = arrayListOf(),
    var reviewAfterTurn: Int = 0,
    var createdTurn: Int = 0,
    var lastReviewedTurn: Int = 0,
    var lastRefreshReason: String? = null,
) : IsPartOfGameInfoSerialization {
    constructor() : this("", "", null, "", null, null, null, null, 0, 0, false, null, arrayListOf(), 0, 0, 0, null)
}

@Serializable
data class AgentStrategicPlan(
    val roadmap: AgentStrategicRoadmapDraft,
    val notes: String? = null,
)

@Serializable
data class AgentStrategicRoadmapDraft(
    val doctrine: String,
    val winPath: String? = null,
    val phase: String,
    val thesis: String? = null,
    val pastSummary: String? = null,
    val currentSituation: String? = null,
    val futurePlan: String? = null,
    val reviewInTurns: Int = 5,
)

@Serializable
data class AgentStrategistRefreshRequest(
    val urgency: String = "scheduled",
    val reason: String,
)
