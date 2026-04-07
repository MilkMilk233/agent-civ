package com.unciv.logic.automation.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AgentActionPlan(
    val actions: List<AgentActionCommand> = emptyList(),
    val handoffToLegacyAI: Boolean = false,
    val strategistRefreshRequest: AgentStrategistRefreshRequest? = null,
    val notes: String? = null,
)

@Serializable
sealed class AgentActionCommand {
    abstract val priority: Int

    @Serializable
    @SerialName("select_empire_option")
    data class SelectEmpireOption(
        override val priority: Int = 0,
        val candidateId: String,
    ) : AgentActionCommand()

    @Serializable
    @SerialName("select_city_option")
    data class SelectCityOption(
        override val priority: Int = 0,
        val candidateId: String,
    ) : AgentActionCommand()

    @Serializable
    @SerialName("select_unit_option")
    data class SelectUnitOption(
        override val priority: Int = 0,
        val candidateId: String,
    ) : AgentActionCommand()

    @Serializable
    @SerialName("unit_move")
    data class UnitMove(
        override val priority: Int = 0,
        val unitId: Int,
        val destinationX: Int,
        val destinationY: Int,
    ) : AgentActionCommand()

    @Serializable
    @SerialName("unit_action")
    data class UnitAction(
        override val priority: Int = 0,
        val unitId: Int,
        val actionType: String,
    ) : AgentActionCommand()

    @Serializable
    @SerialName("end_turn")
    data class EndTurn(
        override val priority: Int = Int.MAX_VALUE,
    ) : AgentActionCommand()
}
