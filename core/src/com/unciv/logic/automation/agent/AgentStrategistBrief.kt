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
    val rivalThreats: List<AgentVictoryThreatObservation> = emptyList(),
    val rivalCities: List<AgentStrategistRivalCitySnapshot> = emptyList(),
    val rivalUnits: List<AgentStrategistRivalUnitSnapshot> = emptyList(),
    val campaignPicture: AgentStrategistCampaignPicture? = null,
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
    val tacticianHandoff: String? = null,
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
    val distanceToNearestRivalCity: Int? = null,
    val distanceToNearestRivalCapital: Int? = null,
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
    val distanceToNearestRivalCity: Int? = null,
    val distanceToNearestRivalCapital: Int? = null,
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

@Serializable
data class AgentStrategistRivalCitySnapshot(
    val civName: String,
    val name: String,
    val relation: String,
    val x: Int,
    val y: Int,
    val isCapital: Boolean,
    val health: Int? = null,
    val combatStrength: Int? = null,
    val distanceToClosestCity: Int? = null,
    val distanceToClosestUnit: Int? = null,
    val facts: List<String> = emptyList(),
)

@Serializable
data class AgentStrategistRivalUnitSnapshot(
    val civName: String,
    val name: String,
    val relation: String,
    val x: Int,
    val y: Int,
    val health: Int? = null,
    val combatStrength: Int? = null,
    val distanceToClosestCity: Int? = null,
    val distanceToClosestUnit: Int? = null,
    val facts: List<String> = emptyList(),
)

@Serializable
data class AgentStrategistTargetReference(
    val civName: String,
    val name: String,
    val x: Int,
    val y: Int,
    val health: Int? = null,
    val combatStrength: Int? = null,
    val distanceToClosestCity: Int? = null,
    val distanceToClosestUnit: Int? = null,
)

@Serializable
data class AgentStrategistCampaignPicture(
    val primaryRivalCiv: String? = null,
    val visibleRivalCities: Int,
    val visibleRivalCapitals: Int,
    val visibleRivalUnits: Int,
    val frontlineFriendlyCombatUnits: Int,
    val frontlineMeleeUnits: Int,
    val frontlineRangedUnits: Int,
    val frontlineDamagedUnits: Int,
    val frontlineCityBombards: Int,
    val meleeUnitsNearNearestRivalCity: Int,
    val rangedUnitsNearNearestRivalCity: Int,
    val nearestRivalCity: AgentStrategistTargetReference? = null,
    val nearestRivalCapital: AgentStrategistTargetReference? = null,
)
