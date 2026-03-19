package com.unciv.logic.automation.agent

import kotlinx.serialization.Serializable

@Serializable
data class AgentRetryContext(
    val retryAttempt: Int,
    val maxRetries: Int,
    val previousPlan: AgentActionPlan,
    val failures: List<AgentPlanFailure>,
)

@Serializable
data class AgentPlanFailure(
    val commandType: String,
    val detail: String,
    val unitId: Int? = null,
    val cityX: Int? = null,
    val cityY: Int? = null,
    val actionType: String? = null,
    val constructionName: String? = null,
)

object AgentRetryContextFactory {
    fun fromValidation(
        plan: AgentActionPlan,
        validation: AgentActionExecutor.ValidationReport,
        retryAttempt: Int,
        maxRetries: Int,
    ): AgentRetryContext {
        val failures = validation.rejectedOutcomes.map { outcome ->
            AgentPlanFailure(
                commandType = outcome.commandType,
                detail = outcome.reason,
                unitId = outcome.unitId,
                cityX = outcome.cityX,
                cityY = outcome.cityY,
                actionType = outcome.actionType,
                constructionName = outcome.constructionName,
            )
        }

        return AgentRetryContext(
            retryAttempt = retryAttempt,
            maxRetries = maxRetries,
            previousPlan = plan,
            failures = failures,
        )
    }
}
