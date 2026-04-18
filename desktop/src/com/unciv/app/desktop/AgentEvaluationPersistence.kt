package com.unciv.app.desktop

import com.unciv.logic.GameInfo
import com.unciv.logic.VictoryData
import com.unciv.logic.automation.agent.AgentObservabilityEvent
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.files.UncivFiles
import java.io.BufferedWriter
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class AgentEvaluationBatchSummary(
    val batchId: String = "",
    val name: String = "",
    val configPath: String = "",
    val startedAtEpochMs: Long = 0L,
    val finishedAtEpochMs: Long? = null,
    val status: String = "running",
    val requestedGames: Int = 0,
    val plannedMatches: Int = 0,
    val completedMatches: Int = 0,
    val failedMatches: Int = 0,
    val cancelledMatches: Int = 0,
    val pairMatchesBySeed: Boolean = true,
    val maxTurns: Int = 0,
    val baseRuleset: String = "",
    val difficulty: String = "",
    val mapType: String = "",
    val mapSize: String = "",
    val agentLabel: String = "AI_AGENT",
    val legacyLabel: String = "AI",
    val agentWins: Int = 0,
    val legacyWins: Int = 0,
    val draws: Int = 0,
    val avgTurns: Double = 0.0,
    val avgInferenceLatencyMs: Double? = null,
    val fallbackRate: Double = 0.0,
    val blockedRate: Double = 0.0,
    val illegalActionRate: Double = 0.0,
)

@Serializable
data class AgentEvaluationMatchSummary(
    val batchId: String = "",
    val matchId: String = "",
    val label: String = "",
    val seed: Long = 0L,
    val pairIndex: Int = 0,
    val rolesSwapped: Boolean = false,
    val startedAtEpochMs: Long = 0L,
    val finishedAtEpochMs: Long? = null,
    val status: String = "running",
    val gameId: String = "",
    val agentCivName: String = "",
    val legacyCivName: String = "",
    val winnerCivName: String? = null,
    val winnerSide: String = "draw",
    val victoryType: String? = null,
    val totalTurns: Int = 0,
    val agentTurnCount: Int = 0,
    val fallbackTurns: Int = 0,
    val blockedTurns: Int = 0,
    val avgInferenceLatencyMs: Double? = null,
    val illegalActionRate: Double = 0.0,
    val maxRejectedActionsOnTurn: Int = 0,
    val interesting: Boolean = false,
    val topConcerns: List<String> = emptyList(),
    val finalSaveFileName: String? = null,
)

@Serializable
data class AgentEvaluationTurnSummary(
    val civName: String = "",
    val turn: Int = 0,
    val status: String = "idle",
    val statusLabel: String = "Observing",
    val plannedActions: Int = 0,
    val executedActions: Int = 0,
    val rejectedActions: Int = 0,
    val llmLatencyMs: Long? = null,
    val fallback: Boolean = false,
    val blocked: Boolean = false,
    val illegalActionRate: Double = 0.0,
    val notes: String? = null,
    val topConcern: String? = null,
    val checkpointFileName: String? = null,
)

@Serializable
data class AgentEvaluationReplayResponse(
    val batch: AgentEvaluationBatchSummary,
    val match: AgentEvaluationMatchSummary,
    val turnSummaries: List<AgentEvaluationTurnSummary>,
    val recentEvents: List<AgentObservabilityEvent>,
)

object AgentEvaluationJson {
    val json = Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    val compactJson = Json {
        prettyPrint = false
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }
}

object AgentEvaluationStore {
    private const val batchesDirName = "batches"
    private const val batchSummaryFile = "batch-summary.json"
    private const val matchSummaryFile = "match-summary.json"
    private const val turnSummaryFile = "turn-summaries.json"
    private const val eventsFile = "events.jsonl"
    private const val configFile = "config.json"
    private const val finalSaveFile = "final-game.uncivsave"
    private const val turnCheckpointDirName = "turn-checkpoints"
    private const val battlefieldRenderDirName = "battlefield-renders"
    private const val battlefieldRenderVersion = "v6"
    private const val staleRunThresholdMs = 3 * 60 * 1000L

    fun rootDir(): Path = Paths.get(
        (System.getenv("UNCIV_AGENT_EVAL_DIR") ?: "agent-evaluations").trim().ifEmpty { "agent-evaluations" },
    )

    fun readRootDirs(): List<Path> {
        val configuredRoots = (System.getenv("UNCIV_AGENT_EVAL_DIRS") ?: "")
            .split(File.pathSeparatorChar)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { Paths.get(it).toAbsolutePath().normalize() }

        return (configuredRoots + rootDir().toAbsolutePath().normalize()).distinct()
    }

    fun storageRootsDescription(): String {
        return readRootDirs().joinToString(" ; ") { it.toString() }
    }

    fun renderCacheRoot(): Path {
        val configured = (System.getenv("UNCIV_AGENT_RENDER_CACHE_DIR") ?: "")
            .trim()
            .takeIf { it.isNotEmpty() }
        return if (configured != null) {
            Paths.get(configured).toAbsolutePath().normalize()
        } else {
            Paths.get(System.getProperty("java.io.tmpdir"))
                .resolve("unciv-agent-replay-cache")
                .toAbsolutePath()
                .normalize()
        }
    }

    fun batchDir(batchId: String): Path = rootDir().resolve(batchesDirName).resolve(batchId)

    fun matchDir(batchId: String, matchId: String): Path = batchDir(batchId).resolve("matches").resolve(matchId)

    fun writeBatchSummary(summary: AgentEvaluationBatchSummary) {
        writeJson(batchDir(summary.batchId).resolve(batchSummaryFile), summary)
    }

    fun writeConfig(batchId: String, rawConfigJson: String) {
        val path = batchDir(batchId).resolve(configFile)
        ensureParent(path)
        Files.write(path, rawConfigJson.toByteArray(StandardCharsets.UTF_8))
    }

    fun writeMatchSummary(summary: AgentEvaluationMatchSummary) {
        writeJson(matchDir(summary.batchId, summary.matchId).resolve(matchSummaryFile), summary)
    }

    fun writeTurnSummaries(batchId: String, matchId: String, turns: List<AgentEvaluationTurnSummary>) {
        writeJson(matchDir(batchId, matchId).resolve(turnSummaryFile), turns)
    }

    fun createTraceWriter(batchId: String, matchId: String): AgentEvaluationTraceWriter {
        val path = matchDir(batchId, matchId).resolve(eventsFile)
        ensureParent(path)
        val writer = Files.newBufferedWriter(
            path,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
        return AgentEvaluationTraceWriter(writer)
    }

    fun writeFinalSave(batchId: String, matchId: String, gameInfo: GameInfo): String {
        val path = matchDir(batchId, matchId).resolve(finalSaveFile)
        ensureParent(path)
        Files.writeString(
            path,
            UncivFiles.gameInfoToString(gameInfo, forceZip = true, updateChecksum = true),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
        return path.fileName.toString()
    }

    fun writeTurnCheckpoint(batchId: String, matchId: String, civName: String, turn: Int, gameInfo: GameInfo): String {
        val path = matchDir(batchId, matchId)
            .resolve(turnCheckpointDirName)
            .resolve(turnCheckpointFileName(civName, turn))
        ensureParent(path)
        Files.writeString(
            path,
            UncivFiles.gameInfoToString(gameInfo, forceZip = true, updateChecksum = true),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
        return path.fileName.toString()
    }

    fun reconcileStaleRunningEntries(
        activeBatchId: String? = null,
        nowEpochMs: Long = System.currentTimeMillis(),
    ) {
        writableBatchDirs().forEach { entry ->
            val batchSummary = readJsonOrNull<AgentEvaluationBatchSummary>(entry.resolve(batchSummaryFile)) ?: return@forEach
            if (batchSummary.status != "running") return@forEach
            if (batchSummary.batchId == activeBatchId) return@forEach

            val lastActivityEpochMs = batchLastActivityEpochMs(batchSummary.batchId) ?: batchSummary.startedAtEpochMs
            if (nowEpochMs - lastActivityEpochMs <= staleRunThresholdMs) return@forEach

            abortBatch(batchSummary, lastActivityEpochMs)
        }
    }

    fun listBatches(limit: Int = 100): List<AgentEvaluationBatchSummary> {
        return readableBatchDirs()
            .mapNotNull { entry -> readJsonOrNull<AgentEvaluationBatchSummary>(entry.resolve(batchSummaryFile)) }
            .distinctBy { it.batchId }
            .sortedWith(compareByDescending<AgentEvaluationBatchSummary> { it.startedAtEpochMs }.thenByDescending { it.batchId })
            .take(limit)
    }

    fun loadBatch(batchId: String): AgentEvaluationBatchSummary? {
        return findBatchDir(batchId)?.let { readJsonOrNull(it.resolve(batchSummaryFile)) }
    }

    fun listMatches(batchId: String): List<AgentEvaluationMatchSummary> {
        val dir = findBatchDir(batchId)?.resolve("matches") ?: return emptyList()
        if (!dir.exists() || !dir.isDirectory()) return emptyList()
        return dir.listDirectoryEntries()
            .mapNotNull { entry -> readJsonOrNull<AgentEvaluationMatchSummary>(entry.resolve(matchSummaryFile)) }
            .sortedWith(compareBy<AgentEvaluationMatchSummary> { it.seed }.thenBy { it.pairIndex }.thenBy { it.matchId })
    }

    fun loadTurnSummaries(batchId: String, matchId: String): List<AgentEvaluationTurnSummary> {
        val turns = readJsonOrNull<List<AgentEvaluationTurnSummary>>(matchDirForRead(batchId, matchId)?.resolve(turnSummaryFile) ?: return emptyList()) ?: emptyList()
        return turns.map { turnSummary ->
            if (!turnSummary.checkpointFileName.isNullOrBlank()) turnSummary
            else {
                val checkpointFileName = findTurnCheckpointFileName(batchId, matchId, turnSummary.civName, turnSummary.turn)
                if (checkpointFileName != null) turnSummary.copy(checkpointFileName = checkpointFileName) else turnSummary
            }
        }
    }

    fun loadEvents(batchId: String, matchId: String): List<AgentObservabilityEvent> {
        val path = matchDirForRead(batchId, matchId)?.resolve(eventsFile) ?: return emptyList()
        if (!path.exists()) return emptyList()
        return Files.readAllLines(path, StandardCharsets.UTF_8)
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                runCatching { AgentEvaluationJson.json.decodeFromString<AgentObservabilityEvent>(line) }.getOrNull()
            }
            .toList()
    }

    fun loadReplay(batchId: String, matchId: String): AgentEvaluationReplayResponse? {
        val batch = loadBatch(batchId) ?: return null
        val match = readJsonOrNull<AgentEvaluationMatchSummary>(
            matchDirForRead(batchId, matchId)?.resolve(matchSummaryFile) ?: return null,
        ) ?: return null
        return AgentEvaluationReplayResponse(
            batch = batch,
            match = match,
            turnSummaries = loadTurnSummaries(batchId, matchId),
            recentEvents = loadEvents(batchId, matchId),
        )
    }

    private inline fun <reified T> readJsonOrNull(path: Path): T? {
        if (!path.exists()) return null
        return runCatching {
            AgentEvaluationJson.json.decodeFromString<T>(Files.readString(path, StandardCharsets.UTF_8))
        }.getOrNull()
    }

    private fun writeJson(path: Path, value: Any) {
        ensureParent(path)
        val tempPath = path.resolveSibling("${path.fileName}.tmp")
        val body = when (value) {
            is AgentEvaluationBatchSummary -> AgentEvaluationJson.json.encodeToString(value)
            is AgentEvaluationMatchSummary -> AgentEvaluationJson.json.encodeToString(value)
            is List<*> -> AgentEvaluationJson.json.encodeToString(value.filterIsInstance<AgentEvaluationTurnSummary>())
            else -> error("Unsupported json payload ${value::class.qualifiedName}")
        }
        Files.writeString(
            tempPath,
            body,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
        Files.move(tempPath, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun ensureParent(path: Path) {
        Files.createDirectories(path.parent)
    }
    private fun abortBatch(summary: AgentEvaluationBatchSummary, finishedAtEpochMs: Long) {
        val matches = listMatches(summary.batchId).map { matchSummary ->
            if (matchSummary.status == "running") abortMatch(matchSummary) else matchSummary
        }

        val abortedSummary = AgentEvaluationAnalyzer.buildBatchSummary(
            batchId = summary.batchId,
            name = summary.name,
            configPath = summary.configPath,
            startedAtEpochMs = summary.startedAtEpochMs,
            finishedAtEpochMs = finishedAtEpochMs,
            requestedGames = summary.requestedGames,
            plannedMatches = summary.plannedMatches,
            pairMatchesBySeed = summary.pairMatchesBySeed,
            maxTurns = summary.maxTurns,
            baseRuleset = summary.baseRuleset,
            difficulty = summary.difficulty,
            mapType = summary.mapType,
            mapSize = summary.mapSize,
            matches = matches,
            statusOverride = "aborted",
        )
        writeBatchSummary(abortedSummary)
    }

    private fun abortMatch(summary: AgentEvaluationMatchSummary): AgentEvaluationMatchSummary {
        val events = loadEvents(summary.batchId, summary.matchId)
        val existingTurnSummaries = loadTurnSummaries(summary.batchId, summary.matchId)
        val turnSummaries = if (existingTurnSummaries.isEmpty() && summary.agentCivName.isNotBlank()) {
            AgentEvaluationAnalyzer.analyzeTurns(events, summary.agentCivName)
        } else {
            existingTurnSummaries
        }

        if (turnSummaries.isNotEmpty()) {
            writeTurnSummaries(summary.batchId, summary.matchId, turnSummaries)
        }

        val latencies = turnSummaries.mapNotNull { it.llmLatencyMs?.toDouble() }
        val illegalRates = turnSummaries.filter { it.plannedActions > 0 }.map { it.illegalActionRate }
        val topConcerns = buildList {
            add("Run appears to have been interrupted before finalization.")
            addAll(summary.topConcerns)
            addAll(turnSummaries.mapNotNull { it.topConcern })
        }.distinct().take(5)

        val finishedAtEpochMs = listOfNotNull(
            matchLastActivityEpochMs(summary.batchId, summary.matchId),
            events.lastOrNull()?.epochMs,
            summary.finishedAtEpochMs,
        ).maxOrNull() ?: System.currentTimeMillis()

        val derivedTotalTurns = turnSummaries.maxOfOrNull { it.turn } ?: summary.totalTurns

        val abortedSummary = summary.copy(
            finishedAtEpochMs = finishedAtEpochMs,
            status = "aborted",
            winnerSide = "aborted",
            totalTurns = maxOf(summary.totalTurns, derivedTotalTurns),
            agentTurnCount = maxOf(summary.agentTurnCount, turnSummaries.size),
            fallbackTurns = maxOf(summary.fallbackTurns, turnSummaries.count { it.fallback }),
            blockedTurns = maxOf(summary.blockedTurns, turnSummaries.count { it.blocked }),
            avgInferenceLatencyMs = if (latencies.isNotEmpty()) latencies.average() else summary.avgInferenceLatencyMs,
            illegalActionRate = if (illegalRates.isNotEmpty()) illegalRates.average() else summary.illegalActionRate,
            maxRejectedActionsOnTurn = maxOf(summary.maxRejectedActionsOnTurn, turnSummaries.maxOfOrNull { it.rejectedActions } ?: 0),
            interesting = true,
            topConcerns = topConcerns,
        )

        writeMatchSummary(abortedSummary)
        return abortedSummary
    }

    private fun batchLastActivityEpochMs(batchId: String): Long? {
        return latestRegularFileMtime(batchDir(batchId))
    }

    private fun matchLastActivityEpochMs(batchId: String, matchId: String): Long? {
        return latestRegularFileMtime(matchDir(batchId, matchId))
    }

    private fun latestRegularFileMtime(root: Path): Long? {
        if (!root.exists()) return null
        Files.walk(root).use { stream ->
            val latest = stream
                .filter { Files.isRegularFile(it) }
                .mapToLong { Files.getLastModifiedTime(it).toMillis() }
                .max()
                .orElse(0L)
            return latest.takeIf { it > 0L }
        }
    }

    fun findTurnCheckpointFileName(batchId: String, matchId: String, civName: String, turn: Int): String? {
        val path = (matchDirForRead(batchId, matchId) ?: return null)
            .resolve(turnCheckpointDirName)
            .resolve(turnCheckpointFileName(civName, turn))
        return path.takeIf { it.exists() }?.fileName?.toString()
    }

    fun findTurnCheckpointPath(batchId: String, matchId: String, civName: String, turn: Int): Path? {
        return (matchDirForRead(batchId, matchId) ?: return null)
            .resolve(turnCheckpointDirName)
            .resolve(turnCheckpointFileName(civName, turn))
            .takeIf { it.exists() }
    }

    fun battlefieldRenderImagePath(batchId: String, matchId: String, civName: String, turn: Int): Path {
        return renderCacheRoot()
            .resolve(batchesDirName)
            .resolve(sanitizeForFileName(batchId))
            .resolve("matches")
            .resolve(sanitizeForFileName(matchId))
            .resolve(battlefieldRenderDirName)
            .resolve(battlefieldRenderFileName(civName, turn))
    }

    fun battlefieldRenderRequestPath(batchId: String, matchId: String, civName: String, turn: Int): Path {
        return renderCacheRoot()
            .resolve("_requests")
            .resolve(sanitizeForFileName(batchId))
            .resolve(sanitizeForFileName(matchId))
            .resolve(battlefieldRenderBaseName(civName, turn) + ".request.json")
    }

    fun battlefieldRenderResultPath(batchId: String, matchId: String, civName: String, turn: Int): Path {
        return renderCacheRoot()
            .resolve("_requests")
            .resolve(sanitizeForFileName(batchId))
            .resolve(sanitizeForFileName(matchId))
            .resolve(battlefieldRenderBaseName(civName, turn) + ".result.json")
    }

    private fun turnCheckpointFileName(civName: String, turn: Int): String {
        return "${sanitizeForFileName(civName)}-turn-${turn.toString().padStart(4, '0')}.uncivsave"
    }

    private fun battlefieldRenderFileName(civName: String, turn: Int): String {
        return battlefieldRenderBaseName(civName, turn) + ".png"
    }

    private fun battlefieldRenderBaseName(civName: String, turn: Int): String {
        return "${sanitizeForFileName(civName)}-turn-${turn.toString().padStart(4, '0')}-$battlefieldRenderVersion"
    }

    private fun sanitizeForFileName(value: String): String {
        return value.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifBlank { "turn" }
    }

    private fun writableBatchDirs(): List<Path> {
        val dir = rootDir().resolve(batchesDirName)
        if (!dir.exists() || !dir.isDirectory()) return emptyList()
        return dir.listDirectoryEntries()
    }

    private fun readableBatchDirs(): List<Path> {
        return readRootDirs().flatMap { root ->
            val dir = root.resolve(batchesDirName)
            if (!dir.exists() || !dir.isDirectory()) emptyList() else dir.listDirectoryEntries()
        }
    }

    private fun findBatchDir(batchId: String): Path? {
        return readRootDirs()
            .asSequence()
            .map { it.resolve(batchesDirName).resolve(batchId) }
            .firstOrNull { it.exists() && it.isDirectory() }
    }

    private fun matchDirForRead(batchId: String, matchId: String): Path? {
        return findBatchDir(batchId)?.resolve("matches")?.resolve(matchId)?.takeIf { it.exists() && it.isDirectory() }
    }
}

class AgentEvaluationTraceWriter(
    private val writer: BufferedWriter,
) : AutoCloseable {
    @Synchronized
    fun append(event: AgentObservabilityEvent) {
        writer.write(AgentEvaluationJson.compactJson.encodeToString(event))
        writer.newLine()
        writer.flush()
    }

    override fun close() {
        writer.close()
    }
}

object AgentEvaluationAnalyzer {
    fun analyzeTurns(events: List<AgentObservabilityEvent>, agentCivName: String): List<AgentEvaluationTurnSummary> {
        return events
            .groupBy { TurnKey(it.civName ?: "Unknown", it.turn ?: -1) }
            .entries
            .asSequence()
            .filter { it.key.civName == agentCivName && it.key.turn >= 0 }
            .map { (key, groupedEvents) -> analyzeTurn(key, groupedEvents.sortedBy { event -> event.epochMs }) }
            .sortedBy { it.turn }
            .toList()
    }

    fun buildMatchSummary(
        batchId: String,
        matchId: String,
        label: String,
        seed: Long,
        pairIndex: Int,
        rolesSwapped: Boolean,
        startedAtEpochMs: Long,
        finishedAtEpochMs: Long,
        gameInfo: GameInfo,
        agentCivName: String,
        legacyCivName: String,
        turnSummaries: List<AgentEvaluationTurnSummary>,
        finalSaveFileName: String?,
        failureMessage: String? = null,
        statusOverride: String? = null,
        winnerSideOverride: String? = null,
    ): AgentEvaluationMatchSummary {
        val fallbackTurns = turnSummaries.count { it.fallback }
        val blockedTurns = turnSummaries.count { it.blocked }
        val avgLatency = turnSummaries.mapNotNull { it.llmLatencyMs?.toDouble() }.averageOrNull()
        val illegalRate = turnSummaries
            .filter { it.plannedActions > 0 }
            .map { it.illegalActionRate }
            .averageOrNull() ?: 0.0
        val winnerVictoryData = gameInfo.victoryData
        val winnerCivId = winnerVictoryData?.winningCiv
        val winnerCiv = runCatching { winnerVictoryData?.winningCivObject }.getOrNull() ?: winnerCivId?.let { winningId ->
            gameInfo.civilizations.firstOrNull { it.civID == winningId }
        }
        val winnerCivName = winnerCiv?.civName ?: winnerCivId
        val winnerSide = winnerSideOverride ?: when (winnerCiv?.playerType) {
            PlayerType.AI_AGENT -> "agent"
            PlayerType.AI -> "legacy"
            PlayerType.Human -> "other"
            null -> when {
                winnerCivId == null -> "draw"
                agentCivName != legacyCivName && winnerCivName == agentCivName -> "agent"
                agentCivName != legacyCivName && winnerCivName == legacyCivName -> "legacy"
                else -> "other"
            }
        }
        val topConcerns = mutableListOf<String>()
        if (failureMessage != null) topConcerns += failureMessage
        topConcerns += turnSummaries.mapNotNull { it.topConcern }.distinct().take(5)
        val interesting = winnerSide != "agent" || fallbackTurns > 0 || blockedTurns > 0 || turnSummaries.any { it.rejectedActions > 0 }

        return AgentEvaluationMatchSummary(
            batchId = batchId,
            matchId = matchId,
            label = label,
            seed = seed,
            pairIndex = pairIndex,
            rolesSwapped = rolesSwapped,
            startedAtEpochMs = startedAtEpochMs,
            finishedAtEpochMs = finishedAtEpochMs,
            status = statusOverride ?: if (failureMessage == null) "completed" else "failed",
            gameId = gameInfo.gameId,
            agentCivName = agentCivName,
            legacyCivName = legacyCivName,
            winnerCivName = winnerCivName,
            winnerSide = winnerSide,
            victoryType = gameInfo.victoryData?.victoryType,
            totalTurns = gameInfo.turns,
            agentTurnCount = turnSummaries.size,
            fallbackTurns = fallbackTurns,
            blockedTurns = blockedTurns,
            avgInferenceLatencyMs = avgLatency,
            illegalActionRate = illegalRate,
            maxRejectedActionsOnTurn = turnSummaries.maxOfOrNull { it.rejectedActions } ?: 0,
            interesting = interesting,
            topConcerns = topConcerns.distinct().take(5),
            finalSaveFileName = finalSaveFileName,
        )
    }

    fun buildBatchSummary(
        batchId: String,
        name: String,
        configPath: String,
        startedAtEpochMs: Long,
        finishedAtEpochMs: Long?,
        requestedGames: Int,
        plannedMatches: Int,
        pairMatchesBySeed: Boolean,
        maxTurns: Int,
        baseRuleset: String,
        difficulty: String,
        mapType: String,
        mapSize: String,
        matches: List<AgentEvaluationMatchSummary>,
        statusOverride: String? = null,
    ): AgentEvaluationBatchSummary {
        val completedMatches = matches.count { it.status == "completed" }
        val failedMatches = matches.count { it.status == "failed" }
        val cancelledMatches = matches.count { it.status == "cancelled" }
        val agentWins = matches.count { it.winnerSide == "agent" }
        val legacyWins = matches.count { it.winnerSide == "legacy" }
        val draws = matches.count { it.winnerSide == "draw" }

        return AgentEvaluationBatchSummary(
            batchId = batchId,
            name = name,
            configPath = configPath,
            startedAtEpochMs = startedAtEpochMs,
            finishedAtEpochMs = finishedAtEpochMs,
            status = statusOverride ?: if (finishedAtEpochMs == null) "running" else "completed",
            requestedGames = requestedGames,
            plannedMatches = plannedMatches,
            completedMatches = completedMatches,
            failedMatches = failedMatches,
            cancelledMatches = cancelledMatches,
            pairMatchesBySeed = pairMatchesBySeed,
            maxTurns = maxTurns,
            baseRuleset = baseRuleset,
            difficulty = difficulty,
            mapType = mapType,
            mapSize = mapSize,
            agentWins = agentWins,
            legacyWins = legacyWins,
            draws = draws,
            avgTurns = matches.map { it.totalTurns.toDouble() }.averageOrNull() ?: 0.0,
            avgInferenceLatencyMs = matches.mapNotNull { it.avgInferenceLatencyMs }.averageOrNull(),
            fallbackRate = matches
                .filter { it.agentTurnCount > 0 }
                .map { it.fallbackTurns.toDouble() / it.agentTurnCount.toDouble() }
                .averageOrNull() ?: 0.0,
            blockedRate = matches
                .filter { it.agentTurnCount > 0 }
                .map { it.blockedTurns.toDouble() / it.agentTurnCount.toDouble() }
                .averageOrNull() ?: 0.0,
            illegalActionRate = matches.map { it.illegalActionRate }.averageOrNull() ?: 0.0,
        )
    }

    private fun analyzeTurn(key: TurnKey, events: List<AgentObservabilityEvent>): AgentEvaluationTurnSummary {
        val requestEvent = events.firstOrNull { it.type == "llm_request" }
        val responseEvent = events.lastOrNull { it.type == "llm_response" }
        val planParsedEvent = events.lastOrNull { it.type == "llm_plan_parsed" }
        val parseErrorEvent = events.lastOrNull { it.type == "llm_parse_error" }
        val requestErrorEvent = events.lastOrNull { it.type == "llm_request_error" }
        val httpErrorEvent = events.lastOrNull { it.type == "llm_http_error" }
        val planAppliedEvent = events.lastOrNull { it.type == "plan_applied" }
        val fallbackEvent = events.lastOrNull { it.type == "fallback_legacy" }
        val planMissingEvent = events.lastOrNull { it.type == "plan_missing" }
        val handoffToLegacy = detail(planParsedEvent, "handoffToLegacyAI")?.toBooleanStrictOrNull() ?: false
        val plannedActions = detail(planAppliedEvent, "plannedActions")?.toIntOrNull()
            ?: detail(fallbackEvent, "plannedActions")?.toIntOrNull()
            ?: detail(planParsedEvent, "actions")?.toIntOrNull()
            ?: 0
        val executedActions = detail(planAppliedEvent, "executedActions")?.toIntOrNull()
            ?: detail(fallbackEvent, "executedActions")?.toIntOrNull()
            ?: 0
        val rejectedActions = detail(planAppliedEvent, "rejectedActions")?.toIntOrNull()
            ?: detail(fallbackEvent, "rejectedActions")?.toIntOrNull()
            ?: 0
        val blocked = parseErrorEvent != null || requestErrorEvent != null || httpErrorEvent != null
        val fallback = fallbackEvent != null || planMissingEvent != null || handoffToLegacy
        val status = when {
            blocked -> "blocked"
            fallback -> "fallback"
            planAppliedEvent != null -> "applied"
            requestEvent != null || responseEvent != null || planParsedEvent != null -> "live"
            else -> "idle"
        }
        val statusLabel = when (status) {
            "blocked" -> "Blocked"
            "fallback" -> "Fallback"
            "applied" -> "Applied"
            "live" -> "Live"
            else -> "Observing"
        }
        val llmLatencyMs = diffMs(requestEvent, responseEvent ?: requestErrorEvent ?: httpErrorEvent)
        val topConcern = when {
            parseErrorEvent != null -> detail(parseErrorEvent, "error") ?: "Structured plan could not be decoded."
            requestErrorEvent != null -> detail(requestErrorEvent, "error") ?: "Planner request failed."
            httpErrorEvent != null -> "Planner returned ${detail(httpErrorEvent, "status") ?: "a non-200 status"}."
            planMissingEvent != null -> "No structured plan was produced."
            fallbackEvent != null -> "Legacy AI took over after plan execution."
            handoffToLegacy -> "Model requested handoff to legacy AI."
            rejectedActions > 0 -> "$rejectedActions actions were rejected by the engine."
            else -> null
        }

        return AgentEvaluationTurnSummary(
            civName = key.civName,
            turn = key.turn,
            status = status,
            statusLabel = statusLabel,
            plannedActions = plannedActions,
            executedActions = executedActions,
            rejectedActions = rejectedActions,
            llmLatencyMs = llmLatencyMs,
            fallback = fallback,
            blocked = blocked,
            illegalActionRate = if (plannedActions > 0) rejectedActions.toDouble() / plannedActions.toDouble() else 0.0,
            notes = detail(planParsedEvent, "notes")?.ifBlank { null },
            topConcern = topConcern,
        )
    }

    private fun detail(event: AgentObservabilityEvent?, key: String): String? {
        return event?.details?.get(key)
    }

    private fun diffMs(startEvent: AgentObservabilityEvent?, endEvent: AgentObservabilityEvent?): Long? {
        if (startEvent == null || endEvent == null) return null
        val delta = endEvent.epochMs - startEvent.epochMs
        return delta.takeIf { it >= 0L }
    }

    private fun Iterable<Double>.averageOrNull(): Double? {
        val values = toList()
        if (values.isEmpty()) return null
        return values.sum() / values.size.toDouble()
    }

    private data class TurnKey(
        val civName: String,
        val turn: Int,
    )
}
