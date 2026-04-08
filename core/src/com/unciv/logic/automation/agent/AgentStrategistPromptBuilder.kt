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
            - Your roadmap is the main narrative brain. Write it like a compact commander memo with fixed subtitles: Past, Now, and Future.
            - lastStrategistReport is your previous compact commander memo. sinceLastReviewFacts are neutral factual deltas since that memo. Use them to update your thinking, not to repeat stale language.
            - citySnapshots and unitSnapshots cover the current empire in compact form. Use that broader perception to judge tempo, sequencing, and whether current projects still fit the roadmap.
            - rivalCities, rivalUnits, and campaignPicture are factual operational state. Use them to think about frontage, target priority, readiness to declare, readiness to capture, and whether the empire is still preparing or should be acting.
            - Do not repeat opener instructions that no longer fit the actual empire size, contact status, or military situation.
            - Treat Strategist Brief JSON as a factual state packet, not a script-written strategic interpretation.
            - Think like a strong human strategist: identify the real bottleneck, the current campaign state, and the next operational objective. Do not turn the report into a long step-by-step playbook.
            - thesis should be a single compact sentence capturing the strategic idea of the roadmap.
            - pastSummary should briefly explain what changed since the last strategist memo and what background context still matters. Keep it to 1-2 sentences.
            - currentSituation should briefly explain what is true now, what the empire's real bottleneck or tension is, and what changed in strategic terms. Keep it to 1-3 sentences.
            - futurePlan should briefly explain what the empire should try to accomplish over the next few turns in natural language. Keep it to 1-3 sentences.
            - For domination-oriented states, reason in campaign terms when possible: preparing, marching, declaring, assaulting, exploiting, or stabilizing after losses.
            - If a rival city or capital is visible, futurePlan should usually identify the next operational objective in campaign terms, not just list internal economy tasks.
            - If war is not yet started, use the visible rival/campaign picture to judge what still blocks declaration. If war is already underway, focus on what must happen to take territory rather than repeating generic economy maintenance.
            - Do not issue exact tactical commands. Avoid wording like "cancel Scout now", "switch Berlin to Warrior", or specific movement orders. Express strategic sequencing and urgency instead.
            - Do not write numbered plans, city-by-city build scripts, or long branch trees. Avoid "first/second/third", "if A then B else C" chains, or repetitive fallback ladders unless the state truly requires one key contingency.
            - Do not mention difficulty, hidden bonuses, or opponent implementation.
            - reviewInTurns should usually stay between 3 and 6 unless there is a strong reason to review sooner or later.
            - Prefer state-based milestones such as "before contact", "after the capital is founded", "once the current Scout finishes", or "before starting a settler".
            - Avoid guessed turn counts unless they are directly grounded by visible project timings, current research timings, or another concrete value in Strategist Brief JSON.
            Strategist Brief JSON:
            $strategistBriefJson
        """.trimIndent()
    }
}
