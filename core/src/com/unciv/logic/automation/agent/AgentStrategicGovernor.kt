package com.unciv.logic.automation.agent

object AgentStrategicGovernor {
    fun buildPlannerBrief(
        memory: AgentMemory,
        observation: AgentObservation,
        empireObservation: AgentEmpireObservation,
    ): AgentPlannerBrief {
        val gameContext = empireObservation.gameContext
        val primaryThreat = empireObservation.victoryThreats.firstOrNull()
        val memo = memory.lastStrategistMemo.takeIf { it.campaignStage.isNotBlank() }
        val campaignControl = memory.campaignControl.toObservation(observation.turn)
        val attentionFacts = buildAttentionFacts(observation, empireObservation, campaignControl)
        val mustActNow = buildMustActNow(observation, empireObservation, campaignControl)
        val campaignContext = buildCampaignContext(memory, observation, empireObservation)
        val cityHighlights = selectCityHighlights(observation, gameContext, campaignContext)
        val memoryContext = buildMemoryContext(memory, observation, empireObservation)
        val unitSurfacing = selectUnitHighlights(observation, gameContext, primaryThreat, campaignContext)
        val unitHighlights = unitSurfacing.units
        val captureReadiness = buildCaptureReadiness(observation, campaignContext)
        val progressInMotion = buildProgressInMotion(observation, empireObservation, cityHighlights, unitHighlights, campaignContext)
        val threatHighlights = observation.visibleThreatsAndTargets.take(if (observation.empireSummary.isAtWar) 3 else 2)
        val strategyObservation = AgentPlannerStrategyObservation(
            gameArchetype = memo?.gameArchetype?.ifBlank { null } ?: gameContext.archetype,
            winPath = memo?.winPath ?: empireObservation.victoryGoal,
            campaignStage = memo?.campaignStage ?: defaultCampaignStage(memory, observation, empireObservation, campaignContext),
            decisiveObjective = memo?.decisiveObjective ?: memory.campaign.decisiveObjective,
            conversionBlocker = memo?.conversionBlocker ?: memory.campaign.conversionBlocker,
            decisionFrame = memo?.decisionFrame?.takeUnless { it.isEmpty() },
            thesis = memo?.thesis,
            pastSummary = memo?.pastSummary,
            currentSituation = memo?.currentSituation,
            futurePlan = memo?.futurePlan,
            tacticianHandoff = memo?.tacticianHandoff ?: memo?.futurePlan,
        )
        val decisionFocus = buildDecisionFocus(
            observation = observation,
            empireObservation = empireObservation,
            strategy = strategyObservation,
            campaignControl = campaignControl,
            campaignContext = campaignContext,
            objectiveTheater = unitSurfacing.objectiveTheater,
            captureReadiness = captureReadiness,
            mustActNow = mustActNow,
            cityHighlights = cityHighlights,
            unitHighlights = unitHighlights,
        )

        val workerFacts = observation.priorityFacts.count { isWorkerFact(it) }
        val suppressedContext = buildList {
            val hiddenCities = observation.cities.size - cityHighlights.size
            val hiddenUnits = observation.units.size - unitHighlights.size
            if (hiddenCities > 0) add("$hiddenCities lower-priority city cards were hidden after strategic ranking.")
            unitSurfacing.suppressedNotes.forEach(::add)
            if (hiddenUnits > 0 && unitSurfacing.suppressedNotes.none { it.contains("unit", ignoreCase = true) }) {
                add("$hiddenUnits lower-priority unit cards were hidden after strategic ranking.")
            }
            if (workerFacts > 2 && workerFacts > unitHighlights.count { it.role == "worker" }) {
                add("${workerFacts - unitHighlights.count { it.role == "worker" }} worker facts were collapsed so broader strategy can dominate.")
            }
        }

        return AgentPlannerBrief(
            gameContext = gameContext,
            strategy = strategyObservation,
            campaignControl = campaignControl,
            decisionFocus = decisionFocus,
            memoryContext = memoryContext,
            campaignContext = campaignContext,
            objectiveTheater = unitSurfacing.objectiveTheater,
            captureReadiness = captureReadiness,
            mustActNow = mustActNow,
            attentionFacts = attentionFacts,
            progressInMotion = progressInMotion,
            empireChoices = AgentPlannerEmpireChoicesObservation(
                researchChoices = empireObservation.researchCandidates.take(if (empireObservation.currentResearch == null || empireObservation.freeTechs > 0) 3 else 2),
                policyChoices = empireObservation.policyCandidates.take(2),
                macroChoices = empireObservation.macroCandidates.take(2),
                diplomacyChoices = empireObservation.diplomacyCandidates.take(if (gameContext.contactComplete && gameContext.duelLike) 1 else 2),
            ),
            cityHighlights = cityHighlights,
            unitHighlights = unitHighlights,
            threatHighlights = threatHighlights,
            suppressedContext = suppressedContext,
        )
    }

    private fun buildDecisionFocus(
        observation: AgentObservation,
        empireObservation: AgentEmpireObservation,
        strategy: AgentPlannerStrategyObservation,
        campaignControl: AgentCampaignControlObservation?,
        campaignContext: AgentPlannerCampaignContextObservation?,
        objectiveTheater: AgentPlannerObjectiveTheaterObservation?,
        captureReadiness: AgentPlannerCaptureReadinessObservation?,
        mustActNow: List<AgentPlannerMustActObservation>,
        cityHighlights: List<AgentCityObservation>,
        unitHighlights: List<AgentUnitObservation>,
    ): AgentPlannerDecisionFocusObservation? {
        val explicitFrame = strategy.decisionFrame?.takeUnless { it.isEmpty() }
        val mode = explicitFrame?.decisionMode
            ?: inferDecisionMode(strategy.campaignStage, campaignControl, campaignContext)
        if (mode.isBlank()) return null

        return AgentPlannerDecisionFocusObservation(
            mode = mode,
            targetFrame = explicitFrame?.targetFrame ?: campaignContext?.objectiveTarget?.name,
            whyNow = explicitFrame?.whyNow ?: strategy.currentSituation ?: strategy.thesis,
            nextCheckpoint = explicitFrame?.nextCheckpoint
                ?: campaignControl?.nextCheckpointSummary
                ?: strategy.futurePlan,
            expiryCondition = explicitFrame?.expiryCondition
                ?: campaignControl?.pivotTriggers?.firstOrNull()
                ?: strategy.conversionBlocker,
            criticalChoicesNow = buildDecisionPriorities(
                mode = mode,
                strategy = strategy,
                campaignControl = campaignControl,
                campaignContext = campaignContext,
                objectiveTheater = objectiveTheater,
                captureReadiness = captureReadiness,
                mustActNow = mustActNow,
                cityHighlights = cityHighlights,
            ).take(5),
            backgroundChores = buildBackgroundChores(
                mode = mode,
                campaignContext = campaignContext,
                captureReadiness = captureReadiness,
            ).take(4),
            actionSurfaceMismatch = buildActionSurfaceMismatch(
                mode = mode,
                observation = observation,
                campaignContext = campaignContext,
                captureReadiness = captureReadiness,
                objectiveTheater = objectiveTheater,
                cityHighlights = cityHighlights,
                unitHighlights = unitHighlights,
            ).take(4),
            launchCohort = buildLaunchCohort(mode, campaignContext, objectiveTheater, captureReadiness),
            supplySnapshot = buildSupplySnapshot(observation, empireObservation, campaignControl),
        )
    }

    private fun buildMemoryContext(
        memory: AgentMemory,
        observation: AgentObservation,
        empireObservation: AgentEmpireObservation,
    ): AgentPlannerMemoryContextObservation? {
        val primaryRivalCiv = memory.campaign.primaryRivalCiv
            ?: empireObservation.victoryThreats.firstOrNull()?.civName
            ?: observation.visibleThreatsAndTargets.firstOrNull { it.civName != observation.civName }?.civName
        if (
            memory.worldModel.summary.isNullOrBlank() &&
            memory.worldModel.notes.isEmpty() &&
            memory.campaign.title.isBlank() &&
            memory.campaign.stage.isBlank() &&
            memory.campaign.decisiveObjective.isNullOrBlank() &&
            memory.campaign.conversionBlocker.isNullOrBlank() &&
            memory.campaign.summary.isNullOrBlank() &&
            memory.empirePlan.summary.isNullOrBlank() &&
            memory.empirePlan.purchaseIntent.isNullOrBlank() &&
            memory.recentChanges.isEmpty() &&
            memory.lessons.isEmpty() &&
            memory.tacticianTurnLog.isEmpty()
        ) return null

        return AgentPlannerMemoryContextObservation(
            strategistMemoLastReviewedTurn = memory.lastStrategistMemo.lastReviewedTurn.takeIf { it > 0 },
            strategistMemoAgeTurns = memory.lastStrategistMemo.lastReviewedTurn
                .takeIf { it > 0 }
                ?.let { observation.turn - it },
            worldModelSummary = memory.worldModel.summary,
            worldModelNotes = memory.worldModel.notes.takeLast(3).map { it.text },
            mainRivalCiv = primaryRivalCiv,
            campaignTitle = memory.campaign.title.takeIf { it.isNotBlank() },
            campaignStage = memory.campaign.stage.takeIf { it.isNotBlank() },
            decisiveObjective = memory.campaign.decisiveObjective,
            conversionBlocker = memory.campaign.conversionBlocker,
            campaignSummary = memory.campaign.summary,
            reinforcementPlan = memory.campaign.reinforcementPlan,
            campaignDoNotDo = memory.campaign.doNotDo.take(4),
            empirePlanSummary = memory.empirePlan.summary,
            purchaseIntent = memory.empirePlan.purchaseIntent,
            recentChanges = memory.recentChanges.takeLast(4).map { it.text },
            lessons = memory.lessons.takeLast(4).map { it.text },
            tacticianTurnLog = memory.tacticianTurnLog.takeLast(4).map { entry ->
                AgentPlannerTacticianTurnLogObservation(
                    turn = entry.turn,
                    basedOnStrategistTurn = entry.basedOnStrategistTurn,
                    summary = entry.summary,
                    completed = entry.completed,
                    stillBlocked = entry.stillBlocked,
                    obsolete = entry.obsolete,
                    carryForward = entry.carryForward,
                    actionSurfaceMismatch = entry.actionSurfaceMismatch,
                    memoValidity = entry.memoValidity,
                    commitmentLevel = entry.commitmentLevel,
                    battleReadiness = entry.battleReadiness,
                    supplyHealth = entry.supplyHealth,
                )
            },
        )
    }

    private fun buildMustActNow(
        observation: AgentObservation,
        empireObservation: AgentEmpireObservation,
        campaignControl: AgentCampaignControlObservation?,
    ): List<AgentPlannerMustActObservation> {
        val items = arrayListOf<AgentPlannerMustActObservation>()

        val citiesNeedingChoice = observation.cities
            .filter { it.project?.status == "needs_choice" || it.project == null }
        if (citiesNeedingChoice.isNotEmpty()) {
            items += AgentPlannerMustActObservation(
                kind = "city_project_choice",
                headline = "${citiesNeedingChoice.size} cities still need an explicit project choice",
                detail = citiesNeedingChoice.take(3).joinToString(", ") { city ->
                    "${city.name}${city.project?.name?.let { " ($it placeholder)" } ?: ""}"
                },
            )
        }

        val settlersReadyToFound = observation.units
            .filter { unit ->
                unit.role == "settler" &&
                    (unit.unitActions.contains("FoundCity") ||
                        unit.unitOptionCandidates.any { candidate ->
                            candidate.candidateId.startsWith("unitsettle:") &&
                                candidate.title.contains("found city here", ignoreCase = true)
                        })
            }
        if (settlersReadyToFound.isNotEmpty()) {
            items += AgentPlannerMustActObservation(
                kind = "settler_can_found_now",
                headline = "${settlersReadyToFound.size} settler units can found immediately",
                detail = settlersReadyToFound.take(2).joinToString(", ") { unit ->
                    "${unit.name} #${unit.id} at (${unit.x}, ${unit.y})"
                },
            )
        }

        if (empireObservation.currentResearch == null && empireObservation.researchCandidates.isNotEmpty()) {
            items += AgentPlannerMustActObservation(
                kind = "research_choice_missing",
                headline = "Research choice is still unresolved",
                detail = empireObservation.researchCandidates.take(3).joinToString(", ") { it.title },
            )
        }

        campaignControl?.let { liveCampaignControl ->
            if (liveCampaignControl.checkpointStatus.equals("missed", ignoreCase = true)) {
                items += AgentPlannerMustActObservation(
                    kind = "campaign_checkpoint_missed",
                    headline = "The current campaign missed its checkpoint",
                    detail = liveCampaignControl.pivotTriggers.firstOrNull()
                        ?: liveCampaignControl.nextCheckpointSummary
                        ?: "The old line should either convert now or pivot into a new live plan.",
                )
            } else if (
                liveCampaignControl.launchWindowOpen &&
                !observation.empireSummary.isAtWar &&
                liveCampaignControl.battleReadiness in setOf("ready", "engaged")
            ) {
                items += AgentPlannerMustActObservation(
                    kind = "launch_window_open",
                    headline = "The current campaign looks launchable now",
                    detail = liveCampaignControl.holdingCosts.firstOrNull()
                        ?: liveCampaignControl.nextCheckpointSummary
                        ?: "More passive staging is now suspicious unless it pays off immediately.",
                )
            }

            if (liveCampaignControl.supplyHealth in setOf("fragile", "collapsing")) {
                items += AgentPlannerMustActObservation(
                    kind = "campaign_supply_pressure",
                    headline = "Campaign supply is under real pressure",
                    detail = liveCampaignControl.holdingCosts.firstOrNull()
                        ?: "The empire cannot keep paying for a stalled line indefinitely.",
                )
            }
        }

        return items
    }

    private fun buildDecisionPriorities(
        mode: String,
        strategy: AgentPlannerStrategyObservation,
        campaignControl: AgentCampaignControlObservation?,
        campaignContext: AgentPlannerCampaignContextObservation?,
        objectiveTheater: AgentPlannerObjectiveTheaterObservation?,
        captureReadiness: AgentPlannerCaptureReadinessObservation?,
        mustActNow: List<AgentPlannerMustActObservation>,
        cityHighlights: List<AgentCityObservation>,
    ): List<AgentPlannerDecisionPriorityObservation> {
        val priorities = arrayListOf<AgentPlannerDecisionPriorityObservation>()
        mustActNow.take(2).forEach { item ->
            priorities += AgentPlannerDecisionPriorityObservation(
                kind = item.kind,
                headline = item.headline,
                detail = item.detail,
            )
        }

        when (mode) {
            "launch_now", "assault" -> {
                if (campaignContext?.warChoiceAvailable == true && !campaignContext.atWar) {
                    priorities += AgentPlannerDecisionPriorityObservation(
                        kind = "war_declaration",
                        headline = "War declaration is a critical live choice",
                        detail = "The strategist frame says this line should convert now, and a legal war declaration is surfaced this turn.",
                    )
                }
                captureReadiness?.let { readiness ->
                    priorities += AgentPlannerDecisionPriorityObservation(
                        kind = "launch_package",
                        headline = "Judge the current launch package against ${readiness.target.name}",
                        detail = readiness.summary,
                    )
                }
                if (objectiveTheater != null && objectiveTheater.supportCities.isNotEmpty()) {
                    priorities += AgentPlannerDecisionPriorityObservation(
                        kind = "frontline_support",
                        headline = "Support cities should reinforce the live assault axis",
                        detail = objectiveTheater.supportCities.joinToString(", ") + " are the closest support cities to the current target.",
                    )
                }
            }
            "stage_briefly" -> {
                priorities += AgentPlannerDecisionPriorityObservation(
                    kind = "checkpoint",
                    headline = "Keep staging tied to one short checkpoint",
                    detail = campaignControl?.nextCheckpointSummary
                        ?: strategy.futurePlan
                        ?: "Use this turn to complete the missing piece that makes the line truly launchable.",
                )
                strategy.conversionBlocker?.let { blocker ->
                    priorities += AgentPlannerDecisionPriorityObservation(
                        kind = "blocker",
                        headline = "The main blocker should dominate staging choices",
                        detail = blocker,
                    )
                }
            }
            "pivot_recover", "stabilize" -> {
                priorities += AgentPlannerDecisionPriorityObservation(
                    kind = "recovery",
                    headline = "Recovery is the live campaign, not more drift",
                    detail = campaignControl?.holdingCosts?.firstOrNull()
                        ?: "Use this turn to reduce the cost of the stalled line and restore real tempo.",
                )
                if (cityHighlights.any { city -> city.project?.status == "needs_choice" || city.project == null }) {
                    priorities += AgentPlannerDecisionPriorityObservation(
                        kind = "city_retool",
                        headline = "City choices should match recovery mode",
                        detail = "Re-evaluate city builds through the recovery lens instead of preserving a stale campaign by inertia.",
                    )
                }
            }
            "expand" -> {
                priorities += AgentPlannerDecisionPriorityObservation(
                    kind = "expansion",
                    headline = "Expansion tempo should stay clearer than passive posture",
                    detail = campaignControl?.nextCheckpointSummary
                        ?: strategy.futurePlan
                        ?: "Serve the live expansion checkpoint before polishing secondary chores.",
                )
            }
        }

        if ((captureReadiness?.workerCaptureOpportunities ?: 0) > 0 && mode in setOf("launch_now", "assault", "stage_briefly")) {
            priorities += AgentPlannerDecisionPriorityObservation(
                kind = "civilian_capture_window",
                headline = "A safe civilian capture window is part of the live battle picture",
                detail = "${captureReadiness?.workerCaptureOpportunities} capture opportunities are surfaced near ${captureReadiness?.target?.name}.",
            )
        }

        return priorities.distinctBy { "${it.kind}|${it.headline}" }
    }

    private fun buildBackgroundChores(
        mode: String,
        campaignContext: AgentPlannerCampaignContextObservation?,
        captureReadiness: AgentPlannerCaptureReadinessObservation?,
    ): List<String> {
        return buildList {
            when (mode) {
                "launch_now", "assault" -> {
                    add("Rear-area exploration, extra focus tweaks, and low-value fortify loops are background unless they directly support the launch.")
                    add("Do not let passive city housekeeping crowd out declaration, marching, or frontline reinforcement.")
                }
                "stage_briefly" -> {
                    add("Use this turn to close the short staging checkpoint, not to reopen broad scouting or city-polish drift.")
                    add("Background micro should stay subordinate to the missing launch piece.")
                }
                "pivot_recover", "stabilize" -> {
                    add("Speculative assault prep is background unless it directly improves supply or removes immediate danger.")
                    add("Extra military growth is background when the active story is recovery.")
                }
                "expand" -> {
                    add("Passive military posture and non-critical rear-area micro are background while expansion tempo is still live.")
                }
            }
            if (campaignContext?.objectiveTarget == null && mode in setOf("launch_now", "assault")) {
                add("Do not burn the turn on fake assault polish when the current target picture is incomplete.")
            }
            if (captureReadiness?.status == "thin_capture_line") {
                add("Extra ranged chip and side cleanup are background if capture capability is still thin.")
            }
        }
    }

    private fun buildActionSurfaceMismatch(
        mode: String,
        observation: AgentObservation,
        campaignContext: AgentPlannerCampaignContextObservation?,
        captureReadiness: AgentPlannerCaptureReadinessObservation?,
        objectiveTheater: AgentPlannerObjectiveTheaterObservation?,
        cityHighlights: List<AgentCityObservation>,
        unitHighlights: List<AgentUnitObservation>,
    ): List<String> {
        return buildList {
            if (mode in setOf("launch_now", "assault")) {
                if (campaignContext?.objectiveTarget == null) {
                    add("The strategist frame says this is a launch/assault turn, but no concrete objective target is resolved in the packet.")
                }
                if (campaignContext?.warChoiceAvailable != true && campaignContext?.atWar != true) {
                    add("The strategist frame says launch/assault, but no declare-war option is currently surfaced.")
                }
                if (captureReadiness != null && captureReadiness.healthyCaptureUnits <= 0) {
                    add("Launch mode is active, but the current packet still shows no healthy capture-capable units near the objective.")
                }
                if (objectiveTheater != null && objectiveTheater.surfacedCombatUnits <= 0) {
                    add("Launch mode is active, but the surfaced objective theater does not currently expose a real combat cohort.")
                }
            }

            if (mode in setOf("launch_now", "assault", "stage_briefly") && (captureReadiness?.healthyCaptureUnits ?: 0) <= 1) {
                val hasFrontlineBuild = cityHighlights.any { city ->
                    city.actions.chooseProject.any { option -> "frontline" in option.yieldHints } ||
                        city.actions.purchase.any { option -> "frontline" in option.yieldHints }
                }
                if (!hasFrontlineBuild) {
                    add("The current packet says capture capability is thin, but no surfaced city option advertises frontline reinforcement.")
                }
            }

            if (mode == "expand" && observation.empireSummary.settlersReady > 0) {
                val settlerFoundSurfaced = unitHighlights.any { unit ->
                    unit.unitOptionCandidates.any { candidate ->
                        candidate.candidateId.startsWith("unitsettle:") &&
                            candidate.title.contains("found city here", ignoreCase = true)
                    }
                }
                if (!settlerFoundSurfaced) {
                    add("Expansion mode is active and a settler is ready, but the highlighted unit packet does not show an immediate found-city option.")
                }
            }
        }.distinct()
    }

    private fun buildLaunchCohort(
        mode: String,
        campaignContext: AgentPlannerCampaignContextObservation?,
        objectiveTheater: AgentPlannerObjectiveTheaterObservation?,
        captureReadiness: AgentPlannerCaptureReadinessObservation?,
    ): AgentPlannerLaunchCohortObservation? {
        if (mode !in setOf("launch_now", "assault", "stage_briefly")) return null
        if (campaignContext == null && objectiveTheater == null && captureReadiness == null) return null
        val target = captureReadiness?.target ?: campaignContext?.objectiveTarget
        return AgentPlannerLaunchCohortObservation(
            target = target,
            warState = when {
                campaignContext?.atWar == true -> "at_war"
                campaignContext?.warChoiceAvailable == true -> "war_choice_available"
                else -> "not_yet_available"
            },
            healthyCaptureUnits = captureReadiness?.healthyCaptureUnits ?: 0,
            damagedCaptureUnits = captureReadiness?.damagedCaptureUnits ?: 0,
            rangedSupportUnits = captureReadiness?.rangedSupportUnits ?: 0,
            surfacedMeleeUnits = objectiveTheater?.surfacedMeleeUnits ?: 0,
            surfacedRangedUnits = objectiveTheater?.surfacedRangedUnits ?: 0,
            supportCities = objectiveTheater?.supportCities ?: emptyList(),
            summary = when {
                captureReadiness != null -> captureReadiness.summary
                objectiveTheater != null -> "${objectiveTheater.surfacedCombatUnits} surfaced combat units are tied to the objective theater."
                else -> "The packet is in a war-facing mode, but the launch cohort picture is still sparse."
            },
        )
    }

    private fun buildSupplySnapshot(
        observation: AgentObservation,
        empireObservation: AgentEmpireObservation,
        campaignControl: AgentCampaignControlObservation?,
    ): AgentPlannerSupplySnapshotObservation {
        val summary = buildString {
            append("${observation.empireSummary.cityCount} cities, ")
            append("${observation.empireSummary.militaryUnitCount} military units, ")
            append("${observation.empireSummary.gold} gold, ")
            append("${observation.empireSummary.happiness} happiness, ")
            append("${observation.empireSummary.sciencePerTurn} science/turn")
            campaignControl?.supplyHealth?.let { append(" • supply $it") }
        }
        return AgentPlannerSupplySnapshotObservation(
            gold = observation.empireSummary.gold,
            happiness = observation.empireSummary.happiness,
            sciencePerTurn = observation.empireSummary.sciencePerTurn,
            cityCount = observation.empireSummary.cityCount,
            militaryUnitCount = observation.empireSummary.militaryUnitCount,
            supplyHealth = campaignControl?.supplyHealth,
            holdingCosts = campaignControl?.holdingCosts ?: emptyList(),
            summary = summary,
        )
    }

    internal fun buildProgressSummary(
        memory: AgentMemory,
        observation: AgentObservation,
        empireObservation: AgentEmpireObservation,
    ): List<AgentPlannerProgressObservation> {
        val campaignContext = buildCampaignContext(memory, observation, empireObservation)
        val cityHighlights = selectCityHighlights(observation, empireObservation.gameContext, campaignContext)
        val unitHighlights = selectUnitHighlights(
            observation,
            empireObservation.gameContext,
            empireObservation.victoryThreats.firstOrNull(),
            campaignContext,
        ).units
        return buildProgressInMotion(observation, empireObservation, cityHighlights, unitHighlights, campaignContext)
    }

    private fun buildAttentionFacts(
        observation: AgentObservation,
        empireObservation: AgentEmpireObservation,
        campaignControl: AgentCampaignControlObservation?,
    ): List<ObservationFact> {
        val facts = linkedMapOf<String, ObservationFact>()
        fun addFact(fact: ObservationFact) {
            facts.putIfAbsent("${fact.category}|${fact.headline}", fact)
        }

        campaignControl?.let { liveCampaignControl ->
            when {
                liveCampaignControl.checkpointStatus.equals("missed", ignoreCase = true) -> addFact(
                    ObservationFact(
                        category = "campaign",
                        severity = "warning",
                        headline = "Current campaign missed its checkpoint",
                        detail = liveCampaignControl.pivotTriggers.firstOrNull()
                            ?: liveCampaignControl.nextCheckpointSummary
                            ?: "The current line should be converted or rewritten now.",
                    )
                )
                liveCampaignControl.launchWindowOpen &&
                    !observation.empireSummary.isAtWar &&
                    liveCampaignControl.battleReadiness in setOf("ready", "engaged") -> addFact(
                    ObservationFact(
                        category = "campaign",
                        severity = "warning",
                        headline = "A launch window is open",
                        detail = liveCampaignControl.holdingCosts.firstOrNull()
                            ?: "The force package already looks launchable, so more passive staging is suspicious.",
                    )
                )
            }

            when (liveCampaignControl.supplyHealth) {
                "collapsing" -> addFact(
                    ObservationFact(
                        category = "campaign",
                        severity = "warning",
                        headline = "Campaign supply is collapsing",
                        detail = liveCampaignControl.holdingCosts.firstOrNull()
                            ?: "Treasury, happiness, or science strain is now severe enough to threaten the line.",
                    )
                )
                "fragile", "strained" -> addFact(
                    ObservationFact(
                        category = "campaign",
                        severity = "warning",
                        headline = "Campaign sustainment is under pressure",
                        detail = liveCampaignControl.holdingCosts.firstOrNull()
                            ?: "Waiting or overbuilding is making the campaign more expensive each turn.",
                    )
                )
            }

            liveCampaignControl.pivotTriggers.take(2).forEach { trigger ->
                addFact(
                    ObservationFact(
                        category = "campaign",
                        severity = "warning",
                        headline = "Campaign control warning",
                        detail = trigger,
                    )
                )
            }
        }

        val threatenedCities = observation.cities
            .filter { it.state.nearbyHostileUnits > 0 || it.state.nearbyHostileCities > 0 }
            .sortedByDescending { it.state.nearbyHostileUnits * 10 + it.state.nearbyHostileCities * 15 + if (it.state.isCapital) 5 else 0 }
        if (threatenedCities.isNotEmpty()) {
            val citySummary = threatenedCities.take(3).joinToString(", ") { city ->
                "${city.name} (${city.state.nearbyHostileUnits} units, ${city.state.nearbyHostileCities} cities)"
            }
            addFact(
                ObservationFact(
                    category = "city",
                    severity = if (threatenedCities.any { it.state.isCapital }) "warning" else "info",
                    headline = "${threatenedCities.size} cities have nearby hostiles",
                    detail = citySummary,
                )
            )
        }

        val citiesNeedingChoice = observation.cities.filter { it.project?.status == "needs_choice" || it.project == null }
        if (citiesNeedingChoice.isNotEmpty()) {
            addFact(
                ObservationFact(
                    category = "city",
                    severity = "warning",
                    headline = "${citiesNeedingChoice.size} cities need a project choice",
                    detail = citiesNeedingChoice.take(3).joinToString(", ") { it.name },
                )
            )
        }

        val finishingProjects = observation.cities
            .filter { (it.project?.turnsLeft ?: Int.MAX_VALUE) <= 1 }
        if (finishingProjects.isNotEmpty()) {
            addFact(
                ObservationFact(
                    category = "city",
                    severity = "info",
                    headline = "${finishingProjects.size} city projects finish within 1 turn",
                    detail = finishingProjects.take(3).joinToString(", ") { city ->
                        "${city.name}: ${city.project?.name ?: "No project"}"
                    },
                )
            )
        }

        val exposedFriendlyCivilians = observation.units.filter { unit ->
            unit.role in setOf("worker", "settler", "great_person", "civilian") &&
                (unit.nearbyHostileUnits > 0 || unit.nearbyHostileCities > 0)
        }
        if (exposedFriendlyCivilians.isNotEmpty()) {
            addFact(
                ObservationFact(
                    category = "unit",
                    severity = "warning",
                    headline = "${exposedFriendlyCivilians.size} civilian units are exposed",
                    detail = exposedFriendlyCivilians.take(3).joinToString(", ") { unit ->
                        "${unit.name} #${unit.id} at (${unit.x}, ${unit.y})"
                    },
                )
            )
        }
        return facts.values.toList()
    }

    private fun selectCityHighlights(
        observation: AgentObservation,
        gameContext: AgentPublicGameContextObservation,
        campaignContext: AgentPlannerCampaignContextObservation?,
    ): List<AgentCityObservation> {
        val warLikeContext = isPressureContext(observation, campaignContext)
        val maxCities = when {
            warLikeContext && gameContext.duelLike -> 4
            warLikeContext -> 5
            gameContext.duelLike -> 3
            else -> 4
        }
        val ranked = observation.cities.sortedByDescending { cityScore(it, campaignContext) }
        if (!warLikeContext || campaignContext?.objectiveTarget == null) {
            return ranked.take(maxCities)
        }

        val supportCities = observation.cities
            .sortedBy { axialDistance(it.x, it.y, campaignContext.objectiveTarget.x, campaignContext.objectiveTarget.y) }
            .take(2)
        return (ranked + supportCities)
            .distinctBy { "${it.x},${it.y}" }
            .take(maxCities)
    }

    private fun selectUnitHighlights(
        observation: AgentObservation,
        gameContext: AgentPublicGameContextObservation,
        primaryThreat: AgentVictoryThreatObservation?,
        campaignContext: AgentPlannerCampaignContextObservation?,
    ): UnitSurfacingResult {
        val warLikeContext = isPressureContext(observation, campaignContext)
        val maxUnits = when {
            observation.empireSummary.isAtWar -> 10
            warLikeContext -> 8
            observation.turn >= 150 -> 5
            else -> 4
        }
        val workerCap = when {
            warLikeContext -> 1
            primaryThreat?.threatLevel == "critical" -> 1
            gameContext.contactComplete && observation.turn >= 120 -> 1
            observation.turn >= 80 -> 2
            else -> 3
        }

        val candidateUnits = observation.units
            .filter { it.detailLevel == "expanded" }
            .ifEmpty { observation.units }
        val objective = campaignContext?.objectiveTarget
        if (warLikeContext && objective != null) {
            return selectObjectiveTheaterUnits(
                observation = observation,
                candidateUnits = candidateUnits,
                gameContext = gameContext,
                primaryThreat = primaryThreat,
                campaignContext = campaignContext,
                objective = objective,
                workerCap = workerCap,
            )
        }

        val ranked = candidateUnits.sortedByDescending { unitScore(it, observation, gameContext, primaryThreat, campaignContext) }
        val selected = arrayListOf<AgentUnitObservation>()
        var workerCount = 0
        for (unit in ranked) {
            if (selected.size >= maxUnits) break
            if (unit.role == "worker" && workerCount >= workerCap) continue
            selected += unit
            if (unit.role == "worker") workerCount += 1
        }
        val fallback = if (selected.isEmpty()) ranked.take(maxUnits) else selected
        return UnitSurfacingResult(units = fallback)
    }

    private fun selectObjectiveTheaterUnits(
        observation: AgentObservation,
        candidateUnits: List<AgentUnitObservation>,
        gameContext: AgentPublicGameContextObservation,
        primaryThreat: AgentVictoryThreatObservation?,
        campaignContext: AgentPlannerCampaignContextObservation,
        objective: AgentStrategistTargetReference,
        workerCap: Int,
    ): UnitSurfacingResult {
        val ranked = candidateUnits.sortedByDescending { unitScore(it, observation, gameContext, primaryThreat, campaignContext) }
        val theaterUnits = ranked.filter { isObjectiveTheaterUnit(it, objective, observation.empireSummary.isAtWar) }
        val selected = arrayListOf<AgentUnitObservation>()
        var workerCount = 0

        theaterUnits.forEach { unit ->
            if (unit.role == "worker" && workerCount >= workerCap) return@forEach
            selected += unit
            if (unit.role == "worker") workerCount += 1
        }

        val remaining = ranked.filter { rankedUnit -> selected.none { it.id == rankedUnit.id } }
        val reserveUnits = arrayListOf<AgentUnitObservation>()
        var reserveWorkerCount = workerCount
        for (unit in remaining) {
            if (!shouldSurfaceAsReserve(unit)) continue
            if (reserveUnits.size >= 4) break
            if (unit.role == "worker" && reserveWorkerCount >= workerCap) continue
            reserveUnits += unit
            if (unit.role == "worker") reserveWorkerCount += 1
        }
        selected += reserveUnits

        val theaterCombatUnits = theaterUnits.filter { isCombatRole(it.role) }
        val reserveCombatUnits = remaining.filter { shouldSurfaceAsReserve(it) && isCombatRole(it.role) }
        val supportCities = observation.cities
            .sortedBy { axialDistance(it.x, it.y, objective.x, objective.y) }
            .take(3)
            .map { it.name }

        val objectiveTheater = AgentPlannerObjectiveTheaterObservation(
            target = objective,
            campaignStage = when {
                observation.empireSummary.isAtWar -> "assault"
                campaignContext.warChoiceAvailable -> "staging"
                else -> "pressure"
            },
            surfacedUnits = selected.size,
            surfacedCombatUnits = selected.count { isCombatRole(it.role) },
            surfacedMeleeUnits = selected.count { isMeleeRole(it.role) },
            surfacedRangedUnits = selected.count { isRangedRole(it.role) },
            reserveCombatUnits = reserveCombatUnits.size,
            reserveMeleeUnits = reserveCombatUnits.count { isMeleeRole(it.role) },
            reserveRangedUnits = reserveCombatUnits.count { isRangedRole(it.role) },
            hiddenRearUnits = (observation.units.size - selected.size).coerceAtLeast(0),
            supportCities = supportCities,
        )

        val suppressedNotes = buildList {
            val hiddenRearUnits = (observation.units.size - selected.size).coerceAtLeast(0)
            if (hiddenRearUnits > 0) {
                add("$hiddenRearUnits rear or off-axis units were summarized so the decisive objective theater could stay fully visible.")
            }
            if (reserveCombatUnits.isNotEmpty()) {
                add("${reserveCombatUnits.size} off-axis combat units remain in reserve behind the current objective.")
            }
        }

        return UnitSurfacingResult(
            units = selected.distinctBy { it.id },
            objectiveTheater = objectiveTheater,
            suppressedNotes = suppressedNotes,
        )
    }

    private fun buildProgressInMotion(
        observation: AgentObservation,
        empireObservation: AgentEmpireObservation,
        cityHighlights: List<AgentCityObservation>,
        unitHighlights: List<AgentUnitObservation>,
        campaignContext: AgentPlannerCampaignContextObservation?,
    ): List<AgentPlannerProgressObservation> {
        val warLikeContext = isPressureContext(observation, campaignContext)
        val progress = arrayListOf<AgentPlannerProgressObservation>()
        empireObservation.currentResearch?.let { research ->
            progress += AgentPlannerProgressObservation(
                category = "research",
                label = research,
                detail = buildString {
                    empireObservation.currentResearchProgress?.let { append("$it") }
                    empireObservation.currentResearchCost?.let { cost ->
                        if (isNotEmpty()) append("/")
                        append(cost)
                    }
                    empireObservation.currentResearchTurnsLeft?.let { turns ->
                        if (isNotEmpty()) append(" invested")
                        append(" • $turns turns left")
                    }
                    if (isEmpty()) append(empireObservation.currentResearchStatus ?: "Research in motion")
                },
            )
        }
        cityHighlights
            .mapNotNull { city ->
                city.project?.let { project ->
                    AgentPlannerProgressObservation(
                        category = "city",
                        label = "${city.name}: ${project.name ?: "Needs project"}",
                        detail = project.note,
                    )
                }
            }
            .take(3)
            .forEach { progress += it }
        unitHighlights
            .mapNotNull { unit ->
                unit.assignmentProgress?.let { assignment ->
                    if (warLikeContext && assignment.role in setOf("explore", "reposition")) return@let null
                    AgentPlannerProgressObservation(
                        category = "unit",
                        label = "${unit.name} #${unit.id}",
                        detail = assignment.progressNote,
                    )
                }
            }
            .take(3)
            .forEach { progress += it }
        return progress.take(6)
    }

    private fun cityScore(
        city: AgentCityObservation,
        campaignContext: AgentPlannerCampaignContextObservation?,
    ): Int {
        var score = 100
        score += city.state.nearbyHostileUnits * 20
        score += city.state.nearbyHostileCities * 30
        if (city.state.isCapital) score += 15
        if (city.state.canBombard) score += 5
        if (city.state.turnsToGrowth != null && city.state.turnsToGrowth <= 2) score += 10
        val project = city.project
        if (project != null) {
            score += when (project.switchCost.lowercase()) {
                "high" -> 18
                "medium" -> 10
                else -> 4
            }
            if (project.status in setOf("in_progress", "following_intent", "committed", "nearly_complete")) score += 8
            if ((project.turnsLeft ?: Int.MAX_VALUE) <= 2) score += 10
        }
        val objective = campaignContext?.objectiveTarget
        if (objective != null) {
            val distance = axialDistance(city.x, city.y, objective.x, objective.y)
            score += (32 - distance * 5).coerceAtLeast(0)
            if (campaignContext.atWar || campaignContext.warChoiceAvailable) {
                val hasMilitaryChoice = city.actions.chooseProject.any { action ->
                    action.yieldHints.any { hint -> hint in setOf("military", "frontline", "ranged") }
                } || city.actions.purchase.any { action ->
                    action.yieldHints.any { hint -> hint in setOf("military", "frontline", "ranged") }
                }
                if (hasMilitaryChoice) score += 18
            }
        }
        return score
    }

    private fun buildCaptureReadiness(
        observation: AgentObservation,
        campaignContext: AgentPlannerCampaignContextObservation?,
    ): AgentPlannerCaptureReadinessObservation? {
        campaignContext ?: return null
        val target = campaignContext.objectiveTarget ?: return null
        val visibleTarget = listOfNotNull(campaignContext.visibleCapital, campaignContext.visibleTarget)
            .firstOrNull { it.x == target.x && it.y == target.y }
        val targetKind = when {
            campaignContext.visibleCapital?.x == target.x && campaignContext.visibleCapital.y == target.y -> "capital"
            campaignContext.lastKnownCapital?.x == target.x && campaignContext.lastKnownCapital.y == target.y -> "capital"
            else -> "city"
        }
        val objectiveDistance = if (campaignContext.atWar) 4 else 5
        val healthyCaptureUnits = observation.units.count { unit ->
            isCaptureUnitRole(unit.role) &&
                unit.health >= 70 &&
                axialDistance(unit.x, unit.y, target.x, target.y) <= objectiveDistance
        }
        val damagedCaptureUnits = observation.units.count { unit ->
            isCaptureUnitRole(unit.role) &&
                unit.health < 70 &&
                axialDistance(unit.x, unit.y, target.x, target.y) <= objectiveDistance
        }
        val rangedSupportUnits = observation.units.count { unit ->
            isRangedRole(unit.role) &&
                unit.health >= 60 &&
                axialDistance(unit.x, unit.y, target.x, target.y) <= objectiveDistance + 1
        }
        val workerCaptureOpportunities = observation.units.count { unit ->
            isCaptureUnitRole(unit.role) &&
                unit.hasMovement &&
                axialDistance(unit.x, unit.y, target.x, target.y) <= objectiveDistance + 1 &&
                unit.unitOptionCandidates.any { candidate ->
                    candidate.category == "attack" && (
                        candidate.title.contains("worker", ignoreCase = true) ||
                            candidate.title.contains("settler", ignoreCase = true) ||
                            candidate.title.contains("great", ignoreCase = true)
                        )
                }
        }
        val status = when {
            healthyCaptureUnits == 0 && !campaignContext.atWar -> "assembling_capture_units"
            healthyCaptureUnits == 0 -> "missing_capture_units"
            healthyCaptureUnits == 1 -> "thin_capture_line"
            rangedSupportUnits == 0 -> "missing_ranged_support"
            damagedCaptureUnits > healthyCaptureUnits -> "frontline_worn_down"
            workerCaptureOpportunities > 0 -> "capture_window_open"
            campaignContext.atWar -> "ready_to_convert"
            else -> "developing_pressure"
        }
        val summary = buildString {
            append("$healthyCaptureUnits healthy capture units")
            if (damagedCaptureUnits > 0) append(", $damagedCaptureUnits damaged melee")
            append(", $rangedSupportUnits ranged support")
            if (workerCaptureOpportunities > 0) append(", $workerCaptureOpportunities safe worker capture chances")
            append(" near ${target.name}.")
        }
        return AgentPlannerCaptureReadinessObservation(
            target = target,
            targetKind = targetKind,
            targetVisible = visibleTarget != null,
            targetHealth = visibleTarget?.health ?: target.health,
            targetStrength = visibleTarget?.combatStrength ?: target.combatStrength,
            healthyCaptureUnits = healthyCaptureUnits,
            damagedCaptureUnits = damagedCaptureUnits,
            rangedSupportUnits = rangedSupportUnits,
            workerCaptureOpportunities = workerCaptureOpportunities,
            status = status,
            summary = summary,
        )
    }

    private fun unitScore(
        unit: AgentUnitObservation,
        observation: AgentObservation,
        gameContext: AgentPublicGameContextObservation,
        primaryThreat: AgentVictoryThreatObservation?,
        campaignContext: AgentPlannerCampaignContextObservation?,
    ): Int {
        val warLikeContext = isPressureContext(observation, campaignContext)
        var score = 100
        score += when (unit.role) {
            "settler" -> 90
            "melee", "ranged", "siege", "naval_melee", "naval_ranged" -> if (warLikeContext) 95 else 70
            "worker" -> if (observation.turn >= 120 || primaryThreat?.threatLevel == "critical") 10 else 45
            "scout" -> if (gameContext.contactComplete) if (warLikeContext) -10 else 5 else 40
            else -> 30
        }
        if (!gameContext.contactComplete && unit.hasMovement && unit.unitOptionCandidates.any { it.candidateId.startsWith("unitexplore:") }) {
            score += if (unit.role == "scout") 70 else 40
        }
        if (unit.unitOptionCandidates.any { it.candidateId.startsWith("unitattack:") }) score += 40
        if (unit.nearbyHostileUnits > 0) score += 25
        if (unit.nearbyHostileCities > 0) score += 35
        if (unit.detailLevel == "expanded") score += 20
        val assignment = unit.assignmentProgress
        if (assignment != null) {
            if (warLikeContext && assignment.role in setOf("explore", "reposition")) {
                score -= 25
            } else {
                if (assignment.status == "on_target") score += 20
                if (assignment.switchCost.lowercase() == "high") score += 15
            }
        }
        val objective = campaignContext?.objectiveTarget
        if (warLikeContext && objective != null && unit.role in setOf("melee", "ranged", "siege", "naval_melee", "naval_ranged")) {
            val distance = axialDistance(unit.x, unit.y, objective.x, objective.y)
            score += (40 - distance * 4).coerceAtLeast(0)
            if (unit.hasMovement) score += 10
        }
        if (unit.hasMovement && unit.unitActions.any { it == "Explore" } && !gameContext.contactComplete) score += 10
        return score
    }

    private fun buildCampaignContext(
        memory: AgentMemory,
        observation: AgentObservation,
        empireObservation: AgentEmpireObservation,
    ): AgentPlannerCampaignContextObservation? {
        val visibleRivalCities = observation.visibleThreatsAndTargets
            .filter { it.kind == "city" && it.civName != observation.civName }
        val visibleRivalUnits = observation.visibleThreatsAndTargets
            .filter { it.kind == "unit" && it.civName != observation.civName }
        val visibleTarget = visibleRivalCities.minWithOrNull(
            compareBy<VisibleTargetObservation> { it.distanceToClosestUnit ?: Int.MAX_VALUE }
                .thenBy { it.distanceToClosestCity ?: Int.MAX_VALUE }
                .thenBy { it.name }
        )
        val visibleCapital = visibleRivalCities
            .filter { it.facts.any { fact -> fact.equals("Capital", ignoreCase = true) } }
            .minWithOrNull(
                compareBy<VisibleTargetObservation> { it.distanceToClosestUnit ?: Int.MAX_VALUE }
                    .thenBy { it.distanceToClosestCity ?: Int.MAX_VALUE }
                    .thenBy { it.name }
            )
        val primaryRivalCiv = memory.campaign.primaryRivalCiv
            ?: empireObservation.victoryThreats.firstOrNull()?.civName
            ?: visibleTarget?.civName
            ?: visibleCapital?.civName
        val lastKnownTarget = lastKnownAnchor(memory, primaryRivalCiv, setOf("city"))
        val lastKnownCapital = lastKnownAnchor(memory, primaryRivalCiv, setOf("capital"))
        val preferredObjective = preferredObjectiveTarget(
            objectiveText = memory.campaign.decisiveObjective,
            visibleTarget = visibleTarget,
            visibleCapital = visibleCapital,
            lastKnownTarget = lastKnownTarget,
            lastKnownCapital = lastKnownCapital,
        )
        val objectiveSource = when (preferredObjective) {
            visibleCapital?.let(::toTargetReference) -> "visible_capital"
            visibleTarget?.let(::toTargetReference) -> "visible_city"
            lastKnownCapital -> "last_known_capital"
            lastKnownTarget -> "last_known_city"
            else -> null
        }
        val objective = preferredObjective
        val frontlineCombatUnits = objective?.let { target ->
            observation.units.count { unit ->
                unit.role in setOf("melee", "ranged", "siege", "naval_melee", "naval_ranged") &&
                    axialDistance(unit.x, unit.y, target.x, target.y) <= 6
            }
        } ?: 0
        val meleeUnitsNearObjective = objective?.let { target ->
            observation.units.count { unit ->
                unit.role in setOf("melee", "naval_melee") &&
                    axialDistance(unit.x, unit.y, target.x, target.y) <= 5
            }
        } ?: 0
        val rangedUnitsNearObjective = objective?.let { target ->
            observation.units.count { unit ->
                unit.role in setOf("ranged", "siege", "naval_ranged") &&
                    axialDistance(unit.x, unit.y, target.x, target.y) <= 5
            }
        } ?: 0
        val warChoiceAvailable = empireObservation.diplomacyCandidates.any { it.candidateId.startsWith("diplo:war:") }
        if (
            visibleRivalCities.isEmpty() &&
            visibleRivalUnits.isEmpty() &&
            lastKnownTarget == null &&
            lastKnownCapital == null &&
            !warChoiceAvailable &&
            !observation.empireSummary.isAtWar
        ) return null
        return AgentPlannerCampaignContextObservation(
            primaryRivalCiv = primaryRivalCiv,
            atWar = observation.empireSummary.isAtWar,
            warChoiceAvailable = warChoiceAvailable,
            visibleRivalCities = visibleRivalCities.size,
            visibleRivalUnits = visibleRivalUnits.size,
            objectiveTarget = objective,
            objectiveSource = objectiveSource,
            visibleTarget = visibleTarget?.let(::toTargetReference),
            visibleCapital = visibleCapital?.let(::toTargetReference),
            lastKnownTarget = lastKnownTarget,
            lastKnownCapital = lastKnownCapital,
            frontlineFriendlyCombatUnits = frontlineCombatUnits,
            meleeUnitsNearObjective = meleeUnitsNearObjective,
            rangedUnitsNearObjective = rangedUnitsNearObjective,
        )
    }

    private fun lastKnownAnchor(
        memory: AgentMemory,
        rivalCiv: String?,
        kinds: Set<String>,
    ): AgentStrategistTargetReference? {
        val anchor = memory.worldModel.anchors
            .filter { anchor ->
                anchor.kind in kinds &&
                    (rivalCiv == null || anchor.civName == rivalCiv)
            }
            .filter { it.kind in kinds }
            .maxByOrNull { it.lastConfirmedTurn }
            ?: return null
        return AgentStrategistTargetReference(
            civName = anchor.civName ?: rivalCiv ?: return null,
            name = anchor.label,
            x = anchor.x ?: return null,
            y = anchor.y ?: return null,
        )
    }

    private fun toTargetReference(target: VisibleTargetObservation): AgentStrategistTargetReference {
        return AgentStrategistTargetReference(
            civName = target.civName,
            name = target.name,
            x = target.x,
            y = target.y,
            health = target.health,
            combatStrength = target.combatStrength,
            distanceToClosestCity = target.distanceToClosestCity,
            distanceToClosestUnit = target.distanceToClosestUnit,
        )
    }

    private fun isPressureContext(
        observation: AgentObservation,
        campaignContext: AgentPlannerCampaignContextObservation?,
    ): Boolean {
        if (observation.empireSummary.isAtWar) return true
        campaignContext ?: return false
        return campaignContext.warChoiceAvailable ||
            (observation.empireSummary.militaryUnitCount >= 8 && campaignContext.primaryRivalCiv != null) ||
            campaignContext.objectiveTarget != null
    }

    private fun preferredObjectiveTarget(
        objectiveText: String?,
        visibleTarget: VisibleTargetObservation?,
        visibleCapital: VisibleTargetObservation?,
        lastKnownTarget: AgentStrategistTargetReference?,
        lastKnownCapital: AgentStrategistTargetReference?,
    ): AgentStrategistTargetReference? {
        val loweredObjective = objectiveText?.lowercase().orEmpty()
        if ("capital" in loweredObjective) {
            return visibleCapital?.let(::toTargetReference) ?: lastKnownCapital ?: visibleTarget?.let(::toTargetReference) ?: lastKnownTarget
        }
        return visibleTarget?.let(::toTargetReference)
            ?: visibleCapital?.let(::toTargetReference)
            ?: lastKnownTarget
            ?: lastKnownCapital
    }

    private fun isObjectiveTheaterUnit(
        unit: AgentUnitObservation,
        objective: AgentStrategistTargetReference,
        atWar: Boolean,
    ): Boolean {
        val distance = axialDistance(unit.x, unit.y, objective.x, objective.y)
        val theaterRadius = if (atWar) 8 else 6
        if (distance <= theaterRadius && isTheaterRelevantRole(unit.role)) return true
        if (unit.nearbyHostileUnits > 0 || unit.nearbyHostileCities > 0) return true
        val assignment = unit.assignmentProgress
        if (assignment?.targetX != null && assignment.targetY != null) {
            val assignmentDistance = axialDistance(assignment.targetX, assignment.targetY, objective.x, objective.y)
            if (assignmentDistance <= 4 && isTheaterRelevantRole(unit.role)) return true
        }
        return false
    }

    private fun shouldSurfaceAsReserve(unit: AgentUnitObservation): Boolean {
        if (isCombatRole(unit.role)) return true
        return unit.role == "worker" && unit.assignmentProgress != null
    }

    private fun isTheaterRelevantRole(role: String): Boolean {
        return isCombatRole(role) || role == "worker" || role == "settler" || role == "scout"
    }

    private fun isCombatRole(role: String): Boolean {
        return role in setOf("melee", "ranged", "siege", "mounted", "armored", "naval_melee", "naval_ranged")
    }

    private fun isMeleeRole(role: String): Boolean {
        return role in setOf("melee", "mounted", "armored", "naval_melee")
    }

    private fun isRangedRole(role: String): Boolean {
        return role in setOf("ranged", "siege", "naval_ranged")
    }

    private fun isCaptureUnitRole(role: String): Boolean {
        return role in setOf("melee", "mounted", "armored", "naval_melee")
    }

    private fun axialDistance(x1: Int, y1: Int, x2: Int, y2: Int): Int {
        val dx = x1 - x2
        val dy = y1 - y2
        return (kotlin.math.abs(dx) + kotlin.math.abs(dy) + kotlin.math.abs(dx + dy)) / 2
    }

    private fun isWorkerFact(fact: ObservationFact): Boolean {
        return fact.category == "tiles" && (
            fact.headline.startsWith("Worker #") ||
                fact.detail.contains("worker", ignoreCase = true) ||
                fact.detail.contains("improvement", ignoreCase = true)
            )
    }

    private fun defaultCampaignStage(
        memory: AgentMemory,
        observation: AgentObservation,
        empireObservation: AgentEmpireObservation,
        campaignContext: AgentPlannerCampaignContextObservation?,
    ): String {
        if (memory.campaign.stage.isNotBlank()) return memory.campaign.stage
        if (observation.empireSummary.isAtWar) return "assault"
        if (!empireObservation.gameContext.contactComplete) return "scouting"
        if (observation.empireSummary.cityCount < 2) return "expansion"
        if (campaignContext?.warChoiceAvailable == true && hasMeaningfulRivalObjective(campaignContext)) return "staging"
        if (campaignContext != null && hasMeaningfulRivalObjective(campaignContext)) return "pressure"
        return "positioning"
    }

    private fun inferDecisionMode(
        campaignStage: String,
        campaignControl: AgentCampaignControlObservation?,
        campaignContext: AgentPlannerCampaignContextObservation?,
    ): String {
        val commitmentLevel = campaignControl?.commitmentLevel
        if (commitmentLevel == "launch_window" && campaignContext?.warChoiceAvailable == true) return "launch_now"
        if (
            campaignControl?.checkpointStatus.equals("missed", ignoreCase = true) &&
            campaignControl?.supplyHealth in setOf("fragile", "collapsing")
        ) return "pivot_recover"
        return when (campaignStage.lowercase()) {
            "assault" -> "assault"
            "rebuild", "consolidation", "positioning" -> "stabilize"
            "expansion", "scouting" -> "expand"
            else -> "stage_briefly"
        }
    }

    private fun hasMeaningfulRivalObjective(campaignContext: AgentPlannerCampaignContextObservation): Boolean {
        return campaignContext.objectiveTarget != null
    }

    private data class UnitSurfacingResult(
        val units: List<AgentUnitObservation>,
        val objectiveTheater: AgentPlannerObjectiveTheaterObservation? = null,
        val suppressedNotes: List<String> = emptyList(),
    )

}
