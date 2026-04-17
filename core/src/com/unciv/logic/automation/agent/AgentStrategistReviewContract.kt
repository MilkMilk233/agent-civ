package com.unciv.logic.automation.agent

import com.unciv.logic.IsPartOfGameInfoSerialization
import kotlinx.serialization.Serializable

@Serializable
data class AgentStrategistReviewTrigger(
    var kind: String = "",
    var metric: String = "",
    var summary: String? = null,
    var withinTurns: Int? = null,
) : IsPartOfGameInfoSerialization {
    constructor() : this("", "", null, null)
}

@Serializable
data class AgentStrategistReviewContract(
    var maxAgeTurns: Int = 8,
    var triggers: ArrayList<AgentStrategistReviewTrigger> = arrayListOf(),
) : IsPartOfGameInfoSerialization {
    constructor() : this(8, arrayListOf())

    fun isEmpty(): Boolean = maxAgeTurns <= 0 && triggers.isEmpty()
}
