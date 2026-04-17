package com.unciv.logic.automation.agent

import kotlinx.serialization.Serializable

@Serializable
data class AgentObservation(
    val turn: Int,
    val civName: String,
    val empireSummary: EmpireSummaryObservation,
    val priorityFacts: List<ObservationFact>,
    val cities: List<AgentCityObservation>,
    val units: List<AgentUnitObservation>,
    val visibleThreatsAndTargets: List<VisibleTargetObservation>,
    val opportunities: List<ObservationFact>,
    val perceptionSummary: PerceptionSummaryObservation,
)

@Serializable
data class EmpireSummaryObservation(
    val isAtWar: Boolean,
    val gold: Int,
    val sciencePerTurn: Int,
    val culturePerTurn: Int,
    val faithPerTurn: Int,
    val happiness: Int,
    val unitSupply: Int,
    val unitSupplyDeficit: Int,
    val unitSupplyProductionPenaltyPercent: Int,
    val visibleTiles: Int,
    val cityCount: Int,
    val unitCount: Int,
    val militaryUnitCount: Int,
    val civilianUnitCount: Int,
    val knownCivs: Int,
    val knownWarOpponents: Int,
    val citiesNeedingProductionChoice: Int,
    val settlersReady: Int,
    val workersReady: Int,
    val damagedUnits: Int,
    val visibleHostileUnits: Int,
    val visibleForeignCities: Int,
)

@Serializable
data class ObservationFact(
    val category: String,
    val severity: String,
    val headline: String,
    val detail: String,
)

@Serializable
data class AgentCityObservation(
    val x: Int,
    val y: Int,
    val name: String,
    val state: AgentCityStateObservation,
    val project: AgentCityProjectObservation? = null,
    val signals: List<String> = emptyList(),
    val actions: AgentCityActionsObservation = AgentCityActionsObservation(),
)

@Serializable
data class AgentCityStateObservation(
    val population: Int,
    val health: Int,
    val isCapital: Boolean,
    val isCoastal: Boolean,
    val isPuppet: Boolean,
    val isGarrisoned: Boolean,
    val canBombard: Boolean,
    val focus: String,
    val productionPerTurn: Int,
    val foodPerTurn: Int,
    val turnsToGrowth: Int? = null,
    val turnsToStarvation: Int? = null,
    val cityStrength: Int,
    val nearbyHostileUnits: Int,
    val nearbyHostileCities: Int,
)

@Serializable
data class AgentCityProjectObservation(
    val name: String? = null,
    val status: String,
    val turnsLeft: Int? = null,
    val productionInvested: Int? = null,
    val productionRemaining: Int? = null,
    val switchCost: String,
    val note: String,
)

@Serializable
data class AgentCityActionsObservation(
    val chooseProject: List<AgentCityActionCandidateObservation> = emptyList(),
    val purchase: List<AgentCityActionCandidateObservation> = emptyList(),
    val buyTile: List<AgentCityActionCandidateObservation> = emptyList(),
    val focus: List<AgentCityActionCandidateObservation> = emptyList(),
    val growthMode: List<AgentCityActionCandidateObservation> = emptyList(),
)

@Serializable
data class AgentCityActionCandidateObservation(
    val candidateId: String,
    val title: String,
    val detail: String,
    val estimatedTurns: Int? = null,
    val goldCost: Int? = null,
    val effectTiming: String? = null,
    val switchCost: String? = null,
    val yieldHints: List<String> = emptyList(),
    val tileSummary: String? = null,
)

@Serializable
data class AgentUnitObservation(
    val detailLevel: String,
    val detailReasons: List<String>,
    val id: Int,
    val x: Int,
    val y: Int,
    val name: String,
    val role: String,
    val health: Int,
    val hasMovement: Boolean,
    val movementPoints: String,
    val strength: Int? = null,
    val rangedStrength: Int? = null,
    val range: Int? = null,
    val unitOptionCandidates: List<UnitOptionCandidateObservation> = emptyList(),
    val nearbyHostileUnits: Int,
    val nearbyHostileCities: Int,
    val reasons: List<String>,
    val localFacts: List<String>,
    val assignmentProgress: UnitAssignmentProgressObservation? = null,
)

@Serializable
data class UnitAssignmentProgressObservation(
    val role: String,
    val targetX: Int? = null,
    val targetY: Int? = null,
    val detail: String? = null,
    val assignmentSource: String,
    val assignmentCategory: String,
    val executionMode: String,
    val completionPolicy: String,
    val lastProgressTurn: Int? = null,
    val staleAfterTurn: Int? = null,
    val status: String,
    val progressNote: String,
    val switchCost: String,
)

@Serializable
data class UnitOptionCandidateObservation(
    val candidateId: String,
    val category: String,
    val title: String,
    val detail: String,
)

@Serializable
data class VisibleTargetObservation(
    val kind: String,
    val civName: String,
    val name: String,
    val relation: String,
    val x: Int,
    val y: Int,
    val health: Int? = null,
    val combatStrength: Int? = null,
    val distanceToClosestCity: Int? = null,
    val distanceToClosestUnit: Int? = null,
    val facts: List<String>,
)

@Serializable
data class PerceptionSummaryObservation(
    val totalCities: Int,
    val expandedCities: Int,
    val totalUnits: Int,
    val expandedUnits: Int,
    val visibleTargets: Int,
)

fun AgentCityObservation.allActionCandidates(): List<AgentCityActionCandidateObservation> = actions.allCandidates()

fun AgentCityActionsObservation.allCandidates(): List<AgentCityActionCandidateObservation> =
    chooseProject + purchase + buyTile + focus + growthMode
