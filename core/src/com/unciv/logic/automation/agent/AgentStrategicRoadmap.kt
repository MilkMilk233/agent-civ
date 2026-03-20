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
    var midTermGoals: ArrayList<String> = arrayListOf(),
    var mustMaintain: ArrayList<String> = arrayListOf(),
    var watchOuts: ArrayList<String> = arrayListOf(),
    var switchTriggers: ArrayList<String> = arrayListOf(),
    var reviewAfterTurn: Int = 0,
    var createdTurn: Int = 0,
    var lastReviewedTurn: Int = 0,
    var lastRefreshReason: String? = null,
) : IsPartOfGameInfoSerialization {
    constructor() : this("", "", null, "", null, arrayListOf(), arrayListOf(), arrayListOf(), arrayListOf(), 0, 0, 0, null)
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
    val midTermGoals: List<String> = emptyList(),
    val mustMaintain: List<String> = emptyList(),
    val watchOuts: List<String> = emptyList(),
    val switchTriggers: List<String> = emptyList(),
    val reviewInTurns: Int = 5,
)

@Serializable
data class AgentStrategistRefreshRequest(
    val urgency: String = "scheduled",
    val reason: String,
)
