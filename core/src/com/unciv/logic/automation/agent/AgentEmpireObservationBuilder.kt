package com.unciv.logic.automation.agent

import com.unciv.Constants
import com.unciv.logic.automation.civilization.UseGoldAutomation
import com.unciv.logic.automation.unit.UnitAutomation
import com.unciv.logic.battle.Battle
import com.unciv.logic.battle.BattleDamage
import com.unciv.logic.battle.CityCombatant
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.battle.TargetHelper
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.Milestone
import com.unciv.models.ruleset.Victory
import com.unciv.models.ruleset.Policy
import com.unciv.models.ruleset.tech.Technology
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.ui.screens.victoryscreen.RankingType
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActionsUpgrade
import kotlin.math.max
import kotlin.math.roundToInt

object AgentEmpireObservationBuilder {
    private const val maxResearchCandidates = 6
    private const val maxPolicyCandidates = 6
    private const val maxVictoryThreats = 2
    private const val maxFreeGreatPersonCandidates = 4

    internal fun build(civInfo: Civilization, memory: AgentMemory): AgentEmpirePlanningContext {
        civInfo.updateStatsForNextTurn()
        civInfo.cities.forEach { it.cityStats.update(updateCivStats = false) }

        val gameContext = buildPublicGameContext(civInfo)
        val researchCandidates = buildResearchCandidates(civInfo)
        val policyCandidates = buildPolicyCandidates(civInfo)
        val macroCandidates = buildMacroCandidates(civInfo)
        val diplomacyContext = AgentDiplomacyOptionBuilder.build(civInfo)
        val diplomacyCandidates = diplomacyContext.diplomacyCandidates
        val candidateMap = LinkedHashMap<String, AgentEmpireRuntimeCandidate>()
        (researchCandidates + policyCandidates + macroCandidates + diplomacyCandidates).forEach { candidate ->
            candidateMap[candidate.observation.candidateId] = candidate
        }
        val currentResearch = civInfo.tech.currentTechnologyName()
        val currentResearchProgress = currentResearch?.let { civInfo.tech.researchOfTech(it) }
        val currentResearchCost = currentResearch?.let { civInfo.tech.costOfTech(it) }
        val currentResearchTurnsLeft = currentResearch?.let {
            if (civInfo.stats.statsForNextTurn.science <= 0f) null else civInfo.tech.turnsToTech(it).toIntOrNull()
        }
        val currentResearchStatus = when {
            currentResearch == null -> "needs_research_choice"
            currentResearchTurnsLeft != null && currentResearchTurnsLeft <= 2 -> "nearly_complete"
            currentResearchProgress != null && currentResearchProgress > 0 -> "in_progress"
            else -> "selected"
        }
        val enabledVictories = enabledVictories(civInfo)
        val preferredVictoryTypes = civInfo.getPreferredVictoryTypes().filter { it != Constants.neutralVictoryType }
        val memo = memory.lastStrategistMemo.takeIf { it.phase.isNotBlank() }
        val victoryPlan = chooseVictoryPlan(civInfo, enabledVictories, preferredVictoryTypes)
        val victoryThreats = buildVictoryThreats(civInfo, enabledVictories, victoryPlan)
        val displayedVictoryGoal = memo?.winPath
        val displayedVictoryFocus = memo?.winPath?.let(::defaultFocusForRoadmapWinPath)

        val observation = AgentEmpireObservation(
            turn = civInfo.gameInfo.turns,
            civName = civInfo.civName,
            strategicState = memory.campaign.stage.ifBlank { memo?.phase ?: "observe_and_plan" },
            isAtWar = civInfo.isAtWar(),
            gameContext = gameContext,
            enabledVictoryTypes = enabledVictories.map { it.name },
            preferredVictoryTypes = preferredVictoryTypes,
            victoryGoal = displayedVictoryGoal,
            victoryFocus = displayedVictoryFocus,
            heuristicVictoryGoal = victoryPlan?.victory?.name,
            heuristicVictoryFocus = victoryPlan?.focus?.name,
            victoryNextMilestone = victoryPlan?.nextMilestone?.uniqueDescription,
            victoryProgressCompleted = victoryPlan?.completedMilestones ?: 0,
            victoryProgressTotal = victoryPlan?.totalMilestones ?: 0,
            victoryThreats = victoryThreats,
            currentResearch = currentResearch,
            currentResearchTurnsLeft = currentResearchTurnsLeft,
            currentResearchProgress = currentResearchProgress,
            currentResearchCost = currentResearchCost,
            currentResearchStatus = currentResearchStatus,
            freeTechs = civInfo.tech.freeTechs,
            storedCulture = civInfo.policies.storedCulture,
            freePolicies = civInfo.policies.freePolicies,
            gold = civInfo.gold,
            happiness = civInfo.getHappiness(),
            researchCandidates = researchCandidates.map { it.observation },
            policyCandidates = policyCandidates.map { it.observation },
            macroCandidates = macroCandidates.map { it.observation },
            diplomacyCandidates = diplomacyCandidates.map { it.observation },
        )
        return AgentEmpirePlanningContext(
            observation = observation,
            candidates = candidateMap,
        )
    }

    private fun buildPublicGameContext(civInfo: Civilization): AgentPublicGameContextObservation {
        val gameParameters = civInfo.gameInfo.gameParameters
        val mapParameters = civInfo.gameInfo.tileMap.mapParameters
        val majorCivCount = gameParameters.players.count { it.chosenCiv != Constants.spectator }
        val knownMajorCivs = civInfo.getKnownCivs().count { it.isMajorCiv() && !it.isDefeated() }
        val duelLike = majorCivCount <= 2
        val contactComplete = knownMajorCivs >= (majorCivCount - 1).coerceAtLeast(0)
        val mapSizeName = mapParameters.mapSize.name
        val mapTypeName = mapParameters.type
        val explorationValue = when {
            contactComplete && duelLike -> "low"
            contactComplete -> if (mapSizeName in setOf("Tiny", "Small")) "low" else "medium"
            duelLike || mapTypeName.equals("Pangaea", ignoreCase = true) -> "high"
            else -> "medium"
        }
        val expansionWindow = when {
            duelLike && mapSizeName == "Tiny" -> "narrow"
            mapSizeName in setOf("Tiny", "Small") -> "medium"
            else -> "wide"
        }
        val archetype = listOf(
            mapSizeName.lowercase(),
            if (duelLike) "duel" else "${majorCivCount}p",
            mapTypeName.lowercase(),
            if (gameParameters.numberOfCityStates == 0) "no_city_states" else "city_states",
            if (gameParameters.noBarbarians) "no_barbs" else "barbs",
        ).joinToString("_")
            .replace(' ', '_')

        return AgentPublicGameContextObservation(
            rulesetName = civInfo.gameInfo.gameParameters.baseRuleset,
            mapSize = mapSizeName,
            mapType = mapTypeName,
            mapShape = mapParameters.shape,
            gameSpeed = civInfo.gameInfo.speed.name,
            majorCivCount = majorCivCount,
            cityStateCount = gameParameters.numberOfCityStates,
            barbariansEnabled = !gameParameters.noBarbarians,
            ruinsEnabled = !mapParameters.noRuins,
            strategicBalance = mapParameters.getStrategicBalance(),
            legendaryStart = mapParameters.getLegendaryStart(),
            duelLike = duelLike,
            contactComplete = contactComplete,
            explorationValue = explorationValue,
            expansionWindow = expansionWindow,
            archetype = archetype,
        )
    }

    private fun buildResearchCandidates(civInfo: Civilization): List<AgentEmpireRuntimeCandidate> {
        val techManager = civInfo.tech
        val researchableTechs = civInfo.gameInfo.ruleset.technologies.values
            .asSequence()
            .filter { techManager.canBeResearched(it.name) }
            .toList()
        if (researchableTechs.isEmpty()) return emptyList()

        val state = civInfo.state
        val candidateTechs = if (techManager.freeTechs > 0) {
            researchableTechs
                .sortedWith(
                    compareByDescending<Technology> { it.getWeightForAiDecision(state) }
                        .thenByDescending { techManager.costOfTech(it.name) }
                        .thenBy { it.name }
                )
                .take(maxResearchCandidates)
        } else if (techManager.techsToResearch.isEmpty()) {
            val grouped = researchableTechs.groupBy { it.cost }.toSortedMap().values.toList()
            val pool = buildList {
                grouped.firstOrNull()?.let(::addAll)
                grouped.getOrNull(1)?.let(::addAll)
            }.ifEmpty { researchableTechs }

            pool.sortedWith(
                compareByDescending<Technology> { it.getWeightForAiDecision(state) }
                    .thenBy { techManager.costOfTech(it.name) }
                    .thenBy { it.name }
            ).take(maxResearchCandidates)
        } else {
            emptyList()
        }

        return candidateTechs.map { tech ->
            val candidateId = "research:${tech.name}"
            AgentEmpireRuntimeCandidate(
                observation = AgentEmpireChoiceCandidateObservation(
                    candidateId = candidateId,
                    category = "research",
                    title = if (techManager.freeTechs > 0) {
                        "Take free tech ${tech.name}"
                    } else {
                        "Research ${tech.name}"
                    },
                    detail = buildString {
                        append("Cost ${techManager.costOfTech(tech.name)}")
                        append(", ${techManager.turnsToTech(tech.name)} turns")
                        append(", weight ${tech.getWeightForAiDecision(state).roundToInt()}")
                    },
                ),
                validate = { currentCiv ->
                    when {
                        !currentCiv.tech.canBeResearched(tech.name) -> "Empire option rejected: technology is no longer researchable"
                        currentCiv.tech.freeTechs <= 0 && currentCiv.tech.techsToResearch.isNotEmpty() ->
                            "Empire option rejected: research is already selected"
                        else -> null
                    }
                },
                execute = { currentCiv ->
                    if (currentCiv.tech.freeTechs > 0) {
                        currentCiv.tech.getFreeTechnology(tech.name)
                        true
                    } else if (currentCiv.tech.techsToResearch.isEmpty()) {
                        currentCiv.tech.selectTechnology(tech.name)
                    } else {
                        false
                    }
                },
                successMessage = if (techManager.freeTechs > 0) {
                    "Free technology selected: ${tech.name}"
                } else {
                    "Research selected: ${tech.name}"
                },
            )
        }
    }

    private fun buildPolicyCandidates(civInfo: Civilization): List<AgentEmpireRuntimeCandidate> {
        if (!civInfo.policies.canAdoptPolicy()) return emptyList()

        val priorityMap = civInfo.policies.priorityMap
        val candidates = buildList<Policy> {
            addAll(civInfo.policies.adoptableBranches)
            addAll(
                civInfo.policies.branches
                    .flatMap { branch -> branch.policies.filter { civInfo.policies.isAdoptable(it) } }
            )
        }
            .distinctBy { it.name }
            .sortedWith(
                compareByDescending<Policy> { priorityMap[it.branch] ?: 0 }
                    .thenByDescending { it.getWeightForAiDecision(civInfo.state) }
                    .thenBy { it.name }
            )
            .take(maxPolicyCandidates)

        return candidates.map { policy ->
            val candidateId = "policy:${policy.name}"
            val branchPriority = priorityMap[policy.branch] ?: 0
            AgentEmpireRuntimeCandidate(
                observation = AgentEmpireChoiceCandidateObservation(
                    candidateId = candidateId,
                    category = "policy",
                    title = "Adopt ${policy.name}",
                    detail = buildString {
                        append("Branch ${policy.branch.name}")
                        append(", branch priority $branchPriority")
                        if (policy is com.unciv.models.ruleset.PolicyBranch) {
                            append(", opens a new branch")
                        }
                    },
                ),
                validate = { currentCiv ->
                    when {
                        !currentCiv.policies.canAdoptPolicy() -> "Empire option rejected: no policy can be adopted now"
                        !currentCiv.policies.isAdoptable(policy) -> "Empire option rejected: policy is no longer adoptable"
                        else -> null
                    }
                },
                execute = { currentCiv ->
                    if (!currentCiv.policies.canAdoptPolicy() || !currentCiv.policies.isAdoptable(policy)) {
                        false
                    } else {
                        currentCiv.policies.adopt(policy)
                        true
                    }
                },
                successMessage = "Policy adopted: ${policy.name}",
            )
        }
    }

    private fun buildMacroCandidates(civInfo: Civilization): List<AgentEmpireRuntimeCandidate> {
        val candidates = arrayListOf<AgentEmpireRuntimeCandidate>()

        candidates += buildFreeGreatPersonCandidates(civInfo)

        if (shouldOfferGoldMacro(civInfo)) {
            candidates += AgentEmpireRuntimeCandidate(
                observation = AgentEmpireChoiceCandidateObservation(
                    candidateId = "macro:gold:auto",
                    category = "macro",
                    title = "Spend gold using empire heuristics",
                    detail = "May upgrade units or invest gold in empire-wide heuristics when a meaningful use is already available.",
                ),
                validate = { currentCiv ->
                    if (shouldOfferGoldMacro(currentCiv)) null else "Empire option rejected: no meaningful empire-wide gold spend is currently available"
                },
                execute = { currentCiv ->
                    val before = goldFingerprint(currentCiv)
                    UseGoldAutomation.useGold(currentCiv)
                    before != goldFingerprint(currentCiv)
                },
                successMessage = "Gold heuristics executed",
            )
        }

        candidates += buildBombardCandidates(civInfo)
        return candidates
    }

    private fun buildFreeGreatPersonCandidates(civInfo: Civilization): List<AgentEmpireRuntimeCandidate> {
        if (civInfo.greatPeople.freeGreatPeople <= 0) return emptyList()
        val capital = civInfo.getCapital() ?: return emptyList()
        val restrictedPool = civInfo.greatPeople.mayaLimitedFreeGP > 0
        val greatPeople = if (restrictedPool) {
            civInfo.greatPeople.getGreatPeople().filter { it.name in civInfo.greatPeople.longCountGPPool }
        } else {
            civInfo.greatPeople.getGreatPeople()
        }
        return greatPeople
            .sortedByDescending { scoreFreeGreatPersonChoice(it) }
            .take(maxFreeGreatPersonCandidates)
            .map { unit ->
                val candidateId = "macro:greatperson:${unit.name}"
                AgentEmpireRuntimeCandidate(
                    observation = AgentEmpireChoiceCandidateObservation(
                        candidateId = candidateId,
                        category = "macro",
                        title = "Take free ${unit.name}",
                        detail = "Claim a free great person in ${capital.name}. ${describeFreeGreatPerson(unit)}",
                    ),
                    validate = { currentCiv ->
                        if (currentCiv.greatPeople.freeGreatPeople <= 0) {
                            return@AgentEmpireRuntimeCandidate "Empire option rejected: no free great person is available"
                        }
                        val liveCapital = currentCiv.getCapital()
                            ?: return@AgentEmpireRuntimeCandidate "Empire option rejected: no capital is available for the free great person"
                        val liveRestrictedPool = currentCiv.greatPeople.mayaLimitedFreeGP > 0
                        val liveGreatPeople = if (liveRestrictedPool) {
                            currentCiv.greatPeople.getGreatPeople().filter { it.name in currentCiv.greatPeople.longCountGPPool }
                        } else {
                            currentCiv.greatPeople.getGreatPeople()
                        }
                        if (liveGreatPeople.none { it.name == unit.name }) {
                            "Empire option rejected: this free great person choice is no longer available"
                        } else {
                            null
                        }
                    },
                    execute = { currentCiv ->
                        val liveCapital = currentCiv.getCapital() ?: return@AgentEmpireRuntimeCandidate false
                        val mayanGreatPerson = currentCiv.greatPeople.mayaLimitedFreeGP > 0
                        val liveGreatPeople = if (mayanGreatPerson) {
                            currentCiv.greatPeople.getGreatPeople().filter { it.name in currentCiv.greatPeople.longCountGPPool }
                        } else {
                            currentCiv.greatPeople.getGreatPeople()
                        }
                        val liveUnit = liveGreatPeople.firstOrNull { it.name == unit.name } ?: return@AgentEmpireRuntimeCandidate false
                        currentCiv.units.addUnit(liveUnit, liveCapital)
                        currentCiv.greatPeople.freeGreatPeople--
                        if (mayanGreatPerson) {
                            currentCiv.greatPeople.longCountGPPool.remove(liveUnit.name)
                            currentCiv.greatPeople.mayaLimitedFreeGP--
                        }
                        true
                    },
                    successMessage = "Free great person chosen: ${unit.name}",
                )
            }
    }

    private fun shouldOfferGoldMacro(civInfo: Civilization): Boolean {
        if (civInfo.gold <= 0) return false
        if (hasAffordableUnitUpgrade(civInfo)) return true
        if (hasMeaningfulCityStateGoldAction(civInfo)) return true
        return false
    }

    private fun hasAffordableUnitUpgrade(civInfo: Civilization): Boolean {
        return civInfo.units.getCivUnits().any { unit ->
            UnitActionsUpgrade.getUpgradeActions(unit).any { it.action != null }
        }
    }

    private fun hasMeaningfulCityStateGoldAction(civInfo: Civilization): Boolean {
        val knownCityStates = civInfo.getKnownCivs().filter { it.isCityState }
        if (knownCityStates.none()) return false
        if (civInfo.gold >= 330 && civInfo.getHappiness() > 0 && civInfo.hasUnique(com.unciv.models.ruleset.unique.UniqueType.CityStateCanBeBoughtForGold)) {
            return true
        }
        return civInfo.gold >= 500
    }

    private fun buildBombardCandidates(civInfo: Civilization): List<AgentEmpireRuntimeCandidate> {
        val candidates = arrayListOf<AgentEmpireRuntimeCandidate>()
        for (city in civInfo.cities) {
            if (!city.canBombard()) continue
            val targets = TargetHelper.getBombardableTiles(city)
                .mapNotNull { tile -> Battle.getMapCombatantOfTile(tile) }
                .filterNot { it is MapUnitCombatant && it.isCivilian() }
            val bestTarget = targets.maxByOrNull { BattleDamage.calculateDamageToDefender(CityCombatant(city), it) }
                ?: continue
            val targetTile = bestTarget.getTile()
            val candidateId = "macro:bombard:${city.location.x},${city.location.y}:${targetTile.position.x},${targetTile.position.y}"
            val expectedDamage = BattleDamage.calculateDamageToDefender(CityCombatant(city), bestTarget)
            candidates += AgentEmpireRuntimeCandidate(
                observation = AgentEmpireChoiceCandidateObservation(
                    candidateId = candidateId,
                    category = "macro",
                    title = "${city.name} can bombard ${bestTarget.getName()}",
                    detail = "Target at (${targetTile.position.x}, ${targetTile.position.y}), expected damage $expectedDamage.",
                ),
                validate = validate@{ currentCiv ->
                    val liveCity = currentCiv.cities.firstOrNull { it.location.x == city.location.x && it.location.y == city.location.y }
                        ?: return@validate "Empire option rejected: bombard city is missing"
                    if (!liveCity.canBombard()) return@validate "Empire option rejected: city can no longer bombard"
                    val liveTargetTile = currentCiv.gameInfo.tileMap.values
                        .firstOrNull { it.position.x == targetTile.position.x && it.position.y == targetTile.position.y }
                        ?: return@validate "Empire option rejected: target tile is missing"
                    val liveTarget = Battle.getMapCombatantOfTile(liveTargetTile)
                        ?: return@validate "Empire option rejected: no enemy remains on the target tile"
                    if (liveTarget is MapUnitCombatant && liveTarget.isCivilian()) {
                        return@validate "Empire option rejected: civilian targets are not valid bombard choices"
                    }
                    null
                },
                execute = execute@{ currentCiv ->
                    val liveCity = currentCiv.cities.firstOrNull { it.location.x == city.location.x && it.location.y == city.location.y }
                        ?: return@execute false
                    val liveTargetTile = currentCiv.gameInfo.tileMap.values
                        .firstOrNull { it.position.x == targetTile.position.x && it.position.y == targetTile.position.y }
                        ?: return@execute false
                    val liveTarget = Battle.getMapCombatantOfTile(liveTargetTile)
                        ?: return@execute false
                    if (liveTarget is MapUnitCombatant && liveTarget.isCivilian()) return@execute false
                    Battle.attack(CityCombatant(liveCity), liveTarget)
                    true
                },
                successMessage = "${city.name} bombarded ${bestTarget.getName()}",
            )
        }
        return candidates
    }

    private fun scoreFreeGreatPersonChoice(unit: BaseUnit): Int {
        return when {
            unit.name.contains("Scientist", ignoreCase = true) -> 120
            unit.name.contains("Engineer", ignoreCase = true) -> 110
            unit.name.contains("Merchant", ignoreCase = true) -> 90
            unit.name.contains("Artist", ignoreCase = true) -> 80
            unit.name.contains("General", ignoreCase = true) -> 75
            else -> 60
        }
    }

    private fun describeFreeGreatPerson(unit: BaseUnit): String {
        return when {
            unit.name.contains("Scientist", ignoreCase = true) ->
                "Useful for academies and instant science tempo."
            unit.name.contains("Engineer", ignoreCase = true) ->
                "Useful for rushing key buildings or creating a manufactory."
            unit.name.contains("Merchant", ignoreCase = true) ->
                "Useful for trade missions or a customs house."
            unit.name.contains("Artist", ignoreCase = true) ->
                "Useful for a golden age or a landmark."
            unit.name.contains("General", ignoreCase = true) ->
                "Useful for a citadel or war support."
            else -> "Useful as a free great person choice."
        }
    }

    private data class VictoryPlanSummary(
        val victory: Victory,
        val nextMilestone: Milestone?,
        val focus: Victory.Focus,
        val completedMilestones: Int,
        val totalMilestones: Int,
        val score: Double,
    )

    private fun enabledVictories(civInfo: Civilization): List<Victory> {
        val enabled = civInfo.gameInfo.gameParameters.victoryTypes
        return civInfo.gameInfo.ruleset.victories.values
            .filter { it.name != Constants.neutralVictoryType && it.name in enabled }
    }

    private fun chooseVictoryPlan(
        civInfo: Civilization,
        victories: List<Victory>,
        preferredVictoryTypes: List<String>,
    ): VictoryPlanSummary? {
        return victories
            .map { victory -> summarizeVictoryPlan(civInfo, victory, preferredVictoryTypes) }
            .maxByOrNull { it.score }
    }

    private fun summarizeVictoryPlan(
        civInfo: Civilization,
        victory: Victory,
        preferredVictoryTypes: List<String>,
    ): VictoryPlanSummary {
        val nextMilestone = civInfo.victoryManager.getNextMilestone(victory)
        val completedMilestones = civInfo.victoryManager.amountMilestonesCompleted(victory)
        val totalMilestones = max(1, victory.milestoneObjects.size)
        val focus = nextMilestone?.getFocus(civInfo) ?: Victory.Focus.Score
        val progressWeight = completedMilestones * 12.0 / totalMilestones
        val preferredWeight = if (victory.name in preferredVictoryTypes) 4.0 else 0.0
        val focusWeight = victoryFocusWeight(civInfo, focus)
        return VictoryPlanSummary(
            victory = victory,
            nextMilestone = nextMilestone,
            focus = focus,
            completedMilestones = completedMilestones,
            totalMilestones = totalMilestones,
            score = progressWeight + preferredWeight + focusWeight,
        )
    }

    private fun victoryFocusWeight(civInfo: Civilization, focus: Victory.Focus): Double = when (focus) {
        Victory.Focus.Science -> civInfo.getStatForRanking(RankingType.Technologies) * 0.6 +
            civInfo.stats.statsForNextTurn.science.toDouble() * 0.05
        Victory.Focus.Culture -> civInfo.getStatForRanking(RankingType.Culture) * 1.0 +
            civInfo.stats.statsForNextTurn.culture.toDouble() * 0.08
        Victory.Focus.Military -> civInfo.getStatForRanking(RankingType.Force) * 0.01
        Victory.Focus.CityStates -> civInfo.gold * 0.002 + civInfo.getKnownCivs().count { it.isCityState } * 0.5
        Victory.Focus.Faith -> 0.0
        Victory.Focus.Gold -> civInfo.gold * 0.003 + civInfo.getStatForRanking(RankingType.Gold) * 0.002
        Victory.Focus.Production -> civInfo.stats.statsForNextTurn.production.toDouble() * 0.05
        Victory.Focus.Score -> civInfo.getStatForRanking(RankingType.Score) * 0.01
    }

    private fun buildVictoryThreats(
        civInfo: Civilization,
        victories: List<Victory>,
        ourPlan: VictoryPlanSummary?,
    ): List<AgentVictoryThreatObservation> {
        val ourScore = civInfo.getStatForRanking(RankingType.Score)
        val ourForce = civInfo.getStatForRanking(RankingType.Force)
        val ourTech = civInfo.getStatForRanking(RankingType.Technologies)
        val rivals = civInfo.getKnownCivs()
            .filter { it.isMajorCiv() && !it.isDefeated() && it != civInfo }

        return rivals
            .map { rival ->
                val rivalPlan = chooseVictoryPlan(rival, victories, rival.getPreferredVictoryTypes())
                val likelyPlan = rivalPlan ?: return@map null
                val ourComparableProgress = ourPlan
                    ?.takeIf { it.victory.name == likelyPlan.victory.name }
                    ?.completedMilestones
                    ?: 0
                val scoreDelta = rival.getStatForRanking(RankingType.Score) - ourScore
                val forceDelta = rival.getStatForRanking(RankingType.Force) - ourForce
                val technologyDelta = rival.getStatForRanking(RankingType.Technologies) - ourTech
                val progressDelta = likelyPlan.completedMilestones - ourComparableProgress
                val threatScore = progressDelta * 5 +
                    technologyDelta * 2 +
                    (if (scoreDelta > 0) 2 else 0) +
                    (if (forceDelta > max(ourForce / 3, 30)) 2 else 0) +
                    (if (ourPlan != null && likelyPlan.victory.name == ourPlan.victory.name) 1 else 0)
                val threatLevel = when {
                    threatScore >= 10 -> "critical"
                    threatScore >= 5 -> "warning"
                    else -> "info"
                }
                AgentVictoryThreatObservation(
                    civName = rival.civName,
                    likelyVictoryType = likelyPlan.victory.name,
                    focus = likelyPlan.focus.name,
                    nextMilestone = likelyPlan.nextMilestone?.uniqueDescription,
                    completedMilestones = likelyPlan.completedMilestones,
                    totalMilestones = likelyPlan.totalMilestones,
                    scoreDeltaVsUs = scoreDelta,
                    forceDeltaVsUs = forceDelta,
                    technologyDeltaVsUs = technologyDelta,
                    threatLevel = threatLevel,
                    detail = buildString {
                        append("${rival.civName} looks likeliest to pursue ${likelyPlan.victory.name}.")
                        if (technologyDelta > 0) append(" Tech lead +$technologyDelta.")
                        if (scoreDelta > 0) append(" Score lead +$scoreDelta.")
                        if (forceDelta > 0) append(" Force lead +$forceDelta.")
                        likelyPlan.nextMilestone?.let { append(" Next milestone: ${it.uniqueDescription}.") }
                    },
                )
            }
            .filterNotNull()
            .sortedWith(
                compareByDescending<AgentVictoryThreatObservation> {
                    when (it.threatLevel) {
                        "critical" -> 3
                        "warning" -> 2
                        else -> 1
                    }
                }.thenByDescending { it.technologyDeltaVsUs ?: 0 }
                    .thenByDescending { it.scoreDeltaVsUs ?: 0 }
                    .thenBy { it.civName }
            )
            .take(maxVictoryThreats)
            .toList()
    }

    private fun goldFingerprint(civInfo: Civilization): String {
        val currentConstructions = civInfo.cities.joinToString("|") { city ->
            "${city.location.x},${city.location.y}:${city.cityConstructions.currentConstructionName()}"
        }
        return listOf(
            civInfo.gold,
            civInfo.units.getCivUnits().count(),
            civInfo.cities.sumOf { city -> city.getTiles().count { tile -> tile.getOwner() == civInfo } },
            currentConstructions,
        ).joinToString("|")
    }

}
    private fun defaultFocusForRoadmapWinPath(winPath: String): String = when (winPath.lowercase()) {
        "scientific" -> Victory.Focus.Science.name
        "cultural" -> Victory.Focus.Culture.name
        "domination" -> Victory.Focus.Military.name
        "diplomatic" -> Victory.Focus.CityStates.name
        else -> Victory.Focus.Score.name
    }
