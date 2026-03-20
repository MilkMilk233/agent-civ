package com.unciv.logic.automation.agent

import kotlinx.serialization.Serializable

@Serializable
data class AgentObservation(
    val turn: Int,
    val civName: String,
    val empireSummary: EmpireSummaryObservation,
    val priorityFacts: List<ObservationFact>,
    val citiesNeedingAttention: List<CityAttentionObservation>,
    val actionableUnits: List<ActionableUnitObservation>,
    val visibleThreatsAndTargets: List<VisibleTargetObservation>,
    val opportunities: List<ObservationFact>,
    val omittedSummary: OmittedSummaryObservation,
)

@Serializable
data class EmpireSummaryObservation(
    val isAtWar: Boolean,
    val gold: Int,
    val sciencePerTurn: Int,
    val culturePerTurn: Int,
    val faithPerTurn: Int,
    val happiness: Int,
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
data class CityAttentionObservation(
    val x: Int,
    val y: Int,
    val name: String,
    val population: Int,
    val health: Int,
    val currentConstruction: String,
    val availableConstructions: List<String>,
    val productionPerTurn: Int,
    val foodPerTurn: Int,
    val turnsToGrowth: Int? = null,
    val turnsToStarvation: Int? = null,
    val cityStrength: Int,
    val canBombard: Boolean,
    val isCapital: Boolean,
    val isCoastal: Boolean,
    val isPuppet: Boolean,
    val isGarrisoned: Boolean,
    val cityFocus: String,
    val nearbyHostileUnits: Int,
    val nearbyHostileCities: Int,
    val reasons: List<String>,
    val localFacts: List<String>,
    val cityOptionCandidates: List<CityOptionCandidateObservation> = emptyList(),
)

@Serializable
data class CityOptionCandidateObservation(
    val candidateId: String,
    val category: String,
    val title: String,
    val detail: String,
)

@Serializable
data class ActionableUnitObservation(
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
    val unitActions: List<String>,
    val legalActionCandidates: List<LegalActionCandidateObservation> = emptyList(),
    val unitOptionCandidates: List<UnitOptionCandidateObservation> = emptyList(),
    val reachableTiles: List<TileRef>,
    val nearbyHostileUnits: Int,
    val nearbyHostileCities: Int,
    val reasons: List<String>,
    val localFacts: List<String>,
)

@Serializable
data class LegalActionCandidateObservation(
    val actionType: String,
    val title: String,
    val moveDestinationX: Int? = null,
    val moveDestinationY: Int? = null,
    val targetX: Int? = null,
    val targetY: Int? = null,
    val rationale: String? = null,
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
data class OmittedSummaryObservation(
    val quietCities: Int,
    val quietUnits: Int,
    val visibleTargetsOmitted: Int,
    val lowerPriorityFactsOmitted: Int,
    val lowerPriorityOpportunitiesOmitted: Int,
)

@Serializable
data class TileRef(
    val x: Int,
    val y: Int,
)
