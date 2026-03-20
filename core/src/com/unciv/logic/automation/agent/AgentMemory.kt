package com.unciv.logic.automation.agent

import com.unciv.logic.IsPartOfGameInfoSerialization
import kotlinx.serialization.Serializable

@Serializable
data class AgentMemory(
    var strategicPosture: StrategicPostureMemory = StrategicPostureMemory(),
    var strategicRoadmap: AgentStrategicRoadmapMemory = AgentStrategicRoadmapMemory(),
    var cityIntents: ArrayList<CityIntentMemory> = arrayListOf(),
    var unitAssignments: ArrayList<UnitAssignmentMemory> = arrayListOf(),
    var recentFailures: ArrayList<RecentFailureMemory> = arrayListOf(),
) : IsPartOfGameInfoSerialization {
    constructor() : this(StrategicPostureMemory(), AgentStrategicRoadmapMemory(), arrayListOf(), arrayListOf(), arrayListOf())

    fun clone(): AgentMemory = AgentMemory(
        strategicPosture = strategicPosture.copy(
            focus = ArrayList(strategicPosture.focus),
            commitments = ArrayList(strategicPosture.commitments),
            watchOuts = ArrayList(strategicPosture.watchOuts),
        ),
        strategicRoadmap = strategicRoadmap.copy(
            midTermGoals = ArrayList(strategicRoadmap.midTermGoals),
            mustMaintain = ArrayList(strategicRoadmap.mustMaintain),
            watchOuts = ArrayList(strategicRoadmap.watchOuts),
            switchTriggers = ArrayList(strategicRoadmap.switchTriggers),
        ),
        cityIntents = ArrayList(cityIntents.map { it.copy(reasons = ArrayList(it.reasons)) }),
        unitAssignments = ArrayList(unitAssignments.map { it.copy() }),
        recentFailures = ArrayList(recentFailures.map { it.copy() }),
    )
}

@Serializable
data class StrategicPostureMemory(
    var mode: String = "",
    var focus: ArrayList<String> = arrayListOf(),
    var gameArchetype: String? = null,
    var doctrine: String? = null,
    var phase: String = "",
    var victoryGoal: String? = null,
    var rivalCiv: String? = null,
    var rivalVictoryGoal: String? = null,
    var turnThesis: String? = null,
    var commitments: ArrayList<String> = arrayListOf(),
    var watchOuts: ArrayList<String> = arrayListOf(),
    var sinceTurn: Int = 0,
    var lastUpdatedTurn: Int = 0,
) : IsPartOfGameInfoSerialization {
    constructor() : this("", arrayListOf(), null, null, "", null, null, null, null, arrayListOf(), arrayListOf(), 0, 0)
}

@Serializable
data class CityIntentMemory(
    var cityX: Int = 0,
    var cityY: Int = 0,
    var cityName: String = "",
    var intent: String = "",
    var target: String? = null,
    var reasons: ArrayList<String> = arrayListOf(),
    var lastProgressTurn: Int = 0,
    var staleAfterTurn: Int = 0,
) : IsPartOfGameInfoSerialization {
    constructor() : this(0, 0, "", "", null, arrayListOf(), 0, 0)
}

@Serializable
data class UnitAssignmentMemory(
    var unitId: Int = 0,
    var unitName: String = "",
    var role: String = "",
    var targetX: Int? = null,
    var targetY: Int? = null,
    var detail: String? = null,
    var lastProgressTurn: Int = 0,
    var staleAfterTurn: Int = 0,
) : IsPartOfGameInfoSerialization {
    constructor() : this(0, "", "", null, null, null, 0, 0)
}

@Serializable
data class RecentFailureMemory(
    var turn: Int = 0,
    var kind: String = "",
    var summary: String = "",
    var unitId: Int? = null,
    var cityX: Int? = null,
    var cityY: Int? = null,
    var actionType: String? = null,
) : IsPartOfGameInfoSerialization {
    constructor() : this(0, "", "", null, null, null, null)
}
