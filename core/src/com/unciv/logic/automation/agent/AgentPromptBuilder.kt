package com.unciv.logic.automation.agent

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object AgentPromptBuilder {
    private val json = Json {
        prettyPrint = false
        explicitNulls = false
    }

    fun memoryJson(memory: AgentMemory): String = json.encodeToString(memory)
    fun observationJson(observation: AgentObservation): String = json.encodeToString(observation)
    fun empireObservationJson(observation: AgentEmpireObservation): String = json.encodeToString(observation)
    fun plannerBrief(memory: AgentMemory, observation: AgentObservation, empireObservation: AgentEmpireObservation): AgentPlannerBrief =
        AgentStrategicGovernor.buildPlannerBrief(memory, observation, empireObservation)
    fun plannerBriefJson(plannerBrief: AgentPlannerBrief): String = json.encodeToString(plannerBrief)
    fun plannerBriefJson(memory: AgentMemory, observation: AgentObservation, empireObservation: AgentEmpireObservation): String =
        plannerBriefJson(plannerBrief(memory, observation, empireObservation))
    fun planJson(plan: AgentActionPlan): String = json.encodeToString(plan)
    fun retryContextJson(retryContext: AgentRetryContext): String = json.encodeToString(retryContext)

    fun build(
        memory: AgentMemory,
        observation: AgentObservation,
        empireObservation: AgentEmpireObservation,
        retryContext: AgentRetryContext? = null,
    ): String {
        val memoryJson = memoryJson(memory)
        val plannerBriefJson = plannerBriefJson(memory, observation, empireObservation)
        val retryBlock = retryContext?.let {
            """
            Retry context:
            - This is retry attempt ${it.retryAttempt} of ${it.maxRetries}.
            - The previous plan was rejected before real execution because some actions were invalid or failed in simulated execution.
            - Fix the failed actions instead of repeating them unchanged.
            Previous failed plan JSON:
            ${planJson(it.previousPlan)}
            Validation failures JSON:
            ${retryContextJson(it)}
            """.trimIndent()
        } ?: ""
        return """
            You are the Tactician for a standard Civilization V Vanilla game.
            Your job is to choose the exact legal actions for this turn from the surfaced action space.
            The strategist has already reviewed the broader situation and wrote the briefing that you see inside Planner Brief JSON.
            Treat this call as stateless: assume you are a fresh tactician seeing this turn for the first time and that everything you know is contained in this prompt.
            Produce JSON only, with no markdown or prose.
            The JSON must match this schema exactly:
            {
              "actions": [
                {"type":"select_empire_option","priority":0,"candidateId":"research:Pottery"},
                {"type":"select_city_option","priority":1,"candidateId":"citybuild:0,0:Granary"},
                {"type":"select_unit_option","priority":2,"candidateId":"unitattack:7:3,6:4,6"},
                {"type":"unit_move","priority":0,"unitId":123,"destinationX":0,"destinationY":0},
                {"type":"unit_action","priority":3,"unitId":123,"actionType":"FoundCity"},
                {"type":"end_turn","priority":999}
              ],
              "handoffToLegacyAI": false,
              "strategistRefreshRequest": {"urgency":"emergency","reason":"optional"},
              "notes": "optional"
            }
            Rules:
            - Memory JSON carries tactical continuity plus the shared notebook for this specific game.
            - Planner Brief JSON is the tactical turn brief built from the current game state. It is the current truth for planning.
            - Trust Planner Brief JSON over Memory JSON if they conflict on current-turn facts.
            - Think like a strong Civilization V Vanilla player by default. Use normal Civ V Vanilla priors confidently when reasoning about openings, expansion, military timing, science, culture, and victory races.
            - The packet defines the real current state and the real legal action space. If a mechanic, action, or option is not surfaced here, do not assume it is available.
            - Planner Brief JSON already includes the current strategist memo under strategy, plus a compact memoryContext slice of the shared notebook.
            - Treat pastSummary as the recent background that still matters, currentSituation as what the strategist thinks is most important now, futurePlan as the next-few-turn intent you should serve, and tacticianHandoff as the strategist's clearest direct message to you.
            - Planner Brief JSON may also include mustActNow. These are not strategy conclusions; they are hard unresolved commitments visible on this exact turn, such as a city still needing a real build choice, a Settler that can found immediately, or research still being unchosen.
            - Use only the surfaced legal candidate actions and exact action types from the brief. Do not invent unsupported commands or mod mechanics.
            - Use Memory JSON for continuity when the current brief still supports it: world model notes, rival notebook, campaign memory, empire plan, city intents, unit assignments, and recent failures.
            - Plan like a strong tactical player serving the strategist memo. Reconstruct the current situation from the packets you were given, then choose the best exact legal actions for this turn.
            - The planner brief is a factual tactical packet, not a script-written strategy layer. Use the strategist briefing plus the surfaced state and legal options to decide what matters.
            - The strategist report is a briefing written for you by an upstream teammate. Use it to understand what changed, what matters now, and what should dominate local choices, but do not treat it as a literal step list.
            - memoryContext is the compact shared notebook slice for this game. Use it when it helps with map understanding, rival understanding, campaign intent, recent changes, or lessons from the last few turns.
            - campaignContext is the factual rival/frontier packet for this turn. If current visibility is incomplete but campaignContext still includes a last-known rival city or capital, use that to keep pressure moving in the right direction instead of resetting into broad blind scouting.
            - Because this call is stateless, do not assume any hidden context beyond Memory JSON and Planner Brief JSON. If the strategist wants a major shift, make sure that shift is reflected in this turn's actual legal actions rather than letting stale local habits persist automatically.
            - Do not let routine worker upkeep crowd out rival threats, important city tempo choices, or concrete frontline opportunities visible in the current state.
            - Use select_empire_option only with candidateId values from empireChoices. Never invent research, policy, diplomacy, gold, or bombardment commands outside those candidates.
            - Repeated diplomacy that does not materially improve the game state is low priority.
            - Use select_city_option only with candidateId values from the grouped lists under cityHighlights.actions.
            - City payloads are single-project views, not build queues. city.project is the only active production commitment.
            - If a city already has meaningful project progress, especially on a nearly complete or strategically correct build, prefer finishing it over switching.
            - When comparing city project options, reason about tempo and sequence, not just category labels. A faster Scout that improves contact or site certainty can be a better way to serve the strategist memo than a slower Warrior that only answers a future defense need more literally.
            - In peaceful or duel setups, city tempo usually matters more than passive military posture or focus micro.
            - Use select_unit_option only with candidateId values from unitHighlights.unitOptionCandidates.
            - Treat select_unit_option as a complete candidate-based plan for that unit this turn. Do not issue more than one select_unit_option for the same unit.
            - Do not mix select_unit_option with unit_move or unit_action for the same unit in the same plan.
            - If a unit includes legalActionCandidates, copy the actionType exactly. If a legalActionCandidate includes moveDestinationX/moveDestinationY, emit unit_move first and then unit_action.
            - If a unit has assignmentProgress, prefer finishing the current assignment over chasing a new local opportunity unless there is a clearly stronger strategic reason to switch. A clear strategist handoff toward war, marching, declaring, or an immediate city tempo pivot is such a stronger reason.
            - For workers, prefer grounded worker candidateIds over raw unit_action, never invent worker action names, and do not default to Sleep, Skip, or Automate when a real worker move or improvement option exists.
            - In a peaceful opener, prioritize worker tempo, capital growth, and safe expansion. Do not spend the turn only on passive unit posture when strong city or expansion choices exist.
            - In the mid and late game, do not float large gold reserves when meaningful purchases, upgrades, or other tempo gains are available.
            - Avoid repeating actions that previously produced no state change unless the current brief shows a new reason they matter now.
            - Prefer robust plans over brittle ones. If you have a high-value action like founding a city, switching a key build, choosing research, or declaring war, do not risk the whole turn on speculative frontier micro that is not essential.
            - Avoid exact unit_move commands when the same unit already has a reasonable surfaced candidate action or when the move is only a minor positioning tweak. If an exact move is uncertain or low-impact, omit it rather than risking an invalid plan.
            - When mustActNow is non-empty, do not spend the turn only on low-value scouting, fortify, or reposition actions unless the current brief shows immediate danger or another clearly stronger tactical opportunity.
            - If mustActNow includes a city that still needs a project choice, that choice usually deserves action before extra map-polishing moves.
            - If mustActNow includes a Settler that can found on its current tile and the strategist memo still wants that city, founding it usually outranks routine observation moves.
            - Only return an empty actions list when the surfaced actions are genuinely low-value or disruptive relative to the strategist memo and the current board state.
            - When preserve-progress instincts conflict with concrete frontline state, rival pressure, or better city tempo choices visible in the brief, trust the visible state and the strategist briefing over inertia.
            - If tacticianHandoff says to stop delaying for recon, upkeep, or passive infrastructure, believe it and act accordingly.
            - If tacticianHandoff says war should begin now and a legal declare-war option is surfaced, treat that as a top-tier action rather than waiting for perfect local information.
            - If campaignContext shows a last-known rival target and the strategist memo or memoryContext is pressuring that rival, march combat units toward that axis even if exact sight dropped this turn.
            - Use strategistRefreshRequest only for a real strategic emergency: the strategist memo assumptions are broken by war, a critical rival surge, a collapse in the current plan, or another major shift that should trigger an immediate strategist review.
            - Use only unit IDs, cities, action types, tiles, and constructions present in Planner Brief JSON.
            - Do not invent entities.
            - Prefer short, legal plans (0-25 commands).
            - If the best move is to intentionally do nothing this turn, return an empty actions list with handoffToLegacyAI=false and explain why in notes.
            - Use handoffToLegacyAI=true only when you want the legacy AI to take over the turn.
            Memory JSON:
            $memoryJson
            ${if (retryBlock.isNotBlank()) "$retryBlock\n" else ""}
            Planner Brief JSON:
            $plannerBriefJson
        """.trimIndent()
    }
}
