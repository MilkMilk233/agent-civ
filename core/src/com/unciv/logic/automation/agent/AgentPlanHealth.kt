package com.unciv.logic.automation.agent

import com.unciv.logic.IsPartOfGameInfoSerialization
import kotlinx.serialization.Serializable

@Serializable
data class AgentPlanHealthLabels(
    val objectiveKind: String? = null,
    val nextMilestoneKind: String? = null,
    val nextMilestoneSummary: String? = null,
    val milestoneHorizonTurns: Int? = null,
    val blockerKind: String? = null,
)

@Serializable
data class AgentPlanHealthMemory(
    var objectiveKind: String? = null,
    var nextMilestoneKind: String? = null,
    var nextMilestoneSummary: String? = null,
    var milestoneHorizonTurns: Int? = null,
    var blockerKind: String? = null,
    var status: String = "",
    var objectiveCreatedTurn: Int = 0,
    var lastMeaningfulProgressTurn: Int = 0,
    var contradictions: ArrayList<String> = arrayListOf(),
    var opportunityCosts: ArrayList<String> = arrayListOf(),
    var pivotRecommended: Boolean = false,
    var pivotReason: String? = null,
    var lastUpdatedTurn: Int = 0,
) : IsPartOfGameInfoSerialization {
    constructor() : this(null, null, null, null, null, "", 0, 0, arrayListOf(), arrayListOf(), false, null, 0)

    fun labels(): AgentPlanHealthLabels = AgentPlanHealthLabels(
        objectiveKind = objectiveKind,
        nextMilestoneKind = nextMilestoneKind,
        nextMilestoneSummary = nextMilestoneSummary,
        milestoneHorizonTurns = milestoneHorizonTurns,
        blockerKind = blockerKind,
    )

    fun isEmpty(): Boolean {
        return objectiveKind.isNullOrBlank() &&
            nextMilestoneKind.isNullOrBlank() &&
            nextMilestoneSummary.isNullOrBlank() &&
            milestoneHorizonTurns == null &&
            blockerKind.isNullOrBlank() &&
            status.isBlank() &&
            objectiveCreatedTurn <= 0 &&
            lastMeaningfulProgressTurn <= 0 &&
            contradictions.isEmpty() &&
            opportunityCosts.isEmpty() &&
            !pivotRecommended &&
            pivotReason.isNullOrBlank() &&
            lastUpdatedTurn <= 0
    }
}

@Serializable
data class AgentPlanHealthObservation(
    val objectiveKind: String? = null,
    val nextMilestoneKind: String? = null,
    val nextMilestoneSummary: String? = null,
    val milestoneHorizonTurns: Int? = null,
    val blockerKind: String? = null,
    val status: String? = null,
    val objectiveCreatedTurn: Int? = null,
    val lastMeaningfulProgressTurn: Int? = null,
    val objectiveAgeTurns: Int? = null,
    val turnsSinceMeaningfulProgress: Int? = null,
    val contradictions: List<String> = emptyList(),
    val opportunityCosts: List<String> = emptyList(),
    val pivotRecommended: Boolean = false,
    val pivotReason: String? = null,
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

fun AgentPlanHealthMemory.toObservation(turn: Int): AgentPlanHealthObservation? {
    if (isEmpty()) return null
    val objectiveCreatedTurn = objectiveCreatedTurn.takeIf { it > 0 }
    val lastMeaningfulProgressTurn = lastMeaningfulProgressTurn.takeIf { it > 0 }
    return AgentPlanHealthObservation(
        objectiveKind = objectiveKind,
        nextMilestoneKind = nextMilestoneKind,
        nextMilestoneSummary = nextMilestoneSummary,
        milestoneHorizonTurns = milestoneHorizonTurns,
        blockerKind = blockerKind,
        status = status.takeIf { it.isNotBlank() },
        objectiveCreatedTurn = objectiveCreatedTurn,
        lastMeaningfulProgressTurn = lastMeaningfulProgressTurn,
        objectiveAgeTurns = objectiveCreatedTurn?.let { (turn - it).coerceAtLeast(0) },
        turnsSinceMeaningfulProgress = lastMeaningfulProgressTurn?.let { (turn - it).coerceAtLeast(0) },
        contradictions = contradictions.toList(),
        opportunityCosts = opportunityCosts.toList(),
        pivotRecommended = pivotRecommended,
        pivotReason = pivotReason,
    )
}
