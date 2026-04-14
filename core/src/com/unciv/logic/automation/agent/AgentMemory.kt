package com.unciv.logic.automation.agent

import com.unciv.logic.IsPartOfGameInfoSerialization
import kotlinx.serialization.Serializable

@Serializable
data class AgentMemory(
    var worldModel: WorldModelMemory = WorldModelMemory(),
    var rivals: ArrayList<RivalNotebookMemory> = arrayListOf(),
    var campaign: CampaignMemory = CampaignMemory(),
    var empirePlan: EmpirePlanMemory = EmpirePlanMemory(),
    var planHealth: AgentPlanHealthMemory = AgentPlanHealthMemory(),
    var campaignControl: AgentCampaignControlMemory = AgentCampaignControlMemory(),
    var recentChanges: ArrayList<MemoryNote> = arrayListOf(),
    var lessons: ArrayList<MemoryNote> = arrayListOf(),
    var lastStrategistMemo: AgentStrategistMemoMemory = AgentStrategistMemoMemory(),
    var tacticianTurnLog: ArrayList<TacticianTurnLogEntry> = arrayListOf(),
    var cityIntents: ArrayList<CityIntentMemory> = arrayListOf(),
    var unitAssignments: ArrayList<UnitAssignmentMemory> = arrayListOf(),
    var recentFailures: ArrayList<RecentFailureMemory> = arrayListOf(),
) : IsPartOfGameInfoSerialization {
    constructor() : this(
        WorldModelMemory(),
        arrayListOf(),
        CampaignMemory(),
        EmpirePlanMemory(),
        AgentPlanHealthMemory(),
        AgentCampaignControlMemory(),
        arrayListOf(),
        arrayListOf(),
        AgentStrategistMemoMemory(),
        arrayListOf(),
        arrayListOf(),
        arrayListOf(),
        arrayListOf(),
    )

    fun clone(): AgentMemory = AgentMemory(
        worldModel = worldModel.copy(
            notes = ArrayList(worldModel.notes.map { it.copy() }),
            anchors = ArrayList(worldModel.anchors.map { it.copy() }),
        ),
        rivals = ArrayList(rivals.map { rival ->
            rival.copy(
                notes = ArrayList(rival.notes.map { it.copy() }),
                anchors = ArrayList(rival.anchors.map { it.copy() }),
            )
        }),
        campaign = campaign.copy(
            doNotDo = ArrayList(campaign.doNotDo),
            notes = ArrayList(campaign.notes.map { it.copy() }),
        ),
        empirePlan = empirePlan.copy(
            notes = ArrayList(empirePlan.notes.map { it.copy() }),
        ),
        planHealth = planHealth.copy(
            contradictions = ArrayList(planHealth.contradictions),
            opportunityCosts = ArrayList(planHealth.opportunityCosts),
        ),
        campaignControl = campaignControl.copy(
            holdingCosts = ArrayList(campaignControl.holdingCosts),
            pivotTriggers = ArrayList(campaignControl.pivotTriggers),
        ),
        recentChanges = ArrayList(recentChanges.map { it.copy() }),
        lessons = ArrayList(lessons.map { it.copy() }),
        lastStrategistMemo = lastStrategistMemo.copy(
            decisionFrame = lastStrategistMemo.decisionFrame.copy(),
            planHealth = lastStrategistMemo.planHealth.copy(),
            campaignControl = lastStrategistMemo.campaignControl.copy(),
            reviewCityNames = ArrayList(lastStrategistMemo.reviewCityNames),
        ),
        tacticianTurnLog = ArrayList(
            tacticianTurnLog.map { entry ->
                entry.copy(
                    whatChanged = ArrayList(entry.whatChanged),
                    completed = ArrayList(entry.completed),
                    stillBlocked = ArrayList(entry.stillBlocked),
                    obsolete = ArrayList(entry.obsolete),
                    carryForward = ArrayList(entry.carryForward),
                    actionSurfaceMismatch = ArrayList(entry.actionSurfaceMismatch),
                    memoValidity = entry.memoValidity,
                    commitmentLevel = entry.commitmentLevel,
                    battleReadiness = entry.battleReadiness,
                    supplyHealth = entry.supplyHealth,
                )
            }
        ),
        cityIntents = ArrayList(cityIntents.map { it.copy(reasons = ArrayList(it.reasons)) }),
        unitAssignments = ArrayList(unitAssignments.map { it.copy() }),
        recentFailures = ArrayList(recentFailures.map { it.copy() }),
    )
}

@Serializable
data class WorldModelMemory(
    var summary: String? = null,
    var notes: ArrayList<MemoryNote> = arrayListOf(),
    var anchors: ArrayList<MemoryAnchor> = arrayListOf(),
    var lastUpdatedTurn: Int = 0,
) : IsPartOfGameInfoSerialization {
    constructor() : this(null, arrayListOf(), arrayListOf(), 0)
}

@Serializable
data class RivalNotebookMemory(
    var rivalCiv: String = "",
    var summary: String? = null,
    var notes: ArrayList<MemoryNote> = arrayListOf(),
    var anchors: ArrayList<MemoryAnchor> = arrayListOf(),
    var lastUpdatedTurn: Int = 0,
) : IsPartOfGameInfoSerialization {
    constructor() : this("", null, arrayListOf(), arrayListOf(), 0)
}

@Serializable
data class CampaignMemory(
    var title: String = "",
    var stage: String = "",
    var decisiveObjective: String? = null,
    var conversionBlocker: String? = null,
    var summary: String? = null,
    var reinforcementPlan: String? = null,
    var primaryRivalCiv: String? = null,
    var doNotDo: ArrayList<String> = arrayListOf(),
    var notes: ArrayList<MemoryNote> = arrayListOf(),
    var lastUpdatedTurn: Int = 0,
) : IsPartOfGameInfoSerialization {
    constructor() : this("", "", null, null, null, null, null, arrayListOf(), arrayListOf(), 0)
}

@Serializable
data class EmpirePlanMemory(
    var summary: String? = null,
    var purchaseIntent: String? = null,
    var notes: ArrayList<MemoryNote> = arrayListOf(),
    var lastUpdatedTurn: Int = 0,
) : IsPartOfGameInfoSerialization {
    constructor() : this(null, null, arrayListOf(), 0)
}

@Serializable
data class MemoryNote(
    var topic: String = "",
    var kind: String = "",
    var text: String = "",
    var confidence: String? = null,
    var civName: String? = null,
    var x: Int? = null,
    var y: Int? = null,
    var firstTurn: Int = 0,
    var lastUpdatedTurn: Int = 0,
    var staleAfterTurn: Int = 0,
) : IsPartOfGameInfoSerialization {
    constructor() : this("", "", "", null, null, null, null, 0, 0, 0)
}

@Serializable
data class MemoryAnchor(
    var kind: String = "",
    var label: String = "",
    var civName: String? = null,
    var x: Int? = null,
    var y: Int? = null,
    var firstSeenTurn: Int = 0,
    var lastConfirmedTurn: Int = 0,
) : IsPartOfGameInfoSerialization {
    constructor() : this("", "", null, null, null, 0, 0)
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
data class TacticianTurnLogEntry(
    var turn: Int = 0,
    var basedOnStrategistTurn: Int? = null,
    var campaignStage: String? = null,
    var decisiveObjective: String? = null,
    var summary: String = "",
    var whatChanged: ArrayList<String> = arrayListOf(),
    var completed: ArrayList<String> = arrayListOf(),
    var stillBlocked: ArrayList<String> = arrayListOf(),
    var obsolete: ArrayList<String> = arrayListOf(),
    var carryForward: ArrayList<String> = arrayListOf(),
    var actionSurfaceMismatch: ArrayList<String> = arrayListOf(),
    var memoValidity: String? = null,
    var commitmentLevel: String? = null,
    var battleReadiness: String? = null,
    var supplyHealth: String? = null,
) : IsPartOfGameInfoSerialization {
    constructor() : this(0, null, null, null, "", arrayListOf(), arrayListOf(), arrayListOf(), arrayListOf(), arrayListOf(), arrayListOf(), null, null, null, null)
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
