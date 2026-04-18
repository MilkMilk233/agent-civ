package com.unciv.logic.automation.agent

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object AgentPromptBuilder {
    private val json = Json {
        prettyPrint = false
        explicitNulls = false
    }

    fun memoryJson(memory: AgentMemory): String = json.encodeToString(buildTacticalMemoryPrompt(memory))
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
        val tacticalCheatSheet = AgentTacticalCheatSheetBank.resolve(
            gameContext = empireObservation.gameContext,
            campaignStage = memory.lastStrategistMemo.campaignStage.ifBlank { empireObservation.campaignStage },
            isAtWar = empireObservation.isAtWar,
        )
        val tacticalCheatSheetSection = tacticalCheatSheet?.let { sheet ->
            buildString {
                appendLine("Background knowledge from the tactician cheat sheet bank:")
                appendLine("- ${sheet.title}.")
                for (bullet in sheet.bullets) {
                    appendLine("- $bullet")
                }
            }.trimEnd()
        } ?: ""
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
                {"type":"end_turn","priority":999}
              ],
              "handoffToLegacyAI": false,
              "strategistRefreshRequest": {"urgency":"emergency","reason":"optional"},
              "tacticianReflection": {
                "summary": "optional",
                "memoValidity": "healthy",
                "commitmentLevel": "optional",
                "battleReadiness": "optional",
                "supplyHealth": "optional",
                "whatChanged": ["optional"],
                "completed": ["optional"],
                "stillBlocked": ["optional"],
                "obsolete": ["optional"],
                "actionSurfaceMismatch": ["optional"]
              },
              "notes": "optional"
            }
            Rules:
            ${if (tacticalCheatSheetSection.isNotBlank()) "$tacticalCheatSheetSection\n" else ""}
            - Planner Brief JSON is current truth; Memory JSON is only continuity residue when the brief does not already cover it.
            - Use only surfaced candidateId values and exact action types. Do not invent entities, commands, or unsupported mechanics.
            - Read victoryIntent as the routed posture packet: effectiveWinPath = current win path, militaryPurpose = what military is for, economicPosture = empire posture.
            - Read strategy, decisionFrame, and controlLanes as strategist intent, not a literal script. Fit your choices inside that envelope, but still react to the actual board.
            - Read decisionFocus, mustActNow, campaignContext, objectiveTheater, captureReadiness, and campaignControl as the live tactical cockpit for this turn.
            - If militaryPurpose is deterrence or defense, do not turn mere rival contact into a conquest story unless the brief shows war, emergency defense, or an explicit pivot.
            - If mustActNow includes a project choice, settler founding, research, or policy adoption, resolve that before low-value map polishing unless there is a clearly stronger emergency.
            - City payloads are single-project views, not queues. If a city already has meaningful progress on a sensible build, prefer finishing it over switching.
            - Use select_empire_option only for empireChoices candidateIds.
            - Use select_city_option only for candidateIds surfaced under cityHighlights.actions.
            - Use select_unit_option only for candidateIds surfaced under unitHighlights.unitOptionCandidates, and issue at most one unit option per unit.
            - Unit options are assignment-level, not low-level micro. stage near target city, attack target city, fallback and heal, auto-explore, and hold position can persist if the unit is left alone.
            - assignmentProgress means the unit is already carrying a job. Prefer finishing high-switch-cost assignments unless the board or strategist frame clearly makes a new assignment better.
            - For workers, prefer real surfaced worker jobs over passive sleep/fortify-style posture when a useful worker option exists.
            - If captureReadiness says healthy capture-capable melee are thin, keep healthy melee on the live city package instead of spending them on low-value side actions.
            - If the current packet does not actually support the strategist frame, say so in tacticianReflection.actionSurfaceMismatch instead of pretending it was executable.
            - Prefer short robust plans over brittle ones. If there is a high-value action like founding a city, choosing research, switching a key project, or taking a concrete war step, do not risk the turn on speculative polish.
            - Avoid repeating actions that already failed or produced no state change unless retry context or the current brief makes the fix explicit.
            - strategistRefreshRequest is only for real strategic emergencies: broken memo assumptions, major rival surge, plan collapse, or another genuine phase break.
            - Keep tacticianReflection sparse and delta-focused: what changed, what completed, what is still blocked, what became obsolete, and whether the memo still looks healthy / strained / contradicted.
            - Prefer short legal plans, usually 0-25 commands.
            - If the best move is intentional inaction, return an empty actions list with handoffToLegacyAI=false and explain why in notes.
            - Use handoffToLegacyAI=true only when you explicitly want the legacy AI to take over the turn.
            Memory JSON:
            $memoryJson
            ${if (retryBlock.isNotBlank()) "$retryBlock\n" else ""}
            Planner Brief JSON:
            $plannerBriefJson
        """.trimIndent()
    }

    private fun buildTacticalMemoryPrompt(memory: AgentMemory): AgentTacticalMemoryPrompt {
        return AgentTacticalMemoryPrompt(
            worldModelAnchors = memory.worldModel.anchors.takeLast(6).map(::describeAnchor),
            cityIntents = memory.cityIntents.takeLast(6).map(::describeCityIntent),
            unitAssignments = memory.unitAssignments.takeLast(8).map(::describeUnitAssignment),
            recentFailures = memory.recentFailures.takeLast(6).map { it.summary },
        )
    }

    private fun describeAnchor(anchor: MemoryAnchor): String {
        val civPart = anchor.civName?.let { " ($it)" } ?: ""
        val coordPart = if (anchor.x != null && anchor.y != null) " at (${anchor.x}, ${anchor.y})" else ""
        return "${anchor.kind}: ${anchor.label}$civPart$coordPart"
    }

    private fun describeCityIntent(intent: CityIntentMemory): String {
        val targetPart = intent.target?.let { " -> $it" } ?: ""
        val reasonsPart = intent.reasons.take(2).takeIf { it.isNotEmpty() }?.joinToString(", ")?.let { " [$it]" } ?: ""
        return "${intent.cityName}: ${intent.intent}$targetPart$reasonsPart"
    }

    private fun describeUnitAssignment(assignment: UnitAssignmentMemory): String {
        val targetPart = if (assignment.targetX != null && assignment.targetY != null) {
            " -> (${assignment.targetX}, ${assignment.targetY})"
        } else ""
        val detailPart = assignment.detail?.let { " [$it]" } ?: ""
        return "${assignment.unitName} #${assignment.unitId} (${publicAssignmentRole(assignment.role)}, ${assignment.assignmentSource})$targetPart$detailPart"
    }

    private fun publicAssignmentRole(role: String): String = role
}
