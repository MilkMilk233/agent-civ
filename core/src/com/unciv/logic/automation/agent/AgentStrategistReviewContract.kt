package com.unciv.logic.automation.agent

import kotlinx.serialization.Serializable

@Serializable
data class AgentStrategistReviewTrigger(
    val kind: String,
    val metric: String,
    val summary: String? = null,
    val withinTurns: Int? = null,
)

@Serializable
data class AgentStrategistReviewContract(
    val maxAgeTurns: Int = 8,
    val triggers: List<AgentStrategistReviewTrigger> = emptyList(),
) {
    fun isEmpty(): Boolean = maxAgeTurns <= 0 && triggers.isEmpty()
}
