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
    fun plannerBriefJson(memory: AgentMemory, observation: AgentObservation, empireObservation: AgentEmpireObservation): String =
        json.encodeToString(plannerBrief(memory, observation, empireObservation))
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
            You are an AI strategy planner for Unciv using the standard Civ V - Gods & Kings style ruleset.
            Produce JSON only, with no markdown or prose.
            The JSON must match this schema exactly:
            {
              "actions": [
                {"type":"select_empire_option","priority":0,"candidateId":"research:Pottery"},
                {"type":"select_city_option","priority":1,"candidateId":"citypurchase:0,0:Granary"},
                {"type":"select_unit_option","priority":2,"candidateId":"unitattack:7:3,6:4,6"},
                {"type":"unit_move","priority":0,"unitId":123,"destinationX":0,"destinationY":0},
                {"type":"unit_action","priority":3,"unitId":123,"actionType":"FoundCity"},
                {"type":"city_choose_construction","priority":4,"cityX":0,"cityY":0,"constructionName":"Granary"},
                {"type":"end_turn","priority":999}
              ],
              "handoffToLegacyAI": false,
              "strategistRefreshRequest": {"urgency":"emergency","reason":"optional"},
              "notes": "optional"
            }
            Rules:
            - Memory JSON carries tactical continuity plus the current strategist roadmap.
            - Planner Brief JSON is the tactical turn brief built from the current game state. It is the current truth for planning.
            - Trust Planner Brief JSON over Memory JSON if they conflict on current-turn facts.
            - This project targets the standard Unciv main game, not mod-specific mechanics. Use normal Civ V strategic priors confidently when reasoning about openings, expansion, military timing, science, culture, and victory races.
            - Planner Brief JSON already includes the current roadmap doctrine, phase, thesis, commitments, and watchOuts. Treat that roadmap as the long and mid-term source of truth unless the board creates a real emergency.
            - Use only the surfaced legal candidate actions and exact action types from the brief. Do not invent unsupported commands or mod mechanics.
            - Use Memory JSON for continuity when the current brief still supports it: city intents, unit assignments, recent failures, and roadmap consistency.
            - Plan like a strong tactical player serving the roadmap. Let criticalAlerts, progressInMotion, threatHighlights, and the current roadmap commitments drive the turn.
            - The planner brief already compressed noise. Do not let routine worker upkeep crowd out rival threats, military floor problems, gold overflow, or important city tempo choices.
            - Use select_empire_option only with candidateId values from empireChoices. Never invent research, policy, diplomacy, religion, gold, bombardment, or spy commands outside those candidates.
            - Repeated diplomacy that does not materially improve the game state is low priority.
            - Use select_city_option only with candidateId values from cityHighlights.cityOptionCandidates.
            - If a city already has meaningful construction progress, especially on a nearly complete or strategically correct build, prefer finishing it over switching.
            - In peaceful or duel setups, city tempo usually matters more than passive military posture or focus micro.
            - Use select_unit_option only with candidateId values from unitHighlights.unitOptionCandidates.
            - If a unit includes legalActionCandidates, copy the actionType exactly. If a legalActionCandidate includes moveDestinationX/moveDestinationY, emit unit_move first and then unit_action.
            - If a unit has assignmentProgress, prefer finishing the current assignment over chasing a new local opportunity unless there is a clearly stronger strategic reason to switch.
            - For workers, prefer grounded worker candidateIds over raw unit_action, never invent worker action names, and do not default to Sleep, Skip, or Automate when a real worker move or improvement option exists.
            - In a peaceful opener, prioritize worker tempo, capital growth, and safe expansion. Do not spend the turn only on passive unit posture when strong city or expansion choices exist.
            - In the mid and late game, do not float large gold reserves when meaningful purchases, upgrades, or other tempo gains are available.
            - Avoid repeating actions that previously produced no state change unless the current brief shows a new reason they matter now.
            - Use strategistRefreshRequest only for a real strategic emergency: the roadmap assumptions are broken by war, a critical rival surge, a collapse in the current plan, or another major shift that should trigger an immediate strategist review.
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
