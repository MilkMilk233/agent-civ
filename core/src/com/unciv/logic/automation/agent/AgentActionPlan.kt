package com.unciv.logic.automation.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AgentActionPlan(
    val actions: List<AgentActionCommand> = emptyList(),
    val handoffToLegacyAI: Boolean = false,
    val strategistRefreshRequest: AgentStrategistRefreshRequest? = null,
    val tacticianReflection: AgentTacticianReflection? = null,
    val notes: String? = null,
)

@Serializable
data class AgentTacticianReflection(
    val summary: String? = null,
    val memoValidity: String? = null,
    val commitmentLevel: String? = null,
    val battleReadiness: String? = null,
    val supplyHealth: String? = null,
    val whatChanged: List<String> = emptyList(),
    val completed: List<String> = emptyList(),
    val stillBlocked: List<String> = emptyList(),
    val obsolete: List<String> = emptyList(),
    val carryForward: List<String> = emptyList(),
    val actionSurfaceMismatch: List<String> = emptyList(),
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
