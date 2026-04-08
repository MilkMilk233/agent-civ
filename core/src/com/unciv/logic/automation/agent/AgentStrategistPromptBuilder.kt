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
        val cheatSheet = AgentStrategicCheatSheetBank.resolve(
            empireObservation.gameContext,
            memory.strategicRoadmap
        )
        val cheatSheetSection = cheatSheet?.let { sheet ->
            buildString {
                appendLine("- Background knowledge from the strategist cheat sheet bank:")
                appendLine("  - ${sheet.title}.")
                for (bullet in sheet.bullets) {
                    appendLine("  - $bullet")
                }
            }.trimEnd()
        } ?: ""
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
                "pastSummary": "optional",
                "currentSituation": "optional",
                "futurePlan": "optional",
                "reviewInTurns": 4
              },
              "notes": "optional"
            }
            Rules:
            - This is the standard Unciv main game with the Civ V - Vanilla ruleset. Use normal Civ V Vanilla strategic priors confidently.
            - Build one coherent roadmap. Do not hedge across multiple win paths unless the game state truly demands a flexible fallback.
            - Prefer stable doctrine. If the existing roadmap still fits, refine it instead of thrashing.
            - On tiny duel maps, think about tempo, expansion, defensive coverage, and pressure on the only rival.
            $cheatSheetSection
            - Use only public setup context and the summarized state in Strategist Brief JSON.
            - stateFacts in Strategist Brief JSON are neutral reminders from simple checks, not strategic conclusions. Do your own reasoning from them.
            - Your roadmap is the main narrative brain. Write it like a compact report with fixed subtitles: Past, Now, and Future.
            - lastStrategistReport is your previous compact commander memo. sinceLastReviewFacts are neutral factual deltas since that memo. Use them to update your thinking, not to repeat stale language.
            - citySnapshots and unitSnapshots cover the current empire in compact form. Use that broader perception to judge tempo, sequencing, and whether current projects still fit the roadmap.
            - Strategist Brief JSON includes roadmapReality with phase/status checks, notable drift, and urgent problems. Use it to update stale assumptions instead of repeating them.
            - Do not repeat opener instructions that no longer fit the actual empire size, contact status, or military situation.
            - If duel contact is still missing and roadmapReality marks it urgent, Future should make that priority legible without turning into local movement orders.
            - pastSummary should briefly explain what happened since the last strategist memo and what background context still matters.
            - currentSituation should briefly explain what is true now, what the empire's real bottleneck or tension is, and what changed in strategic terms.
            - futurePlan should briefly explain what the empire should try to accomplish over the next few turns in natural language.
            - Do not issue exact tactical commands. Avoid wording like "cancel Scout now", "switch Berlin to Warrior", or specific movement orders. Express strategic sequencing and urgency instead.
            - Do not mention difficulty, hidden bonuses, or opponent implementation.
            - reviewInTurns should usually stay between 3 and 6 unless there is a strong reason to review sooner or later.
            - Prefer state-based milestones such as "before contact", "after the capital is founded", "once the current Scout finishes", or "before starting a settler".
            - Avoid guessed turn counts unless they are directly grounded by visible project timings, current research timings, or another concrete value in Strategist Brief JSON.
            Strategist Brief JSON:
            $strategistBriefJson
        """.trimIndent()
    }
}
