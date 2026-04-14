package com.unciv.logic.automation.agent

import com.unciv.logic.IsPartOfGameInfoSerialization
import kotlinx.serialization.Serializable

@Serializable
data class AgentStrategistDecisionFrame(
    var decisionMode: String? = null,
    var targetFrame: String? = null,
    var whyNow: String? = null,
    var nextCheckpoint: String? = null,
    var expiryCondition: String? = null,
) : IsPartOfGameInfoSerialization {
    constructor() : this(null, null, null, null, null)

    fun isEmpty(): Boolean {
        return decisionMode.isNullOrBlank() &&
            targetFrame.isNullOrBlank() &&
            whyNow.isNullOrBlank() &&
            nextCheckpoint.isNullOrBlank() &&
            expiryCondition.isNullOrBlank()
    }
}
