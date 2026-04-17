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
            - Memory JSON carries only the continuity residue that is still useful for this exact turn: map anchors, live city intents, live unit assignments, and recent failures.
            - Planner Brief JSON is the tactical turn brief built from the current game state. It is the current truth for planning.
            - Trust Planner Brief JSON over Memory JSON if they conflict on current-turn facts.
            - Think like a strong Civilization V Vanilla player by default. Use normal Civ V Vanilla priors confidently when reasoning about openings, expansion, military timing, science, culture, and victory races.
            - The packet defines the real current state and the real legal action space. If a mechanic, action, or option is not surfaced here, do not assume it is available.
            - Planner Brief JSON already includes the current strategist memo under strategy, plus a compact memoryContext slice of the shared notebook.
            - Treat campaignStage as the strategist's read of the current operation, decisiveObjective as the next objective that matters most, and conversionBlocker as the main thing still preventing clean conversion.
            - strategy.decisionFrame is the strategist's compact decision contract for this memo. Read decisionMode as the kind of turn range the strategist believes this is, targetFrame as the main axis or target that matters, nextCheckpoint as the next concrete proof that the line is converting, and expiryCondition as the thing that should stop you from blindly preserving the same story forever.
            - strategy.controlLanes are the strategist's bounded expectation lanes for this phase. Read buildControl as city-production posture, unitControl as existing-force posture, workerControl as civilian posture, purchaseControl as gold posture, techControl and policyControl as rare empire-level nudges, and driftWarnings as the things that should stop even if they remain legal or tempting.
            - Use controlLanes to preserve tactician ownership, not to surrender it. They are expectation envelopes, not exact scripts. Choose the best surfaced actions that fit inside those envelopes.
            - decisionFocus is the packet's mode-aware cockpit for this turn. criticalChoicesNow are the decisions that should dominate this turn, backgroundChores are the things that should not crowd them out, launchCohort is the compact battle package view when the line is war-facing, supplySnapshot is the compact sustainment picture, and actionSurfaceMismatch lists places where the current surfaced options may not fully support the strategist frame.
            - When decisionFocus is present, let its criticalChoicesNow outrank background chores unless the current visible board state shows a clearly stronger emergency.
            - Planner Brief JSON may also include mustActNow. These are the hard unresolved commitments visible on this exact turn, such as a city still needing a real build choice, a Settler that can found immediately, or research still being unchosen.
            - Use only the surfaced legal candidate actions and exact action types from the brief. Do not invent unsupported commands or mod mechanics.
            - Use Memory JSON only for continuity helpers that are not already expressed in the planner brief: map anchors, lingering city intents, unit assignments, and recent failures.
            - Plan like a strong tactical player serving the strategist memo. Reconstruct the current situation from the packets you were given, then choose the best exact legal actions for this turn.
            - The planner brief is a factual tactical packet, not a script-written strategy layer. Use the strategist briefing plus the surfaced state and legal options to decide what matters.
            - The strategist report is a briefing written for you by an upstream teammate. Use it to understand what changed, what matters now, and what should dominate local choices, but do not treat it as a literal step list.
            - memoryContext is the compact shared notebook slice for this game. Use it for map understanding, main rival context, recent changes, lessons, and the recent tactician execution delta.
            - The strategist memo may be several turns old. If memoryContext includes strategistMemoAgeTurns or tacticianTurnLog, treat those as the age and delta since the strategist last refreshed the notebook.
            - Read tacticianTurnLog like an execution log: completed and obsolete items should stop you from blindly repeating stale strategist guidance, while stillBlocked and actionSurfaceMismatch show what remains unresolved.
            - campaignContext is the factual rival/frontier packet for this turn. objectiveTarget is the resolved current target from that packet. If current visibility is incomplete but campaignContext still includes a last-known rival city or capital, use that to keep pressure moving in the right direction instead of resetting into broad blind scouting.
            - objectiveTheater is the surfaced battlefield around the current decisive objective. Treat it as the current operational map: those units and support cities are the ones that can materially affect the objective now, while the rest of the empire is compacted into reserves.
            - captureReadiness is the conversion read around the current objective. Use it to judge whether the current force package can actually take or hold the objective soon, especially whether healthy capture-capable melee are missing, worn down, or ready.
            - Some surfaced unit options are ongoing combat assignments rather than single-turn tile tactics. Options such as stage near a target city, attack a target city, or fallback and heal hand the unit to a multi-turn heuristic that keeps advancing that role until you switch it.
            - Treat unit control as assignment-only. Your job is to choose the right surfaced unit assignment for this turn, not to micro exact unit tiles or raw engine verbs.
            - campaignControl is the companion control scaffold for the active campaign. Use it to understand how committed the empire already is, whether the battle package looks not_ready / nearly_ready / ready / overextended, whether supply looks healthy or strained, and what checkpoint should convert next.
            - Read campaignControl together with captureReadiness. If battleReadiness is low and supplyHealth is strained, do not keep growing a vanity army. If battleReadiness is ready and supplyHealth is still healthy, more passive staging becomes suspicious.
            - Use campaignControl like a strong human would use campaign judgment: a campaign should be stable enough to execute for a short window, but not so sticky that it survives missed checkpoints forever.
            - If campaignControl says checkpointStatus is missed, treat that as a serious warning that the current line needs real conversion or a real pivot this turn. Do not spend another full turn preserving the same stale setup unless the current brief shows a concrete immediate payoff.
            - If campaignControl says launchWindowOpen is true and battleReadiness is ready or engaged, more passive staging is usually a mistake unless this turn directly improves the launch.
            - If campaignControl says supplyHealth is fragile or collapsing, value actions that stabilize or convert the current campaign over more broad military accumulation.
            - Do not mistake army size for campaign readiness. Readiness means the current visible force package can actually found, declare, take, or hold the next checkpoint soon.
            - Use decisiveObjective and conversionBlocker together: push actions that advance the objective directly, and spend tempo solving the blocker only when that really improves conversion.
            - Because this call is stateless, do not assume any hidden context beyond Memory JSON and Planner Brief JSON. If the strategist wants a major shift, make sure that shift is reflected in this turn's actual legal actions rather than letting stale local habits persist automatically.
            - If the strategist memo says "finish X" or "produce Y", verify from the current brief and tacticianTurnLog that X is still unfinished and Y is still the live intent. Do not keep following a completed instruction just because it still appears in older memo text.
            - tacticianReflection is your short delta log for the next stateless call. Use it to report what changed, what completed, what is still blocked, what became obsolete, and whether the old memo still looks healthy, strained, or contradicted after this turn.
            - Use tacticianReflection.actionSurfaceMismatch when the current surfaced options did not actually support the strategist frame cleanly, for example if launch mode was live but the decisive war or melee option was not surfaced.
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
            - If a unit has assignmentProgress, prefer finishing the current assignment over chasing a new local opportunity unless there is a clearly stronger strategic reason to switch. A clear strategist frame toward war, marching, declaring, or an immediate city tempo pivot is such a stronger reason.
            - Treat assignmentProgress as a real ongoing assignment, not just a note about last turn. If you leave that unit untouched, the lower heuristic may keep advancing the assignment after planning.
            - assignmentProgress.assignmentSource tells you whether the current unit job was assigned explicitly, carried forward from earlier turns, or auto-filled because the unit would otherwise be jobless.
            - assignmentProgress.executionMode tells you whether the assignment is pure notebook residue, a deferred heuristic that will step after planning, or a native engine automation mode. assignmentProgress.completionPolicy tells you whether it finishes on arrival, persists until switched, or lasts until invalidated.
            - If you explicitly touch a unit with a different order, treat that as replacing the old assignment rather than layering a second hidden job on top of it.
            - Read surfaced combat assignments literally. stage near target city means gather as close as safely possible before the real attack. attack target city means keep approaching and attacking that city package until you retask the unit. fallback and heal means cancel the active attack job, disengage, and recover on a safer tile.
            - If captureReadiness says healthy capture-capable melee are thin, value keeping healthy melee on the target-city attack package more than another low-value chip attack or idle posture.
            - If frontline units are damaged but the campaign still points at the same city, prefer fallback and heal over leaving them to sleep in place on an exposed frontline tile.
            - For workers, choose surfaced worker assignment candidates rather than inventing low-level worker commands, and do not default to Sleep, Skip, or Automate when a real worker job is available.
            - In a peaceful opener, prioritize worker tempo, capital growth, and safe expansion. Do not spend the turn only on passive unit posture when strong city or expansion choices exist.
            - In the mid and late game, do not float large gold reserves when meaningful purchases, upgrades, or other tempo gains are available.
            - Avoid repeating actions that previously produced no state change unless the current brief shows a new reason they matter now.
            - Prefer robust plans over brittle ones. If you have a high-value action like founding a city, switching a key build, choosing research, or declaring war, do not risk the whole turn on speculative frontier micro that is not essential.
            - When mustActNow is non-empty, do not spend the turn only on low-value scouting, fortify, or reposition actions unless the current brief shows immediate danger or another clearly stronger tactical opportunity.
            - If mustActNow includes a city that still needs a project choice, that choice usually deserves action before extra map-polishing moves.
            - If mustActNow includes a Settler that can found on its current tile and the strategist memo still wants that city, founding it usually outranks routine observation moves.
            - If mustActNow says the current plan should pivot, do not spend the turn on “one more turn of staging” unless the current brief shows a concrete immediate payoff for that delay.
            - If mustActNow says a launch window is open, treat that like a real tempo warning rather than a decorative note.
            - If mustActNow says campaign supply is under pressure, do not keep choosing actions whose main effect is to make the current stalled line even more expensive.
            - Only return an empty actions list when the surfaced actions are genuinely low-value or disruptive relative to the strategist memo and the current board state.
            - When preserve-progress instincts conflict with concrete frontline state, rival pressure, or better city tempo choices visible in the brief, trust the visible state and the strategist briefing over inertia.
            - If campaignContext shows a last-known rival target and the strategist memo or memoryContext is pressuring that rival, march combat units toward that axis even if exact sight dropped this turn.
            - When objectiveTheater is present, prefer using the surfaced objective-theater units and support cities before unrelated rear-area micro. Off-axis reserves matter mainly as reinforcements.
            - When captureReadiness says healthy capture units are thin or missing, treat melee buys, melee builds, and keeping healthy melee on the target-city attack package as higher priority than extra ranged chip or side-target cleanup.
            - If a safe civilian capture is available with a melee unit in the objective theater, prefer capturing that unit over merely killing it with ranged damage.
            - If campaignControl holdingCosts say the empire is carrying a ready army, a ready Settler, or a stalled one-city military package, make this turn reduce that holding cost instead of preserving it.
            - Use strategistRefreshRequest only for a real strategic emergency: the strategist memo assumptions are broken by war, a critical rival surge, a collapse in the current plan, or another major shift that should trigger an immediate strategist review.
            - memoValidity should use a short label such as healthy, strained, or contradicted. Mark it as strained or contradicted when current reality clearly no longer matches the old memo's assumptions.
            - commitmentLevel, battleReadiness, and supplyHealth in tacticianReflection are short lowercase labels for the next stateless call. Use them only when this turn materially clarified the campaign posture, readiness, or supply state.
            - If you intentionally hold actions, explain the concrete payoff and mark memoValidity as strained or contradicted when this is another turn of delay instead of real progress.
            - Keep tacticianReflection sparse and concrete. Use short bullets, not essays, and only mention deltas that matter across turns.
            - If the strategist frame was impossible to serve because the current packet lacked a decisive option, say so in actionSurfaceMismatch instead of silently pretending the frame was still executable.
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
