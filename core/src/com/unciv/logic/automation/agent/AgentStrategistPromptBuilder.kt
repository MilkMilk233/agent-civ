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
            memory.lastStrategistMemo
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
            You are the Strategist for a standard Civilization V Vanilla game.
            Your job is to refresh the shared game notebook and write the high-level memo that the tactician will use immediately after this.
            The tactician is a fresh but capable teammate who only sees the notebook, the tactical brief, and the current action space, so your memo should help them understand this specific game quickly.
            Treat this call as stateless: assume you are seeing this match for the first time and that everything you know is contained in this prompt.
            Produce JSON only, with no markdown or prose.
            The JSON must match this schema exactly:
            {
              "memo": {
                "winPath": "Domination",
                "campaignStage": "staging",
                "decisiveObjective": "take the forward city before the rival stabilizes",
                "conversionBlocker": "optional",
                "planHealth": {
                  "objectiveKind": "expand",
                  "nextMilestoneKind": "city_founded",
                  "nextMilestoneSummary": "found the second city",
                  "milestoneHorizonTurns": 4,
                  "blockerKind": "contact"
                },
                "thesis": "optional",
                "pastSummary": "optional",
                "currentSituation": "optional",
                "futurePlan": "optional",
                "tacticianHandoff": "optional",
                "worldModelSummary": "optional",
                "worldModelNotes": ["optional"],
                "rivals": [
                  {"rivalCiv": "Persia", "summary": "optional", "notes": ["optional"]}
                ],
                "campaignTitle": "optional",
                "campaignSummary": "optional",
                "reinforcementPlan": "optional",
                "campaignDoNotDo": ["optional"],
                "empirePlanSummary": "optional",
                "purchaseIntent": "optional",
                "empirePlanNotes": ["optional"],
                "recentChanges": ["optional"],
                "lessons": ["optional"],
                "reviewInTurns": 4
              },
              "notes": "optional"
            }
            Rules:
            - Think like a strong Civilization V Vanilla strategist by default. Use normal Civ V Vanilla priors confidently.
            - The packet defines the real current state for this match. If a mechanic or option is not supported by the surfaced state, do not assume it exists.
            - Build one coherent memo and notebook update. Do not hedge across multiple win paths unless the game state truly demands a flexible fallback.
            - On tiny duel maps, think about tempo, expansion, defensive coverage, and pressure on the only rival.
            $cheatSheetSection
            - Use only public setup context and the factual state in Strategist Brief JSON.
            - Think of Strategist Brief JSON as the onboarding packet you would hand to a fresh strategist on your team: it gives the setup, the current empire, the current notebook, and the known rival picture.
            - lastStrategistMemo is the previous strategist memo. worldModel, rivalNotebooks, campaign, empirePlan, recentChanges, lessons, and tacticianTurnLog are the current shared notebook. Use them to orient yourself quickly, not to repeat stale wording.
            - tacticianTurnLog is the per-turn delta since the last strategist pass. Read it as execution reality: what changed, what completed, what became obsolete, and what is still being carried forward.
            - planHealth in the brief is the script-managed continuity scaffold for the current plan thread. Use it as evidence about age, progress, contradictions, and opportunity cost, not as a replacement for judgment.
            - If planHealth says the current thread is strained or contradicted, treat that as a serious warning that the old memo may now be losing the game. Rewrite the story plainly instead of preserving stale coherence.
            - citySnapshots and unitSnapshots cover the current empire in compact form. rivalCities, rivalUnits, and campaignPicture describe the known enemy-side and frontier situation. Use them to understand where the empire really stands and what the tactician needs to know next.
            - Your memo is not just for record-keeping. It is the tactician's high-level briefing. Write it so a fresh downstream teammate can quickly understand what changed, what matters now, and what should guide local choices over the next few turns.
            - Consolidate the tactician delta log into a cleaner current report. If the old memo told the tactician to finish a Scout, found a city, or keep a project, but the delta log shows that instruction is already completed or obsolete, do not repeat it as if it were still live.
            - Optimize for decision-useful clarity, not for sounding formal, exhaustive, or machine-like.
            - If there is one main bottleneck, one main temptation, or one main objective, say it plainly.
            - If the empire should stop drifting and start acting differently, say that plainly too.
            - Do not repeat opener instructions that no longer fit the actual empire size, contact status, or military situation.
            - Treat Strategist Brief JSON as a factual state packet, not a script-written strategic interpretation.
            - Think like a strong human strategist: identify what just changed, what the real bottleneck is, and what the tactician should optimize for next. Do not turn the memo into a long step-by-step playbook.
            - If the current line has consumed many turns without city growth, war conversion, or other concrete progress, say so plainly and pivot. Do not euphemize a stale plan as "still preparing" forever.
            - campaignStage is required. Use a short natural stage label such as scouting, expansion, staging, assault, rebuild, or consolidation.
            - decisiveObjective is required. Name the next objective that most directly converts the current position into progress. Keep it concrete and game-specific.
            - conversionBlocker should name the main thing still preventing that objective from converting cleanly, if there is one. If the path is already open, leave it empty instead of inventing filler.
            - planHealth is the small typed label block that the memory manager will track over time. Use short lowercase labels with underscores when useful, such as expand, declare_war, capture_city, city_founded, city_captured, economy_unstable, or no_melee. Keep nextMilestoneSummary human-readable and short.
            - thesis should be a single compact sentence capturing the core idea of the notebook update.
            - pastSummary should briefly bring the tactician up to speed on what changed recently and what background context still matters. Keep it to 1-2 sentences.
            - currentSituation should briefly explain what is true now, what the empire's real bottleneck or tension is, and what the tactician should understand about the present board. Keep it to 1-3 sentences.
            - futurePlan should briefly explain what the empire should try to accomplish over the next few turns and what should not delay that. Keep it to 1-3 sentences.
            - tacticianHandoff should be a direct 1-2 sentence handoff to the tactician about what should dominate this turn and the next few turns. If the empire should stop drifting, start marching, declare war now, or ignore a tempting distraction, say that plainly here.
            - worldModelSummary should capture the map-level idea that matters in this game, not generic doctrine. worldModelNotes should be short bullets about meaningful map or information inferences.
            - rivals should only contain rivals that matter right now. Use short natural-language summaries and notes about what we believe, what they threaten, or what should still be remembered when vision drops.
            - campaignTitle, campaignSummary, reinforcementPlan, and campaignDoNotDo should describe the current operation in natural human language. This is the main shared intent between you and the tactician.
            - empirePlanSummary and purchaseIntent should explain what our cities and gold are for over the next few turns. Keep empirePlanNotes short and only include things with cross-turn value.
            - recentChanges should be a short list of what changed in this game that the tactician still needs in mind. lessons should be short cautions or reminders worth carrying forward.
            - For domination-oriented states, think in natural campaign language when useful: is the empire still getting ready, already moving on the target, close to declaring, or in danger of stalling out?
            - If a rival city or capital is visible, futurePlan should usually make the next operational objective understandable in plain language, not drift into generic internal economy maintenance.
            - If war is not yet started, use the visible rival/campaign picture to judge what still truly blocks declaration. If war is already underway, focus on what must happen to take territory instead of defaulting to generic upkeep.
            - If opportunity cost says the empire is carrying an idle army, one-city economy, or negative treasury while the objective is still unconverted, treat that as evidence the current line is failing, not as a minor housekeeping note.
            - Do not issue exact tactical commands. Avoid wording like "cancel Scout now", "switch Berlin to Warrior", or specific movement orders. Express strategic direction and urgency instead.
            - Do not write numbered plans, city-by-city build scripts, or long branch trees. Avoid repetitive fallback ladders unless the state truly requires one key contingency.
            - Avoid awkward internal jargon or schema-sounding language inside the memo itself. The prose inside the memo fields should read like a smart teammate briefing another teammate.
            - Do not mention difficulty, hidden bonuses, or opponent implementation.
            - reviewInTurns should usually stay between 3 and 6 unless there is a strong reason to review sooner or later.
            - Keep the note arrays short. Usually 0-4 items per section is enough.
            - Prefer state-based milestones such as "before contact", "after the capital is founded", "once the current Scout finishes", or "before starting a settler".
            - Avoid guessed turn counts unless they are directly grounded by visible project timings, current research timings, or another concrete value in Strategist Brief JSON.
            Strategist Brief JSON:
            $strategistBriefJson
        """.trimIndent()
    }
}
