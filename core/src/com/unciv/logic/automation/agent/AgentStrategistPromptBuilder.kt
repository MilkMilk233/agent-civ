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
        val victoryIntent = AgentVictoryIntentResolver.resolve(memory, observation, empireObservation)
        val cheatSheet = AgentStrategicCheatSheetBank.resolve(
            empireObservation.gameContext,
            memory.lastStrategistMemo,
            victoryIntent,
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
                "decisionFrame": {
                  "decisionMode": "stage_briefly",
                  "targetFrame": "assemble on the frontier city approach",
                  "whyNow": "the empire is still building the first real launch package",
                  "nextCheckpoint": "finish assembly and be ready to declare without more drifting",
                  "expiryCondition": "if the package still cannot launch after the next short review window"
                },
                "controlLanes": {
                  "buildControl": "optional",
                  "unitControl": "optional",
                  "workerControl": "optional",
                  "purchaseControl": "optional",
                  "techControl": "optional",
                  "policyControl": "optional",
                  "driftWarnings": ["optional"]
                },
                "reviewContract": {
                  "maxAgeTurns": 8,
                  "triggers": [
                    {"kind": "milestone_reached", "metric": "second_city_founded", "summary": "The opener phase ends once the second city is founded."},
                    {"kind": "deadline_missed", "metric": "second_city_founded", "withinTurns": 4, "summary": "Replan if the second city still is not founded by the short horizon."}
                  ]
                },
                "campaignControl": {
                  "commitmentLevel": "prepare",
                  "battleReadiness": "nearly_ready",
                  "supplyHealth": "healthy",
                  "nextCheckpointKind": "city_founded",
                  "nextCheckpointSummary": "convert the second city into real map tempo",
                  "checkpointHorizonTurns": 4,
                  "pivotTriggerKind": "stalled"
                },
                "thesis": "optional",
                "pastSummary": "optional",
                "currentSituation": "optional",
                "futurePlan": "optional",
                "tacticianHandoff": "optional",
                "worldModelSummary": "optional",
                "worldModelNotes": ["optional"],
                "campaignTitle": "optional",
                "campaignSummary": "optional",
                "reinforcementPlan": "optional",
                "campaignDoNotDo": ["optional"],
                "empirePlanSummary": "optional",
                "purchaseIntent": "optional",
                "empirePlanNotes": ["optional"],
                "recentChanges": ["optional"],
                "lessons": ["optional"]
              },
              "notes": "optional"
            }
            Rules:
            - Think like a strong Civilization V Vanilla strategist by default. Use normal Civ V Vanilla priors confidently.
            - Strategist Brief JSON is the factual packet for this call. If a mechanic or option is not supported there, do not assume it exists.
            - victoryIntent is the engine-owned routing packet for legal wins, routed win path, military purpose, and race/campaign rival context.
            - Build one coherent memo and notebook update. Do not hedge across multiple win paths unless the position truly needs a flexible fallback.
            - On tiny duel maps, think about tempo, expansion, defensive coverage, and pressure on the only rival.
            $cheatSheetSection
            - Use only public setup context and the factual state in the brief.
            - lastStrategistMemo, worldModel, campaign, empirePlan, recentChanges, lessons, and tacticianTurnLog are the shared notebook. Use them to orient quickly, not to repeat stale wording.
            - tacticianTurnLog is execution reality since the last strategist pass. If something is completed or obsolete there, do not repeat it as if it were still live.
            - campaignControl and refreshRequest define the current phase boundary. If checkpointStatus is missed, launchWindowOpen is live, or supplyHealth is fragile, say plainly whether the line should launch, stabilize, or pivot.
            - citySnapshots, unitSnapshots, rivalCities, rivalUnits, and campaignPicture are compact board summaries. Use them to identify the real bottleneck and the real next checkpoint.
            - Write the memo as a teammate briefing, not as a step-by-step script. Optimize for clarity, sufficiency, and what should stop as much as what should happen next.
            - Be explicit about sufficiency points: recon already sufficient, enough deterrence already built, enough staging already done, and what should replace drift.
            - memo.winPath must be one of enabledVictoryTypes. If exactly one victory type is legal, use that one.
            - If victoryIntent says militaryPurpose is defense or deterrence, military action can still matter, but it should support the legal routed win path instead of replacing it with an illegal conquest story.
            - campaignStage and decisiveObjective are required. Keep them short, concrete, and game-specific. Only fill conversionBlocker when there is a real blocker.
            - decisionFrame is the compact strategist-to-tactician contract: what mode this is, what axis matters, why now, what checkpoint proves the line is working, and what should make the line expire.
            - decisionMode should be a short lowercase label such as expand, deter_while_expanding, defend_and_boom, stage_briefly, launch_now, assault, pivot_recover, or stabilize.
            - controlLanes are bounded expectations, not exact commands. Fill only the lanes that matter, keep them concise, and make them declarative.
            - Put durable expectations in decisionFrame and controlLanes, not in tacticianHandoff.
            - reviewContract should be a short set of concrete, script-verifiable triggers. maxAgeTurns is just a backstop.
            - Use concrete trigger metrics such as contact_made, city_founded, second_city_founded, war_declared, city_captured, rival_city_visible, rival_capital_visible, target_site_contested, or action_surface_mismatch.
            - Do not use vague persistent conditions as triggers. A trigger should mean a real phase boundary, not just "the warning is still bad."
            - campaignControl labels should stay short and typed. nextCheckpointSummary should describe the next concrete state change that proves conversion.
            - If the empire has drifted for many turns without city growth, war conversion, or another real checkpoint, say so plainly and pivot. Do not euphemize stalling as "still preparing."
            - If war timing is the real question, own it directly: still staging briefly, launch now, or pivot away.
            - Do not issue exact tactical commands, city-by-city scripts, or long branch trees. Express strategic direction and urgency instead.
            - Keep arrays short, usually 0-4 items per section.
            - Keep the memo readable like a smart teammate briefing another teammate, not schema jargon.
            Strategist Brief JSON:
            $strategistBriefJson
        """.trimIndent()
    }
}
