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
    val lastStrategistReport: AgentStrategistReportMemo? = null,
    val sinceLastReviewFacts: List<String> = emptyList(),
    val roadmapReality: AgentStrategistRoadmapRealityObservation? = null,
    val rivalThreats: List<AgentVictoryThreatObservation> = emptyList(),
    val stateFacts: List<ObservationFact> = emptyList(),
    val progressInMotion: List<AgentPlannerProgressObservation> = emptyList(),
    val citySnapshots: List<AgentStrategistCitySnapshot> = emptyList(),
    val unitSnapshots: List<AgentStrategistUnitSnapshot> = emptyList(),
    val recentFailures: List<String> = emptyList(),
)

@Serializable
data class AgentStrategistReportMemo(
    val doctrine: String,
    val phase: String,
    val winPath: String? = null,
    val thesis: String? = null,
    val pastSummary: String? = null,
    val currentSituation: String? = null,
    val futurePlan: String? = null,
)

@Serializable
data class AgentStrategistRoadmapRealityObservation(
    val currentPhaseReality: String,
    val expansionStatus: String,
    val contactStatus: String,
    val militaryStatus: String,
    val notableDrift: List<String> = emptyList(),
    val urgentProblems: List<String> = emptyList(),
)

@Serializable
data class AgentStrategistCitySnapshot(
    val x: Int,
    val y: Int,
    val name: String,
    val population: Int,
    val focus: String,
    val currentProject: String? = null,
    val projectStatus: String? = null,
    val projectTurnsLeft: Int? = null,
    val projectProductionInvested: Int? = null,
    val projectProductionRemaining: Int? = null,
    val productionPerTurn: Int,
    val foodPerTurn: Int,
    val turnsToGrowth: Int? = null,
    val turnsToStarvation: Int? = null,
    val cityStrength: Int,
    val nearbyHostileUnits: Int,
    val nearbyHostileCities: Int,
    val signals: List<String> = emptyList(),
    val projectOptions: List<AgentStrategistCityProjectOptionSnapshot> = emptyList(),
)

@Serializable
data class AgentStrategistUnitSnapshot(
    val id: Int,
    val x: Int,
    val y: Int,
    val name: String,
    val role: String,
    val detailLevel: String,
    val health: Int,
    val hasMovement: Boolean,
    val movementPoints: String,
    val strength: Int? = null,
    val rangedStrength: Int? = null,
    val range: Int? = null,
    val nearbyHostileUnits: Int,
    val nearbyHostileCities: Int,
    val reasons: List<String>,
    val localFacts: List<String> = emptyList(),
    val assignmentProgress: UnitAssignmentProgressObservation? = null,
)

@Serializable
data class AgentStrategistCityProjectOptionSnapshot(
    val title: String,
    val estimatedTurns: Int? = null,
    val goldCost: Int? = null,
    val switchCost: String? = null,
    val yieldHints: List<String> = emptyList(),
)
