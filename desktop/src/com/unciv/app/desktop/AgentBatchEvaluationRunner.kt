package com.unciv.app.desktop

import com.badlogic.gdx.files.FileHandle
import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.json.fromJsonFile
import com.unciv.json.json
import com.unciv.logic.GameStarter
import com.unciv.logic.automation.agent.AgentObservability
import com.unciv.logic.map.tile.Tile
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.map.MapParameters
import com.unciv.models.metadata.GameParameters
import com.unciv.models.metadata.GameSettings
import com.unciv.models.metadata.GameSetupInfo
import com.unciv.models.metadata.Player
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.skins.SkinCache
import com.unciv.models.tilesets.TileSetCache
import com.unciv.ui.components.tilegroups.TileGroupMap
import com.unciv.utils.Concurrency
import com.unciv.utils.Log
import java.util.concurrent.CancellationException
import kotlin.math.min
import kotlin.time.ExperimentalTime

data class AgentEvaluationConfig(
    val name: String = "",
    val games: Int = 10,
    val maxTurns: Int = 500,
    val seedStart: Long = 1L,
    val pairMatchesBySeed: Boolean = true,
    val saveFinalGames: Boolean = false,
    val saveOnlyInterestingGames: Boolean = true,
    val gameParameters: GameParameters = GameParameters(),
    val mapParameters: MapParameters = MapParameters(),
)

@ExperimentalTime
object AgentBatchEvaluationRunner {
    fun run(configPath: String): AgentEvaluationBatchSummary {
        val configFile = FileHandle(configPath)
        val config = json().fromJsonFile(AgentEvaluationConfig::class.java, configFile)
        return runConfig(
            config = config,
            configPath = configFile.path(),
            rawConfigJson = configFile.readString(Charsets.UTF_8.name()),
        )
    }

    fun runConfig(
        config: AgentEvaluationConfig,
        configPath: String,
        rawConfigJson: String,
        batchId: String = createBatchId(config.name),
        shouldCancel: () -> Boolean = { false },
    ): AgentEvaluationBatchSummary {
        validateConfig(config)
        val batchStartedAt = System.currentTimeMillis()
        val batchName = config.name.ifBlank { batchId }
        val plannedMatches = config.games * if (config.pairMatchesBySeed) 2 else 1
        val completedMatches = mutableListOf<AgentEvaluationMatchSummary>()

        fun throwIfCancelled() {
            if (shouldCancel() || Thread.currentThread().isInterrupted) {
                throw CancellationException("Batch cancelled by user")
            }
        }

        AgentEvaluationStore.writeConfig(batchId, rawConfigJson)
        AgentEvaluationStore.writeBatchSummary(
            AgentEvaluationAnalyzer.buildBatchSummary(
                batchId = batchId,
                name = batchName,
                configPath = configPath,
                startedAtEpochMs = batchStartedAt,
                finishedAtEpochMs = null,
                requestedGames = config.games,
                plannedMatches = plannedMatches,
                pairMatchesBySeed = config.pairMatchesBySeed,
                maxTurns = config.maxTurns,
                baseRuleset = config.gameParameters.baseRuleset,
                difficulty = config.gameParameters.difficulty,
                mapType = config.mapParameters.type,
                mapSize = config.mapParameters.mapSize.name,
                matches = completedMatches,
                statusOverride = "running",
            ),
        )

        println("Starting batch $batchId with $plannedMatches matches")

        var finalStatus = "completed"

        try {
            for (index in 0 until config.games) {
                throwIfCancelled()
                val seed = config.seedStart + index
                val primaryMatch = runSingleMatch(
                    batchId = batchId,
                    config = config,
                    configPath = configPath,
                    seed = seed,
                    pairIndex = 0,
                    rolesSwapped = false,
                    shouldCancel = shouldCancel,
                )
                completedMatches += primaryMatch
                AgentEvaluationStore.writeBatchSummary(
                    buildBatchSummary(
                        batchId,
                        batchName,
                        configPath,
                        batchStartedAt,
                        config,
                        completedMatches,
                        null,
                        statusOverride = "running",
                    ),
                )
                if (primaryMatch.status == "cancelled") {
                    finalStatus = "cancelled"
                    break
                }

                if (config.pairMatchesBySeed) {
                    throwIfCancelled()
                    val swappedMatch = runSingleMatch(
                        batchId = batchId,
                        config = config,
                        configPath = configPath,
                        seed = seed,
                        pairIndex = 1,
                        rolesSwapped = true,
                        shouldCancel = shouldCancel,
                    )
                    completedMatches += swappedMatch
                    AgentEvaluationStore.writeBatchSummary(
                        buildBatchSummary(
                            batchId,
                            batchName,
                            configPath,
                            batchStartedAt,
                            config,
                            completedMatches,
                            null,
                            statusOverride = "running",
                        ),
                    )
                    if (swappedMatch.status == "cancelled") {
                        finalStatus = "cancelled"
                        break
                    }
                }
            }
        } catch (_: CancellationException) {
            finalStatus = "cancelled"
        }

        val finishedAtEpochMs = System.currentTimeMillis()
        val finalBatchSummary = buildBatchSummary(
            batchId,
            batchName,
            configPath,
            batchStartedAt,
            config,
            completedMatches,
            finishedAtEpochMs,
            statusOverride = finalStatus,
        )
        AgentEvaluationStore.writeBatchSummary(finalBatchSummary)

        println(
            "${if (finalStatus == "cancelled") "Cancelled" else "Completed"} batch $batchId: " +
                "agentWins=${finalBatchSummary.agentWins}, " +
                "legacyWins=${finalBatchSummary.legacyWins}, " +
                "draws=${finalBatchSummary.draws}, " +
                "fallbackRate=${"%.1f".format(finalBatchSummary.fallbackRate * 100)}%, " +
                "illegalRate=${"%.1f".format(finalBatchSummary.illegalActionRate * 100)}%",
        )
        return finalBatchSummary
    }

    private fun runSingleMatch(
        batchId: String,
        config: AgentEvaluationConfig,
        configPath: String,
        seed: Long,
        pairIndex: Int,
        rolesSwapped: Boolean,
        shouldCancel: () -> Boolean = { false },
    ): AgentEvaluationMatchSummary {
        val startedAt = System.currentTimeMillis()
        val matchId = "$batchId-s${seed}-p${pairIndex}"
        val label = "Seed $seed${if (rolesSwapped) " · swapped" else ""}"

        AgentObservability.clear()
        var listenerId: Long? = null
        var traceWriter: AgentEvaluationTraceWriter? = null
        val capturedEvents = mutableListOf<com.unciv.logic.automation.agent.AgentObservabilityEvent>()
        var gameInfo: com.unciv.logic.GameInfo? = null
        var agentCivName: String? = null
        var legacyCivName: String? = null

        fun throwIfCancelled() {
            if (shouldCancel() || Thread.currentThread().isInterrupted) {
                throw CancellationException("Batch cancelled by user")
            }
        }

        try {
            throwIfCancelled()
            val gameParameters = config.gameParameters.deepCopyForEvaluation().withBatchDefaults()
            if (rolesSwapped) gameParameters.swapAgentAndLegacyPlayerTypes()
            val mapParameters = config.mapParameters.clone().apply { this.seed = seed }
            val gameSetupInfo = GameSetupInfo(gameParameters, mapParameters)
            gameInfo = GameStarter.startNewGame(gameSetupInfo)
            if (gameInfo.gameParameters.victoryTypes.isEmpty()) {
                gameInfo.gameParameters.victoryTypes = ArrayList(gameInfo.ruleset.victories.keys)
            }
            UncivGame.Current.gameInfo = gameInfo

            agentCivName = gameInfo.civilizations.firstOrNull {
                !it.isSpectator() && it.isMajorCiv() && it.playerType == PlayerType.AI_AGENT
            }?.civName ?: error("No AI_AGENT civilization found after game setup")
            legacyCivName = gameInfo.civilizations.firstOrNull {
                !it.isSpectator() && it.isMajorCiv() && it.playerType == PlayerType.AI
            }?.civName ?: error("No legacy AI civilization found after game setup")
            val agentCivNameValue = agentCivName
            val legacyCivNameValue = legacyCivName

            val initialSummary = AgentEvaluationMatchSummary(
                batchId = batchId,
                matchId = matchId,
                label = "$label · $agentCivNameValue vs $legacyCivNameValue",
                seed = seed,
                pairIndex = pairIndex,
                rolesSwapped = rolesSwapped,
                startedAtEpochMs = startedAt,
                status = "running",
                gameId = gameInfo.gameId,
                agentCivName = agentCivNameValue,
                legacyCivName = legacyCivNameValue,
            )
            AgentEvaluationStore.writeMatchSummary(initialSummary)

            traceWriter = AgentEvaluationStore.createTraceWriter(batchId, matchId)
            val localTraceWriter = traceWriter
            listenerId = AgentObservability.addListener { event ->
                capturedEvents += event
                localTraceWriter.append(event)
            }

            println("Running ${initialSummary.label}")
            throwIfCancelled()
            val supportsBattlefieldSnapshots = AgentBatchEvaluationEnvironment.isRenderCapable()
            if (supportsBattlefieldSnapshots) {
                runTurnByTurnMatch(
                    batchId = batchId,
                    matchId = matchId,
                    gameInfo = gameInfo,
                    agentCivName = agentCivNameValue,
                    maxTurns = config.maxTurns,
                    capturedEvents = capturedEvents,
                    shouldCancel = shouldCancel,
                )
            } else {
                gameInfo.simulateUntilWin = true
                gameInfo.simulateMaxTurns = config.maxTurns
                gameInfo.nextTurn()
            }
            throwIfCancelled()

            val finishedAt = System.currentTimeMillis()
            val turnSummaries = AgentEvaluationAnalyzer.analyzeTurns(capturedEvents, agentCivNameValue)
                .withReplayScreenshots(batchId, matchId)
            AgentEvaluationStore.writeTurnSummaries(batchId, matchId, turnSummaries)
            var matchSummary = AgentEvaluationAnalyzer.buildMatchSummary(
                batchId = batchId,
                matchId = matchId,
                label = initialSummary.label,
                seed = seed,
                pairIndex = pairIndex,
                rolesSwapped = rolesSwapped,
                startedAtEpochMs = startedAt,
                finishedAtEpochMs = finishedAt,
                gameInfo = gameInfo,
                agentCivName = agentCivNameValue,
                legacyCivName = legacyCivNameValue,
                turnSummaries = turnSummaries,
                finalSaveFileName = null,
            )

            val shouldSaveGame = config.saveFinalGames && (!config.saveOnlyInterestingGames || matchSummary.interesting)
            if (shouldSaveGame) {
                val finalSaveFileName = AgentEvaluationStore.writeFinalSave(batchId, matchId, gameInfo)
                matchSummary = matchSummary.copy(finalSaveFileName = finalSaveFileName)
            }

            AgentEvaluationStore.writeMatchSummary(matchSummary)
            println(
                "Completed ${matchSummary.label}: " +
                    "winner=${matchSummary.winnerSide}, " +
                    "turns=${matchSummary.totalTurns}, " +
                    "fallbackTurns=${matchSummary.fallbackTurns}, " +
                    "blockedTurns=${matchSummary.blockedTurns}",
            )
            return matchSummary
        } catch (ex: CancellationException) {
            val finishedAt = System.currentTimeMillis()
            val turnSummaries = agentCivName
                ?.let { AgentEvaluationAnalyzer.analyzeTurns(capturedEvents, it).withReplayScreenshots(batchId, matchId) }
                ?: emptyList()
            AgentEvaluationStore.writeTurnSummaries(batchId, matchId, turnSummaries)

            val cancelledSummary = if (gameInfo != null && agentCivName != null && legacyCivName != null) {
                AgentEvaluationAnalyzer.buildMatchSummary(
                    batchId = batchId,
                    matchId = matchId,
                    label = "$label · $agentCivName vs $legacyCivName",
                    seed = seed,
                    pairIndex = pairIndex,
                    rolesSwapped = rolesSwapped,
                    startedAtEpochMs = startedAt,
                    finishedAtEpochMs = finishedAt,
                    gameInfo = gameInfo,
                    agentCivName = agentCivName,
                    legacyCivName = legacyCivName,
                    turnSummaries = turnSummaries,
                    finalSaveFileName = null,
                    failureMessage = "Batch cancelled by user",
                    statusOverride = "cancelled",
                    winnerSideOverride = "cancelled",
                )
            } else {
                AgentEvaluationMatchSummary(
                    batchId = batchId,
                    matchId = matchId,
                    label = label,
                    seed = seed,
                    pairIndex = pairIndex,
                    rolesSwapped = rolesSwapped,
                    startedAtEpochMs = startedAt,
                    finishedAtEpochMs = finishedAt,
                    status = "cancelled",
                    winnerSide = "cancelled",
                    topConcerns = listOf("Batch cancelled by user"),
                )
            }

            AgentEvaluationStore.writeMatchSummary(cancelledSummary)
            return cancelledSummary
        } catch (ex: Exception) {
            Log.error("Agent evaluation match failed", ex)
            val failureSummary = buildList {
                add(ex::class.simpleName.orEmpty())
                ex.message?.takeIf { it.isNotBlank() }?.let(::add)
                addAll(
                    ex.stackTrace
                        .take(4)
                        .map { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" },
                )
            }.distinct().filter { it.isNotBlank() }
            val failedSummary = AgentEvaluationMatchSummary(
                batchId = batchId,
                matchId = matchId,
                label = label,
                seed = seed,
                pairIndex = pairIndex,
                rolesSwapped = rolesSwapped,
                startedAtEpochMs = startedAt,
                finishedAtEpochMs = System.currentTimeMillis(),
                status = "failed",
                winnerSide = "failed",
                topConcerns = failureSummary,
            )
            AgentEvaluationStore.writeMatchSummary(failedSummary)
            AgentEvaluationStore.writeTurnSummaries(batchId, matchId, emptyList())
            return failedSummary
        } finally {
            listenerId?.let(AgentObservability::removeListener)
            traceWriter?.close()
        }
    }

    private fun runTurnByTurnMatch(
        batchId: String,
        matchId: String,
        gameInfo: com.unciv.logic.GameInfo,
        agentCivName: String,
        maxTurns: Int,
        capturedEvents: List<com.unciv.logic.automation.agent.AgentObservabilityEvent>,
        shouldCancel: () -> Boolean,
    ) {
        val capturedTurns = mutableSetOf<Int>()
        gameInfo.simulateUntilWin = true
        gameInfo.simulateMaxTurns = maxTurns

        while (gameInfo.simulateUntilWin && gameInfo.turns < maxTurns) {
            if (shouldCancel() || Thread.currentThread().isInterrupted) {
                throw CancellationException("Batch cancelled by user")
            }

            val nextStopTurn = gameInfo.turns + 1
            gameInfo.simulateMaxTurns = nextStopTurn
            UncivGame.Current.gameInfo = gameInfo
            gameInfo.nextTurn()

            val resolvedTurn = capturedEvents
                .lastOrNull { event ->
                    event.civName == agentCivName &&
                        event.type in setOf("plan_applied", "fallback_legacy", "plan_missing")
                }
                ?.turn

            if (resolvedTurn != null && capturedTurns.add(resolvedTurn)) {
                captureBattlefieldSnapshot(batchId, matchId, agentCivName, resolvedTurn, gameInfo)
            }
        }
    }

    private fun captureBattlefieldSnapshot(
        batchId: String,
        matchId: String,
        civName: String,
        turn: Int,
        gameInfo: com.unciv.logic.GameInfo,
    ) {
        runCatching {
            val originalShowTutorials = runCatching { UncivGame.Current.settings.showTutorials }.getOrDefault(true)
            val originalShowSettlerSuggestions = runCatching {
                UncivGame.Current.settings.showSettlersSuggestedCityLocations
            }.getOrDefault(true)
            val liveGameInfo = gameInfo
            val displayGameInfo = gameInfo.clone()
            displayGameInfo.tileMap.mapParameters = displayGameInfo.tileMap.mapParameters.clone().apply {
                worldWrap = false
            }
            displayGameInfo.currentPlayer = civName
            displayGameInfo.setTransients()
            val viewingCiv = displayGameInfo.getCivilization(civName)
            viewingCiv.popupAlerts.clear()
            viewingCiv.tradeRequests.clear()
            viewingCiv.greatPeople.freeGreatPeople = 0
            viewingCiv.flagsCountdown.remove("ShowDiplomaticVotingResults")

            UncivGame.Current.settings.showTutorials = false
            UncivGame.Current.settings.showSettlersSuggestedCityLocations = false
            try {
                Concurrency.runBlocking("agent-dashboard-load-turn-$turn") {
                    UncivGame.Current.loadGame(displayGameInfo)
                }

                UncivGame.Current.worldScreen?.clearSelectionForCapture()

                focusWorldScreenForScreenshot(civName)
                val pngBytes = AgentLiveScreenshotService.capturePng()
                AgentLiveTurnScreenshotStore.save(AgentObservability.currentGeneration(), civName, turn, pngBytes)
                AgentEvaluationStore.writeTurnScreenshot(batchId, matchId, civName, turn, pngBytes)
            } finally {
                runCatching {
                    Concurrency.runBlocking("agent-dashboard-restore-live-game-$turn") {
                        UncivGame.Current.loadGame(liveGameInfo)
                    }
                }.onFailure { restoreError ->
                    Log.debug("Agent dashboard failed to restore live game after screenshot capture for %s turn %s", civName, turn)
                    Log.debug(restoreError.toString())
                }
                UncivGame.Current.settings.showTutorials = originalShowTutorials
                UncivGame.Current.settings.showSettlersSuggestedCityLocations = originalShowSettlerSuggestions
            }
        }.onFailure { error ->
            Log.debug("Agent dashboard screenshot capture failed for %s turn %s", civName, turn)
            Log.debug(error.toString())
        }
    }

    private fun focusWorldScreenForScreenshot(civName: String) {
        val worldScreen = UncivGame.Current.worldScreen ?: return
        val mapHolder = worldScreen.mapHolder
        val viewingCiv = worldScreen.gameInfo.getCivilization(civName)

        val interestTiles = LinkedHashSet<Tile>()
        interestTiles.addAll(viewingCiv.cities.map { it.getCenterTile() })
        interestTiles.addAll(viewingCiv.units.getCivUnits().map { it.getTile() })
        interestTiles.addAll(
            viewingCiv.viewableTiles.filter { tile ->
                tile.isCityCenter() && tile.getCity()?.civ != viewingCiv
            },
        )
        interestTiles.addAll(
            viewingCiv.viewableTiles
                .flatMap { tile ->
                    tile.getUnits().filter { unit ->
                        unit.civ != viewingCiv
                    }.map { it.getTile() }
                },
        )

        if (interestTiles.isEmpty()) {
            viewingCiv.getCapital()?.getCenterTile()?.let(interestTiles::add)
            viewingCiv.units.getCivUnits().firstOrNull()?.getTile()?.let(interestTiles::add)
        }

        val interestGroups = interestTiles.mapNotNull(mapHolder.tileGroups::get).distinct()
        if (interestGroups.isEmpty()) return

        val padding = TileGroupMap.groupSize * 2f
        val minX = interestGroups.minOf { it.x } - padding
        val maxX = interestGroups.maxOf { it.x + it.width } + padding
        val minY = interestGroups.minOf { it.y } - padding
        val maxY = interestGroups.maxOf { it.y + it.height } + padding

        val desiredWidth = maxX - minX
        val desiredHeight = maxY - minY
        val targetZoom = min(
            (mapHolder.width * mapHolder.scaleX) / desiredWidth,
            (mapHolder.height * mapHolder.scaleY) / desiredHeight,
        )

        mapHolder.zoom(targetZoom)
        val centerX = (minX + maxX) / 2f
        val centerY = (minY + maxY) / 2f
        mapHolder.scrollTo(centerX, mapHolder.maxY - centerY, immediately = true)
        worldScreen.shouldUpdate = true
    }

    private fun List<AgentEvaluationTurnSummary>.withReplayScreenshots(
        batchId: String,
        matchId: String,
    ): List<AgentEvaluationTurnSummary> {
        return map { turnSummary ->
            turnSummary.copy(
                screenshotFileName = AgentEvaluationStore.findTurnScreenshotFileName(
                    batchId = batchId,
                    matchId = matchId,
                    civName = turnSummary.civName,
                    turn = turnSummary.turn,
                ),
            )
        }
    }

    private fun buildBatchSummary(
        batchId: String,
        name: String,
        configPath: String,
        startedAtEpochMs: Long,
        config: AgentEvaluationConfig,
        matches: List<AgentEvaluationMatchSummary>,
        finishedAtEpochMs: Long?,
        statusOverride: String? = null,
    ): AgentEvaluationBatchSummary {
        return AgentEvaluationAnalyzer.buildBatchSummary(
            batchId = batchId,
            name = name,
            configPath = configPath,
            startedAtEpochMs = startedAtEpochMs,
            finishedAtEpochMs = finishedAtEpochMs,
            requestedGames = config.games,
            plannedMatches = config.games * if (config.pairMatchesBySeed) 2 else 1,
            pairMatchesBySeed = config.pairMatchesBySeed,
            maxTurns = config.maxTurns,
            baseRuleset = config.gameParameters.baseRuleset,
            difficulty = config.gameParameters.difficulty,
            mapType = config.mapParameters.type,
            mapSize = config.mapParameters.mapSize.name,
            matches = matches,
            statusOverride = statusOverride,
        )
    }

    private fun validateConfig(config: AgentEvaluationConfig) {
        require(config.games > 0) { "games must be > 0" }
        require(config.maxTurns > 0) { "maxTurns must be > 0" }

        val nonSpectatorPlayers = config.gameParameters.players.filter { it.chosenCiv != Constants.spectator }
        require(nonSpectatorPlayers.count { it.playerType == PlayerType.AI_AGENT } == 1) {
            "Config must contain exactly one AI_AGENT non-spectator player"
        }
        require(nonSpectatorPlayers.count { it.playerType == PlayerType.AI } == 1) {
            "Config must contain exactly one legacy AI non-spectator player"
        }
        require(nonSpectatorPlayers.none { it.playerType == PlayerType.Human }) {
            "Batch evaluation supports only AI_AGENT vs AI for non-spectator players"
        }
    }

    fun createBatchId(name: String): String {
        val slug = name
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifBlank { "agent-eval" }
        return "$slug-${System.currentTimeMillis()}"
    }

    private fun GameParameters.withBatchDefaults(): GameParameters {
        val parameters = this
        parameters.players = ArrayList(parameters.players)
        if (parameters.players.none { it.chosenCiv == Constants.spectator }) {
            parameters.players.add(Player(Constants.spectator, PlayerType.Human))
        }
        parameters.isOnlineMultiplayer = false
        parameters.shufflePlayerOrder = false
        return parameters
    }

    private fun GameParameters.swapAgentAndLegacyPlayerTypes() {
        players.forEach { player ->
            when (player.playerType) {
                PlayerType.AI_AGENT -> player.playerType = PlayerType.AI
                PlayerType.AI -> player.playerType = PlayerType.AI_AGENT
                PlayerType.Human -> Unit
            }
        }
    }

    private fun GameParameters.deepCopyForEvaluation(): GameParameters {
        val copy = clone()
        copy.players = players.map { player ->
            Player(
                chosenCiv = player.chosenCiv,
                playerType = player.playerType,
                playerId = player.playerId,
            )
        }.toCollection(ArrayList())
        return copy
    }
}

@ExperimentalTime
internal object AgentBatchEvaluationLauncher {
    @JvmStatic
    fun main(arg: Array<String>) {
        require(arg.isNotEmpty()) { "Usage: AgentBatchEvaluationLauncher <config.json>" }

        AgentBatchEvaluationEnvironment.ensureReady(startObservabilityServer = true)
        AgentBatchEvaluationRunner.run(arg[0])
    }
}

@ExperimentalTime
internal object AgentBatchEvaluationEnvironment {
    private var initialized = false

    @Synchronized
    fun ensureReady(startObservabilityServer: Boolean = false) {
        Log.backend = DesktopLogBackend()

        if (!initialized) {
            val canReuseDesktopGame = UncivGame.isCurrentInitialized() &&
                !UncivGame.Current.isConsoleMode &&
                runCatching { UncivGame.Current.settings }.isSuccess

            if (!canReuseDesktopGame) {
                val game = UncivGame(true)
                UncivGame.Current = game
                UncivGame.Current.settings = GameSettings().apply {
                    showTutorials = false
                    turnsBetweenAutosaves = 10000
                }

                RulesetCache.loadRulesets(true)
                TileSetCache.loadTileSetConfigs(true)
                SkinCache.loadSkinConfigs(true)
            }
            initialized = true
        }

        if (startObservabilityServer) {
            AgentObservabilityServer.startFromEnvironment()
        }
    }

    fun isRenderCapable(): Boolean {
        return initialized &&
            UncivGame.isCurrentInitialized() &&
            !UncivGame.Current.isConsoleMode &&
            runCatching { UncivGame.Current.settings }.isSuccess
    }
}
