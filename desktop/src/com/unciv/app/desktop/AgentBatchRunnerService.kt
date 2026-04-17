package com.unciv.app.desktop

import com.unciv.Constants
import com.unciv.json.json
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.map.MapShape
import com.unciv.logic.map.MapSize
import com.unciv.logic.map.MapType
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.RulesetCache
import com.unciv.utils.Log
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlinx.serialization.Serializable

@Serializable
data class AgentBatchRunnerStatus(
    val launchEnabled: Boolean = false,
    val running: Boolean = false,
    val cancelRequested: Boolean = false,
    val currentBatchId: String? = null,
    val currentBatchName: String? = null,
    val startedAtEpochMs: Long? = null,
    val finishedAtEpochMs: Long? = null,
    val lastCompletedBatchId: String? = null,
    val lastCompletedBatchStatus: String? = null,
    val lastError: String? = null,
    val storageDir: String = "",
)

@Serializable
data class AgentBatchRunnerMapSizeOption(
    val name: String,
    val radius: Int,
    val width: Int,
    val height: Int,
)

@Serializable
data class AgentBatchRunnerRulesetOptions(
    val name: String,
    val difficulties: List<String>,
    val speeds: List<String>,
    val civilizations: List<String>,
    val victoryTypes: List<String>,
)

@Serializable
data class AgentBatchRunnerFormOptions(
    val defaultBaseRuleset: String = BaseRuleset.Civ_V_GnK.fullName,
    val baseRulesets: List<String> = emptyList(),
    val playerTypes: List<String> = emptyList(),
    val rulesets: List<AgentBatchRunnerRulesetOptions> = emptyList(),
    val mapTypes: List<String> = emptyList(),
    val mapShapes: List<String> = emptyList(),
    val mapSizes: List<AgentBatchRunnerMapSizeOption> = emptyList(),
    val supportedCompetitivePlayerCount: Int = 2,
    val supportsAdditionalCompetitivePlayers: Boolean = false,
)

@kotlin.time.ExperimentalTime
object AgentBatchRunnerService {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "agent-batch-runner").apply { isDaemon = true }
    }

    private val lock = Any()
    private var launchEnabled = false
    private var running = false
    private var cancelRequested = false
    private var currentBatchId: String? = null
    private var currentBatchName: String? = null
    private var startedAtEpochMs: Long? = null
    private var finishedAtEpochMs: Long? = null
    private var lastCompletedBatchId: String? = null
    private var lastCompletedBatchStatus: String? = null
    private var lastError: String? = null
    private var currentTask: Future<*>? = null

    fun enableDashboardLaunches() {
        synchronized(lock) {
            launchEnabled = true
        }
    }

    fun status(): AgentBatchRunnerStatus = synchronized(lock) {
        AgentBatchRunnerStatus(
            launchEnabled = launchEnabled,
            running = running,
            cancelRequested = cancelRequested,
            currentBatchId = currentBatchId,
            currentBatchName = currentBatchName,
            startedAtEpochMs = startedAtEpochMs,
            finishedAtEpochMs = finishedAtEpochMs,
            lastCompletedBatchId = lastCompletedBatchId,
            lastCompletedBatchStatus = lastCompletedBatchStatus,
            lastError = lastError,
            storageDir = AgentEvaluationStore.rootDir().toAbsolutePath().normalize().toString(),
        )
    }

    fun templateConfigJson(): String {
        return AgentBatchRunnerService::class.java
            .getResourceAsStream("/agent-evaluation-smoke-config.json")
            ?.bufferedReader(StandardCharsets.UTF_8)
            ?.use { it.readText() }
            ?.trim()
            ?: """{"name":"agent-vs-legacy-smoke","games":1,"maxTurns":40,"seedStart":2000,"pairMatchesBySeed":true}"""
    }

    fun formOptions(): AgentBatchRunnerFormOptions {
        synchronized(lock) {
            check(launchEnabled) { "Batch launch options are only available from dashboard mode." }
        }

        AgentBatchEvaluationEnvironment.ensureReady()

        val baseRulesets = RulesetCache.getSortedBaseRulesets().ifEmpty { listOf(BaseRuleset.Civ_V_GnK.fullName) }
        val fallbackRuleset = baseRulesets.firstOrNull()?.let { RulesetCache[it] }
            ?: RulesetCache[BaseRuleset.Civ_V_GnK.fullName]
            ?: RulesetCache.values.firstOrNull()
            ?: error("No rulesets are loaded for batch options")

        val rulesets = baseRulesets.map { rulesetName ->
            val ruleset = RulesetCache[rulesetName] ?: fallbackRuleset
            AgentBatchRunnerRulesetOptions(
                name = rulesetName,
                difficulties = ruleset.difficulties.keys.toList(),
                speeds = ruleset.speeds.keys.toList(),
                civilizations = buildList {
                    add(Constants.random)
                    addAll(
                        ruleset.nations.values
                            .asSequence()
                            .filter { it.isMajorCiv }
                            .map { it.name }
                            .sorted()
                        .toList(),
                    )
                }.distinct(),
                victoryTypes = ruleset.victories.keys.toList(),
            )
        }

        return AgentBatchRunnerFormOptions(
            defaultBaseRuleset = baseRulesets.firstOrNull() ?: BaseRuleset.Civ_V_GnK.fullName,
            baseRulesets = baseRulesets,
            playerTypes = listOf(PlayerType.AI_AGENT.name, PlayerType.AI.name),
            rulesets = rulesets,
            mapTypes = listOf(
                MapType.pangaea,
                MapType.continentAndIslands,
                MapType.twoContinents,
                MapType.threeContinents,
                MapType.fourCorners,
                MapType.archipelago,
                MapType.fractal,
                MapType.innerSea,
                MapType.lakes,
                MapType.smallContinents,
                MapType.perlin,
                MapType.empty,
            ),
            mapShapes = listOf(
                MapShape.hexagonal,
                MapShape.rectangular,
                MapShape.flatEarth,
            ),
            mapSizes = buildList {
                addAll(
                    MapSize.Predefined.entries.map { size ->
                        AgentBatchRunnerMapSizeOption(
                            name = size.name,
                            radius = size.radius,
                            width = size.width,
                            height = size.height,
                        )
                    },
                )
                add(
                    AgentBatchRunnerMapSizeOption(
                        name = MapSize.custom,
                        radius = MapSize.Tiny.radius,
                        width = MapSize.Tiny.width,
                        height = MapSize.Tiny.height,
                    ),
                )
            },
        )
    }

    fun submit(rawConfigJson: String): AgentBatchRunnerStatus {
        val config = json().fromJson(AgentEvaluationConfig::class.java, rawConfigJson)
        val batchId = AgentBatchEvaluationRunner.createBatchId(config.name)
        val batchName = config.name.ifBlank { batchId }
        val startedAt = System.currentTimeMillis()

        synchronized(lock) {
            check(launchEnabled) { "Batch launch is only available from dashboard mode." }
            check(!running) { "A batch is already running. Wait for it to finish before starting another." }
            running = true
            currentBatchId = batchId
            currentBatchName = batchName
            startedAtEpochMs = startedAt
            finishedAtEpochMs = null
            cancelRequested = false
            lastError = null
        }

        val submittedTask = executor.submit {
            try {
                AgentBatchEvaluationEnvironment.ensureReady()
                val summary = AgentBatchEvaluationRunner.runConfig(
                    config = config,
                    configPath = "dashboard://submitted",
                    rawConfigJson = rawConfigJson,
                    batchId = batchId,
                    shouldCancel = { isCancellationRequested() },
                )
                synchronized(lock) {
                    running = false
                    cancelRequested = false
                    currentBatchId = null
                    currentBatchName = null
                    finishedAtEpochMs = System.currentTimeMillis()
                    lastCompletedBatchId = summary.batchId
                    lastCompletedBatchStatus = summary.status
                    lastError = null
                    currentTask = null
                }
            } catch (ex: Exception) {
                Log.error("Agent batch launch from dashboard failed", ex)
                synchronized(lock) {
                    running = false
                    cancelRequested = false
                    currentBatchId = null
                    currentBatchName = null
                    finishedAtEpochMs = System.currentTimeMillis()
                    lastCompletedBatchStatus = "failed"
                    lastError = listOfNotNull(
                        ex::class.simpleName,
                        ex.message?.takeIf { it.isNotBlank() },
                    ).joinToString(": ")
                    currentTask = null
                }
            } finally {
                Thread.interrupted()
            }
        }

        synchronized(lock) {
            currentTask = submittedTask
        }

        return status()
    }

    fun cancel(): AgentBatchRunnerStatus {
        synchronized(lock) {
            check(launchEnabled) { "Batch cancel is only available from dashboard mode." }
            check(running) { "No batch is currently running." }
            cancelRequested = true
            currentTask?.cancel(true)
        }
        return status()
    }

    private fun isCancellationRequested(): Boolean = synchronized(lock) { cancelRequested }
}
