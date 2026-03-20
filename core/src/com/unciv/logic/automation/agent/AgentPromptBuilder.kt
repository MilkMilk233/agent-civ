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
    fun planJson(plan: AgentActionPlan): String = json.encodeToString(plan)
    fun retryContextJson(retryContext: AgentRetryContext): String = json.encodeToString(retryContext)

    fun build(
        memory: AgentMemory,
        observation: AgentObservation,
        empireObservation: AgentEmpireObservation,
        retryContext: AgentRetryContext? = null,
    ): String {
        val memoryJson = memoryJson(memory)
        val observationJson = observationJson(observation)
        val empireObservationJson = empireObservationJson(empireObservation)
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
            You are an AI strategy planner for a turn-based 4X game.
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
              "notes": "optional"
            }
            Rules:
            - Memory JSON captures current strategic intent carried over from earlier turns. It may be stale.
            - Observation JSON is a curated tactical current-turn brief, not a full save dump.
            - Empire Observation JSON contains current empire-wide choices such as research, policies, faith/gold spending, city bombardment, diplomacy/trade offers, and spy assignments.
            - Trust Observation JSON over Memory JSON if they conflict.
            - Use Memory JSON to preserve continuity: keep strategicPosture consistent when still relevant, continue cityIntents and unitAssignments when the observation still supports them, and avoid repeating recentFailures.
            - Focus first on empireSummary, priorityFacts, citiesNeedingAttention, actionableUnits, visibleThreatsAndTargets, and opportunities.
            - Empire Observation JSON may include currentResearchTurnsLeft/currentResearchProgress/currentResearchStatus. Treat a nearly-complete research choice as already in flight unless a free-tech choice or missing research queue requires action.
            - For city governance, each city in citiesNeedingAttention may include cityOptionCandidates for legal production changes, purchases, tile buys, and city focus changes. Use select_city_option only with those exact candidateId values.
            - Cities may include constructionProgress. If a build is already underway and especially if it is nearly complete or still matches memory intent, prefer finishing it instead of switching production.
            - Cities may also list topConstructionChoices. When the opener is peaceful, city development is usually more important than passive unit posture.
            - For tactical control, actionableUnits may include unitOptionCandidates for grounded attack, settlement, worker, and recovery choices. Use select_unit_option only with those exact candidateId values.
            - Actionable units may include assignmentProgress. If a worker or settler is already moving toward a valid assignment or is already on the target tile, prefer finishing that assignment over chasing a new opportunity.
            - Treat omittedSummary as a sign that quieter state exists, but only act through the entities explicitly listed in the observation.
            - Use select_empire_option only with exact candidateId values from Empire Observation JSON.
            - Empire Observation JSON is the only source of legal empire-level options. Do not invent research, policy, religion, gold, bombardment, diplomacy, trade, or spy commands outside those candidateIds.
            - Diplomacy candidates may include declarations of friendship, embassy requests, open borders, research agreements, defensive pacts, or luxury exchanges. Use only the exact candidateId values already present.
            - Spy candidates may include specific city assignments or coup preparation. Use only the exact candidateId values already present.
            - Prefer select_city_option for city purchases, tile buys, and city focus changes instead of describing those actions in notes.
            - If cityOptionCandidates include construction candidates such as citybuild:..., prefer those exact candidateId values over inventing or paraphrasing build changes.
            - Prefer select_unit_option for attacks, settler city-site moves, worker job moves, worker improvements, founding on the current tile, and recovery/fortify choices when unitOptionCandidates are present.
            - If an actionable unit includes legalActionCandidates, copy the candidate actionType exactly.
            - If a legalActionCandidate includes moveDestinationX/moveDestinationY, emit a unit_move to that tile before the unit_action.
            - For workers especially, prefer select_unit_option candidateIds over raw unit_action whenever worker unitOptionCandidates are present.
            - For workers especially, do not invent action names like BuildFarm, BuildQuarry, ImproveWorkedTile, or similar paraphrases. Use only exact actionType values already present in unitActions or legalActionCandidates.
            - Do not use Sleep, Skip, or Automate as the default way to finish a worker turn when a grounded worker move or improvement candidate is available.
            - If a worker assignment is already in progress, switching away should require a clearly stronger reason than simply noticing another good tile elsewhere.
            - During a peaceful early opener, prioritize worker tempo, capital growth, and safe expansion over focus micro, fortify, skip, or empty no-op turns.
            - Do not spend a calm opener turn only on passive military posture when a city still has strong growth or expansion choices.
            - If a city is already building Worker, Settler, Granary, or Monument in a peaceful opener, do not switch away lightly once production has been invested.
            - Avoid repeating macro or focus changes that previously produced no state change unless the observation clearly shows a new reason they matter now.
            - Use only unit IDs, cities, action types, tiles, and constructions present in the observation.
            - Do not invent entities.
            - Prefer short, legal plans (0-25 commands).
            - If the best move is to intentionally do nothing this turn, return an empty actions list with handoffToLegacyAI=false and explain why in notes.
            - Use handoffToLegacyAI=true only when you want the legacy AI to take over the turn.
            Memory JSON:
            $memoryJson
            ${if (retryBlock.isNotBlank()) "$retryBlock\n" else ""}
            Empire Observation JSON:
            $empireObservationJson
            Observation JSON:
            $observationJson
        """.trimIndent()
    }
}
