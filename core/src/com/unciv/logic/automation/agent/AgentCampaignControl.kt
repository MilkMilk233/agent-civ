package com.unciv.logic.automation.agent

import com.unciv.logic.IsPartOfGameInfoSerialization
import kotlinx.serialization.Serializable

@Serializable
data class AgentCampaignControlLabels(
    val commitmentLevel: String? = null,
    val battleReadiness: String? = null,
    val supplyHealth: String? = null,
    val nextCheckpointKind: String? = null,
    val nextCheckpointSummary: String? = null,
    val checkpointHorizonTurns: Int? = null,
    val pivotTriggerKind: String? = null,
)

@Serializable
data class AgentCampaignControlMemory(
    var commitmentLevel: String? = null,
    var battleReadiness: String? = null,
    var supplyHealth: String? = null,
    var nextCheckpointKind: String? = null,
    var nextCheckpointSummary: String? = null,
    var checkpointHorizonTurns: Int? = null,
    var pivotTriggerKind: String? = null,
    var checkpointStatus: String = "",
    var commitmentStartedTurn: Int = 0,
    var lastCheckpointTurn: Int = 0,
    var launchWindowOpen: Boolean = false,
    var holdingCosts: ArrayList<String> = arrayListOf(),
    var pivotTriggers: ArrayList<String> = arrayListOf(),
    var lastUpdatedTurn: Int = 0,
) : IsPartOfGameInfoSerialization {
    constructor() : this(null, null, null, null, null, null, null, "", 0, 0, false, arrayListOf(), arrayListOf(), 0)

    fun labels(): AgentCampaignControlLabels = AgentCampaignControlLabels(
        commitmentLevel = commitmentLevel,
        battleReadiness = battleReadiness,
        supplyHealth = supplyHealth,
        nextCheckpointKind = nextCheckpointKind,
        nextCheckpointSummary = nextCheckpointSummary,
        checkpointHorizonTurns = checkpointHorizonTurns,
        pivotTriggerKind = pivotTriggerKind,
    )

    fun isEmpty(): Boolean {
        return commitmentLevel.isNullOrBlank() &&
            battleReadiness.isNullOrBlank() &&
            supplyHealth.isNullOrBlank() &&
            nextCheckpointKind.isNullOrBlank() &&
            nextCheckpointSummary.isNullOrBlank() &&
            checkpointHorizonTurns == null &&
            pivotTriggerKind.isNullOrBlank() &&
            checkpointStatus.isBlank() &&
            commitmentStartedTurn <= 0 &&
            lastCheckpointTurn <= 0 &&
            !launchWindowOpen &&
            holdingCosts.isEmpty() &&
            pivotTriggers.isEmpty() &&
            lastUpdatedTurn <= 0
    }
}

@Serializable
data class AgentCampaignControlObservation(
    val commitmentLevel: String? = null,
    val battleReadiness: String? = null,
    val supplyHealth: String? = null,
    val nextCheckpointKind: String? = null,
    val nextCheckpointSummary: String? = null,
    val checkpointHorizonTurns: Int? = null,
    val pivotTriggerKind: String? = null,
    val checkpointStatus: String? = null,
    val commitmentStartedTurn: Int? = null,
    val lastCheckpointTurn: Int? = null,
    val commitmentAgeTurns: Int? = null,
    val turnsSinceCheckpoint: Int? = null,
    val launchWindowOpen: Boolean = false,
    val holdingCosts: List<String> = emptyList(),
    val pivotTriggers: List<String> = emptyList(),
)

fun AgentCampaignControlMemory.toObservation(turn: Int): AgentCampaignControlObservation? {
    if (isEmpty()) return null
    val commitmentStartedTurn = commitmentStartedTurn.takeIf { it > 0 }
    val lastCheckpointTurn = lastCheckpointTurn.takeIf { it > 0 }
    return AgentCampaignControlObservation(
        commitmentLevel = commitmentLevel,
        battleReadiness = battleReadiness,
        supplyHealth = supplyHealth,
        nextCheckpointKind = nextCheckpointKind,
        nextCheckpointSummary = nextCheckpointSummary,
        checkpointHorizonTurns = checkpointHorizonTurns,
        pivotTriggerKind = pivotTriggerKind,
        checkpointStatus = checkpointStatus.takeIf { it.isNotBlank() },
        commitmentStartedTurn = commitmentStartedTurn,
        lastCheckpointTurn = lastCheckpointTurn,
        commitmentAgeTurns = commitmentStartedTurn?.let { (turn - it).coerceAtLeast(0) },
        turnsSinceCheckpoint = lastCheckpointTurn?.let { (turn - it).coerceAtLeast(0) },
        launchWindowOpen = launchWindowOpen,
        holdingCosts = holdingCosts.toList(),
        pivotTriggers = pivotTriggers.toList(),
    )
}
