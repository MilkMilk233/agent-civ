package com.unciv.logic.automation.agent

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object AgentPromptBuilder {
    private val json = Json {
        prettyPrint = false
        explicitNulls = false
    }

    fun observationJson(observation: AgentObservation): String = json.encodeToString(observation)

    fun build(observation: AgentObservation): String {
        val observationJson = observationJson(observation)
        return """
            You are an AI strategy planner for a turn-based 4X game.
            Produce JSON only, with no markdown or prose.
            The JSON must match this schema exactly:
            {
              "actions": [
                {"type":"unit_move","priority":0,"unitId":123,"destinationX":0,"destinationY":0},
                {"type":"unit_action","priority":1,"unitId":123,"actionType":"FoundCity"},
                {"type":"city_choose_construction","priority":2,"cityX":0,"cityY":0,"constructionName":"Granary"},
                {"type":"end_turn","priority":999}
              ],
              "handoffToLegacyAI": false,
              "notes": "optional"
            }
            Rules:
            - The observation is a curated current-turn brief, not a full save dump.
            - Focus first on empireSummary, priorityFacts, citiesNeedingAttention, actionableUnits, visibleThreatsAndTargets, and opportunities.
            - Treat omittedSummary as a sign that quieter state exists, but only act through the entities explicitly listed in the observation.
            - Use only unit IDs, cities, action types, tiles, and constructions present in the observation.
            - Do not invent entities.
            - Prefer short, legal plans (0-25 commands).
            - If uncertain, return an empty actions list and set handoffToLegacyAI=true.
            Observation JSON:
            $observationJson
        """.trimIndent()
    }
}
