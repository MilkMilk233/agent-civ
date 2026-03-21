package com.unciv.logic.automation.agent

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object AgentStrategistPromptBuilder {
    private val json = Json {
        prettyPrint = false
        explicitNulls = false
    }

    fun strategistBriefJson(
        memory: AgentMemory,
        observation: AgentObservation,
        empireObservation: AgentEmpireObservation,
        refreshRequest: AgentStrategistRefreshRequest,
    ): String = json.encodeToString(
        AgentStrategistGovernor.buildBrief(memory, observation, empireObservation, refreshRequest)
    )

    fun build(
        memory: AgentMemory,
        observation: AgentObservation,
        empireObservation: AgentEmpireObservation,
        refreshRequest: AgentStrategistRefreshRequest,
    ): String {
        val strategistBriefJson = strategistBriefJson(memory, observation, empireObservation, refreshRequest)
        return """
            You are the Strategist for Unciv using the standard Civ V - Vanilla ruleset.
            Your job is to choose or revise the high-level roadmap for the next few turns like a strong human player.
            Produce JSON only, with no markdown or prose.
            The JSON must match this schema exactly:
            {
              "roadmap": {
                "doctrine": "duel_expansion_then_pressure",
                "winPath": "Domination",
                "phase": "opener",
                "thesis": "optional",
                "midTermGoals": ["optional"],
                "mustMaintain": ["optional"],
                "watchOuts": ["optional"],
                "switchTriggers": ["optional"],
                "reviewInTurns": 4
              },
              "notes": "optional"
            }
            Rules:
            - This is the standard Unciv main game with the Civ V - Vanilla ruleset. Use normal Civ V Vanilla strategic priors confidently.
            - Build one coherent roadmap. Do not hedge across multiple win paths unless the game state truly demands a flexible fallback.
            - Prefer stable doctrine. If the existing roadmap still fits, refine it instead of thrashing.
            - On tiny duel maps, think about tempo, expansion, military timing, and pressure on the only rival.
            - Use only public setup context and the summarized state in Strategist Brief JSON.
            - Strategist Brief JSON includes roadmapReality. Completed goals should disappear from the new roadmap, and stale goals must be rewritten to match the current board state.
            - Do not repeat opener instructions that no longer fit the actual empire size, contact status, or military situation.
            - If duel contact is still missing and roadmapReality marks it urgent, give the next roadmap concrete near-term goals that force contact.
            - Do not mention difficulty, hidden bonuses, or opponent implementation.
            - reviewInTurns should usually stay between 3 and 6 on quick duel maps unless there is a strong reason to review sooner or later.
            - midTermGoals should contain 2-4 concrete aims for the next few turns.
            - mustMaintain should contain 2-4 floors or non-negotiables.
            - watchOuts should contain 1-3 high-risk failure patterns or rival warnings.
            - switchTriggers should contain 2-4 clear conditions that would justify revising doctrine.
            Strategist Brief JSON:
            $strategistBriefJson
        """.trimIndent()
    }
}
