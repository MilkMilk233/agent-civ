package com.unciv.logic.automation.agent

import com.unciv.logic.automation.civilization.ReligionAutomation
import com.unciv.logic.automation.civilization.UseGoldAutomation
import com.unciv.logic.automation.unit.UnitAutomation
import com.unciv.logic.battle.Battle
import com.unciv.logic.battle.BattleDamage
import com.unciv.logic.battle.CityCombatant
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.battle.TargetHelper
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.managers.ReligionState
import com.unciv.models.ruleset.Policy
import com.unciv.models.ruleset.tech.Technology
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActionsUpgrade
import kotlin.math.roundToInt

object AgentEmpireObservationBuilder {
    private const val maxResearchCandidates = 6
    private const val maxPolicyCandidates = 6

    internal fun build(civInfo: Civilization, memory: AgentMemory): AgentEmpirePlanningContext {
        civInfo.updateStatsForNextTurn()
        civInfo.cities.forEach { it.cityStats.update(updateCivStats = false) }

        val researchCandidates = buildResearchCandidates(civInfo)
        val policyCandidates = buildPolicyCandidates(civInfo)
        val macroCandidates = buildMacroCandidates(civInfo)
        val diplomacyContext = AgentDiplomacyOptionBuilder.build(civInfo)
        val diplomacyCandidates = diplomacyContext.diplomacyCandidates
        val spyCandidates = diplomacyContext.spyCandidates
        val candidateMap = LinkedHashMap<String, AgentEmpireRuntimeCandidate>()
        (researchCandidates + policyCandidates + macroCandidates + diplomacyCandidates + spyCandidates).forEach { candidate ->
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
            else -> "queued"
        }

        val observation = AgentEmpireObservation(
            turn = civInfo.gameInfo.turns,
            civName = civInfo.civName,
            strategicPosture = memory.strategicPosture.mode,
            isAtWar = civInfo.isAtWar(),
            currentResearch = currentResearch,
            currentResearchTurnsLeft = currentResearchTurnsLeft,
            currentResearchProgress = currentResearchProgress,
            currentResearchCost = currentResearchCost,
            currentResearchStatus = currentResearchStatus,
            freeTechs = civInfo.tech.freeTechs,
            storedCulture = civInfo.policies.storedCulture,
            freePolicies = civInfo.policies.freePolicies,
            storedFaith = civInfo.religionManager.storedFaith,
            religionState = civInfo.religionManager.religionState.name,
            gold = civInfo.gold,
            happiness = civInfo.getHappiness(),
            macroFacts = buildMacroFacts(civInfo, researchCandidates, policyCandidates, macroCandidates, diplomacyCandidates, spyCandidates),
            researchCandidates = researchCandidates.map { it.observation },
            policyCandidates = policyCandidates.map { it.observation },
            macroCandidates = macroCandidates.map { it.observation },
            diplomacyCandidates = diplomacyCandidates.map { it.observation },
            spyCandidates = spyCandidates.map { it.observation },
        )
        return AgentEmpirePlanningContext(
            observation = observation,
            candidates = candidateMap,
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
                            "Empire option rejected: research is already queued"
                        else -> null
                    }
                },
                execute = { currentCiv ->
                    if (currentCiv.tech.freeTechs > 0) {
                        currentCiv.tech.getFreeTechnology(tech.name)
                        true
                    } else if (currentCiv.tech.techsToResearch.isEmpty()) {
                        currentCiv.tech.techsToResearch.add(tech.name)
                        true
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

        if (shouldOfferReligionMacro(civInfo)) {
            candidates += AgentEmpireRuntimeCandidate(
                observation = AgentEmpireChoiceCandidateObservation(
                    candidateId = "macro:religion:auto",
                    category = "macro",
                    title = "Manage religion and faith spending",
                    detail = "Uses the current religion heuristics to spend faith and resolve pending belief choices.",
                ),
                validate = { currentCiv ->
                    if (shouldOfferReligionMacro(currentCiv)) null else "Empire option rejected: no religion action is currently available"
                },
                execute = { currentCiv ->
                    val before = religionFingerprint(currentCiv)
                    ReligionAutomation.spendFaithOnReligion(currentCiv)
                    ReligionAutomation.chooseReligiousBeliefs(currentCiv)
                    before != religionFingerprint(currentCiv)
                },
                successMessage = "Religion heuristics executed",
            )
        }

        candidates += buildBombardCandidates(civInfo)
        return candidates
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

    private fun buildMacroFacts(
        civInfo: Civilization,
        researchCandidates: List<AgentEmpireRuntimeCandidate>,
        policyCandidates: List<AgentEmpireRuntimeCandidate>,
        macroCandidates: List<AgentEmpireRuntimeCandidate>,
        diplomacyCandidates: List<AgentEmpireRuntimeCandidate> = emptyList(),
        spyCandidates: List<AgentEmpireRuntimeCandidate> = emptyList(),
    ): List<ObservationFact> {
        val facts = mutableListOf<ObservationFact>()
        if (researchCandidates.isNotEmpty()) {
            facts += ObservationFact(
                category = "research",
                severity = if (civInfo.tech.currentTechnologyName() == null) "warning" else "info",
                headline = if (civInfo.tech.freeTechs > 0) "A free technology is available" else "Research can be chosen",
                detail = researchCandidates.first().observation.detail,
            )
        }
        if (policyCandidates.isNotEmpty()) {
            facts += ObservationFact(
                category = "policy",
                severity = "info",
                headline = "A policy can be adopted",
                detail = policyCandidates.first().observation.title,
            )
        }
        val goldCandidate = macroCandidates.firstOrNull { it.observation.candidateId == "macro:gold:auto" }
        if (goldCandidate != null) {
            facts += ObservationFact(
                category = "economy",
                severity = if (civInfo.gold >= 300) "info" else "warning",
                headline = "Gold can be spent this turn",
                detail = goldCandidate.observation.detail,
            )
        }
        val religionCandidate = macroCandidates.firstOrNull { it.observation.candidateId == "macro:religion:auto" }
        if (religionCandidate != null) {
            facts += ObservationFact(
                category = "religion",
                severity = "info",
                headline = "Religion or faith actions are available",
                detail = religionCandidate.observation.detail,
            )
        }
        val bombardCount = macroCandidates.count { it.observation.candidateId.startsWith("macro:bombard:") }
        if (bombardCount > 0) {
            facts += ObservationFact(
                category = "war",
                severity = if (civInfo.isAtWar()) "warning" else "info",
                headline = "Cities can bombard visible enemies",
                detail = "$bombardCount bombard action candidates are available before unit planning.",
            )
        }
        if (diplomacyCandidates.isNotEmpty()) {
            facts += ObservationFact(
                category = "diplomacy",
                severity = "info",
                headline = "External diplomatic actions are available",
                detail = "${diplomacyCandidates.size} diplomacy or trade candidates can be chosen this turn.",
            )
        }
        if (spyCandidates.isNotEmpty()) {
            facts += ObservationFact(
                category = "spy",
                severity = "info",
                headline = "Spy assignments are available",
                detail = "${spyCandidates.size} spy candidates are available for reassignment or coup planning.",
            )
        }
        return facts.take(6)
    }

    private fun shouldOfferReligionMacro(civInfo: Civilization): Boolean {
        if (!civInfo.gameInfo.isReligionEnabled()) return false
        return civInfo.religionManager.storedFaith > 0 ||
            civInfo.religionManager.canFoundOrExpandPantheon() ||
            civInfo.religionManager.religionState == ReligionState.FoundingReligion ||
            civInfo.religionManager.religionState == ReligionState.EnhancingReligion ||
            civInfo.religionManager.hasFreeBeliefs()
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

    private fun religionFingerprint(civInfo: Civilization): String {
        val beliefCount = civInfo.religionManager.religion?.getAllBeliefsOrdered()?.count() ?: 0
        return listOf(
            civInfo.religionManager.storedFaith,
            civInfo.religionManager.religionState.name,
            beliefCount,
            civInfo.units.getCivUnits().count(),
            civInfo.cities.joinToString("|") { it.cityConstructions.currentConstructionName() },
        ).joinToString("|")
    }
}
