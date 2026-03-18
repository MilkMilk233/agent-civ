package com.unciv.logic.automation.agent

import kotlinx.serialization.Serializable

@Serializable
data class AgentObservation(
    val turn: Int,
    val civName: String,
    val isAtWar: Boolean,
    val gold: Int,
    val sciencePerTurn: Int,
    val culturePerTurn: Int,
    val faithPerTurn: Int,
    val happiness: Int,
    val visibleTiles: Int,
    val cities: List<CityObservation>,
    val units: List<UnitObservation>,
    val knownCivs: List<KnownCivObservation>,
)

@Serializable
data class CityObservation(
    val x: Int,
    val y: Int,
    val name: String,
    val population: Int,
    val health: Int,
    val currentConstruction: String,
    val availableConstructions: List<String>,
)

@Serializable
data class UnitObservation(
    val id: Int,
    val x: Int,
    val y: Int,
    val name: String,
    val health: Int,
    val hasMovement: Boolean,
    val movementPoints: String,
    val unitActions: List<String>,
    val reachableTiles: List<TileRef>,
)

@Serializable
data class KnownCivObservation(
    val civName: String,
    val diplomaticStatus: String,
    val isAtWar: Boolean,
    val isCityState: Boolean,
)

@Serializable
data class TileRef(
    val x: Int,
    val y: Int,
)
