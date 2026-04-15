package com.unciv.logic.automation.agent

import kotlinx.serialization.Serializable

@Serializable
data class AgentTacticalMemoryPrompt(
    val worldModelAnchors: List<String> = emptyList(),
    val cityIntents: List<String> = emptyList(),
    val unitAssignments: List<String> = emptyList(),
    val recentFailures: List<String> = emptyList(),
)
