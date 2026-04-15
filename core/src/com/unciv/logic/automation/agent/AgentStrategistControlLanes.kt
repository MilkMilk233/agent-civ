package com.unciv.logic.automation.agent

import kotlinx.serialization.Serializable

@Serializable
data class AgentStrategistControlLanes(
    val buildControl: String? = null,
    val unitControl: String? = null,
    val workerControl: String? = null,
    val purchaseControl: String? = null,
    val techControl: String? = null,
    val policyControl: String? = null,
    val driftWarnings: List<String> = emptyList(),
) {
    fun isEmpty(): Boolean {
        return buildControl.isNullOrBlank() &&
            unitControl.isNullOrBlank() &&
            workerControl.isNullOrBlank() &&
            purchaseControl.isNullOrBlank() &&
            techControl.isNullOrBlank() &&
            policyControl.isNullOrBlank() &&
            driftWarnings.isEmpty()
    }
}
