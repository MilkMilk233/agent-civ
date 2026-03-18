package com.unciv.app.desktop

import com.badlogic.gdx.files.FileHandle
import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.json.fromJsonFile
import com.unciv.json.json
import com.unciv.logic.GameStarter
import com.unciv.logic.automation.agent.AgentObservability
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.map.MapParameters
import com.unciv.models.metadata.GameParameters
import com.unciv.models.metadata.GameSettings
import com.unciv.models.metadata.GameSetupInfo
import com.unciv.models.metadata.Player
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.skins.SkinCache
import com.unciv.models.tilesets.TileSetCache
import com.unciv.utils.Log
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
    fun run(configPath: String) {
        val configFile = FileHandle(configPath)
        val config = json().fromJsonFile(AgentEvaluationConfig::class.java, configFile)
        validateConfig(config)

        val batchId = buildBatchId(config.name)
        val batchStartedAt = System.currentTimeMillis()
        val batchName = config.name.ifBlank { batchId }
        val plannedMatches = config.games * if (config.pairMatchesBySeed) 2 else 1
        val completedMatches = mutableListOf<AgentEvaluationMatchSummary>()

        AgentEvaluationStore.writeConfig(batchId, configFile.readString(Charsets.UTF_8.name()))
        AgentEvaluationStore.writeBatchSummary(
            AgentEvaluationAnalyzer.buildBatchSummary(
                batchId = batchId,
                name = batchName,
                configPath = configFile.path(),
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
            ),
        )

        println("Starting batch $batchId with $plannedMatches matches")

        for (index in 0 until config.games) {
            val seed = config.seedStart + index
            completedMatches += runSingleMatch(
                batchId = batchId,
                config = config,
                configPath = configFile.path(),
                seed = seed,
                pairIndex = 0,
                rolesSwapped = false,
            )
            AgentEvaluationStore.writeBatchSummary(buildBatchSummary(batchId, batchName, configFile.path(), batchStartedAt, config, completedMatches, null))

            if (config.pairMatchesBySeed) {
                completedMatches += runSingleMatch(
                    batchId = batchId,
                    config = config,
                    configPath = configFile.path(),
                    seed = seed,
                    pairIndex = 1,
                    rolesSwapped = true,
                )
                AgentEvaluationStore.writeBatchSummary(buildBatchSummary(batchId, batchName, configFile.path(), batchStartedAt, config, completedMatches, null))
            }
        }

        val finishedAtEpochMs = System.currentTimeMillis()
        val finalBatchSummary = buildBatchSummary(batchId, batchName, configFile.path(), batchStartedAt, config, completedMatches, finishedAtEpochMs)
        AgentEvaluationStore.writeBatchSummary(finalBatchSummary)

        println(
            "Completed batch $batchId: " +
                "agentWins=${finalBatchSummary.agentWins}, " +
                "legacyWins=${finalBatchSummary.legacyWins}, " +
                "draws=${finalBatchSummary.draws}, " +
                "fallbackRate=${"%.1f".format(finalBatchSummary.fallbackRate * 100)}%, " +
                "illegalRate=${"%.1f".format(finalBatchSummary.illegalActionRate * 100)}%",
        )
    }

    private fun runSingleMatch(
        batchId: String,
        config: AgentEvaluationConfig,
        configPath: String,
        seed: Long,
        pairIndex: Int,
        rolesSwapped: Boolean,
    ): AgentEvaluationMatchSummary {
        val startedAt = System.currentTimeMillis()
        val matchId = "$batchId-s${seed}-p${pairIndex}"
        val label = "Seed $seed${if (rolesSwapped) " · swapped" else ""}"

        AgentObservability.clear()
        var listenerId: Long? = null
        var traceWriter: AgentEvaluationTraceWriter? = null
        val capturedEvents = mutableListOf<com.unciv.logic.automation.agent.AgentObservabilityEvent>()

        try {
            val gameParameters = config.gameParameters.deepCopyForEvaluation().withBatchDefaults()
            if (rolesSwapped) gameParameters.swapAgentAndLegacyPlayerTypes()
            val mapParameters = config.mapParameters.clone().apply { this.seed = seed }
            val gameSetupInfo = GameSetupInfo(gameParameters, mapParameters)
            val gameInfo = GameStarter.startNewGame(gameSetupInfo)
            gameInfo.gameParameters.victoryTypes = ArrayList(gameInfo.ruleset.victories.keys)
            gameInfo.simulateUntilWin = true
            gameInfo.simulateMaxTurns = config.maxTurns
            UncivGame.Current.gameInfo = gameInfo

            val agentCivName = gameInfo.civilizations.firstOrNull {
                !it.isSpectator() && it.isMajorCiv() && it.playerType == PlayerType.AI_AGENT
            }?.civName ?: error("No AI_AGENT civilization found after game setup")
            val legacyCivName = gameInfo.civilizations.firstOrNull {
                !it.isSpectator() && it.isMajorCiv() && it.playerType == PlayerType.AI
            }?.civName ?: error("No legacy AI civilization found after game setup")

            val initialSummary = AgentEvaluationMatchSummary(
                batchId = batchId,
                matchId = matchId,
                label = "$label · $agentCivName vs $legacyCivName",
                seed = seed,
                pairIndex = pairIndex,
                rolesSwapped = rolesSwapped,
                startedAtEpochMs = startedAt,
                status = "running",
                gameId = gameInfo.gameId,
                agentCivName = agentCivName,
                legacyCivName = legacyCivName,
            )
            AgentEvaluationStore.writeMatchSummary(initialSummary)

            traceWriter = AgentEvaluationStore.createTraceWriter(batchId, matchId)
            val localTraceWriter = traceWriter
            listenerId = AgentObservability.addListener { event ->
                capturedEvents += event
                localTraceWriter.append(event)
            }

            println("Running ${initialSummary.label}")
            gameInfo.nextTurn()

            val finishedAt = System.currentTimeMillis()
            val turnSummaries = AgentEvaluationAnalyzer.analyzeTurns(capturedEvents, agentCivName)
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
                agentCivName = agentCivName,
                legacyCivName = legacyCivName,
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

    private fun buildBatchSummary(
        batchId: String,
        name: String,
        configPath: String,
        startedAtEpochMs: Long,
        config: AgentEvaluationConfig,
        matches: List<AgentEvaluationMatchSummary>,
        finishedAtEpochMs: Long?,
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

    private fun buildBatchId(name: String): String {
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

        Log.backend = DesktopLogBackend()
        val game = UncivGame(true)
        UncivGame.Current = game
        UncivGame.Current.settings = GameSettings().apply {
            showTutorials = false
            turnsBetweenAutosaves = 10000
        }

        RulesetCache.loadRulesets(true)
        TileSetCache.loadTileSetConfigs(true)
        SkinCache.loadSkinConfigs(true)
        AgentObservabilityServer.startFromEnvironment()

        AgentBatchEvaluationRunner.run(arg[0])
    }
}
