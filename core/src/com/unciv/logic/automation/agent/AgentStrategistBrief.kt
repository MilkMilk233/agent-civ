package com.unciv.logic.automation.agent

import kotlinx.serialization.Serializable

@Serializable
data class AgentStrategistBrief(
    val refreshRequest: AgentStrategistRefreshRequest,
    val gameContext: AgentPublicGameContextObservation,
    val enabledVictoryTypes: List<String>,
    val empireSummary: EmpireSummaryObservation,
    val currentResearch: String? = null,
    val currentResearchStatus: String? = null,
    val currentRoadmap: AgentStrategicRoadmapMemory? = null,
    val roadmapReality: AgentStrategistRoadmapRealityObservation? = null,
    val rivalThreats: List<AgentVictoryThreatObservation> = emptyList(),
    val macroFacts: List<ObservationFact> = emptyList(),
    val progressInMotion: List<AgentPlannerProgressObservation> = emptyList(),
    val citySnapshots: List<AgentStrategistCitySnapshot> = emptyList(),
    val unitSnapshots: List<AgentStrategistUnitSnapshot> = emptyList(),
    val recentFailures: List<String> = emptyList(),
)

@Serializable
data class AgentStrategistRoadmapRealityObservation(
    val currentPhaseReality: String,
    val expansionStatus: String,
    val contactStatus: String,
    val militaryStatus: String,
    val completedGoals: List<String> = emptyList(),
    val staleGoals: List<String> = emptyList(),
    val urgentProblems: List<String> = emptyList(),
)

@Serializable
data class AgentStrategistCitySnapshot(
    val name: String,
    val population: Int,
    val currentConstruction: String,
    val turnsToGrowth: Int? = null,
    val cityStrength: Int,
    val reasons: List<String>,
    val topConstructionChoices: List<String> = emptyList(),
)

@Serializable
data class AgentStrategistUnitSnapshot(
    val id: Int,
    val name: String,
    val role: String,
    val health: Int,
    val hasMovement: Boolean,
    val nearbyHostileUnits: Int,
    val nearbyHostileCities: Int,
    val reasons: List<String>,
    val assignmentProgress: UnitAssignmentProgressObservation? = null,
)
