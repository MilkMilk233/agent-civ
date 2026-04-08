package com.unciv.logic.automation.agent

import com.unciv.Constants
import com.unciv.logic.automation.civilization.DiplomacyAutomation
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.PopupAlert
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.trade.Trade
import com.unciv.logic.trade.TradeEvaluation
import com.unciv.logic.trade.TradeLogic
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeOfferType
import com.unciv.logic.trade.TradeRequest
import com.unciv.models.ruleset.tile.ResourceType

object AgentDiplomacyOptionBuilder {
    private const val maxDiplomacyCandidates = 12

    internal fun build(civInfo: Civilization): AgentDiplomacyPlanningContext {
        val diplomacyCandidates = buildList {
            addAll(buildDeclareWarCandidates(civInfo))
            addAll(buildFriendshipCandidates(civInfo))
            addAll(buildEmbassyCandidates(civInfo))
            addAll(buildOpenBordersCandidates(civInfo))
            addAll(buildResearchAgreementCandidates(civInfo))
            addAll(buildDefensivePactCandidates(civInfo))
            addAll(buildLuxuryTradeCandidates(civInfo))
        }
            .sortedByDescending { scoreCandidate(it.observation) }
            .take(maxDiplomacyCandidates)

        return AgentDiplomacyPlanningContext(
            diplomacyCandidates = diplomacyCandidates,
        )
    }

    private fun buildDeclareWarCandidates(civInfo: Civilization): List<AgentEmpireRuntimeCandidate> {
        return civInfo.getKnownCivs()
            .asSequence()
            .filter { it.isMajorCiv() }
            .filterNot { civInfo.isAtWarWith(it) }
            .filter { civInfo.getDiplomacyManager(it)?.canDeclareWar() == true }
            .map { otherCiv ->
                val candidateId = "diplo:war:${otherCiv.civID}"
                AgentEmpireRuntimeCandidate(
                    observation = AgentEmpireChoiceCandidateObservation(
                        candidateId = candidateId,
                        category = "diplomacy",
                        title = "Declare war on ${otherCiv.civName}",
                        detail = "${relationshipSummary(civInfo, otherCiv)}. Immediate transition from peace to open war.",
                    ),
                    validate = { currentCiv ->
                        val liveOther = currentCiv.gameInfo.civilizations.firstOrNull { it.civID == otherCiv.civID }
                            ?: return@AgentEmpireRuntimeCandidate "Empire option rejected: civilization is missing"
                        when {
                            currentCiv.isAtWarWith(liveOther) ->
                                "Empire option rejected: already at war with ${liveOther.civName}"
                            currentCiv.getDiplomacyManager(liveOther)?.canDeclareWar() != true ->
                                "Empire option rejected: war declaration is no longer available"
                            else -> null
                        }
                    },
                    execute = { currentCiv ->
                        val liveOther = currentCiv.gameInfo.civilizations.firstOrNull { it.civID == otherCiv.civID }
                            ?: return@AgentEmpireRuntimeCandidate false
                        val liveDiploManager = currentCiv.getDiplomacyManager(liveOther) ?: return@AgentEmpireRuntimeCandidate false
                        if (currentCiv.isAtWarWith(liveOther) || !liveDiploManager.canDeclareWar()) return@AgentEmpireRuntimeCandidate false
                        liveDiploManager.declareWar()
                        currentCiv.isAtWarWith(liveOther)
                    },
                    successMessage = "War declared on ${otherCiv.civName}",
                )
            }
            .toList()
    }

    private fun buildFriendshipCandidates(civInfo: Civilization): List<AgentEmpireRuntimeCandidate> {
        return civInfo.getKnownCivs()
            .asSequence()
            .filter { civInfo.diplomacyFunctions.canSignDeclarationOfFriendshipWith(it) }
            .filterNot { it.getDiplomacyManager(civInfo)?.hasFlag(DiplomacyFlags.DeclinedDeclarationOfFriendship) == true }
            .filterNot { otherCiv ->
                otherCiv.popupAlerts.any { it.type == AlertType.DeclarationOfFriendship && it.value == civInfo.civID }
            }
            .sortedByDescending { civInfo.getDiplomacyManager(it)?.opinionOfOtherCiv() ?: Float.NEGATIVE_INFINITY }
            .map { otherCiv ->
                val candidateId = "diplo:friendship:${otherCiv.civID}"
                AgentEmpireRuntimeCandidate(
                    observation = AgentEmpireChoiceCandidateObservation(
                        candidateId = candidateId,
                        category = "diplomacy",
                        title = "Offer friendship to ${otherCiv.civName}",
                        detail = "${relationshipSummary(civInfo, otherCiv)}. Opens the door to deeper diplomatic agreements.",
                    ),
                    validate = { currentCiv ->
                        val liveOther = currentCiv.gameInfo.civilizations.firstOrNull { it.civID == otherCiv.civID }
                            ?: return@AgentEmpireRuntimeCandidate "Empire option rejected: civilization is missing"
                        when {
                            !currentCiv.diplomacyFunctions.canSignDeclarationOfFriendshipWith(liveOther) ->
                                "Empire option rejected: declaration of friendship is no longer available"
                            liveOther.popupAlerts.any { it.type == AlertType.DeclarationOfFriendship && it.value == currentCiv.civID } ->
                                "Empire option rejected: friendship offer is already pending"
                            else -> null
                        }
                    },
                    execute = { currentCiv ->
                        val liveOther = currentCiv.gameInfo.civilizations.firstOrNull { it.civID == otherCiv.civID } ?: return@AgentEmpireRuntimeCandidate false
                        val before = liveOther.popupAlerts.size
                        liveOther.popupAlerts.add(PopupAlert(AlertType.DeclarationOfFriendship, currentCiv.civID))
                        liveOther.popupAlerts.size > before
                    },
                    successMessage = "Friendship offer sent to ${otherCiv.civName}",
                )
            }
            .toList()
    }

    private fun buildEmbassyCandidates(civInfo: Civilization): List<AgentEmpireRuntimeCandidate> {
        return civInfo.getKnownCivs()
            .asSequence()
            .filter { civInfo.diplomacyFunctions.canEstablishEmbassyWith(it) }
            .filterNot { civInfo.getDiplomacyManager(it)?.hasFlag(DiplomacyFlags.DeclinedEmbassy) == true }
            .mapNotNull { otherCiv ->
                val trade = buildEmbassyTrade(civInfo, otherCiv) ?: return@mapNotNull null
                if (hasPendingTradeRequest(civInfo, otherCiv, trade)) return@mapNotNull null
                val candidateId = "diplo:embassy:${otherCiv.civID}"
                AgentEmpireRuntimeCandidate(
                    observation = AgentEmpireChoiceCandidateObservation(
                        candidateId = candidateId,
                        category = "diplomacy",
                        title = "Request embassy with ${otherCiv.civName}",
                        detail = "${relationshipSummary(civInfo, otherCiv)}. ${embassyOfferSummary(civInfo, otherCiv, trade)}",
                    ),
                    validate = { currentCiv ->
                        val liveOther = currentCiv.gameInfo.civilizations.firstOrNull { it.civID == otherCiv.civID }
                            ?: return@AgentEmpireRuntimeCandidate "Empire option rejected: civilization is missing"
                        val liveTrade = buildEmbassyTrade(currentCiv, liveOther)
                            ?: return@AgentEmpireRuntimeCandidate "Empire option rejected: embassy trade is no longer available"
                        if (hasPendingTradeRequest(currentCiv, liveOther, liveTrade)) {
                            "Empire option rejected: embassy offer is already pending"
                        } else {
                            null
                        }
                    },
                    execute = { currentCiv ->
                        val liveOther = currentCiv.gameInfo.civilizations.firstOrNull { it.civID == otherCiv.civID } ?: return@AgentEmpireRuntimeCandidate false
                        val liveTrade = buildEmbassyTrade(currentCiv, liveOther) ?: return@AgentEmpireRuntimeCandidate false
                        enqueueTradeRequest(currentCiv, liveOther, liveTrade)
                    },
                    successMessage = "Embassy request sent to ${otherCiv.civName}",
                )
            }
            .toList()
    }

    private fun buildOpenBordersCandidates(civInfo: Civilization): List<AgentEmpireRuntimeCandidate> {
        return civInfo.getKnownCivs()
            .asSequence()
            .filter { otherCiv ->
                val ourDiploManager = civInfo.getDiplomacyManager(otherCiv) ?: return@filter false
                otherCiv.isMajorCiv()
                    && !civInfo.isAtWarWith(otherCiv)
                    && otherCiv.diplomacyFunctions.hasMutualEmbassyWith(civInfo)
                    && !ourDiploManager.hasOpenBorders
                    && !ourDiploManager.otherCivDiplomacy().hasOpenBorders
                    && civInfo.hasUnique(com.unciv.models.ruleset.unique.UniqueType.EnablesOpenBorders)
                    && otherCiv.hasUnique(com.unciv.models.ruleset.unique.UniqueType.EnablesOpenBorders)
                    && !ourDiploManager.hasFlag(DiplomacyFlags.DeclinedOpenBorders)
            }
            .map { otherCiv ->
                val trade = Trade().apply {
                    ourOffers.add(TradeOffer(Constants.openBorders, TradeOfferType.Agreement, speed = civInfo.gameInfo.speed))
                    theirOffers.add(TradeOffer(Constants.openBorders, TradeOfferType.Agreement, speed = civInfo.gameInfo.speed))
                }
                candidateForTrade(
                    civInfo = civInfo,
                    otherCiv = otherCiv,
                    trade = trade,
                    candidateId = "diplo:openborders:${otherCiv.civID}",
                    title = "Offer open borders to ${otherCiv.civName}",
                    detail = "${relationshipSummary(civInfo, otherCiv)}. Mutual open borders agreement.",
                    successMessage = "Open borders offer sent to ${otherCiv.civName}",
                )
            }
            .toList()
    }

    private fun buildResearchAgreementCandidates(civInfo: Civilization): List<AgentEmpireRuntimeCandidate> {
        return civInfo.getKnownCivs()
            .asSequence()
            .filter { civInfo.diplomacyFunctions.canSignResearchAgreementWith(it) }
            .filterNot { civInfo.getDiplomacyManager(it)?.hasFlag(DiplomacyFlags.DeclinedResearchAgreement) == true }
            .map { otherCiv ->
                val cost = civInfo.diplomacyFunctions.getResearchAgreementCost(otherCiv)
                val trade = Trade().apply {
                    val offer = TradeOffer(Constants.researchAgreement, TradeOfferType.Treaty, cost, civInfo.gameInfo.speed)
                    ourOffers.add(offer)
                    theirOffers.add(offer.copy())
                }
                candidateForTrade(
                    civInfo = civInfo,
                    otherCiv = otherCiv,
                    trade = trade,
                    candidateId = "diplo:researchagreement:${otherCiv.civID}",
                    title = "Propose research agreement to ${otherCiv.civName}",
                    detail = "${relationshipSummary(civInfo, otherCiv)}. Shared science treaty costing $cost gold each.",
                    successMessage = "Research agreement proposed to ${otherCiv.civName}",
                )
            }
            .toList()
    }

    private fun buildDefensivePactCandidates(civInfo: Civilization): List<AgentEmpireRuntimeCandidate> {
        return civInfo.getKnownCivs()
            .asSequence()
            .filter { civInfo.diplomacyFunctions.canSignDefensivePactWith(it) }
            .filterNot { civInfo.getDiplomacyManager(it)?.hasFlag(DiplomacyFlags.DeclinedDefensivePact) == true }
            .map { otherCiv ->
                val trade = Trade().apply {
                    val offer = TradeOffer(Constants.defensivePact, TradeOfferType.Treaty, speed = civInfo.gameInfo.speed)
                    ourOffers.add(offer)
                    theirOffers.add(offer.copy())
                }
                candidateForTrade(
                    civInfo = civInfo,
                    otherCiv = otherCiv,
                    trade = trade,
                    candidateId = "diplo:defensivepact:${otherCiv.civID}",
                    title = "Propose defensive pact to ${otherCiv.civName}",
                    detail = "${relationshipSummary(civInfo, otherCiv)}. Mutual military protection treaty.",
                    successMessage = "Defensive pact proposed to ${otherCiv.civName}",
                )
            }
            .toList()
    }

    private fun buildLuxuryTradeCandidates(civInfo: Civilization): List<AgentEmpireRuntimeCandidate> {
        val evaluation = TradeEvaluation()
        return civInfo.getKnownCivs()
            .asSequence()
            .filter { it.isMajorCiv() && !it.isAtWarWith(civInfo) }
            .filterNot { civInfo.getDiplomacyManager(it)?.hasFlag(DiplomacyFlags.DeclinedLuxExchange) == true }
            .flatMap { otherCiv ->
                val tradeLogic = TradeLogic(civInfo, otherCiv)
                val ourLuxuries = tradeLogic.ourAvailableOffers
                    .filter { it.type == TradeOfferType.Luxury_Resource && it.amount > 1 }
                    .filter { tradeLogic.theirAvailableOffers.none { their -> their.type == TradeOfferType.Luxury_Resource && their.name == it.name } }
                val theirLuxuries = tradeLogic.theirAvailableOffers
                    .filter { it.type == TradeOfferType.Luxury_Resource && it.amount > 1 }
                    .filter { tradeLogic.ourAvailableOffers.none { ours -> ours.type == TradeOfferType.Luxury_Resource && ours.name == it.name } }
                    .sortedByDescending { luxury ->
                        civInfo.cities.count { city -> city.demandedResource == luxury.name }
                    }
                sequence {
                    val pairs = minOf(ourLuxuries.size, theirLuxuries.size, 2)
                    for (i in 0 until pairs) {
                        val ourOffer = ourLuxuries[i].copy(amount = 1)
                        val theirOffer = theirLuxuries[i].copy(amount = 1)
                        val trade = Trade().apply {
                            ourOffers.add(ourOffer)
                            theirOffers.add(theirOffer)
                        }
                        if (!evaluation.isTradeValid(trade, civInfo, otherCiv)) continue
                        if (hasPendingTradeRequest(civInfo, otherCiv, trade)) continue
                        yield(
                            AgentEmpireRuntimeCandidate(
                                observation = AgentEmpireChoiceCandidateObservation(
                                    candidateId = "trade:luxuryswap:${otherCiv.civID}:${ourOffer.name}:${theirOffer.name}",
                                    category = "trade",
                                    title = "Trade ${ourOffer.name} for ${theirOffer.name} with ${otherCiv.civName}",
                                    detail = "${relationshipSummary(civInfo, otherCiv)}. Swap one surplus luxury for a luxury we do not currently have.",
                                ),
                                validate = { currentCiv ->
                                    val liveOther = currentCiv.gameInfo.civilizations.firstOrNull { it.civID == otherCiv.civID }
                                        ?: return@AgentEmpireRuntimeCandidate "Empire option rejected: civilization is missing"
                                    val liveTrade = Trade().apply {
                                        ourOffers.add(TradeOffer(ourOffer.name, ourOffer.type, 1, currentCiv.gameInfo.speed))
                                        theirOffers.add(TradeOffer(theirOffer.name, theirOffer.type, 1, currentCiv.gameInfo.speed))
                                    }
                                    when {
                                        !TradeEvaluation().isTradeValid(liveTrade, currentCiv, liveOther) ->
                                            "Empire option rejected: luxury trade is no longer valid"
                                        hasPendingTradeRequest(currentCiv, liveOther, liveTrade) ->
                                            "Empire option rejected: luxury trade is already pending"
                                        else -> null
                                    }
                                },
                                execute = { currentCiv ->
                                    val liveOther = currentCiv.gameInfo.civilizations.firstOrNull { it.civID == otherCiv.civID }
                                        ?: return@AgentEmpireRuntimeCandidate false
                                    val liveTrade = Trade().apply {
                                        ourOffers.add(TradeOffer(ourOffer.name, ourOffer.type, 1, currentCiv.gameInfo.speed))
                                        theirOffers.add(TradeOffer(theirOffer.name, theirOffer.type, 1, currentCiv.gameInfo.speed))
                                    }
                                    enqueueTradeRequest(currentCiv, liveOther, liveTrade)
                                },
                                successMessage = "Luxury swap offered to ${otherCiv.civName}",
                            )
                        )
                    }
                }
            }
            .toList()
    }

    private fun candidateForTrade(
        civInfo: Civilization,
        otherCiv: Civilization,
        trade: Trade,
        candidateId: String,
        title: String,
        detail: String,
        successMessage: String,
    ): AgentEmpireRuntimeCandidate {
        return AgentEmpireRuntimeCandidate(
            observation = AgentEmpireChoiceCandidateObservation(
                candidateId = candidateId,
                category = if (trade.ourOffers.any { it.type == TradeOfferType.Treaty || it.type == TradeOfferType.Agreement || it.type == TradeOfferType.Embassy }) "diplomacy" else "trade",
                title = title,
                detail = detail,
            ),
            validate = { currentCiv ->
                val liveOther = currentCiv.gameInfo.civilizations.firstOrNull { it.civID == otherCiv.civID }
                    ?: return@AgentEmpireRuntimeCandidate "Empire option rejected: civilization is missing"
                when {
                    !TradeEvaluation().isTradeValid(trade, currentCiv, liveOther) ->
                        "Empire option rejected: trade is no longer valid"
                    hasPendingTradeRequest(currentCiv, liveOther, trade) ->
                        "Empire option rejected: trade offer is already pending"
                    else -> null
                }
            },
            execute = { currentCiv ->
                val liveOther = currentCiv.gameInfo.civilizations.firstOrNull { it.civID == otherCiv.civID } ?: return@AgentEmpireRuntimeCandidate false
                enqueueTradeRequest(currentCiv, liveOther, trade)
            },
            successMessage = successMessage,
        )
    }

    private fun buildEmbassyTrade(civInfo: Civilization, otherCiv: Civilization): Trade? {
        if (!civInfo.diplomacyFunctions.canEstablishEmbassyWith(otherCiv)) return null
        val tradeLogic = TradeLogic(civInfo, otherCiv)
        val embassyOffer = TradeOffer(Constants.acceptEmbassy, TradeOfferType.Embassy, speed = civInfo.gameInfo.speed)
        tradeLogic.currentTrade.theirOffers.add(embassyOffer)
        if (otherCiv.diplomacyFunctions.canEstablishEmbassyWith(civInfo)) {
            tradeLogic.currentTrade.ourOffers.add(embassyOffer)
        } else {
            val embassyValue = TradeEvaluation().evaluateBuyCostWithInflation(embassyOffer, civInfo, otherCiv, tradeLogic.currentTrade)
            val embassyGptValue = embassyValue / civInfo.gameInfo.speed.dealDuration
            val ourGpt = civInfo.stats.statsForNextTurn.gold.toInt()
            when {
                embassyGptValue in 1..ourGpt -> tradeLogic.currentTrade.ourOffers.add(
                    TradeOffer(Constants.goldPerTurn, TradeOfferType.Gold_Per_Turn, embassyGptValue, civInfo.gameInfo.speed)
                )
                civInfo.gold >= embassyValue && ourGpt >= 0 -> tradeLogic.currentTrade.ourOffers.add(
                    TradeOffer(Constants.flatGold, TradeOfferType.Gold, embassyValue, civInfo.gameInfo.speed)
                )
            }
        }
        return tradeLogic.currentTrade
    }

    private fun embassyOfferSummary(civInfo: Civilization, otherCiv: Civilization, trade: Trade): String {
        val goldOffer = trade.ourOffers.firstOrNull { it.type == TradeOfferType.Gold || it.type == TradeOfferType.Gold_Per_Turn }
        return when {
            otherCiv.diplomacyFunctions.canEstablishEmbassyWith(civInfo) -> "Mutual embassies are possible."
            goldOffer != null -> "Offer includes ${goldOffer.amount} ${if (goldOffer.type == TradeOfferType.Gold_Per_Turn) "gold per turn" else "gold"} to secure the embassy."
            else -> "Embassy request may rely on the other civ to counteroffer."
        }
    }

    private fun relationshipSummary(civInfo: Civilization, otherCiv: Civilization): String {
        val diploManager = civInfo.getDiplomacyManager(otherCiv)
        val opinion = diploManager?.opinionOfOtherCiv()?.toInt()
        val level = diploManager?.relationshipLevel()?.name ?: "Unknown"
        return buildString {
            append("Relation $level")
            if (opinion != null) append(", opinion $opinion")
        }
    }

    private fun scoreCandidate(observation: AgentEmpireChoiceCandidateObservation): Int {
        val lower = "${observation.title} ${observation.detail}".lowercase()
        return when {
            observation.category == "diplomacy" && "declare war" in lower -> 140
            observation.category == "diplomacy" && "research agreement" in lower -> 130
            observation.category == "diplomacy" && "defensive pact" in lower -> 120
            observation.category == "trade" -> 110
            else -> 90
        }
    }

    private fun hasPendingTradeRequest(civInfo: Civilization, otherCiv: Civilization, trade: Trade): Boolean {
        val reversed = trade.reverse()
        return otherCiv.tradeRequests.any { request ->
            request.requestingCiv == civInfo.civID && request.trade.equalTrade(reversed)
        }
    }

    private fun enqueueTradeRequest(civInfo: Civilization, otherCiv: Civilization, trade: Trade): Boolean {
        if (hasPendingTradeRequest(civInfo, otherCiv, trade)) return false
        val before = otherCiv.tradeRequests.size
        otherCiv.tradeRequests.add(TradeRequest(civInfo.civID, trade.reverse()))
        return otherCiv.tradeRequests.size > before
    }

    internal data class AgentDiplomacyPlanningContext(
        val diplomacyCandidates: List<AgentEmpireRuntimeCandidate>,
    )
}
