package com.unciv.app.desktop

import com.unciv.utils.Concurrency
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

@Serializable
data class AgentBattlefieldRenderStatus(
    val status: String,
    val message: String? = null,
)

object AgentBattlefieldRenderService {
    private const val failureRetryCooldownMs = 5_000L

    private data class RenderKey(
        val batchId: String,
        val matchId: String,
        val civName: String,
        val turn: Int,
    )

    private data class RenderJobState(
        @Volatile var status: String,
        @Volatile var message: String? = null,
        @Volatile var updatedAtEpochMs: Long = System.currentTimeMillis(),
    )

    private val activeJobs = ConcurrentHashMap<RenderKey, RenderJobState>()

    data class ResolveResult(
        val status: String,
        val imagePath: Path? = null,
        val message: String? = null,
    )

    fun resolveOrStart(batchId: String, matchId: String, civName: String, turn: Int): ResolveResult {
        val cachedImagePath = AgentEvaluationStore.battlefieldRenderImagePath(batchId, matchId, civName, turn)
        if (cachedImagePath.isUsableImage()) {
            return ResolveResult(status = "ready", imagePath = cachedImagePath)
        }

        val checkpointPath = AgentEvaluationStore.findTurnCheckpointPath(batchId, matchId, civName, turn)
            ?: return ResolveResult(status = "missing_checkpoint", message = "No saved post-turn checkpoint exists for this turn.")

        val key = RenderKey(batchId, matchId, civName, turn)
        val existing = activeJobs[key]
        if (existing != null) {
            if (existing.status == "rendering") {
                return ResolveResult(status = "rendering", message = existing.message)
            }
            if (existing.status == "failed" && System.currentTimeMillis() - existing.updatedAtEpochMs < failureRetryCooldownMs) {
                return ResolveResult(status = "failed", message = existing.message)
            }
        }

        val jobState = RenderJobState(status = "rendering", message = "Rendering battlefield snapshot...")
        activeJobs[key] = jobState
        launchRenderJob(key, checkpointPath, cachedImagePath, jobState)
        return ResolveResult(status = "rendering", message = jobState.message)
    }

    private fun launchRenderJob(
        key: RenderKey,
        checkpointPath: Path,
        outputPath: Path,
        jobState: RenderJobState,
    ) {
        Concurrency.runOnNonDaemonThreadPool("ReplayBattlefieldRender-${key.turn}") {
            val requestPath = AgentEvaluationStore.battlefieldRenderRequestPath(key.batchId, key.matchId, key.civName, key.turn)
            val resultPath = AgentEvaluationStore.battlefieldRenderResultPath(key.batchId, key.matchId, key.civName, key.turn)

            runCatching {
                writeRenderRequest(
                    requestPath = requestPath,
                    resultPath = resultPath,
                    checkpointPath = checkpointPath,
                    outputPath = outputPath,
                    civName = key.civName,
                )
                val processOutput = runRenderWorker(requestPath)
                val result = readRenderResult(resultPath)
                when {
                    outputPath.isUsableImage() -> {
                        jobState.status = "ready"
                        jobState.message = null
                    }
                    result?.status == "ok" -> {
                        jobState.status = "failed"
                        jobState.message = "Render worker reported success, but no PNG was written."
                    }
                    else -> {
                        jobState.status = "failed"
                        jobState.message = result?.error ?: processOutput.ifBlank { "Render worker failed without writing a result." }
                    }
                }
            }.onFailure { error ->
                jobState.status = "failed"
                jobState.message = error.message ?: error::class.simpleName ?: "Unknown render error"
            }

            jobState.updatedAtEpochMs = System.currentTimeMillis()
            if (jobState.status == "ready") {
                activeJobs.remove(key)
            }
        }
    }

    private fun writeRenderRequest(
        requestPath: Path,
        resultPath: Path,
        checkpointPath: Path,
        outputPath: Path,
        civName: String,
    ) {
        Files.createDirectories(requestPath.parent)
        Files.createDirectories(outputPath.parent)
        Files.deleteIfExists(resultPath)

        val request = AgentBattlefieldRenderRequest(
            checkpointPath = checkpointPath.toString(),
            outputPath = outputPath.toString(),
            civName = civName,
            resultPath = resultPath.toString(),
        )
        Files.writeString(
            requestPath,
            AgentEvaluationJson.json.encodeToString(AgentBattlefieldRenderRequest.serializer(), request),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
    }

    private fun runRenderWorker(requestPath: Path): String {
        val workspaceDir = resolveWorkspaceDir()
        val jarPath = workspaceDir.resolve("desktop/build/libs/Unciv.jar")
        val baseCommand = if (Files.exists(jarPath)) {
            mutableListOf(
                "java",
                "-jar",
                jarPath.toString(),
                "--agentrender=${requestPath.toAbsolutePath()}",
            )
        } else {
            mutableListOf(
                "./gradlew",
                "desktop:run",
                "--args=--agentrender=${requestPath.toAbsolutePath()}",
            )
        }
        val command = if (System.getenv("DISPLAY").isNullOrBlank() && isCommandAvailable("xvfb-run")) {
            mutableListOf("xvfb-run", "-a").apply { addAll(baseCommand) }
        } else {
            baseCommand
        }
        val process = ProcessBuilder(command)
            .directory(workspaceDir.toFile())
            .redirectErrorStream(true)
            .apply {
                environment()["UNCIV_AGENT_OBS_ENABLED"] = "false"
                environment()["UNCIV_AGENT_SKIP_FRONTEND_BUILD"] = "true"
                environment()["UNCIV_AGENT_WORKSPACE_DIR"] = workspaceDir.toString()
            }
            .start()

        val output = process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        val exitCode = process.waitFor()
        return if (exitCode == 0) output else buildString {
            append("Render worker exited with code ")
            append(exitCode)
            val tail = output.trim().takeLast(1200).trim()
            if (tail.isNotEmpty()) {
                append(": ")
                append(tail)
            }
        }
    }

    private fun readRenderResult(resultPath: Path): AgentBattlefieldRenderResult? {
        if (!Files.exists(resultPath)) return null
        return runCatching {
            AgentEvaluationJson.json.decodeFromString(
                AgentBattlefieldRenderResult.serializer(),
                Files.readString(resultPath, StandardCharsets.UTF_8),
            )
        }.getOrNull()
    }

    private fun Path.isUsableImage(): Boolean {
        return Files.exists(this) && runCatching { Files.size(this) > 0L }.getOrDefault(false)
    }

    private fun isCommandAvailable(command: String): Boolean {
        val pathEntries = (System.getenv("PATH") ?: "")
            .split(File.pathSeparatorChar)
            .filter { it.isNotBlank() }
        return pathEntries.asSequence()
            .map { Paths.get(it).resolve(command) }
            .any { Files.isExecutable(it) }
    }

    private fun resolveWorkspaceDir(): Path {
        val configured = (System.getenv("UNCIV_AGENT_WORKSPACE_DIR") ?: "")
            .trim()
            .takeIf { it.isNotEmpty() }
            ?.let { Paths.get(it).toAbsolutePath().normalize() }
        if (configured != null) return configured
        return Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize()
    }
}
