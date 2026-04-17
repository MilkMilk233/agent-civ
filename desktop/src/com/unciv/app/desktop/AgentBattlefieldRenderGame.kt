package com.unciv.app.desktop

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.unciv.UncivGame
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.utils.Concurrency
import kotlinx.serialization.Serializable
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.zip.Deflater

@Serializable
data class AgentBattlefieldRenderRequest(
    val checkpointPath: String,
    val outputPath: String,
    val civName: String,
    val width: Int = 1600,
    val height: Int = 900,
    val resultPath: String? = null,
    val settleDelayMs: Long = 300L,
) {
    companion object {
        fun fromFile(path: String): AgentBattlefieldRenderRequest {
            val body = Files.readString(Paths.get(path), StandardCharsets.UTF_8)
            return AgentEvaluationJson.json.decodeFromString(AgentBattlefieldRenderRequest.serializer(), body)
        }
    }
}

@Serializable
data class AgentBattlefieldRenderResult(
    val status: String,
    val outputPath: String? = null,
    val error: String? = null,
)

class AgentBattlefieldRenderGame(
    override var customDataDirectory: String?,
    private val request: AgentBattlefieldRenderRequest,
) : UncivGame() {
    @Volatile private var started = false
    @Volatile private var finished = false

    override fun render() {
        super.render()
        if (!isInitialized || started || finished) return
        started = true
        Concurrency.runOnNonDaemonThreadPool("AgentBattlefieldRender") {
            runRenderRequest()
        }
    }

    private suspend fun runRenderRequest() {
        try {
            val loadedGame = files.loadGameFromFile(FileHandle(request.checkpointPath))
            val viewingCiv = loadedGame.getCivilizationOrNull(request.civName)
                ?: error("Could not find civilization '${request.civName}' in checkpoint ${request.checkpointPath}")

            val worldScreen = loadGame(
                newGameInfo = loadedGame,
                callFromLoadScreen = true,
                viewingCivOverride = viewingCiv,
            )

            prepareWorldScreen(worldScreen, viewingCiv)
            Thread.sleep(request.settleDelayMs.coerceAtLeast(0L))
            capture(worldScreen)
            writeResult(AgentBattlefieldRenderResult(status = "ok", outputPath = request.outputPath))
        } catch (ex: Throwable) {
            writeResult(
                AgentBattlefieldRenderResult(
                    status = "failed",
                    error = buildErrorMessage(ex),
                ),
            )
        } finally {
            finished = true
            Gdx.app.postRunnable { Gdx.app.exit() }
        }
    }

    private suspend fun prepareWorldScreen(worldScreen: WorldScreen, viewingCiv: Civilization) {
        val center = preferredCenterFor(viewingCiv)
        com.unciv.utils.withGLContext {
            worldScreen.prepareForBattlefieldRender()
            worldScreen.stage.viewport.update(request.width, request.height, true)
            worldScreen.mapHolder.setSize(worldScreen.stage.width, worldScreen.stage.height)
            worldScreen.mapHolder.layout()

            repeat(2) { worldScreen.mapHolder.zoomOut(immediate = true) }
            worldScreen.mapHolder.setCenterPosition(center, immediately = true, selectUnit = false)
            worldScreen.shouldUpdate = true
        }
    }

    private suspend fun capture(worldScreen: WorldScreen) {
        com.unciv.utils.withGLContext {
            repeat(2) { worldScreen.render(0f) }
            val pixmap = Pixmap.createFromFrameBuffer(0, 0, request.width, request.height)
            try {
                ensureParent(Paths.get(request.outputPath))
                PixmapIO.writePNG(
                    FileHandle(request.outputPath),
                    pixmap,
                    Deflater.DEFAULT_COMPRESSION,
                    true,
                )
            } finally {
                pixmap.dispose()
            }
        }
    }

    private fun preferredCenterFor(viewingCiv: Civilization): HexCoord {
        val enemyCities = gameInfo
            ?.civilizations
            ?.asSequence()
            ?.filter { it != viewingCiv && viewingCiv.isAtWarWith(it) }
            ?.flatMap { it.cities.asSequence() }
            ?.map { it.getCenterTile() }
            ?.toList()
            .orEmpty()

        val frontlineUnit = viewingCiv.units.getCivUnits()
            .minByOrNull { unit ->
                enemyCities.minOfOrNull { enemyCity -> unit.getTile().aerialDistanceTo(enemyCity) } ?: Int.MAX_VALUE
            }
        if (frontlineUnit != null) return frontlineUnit.getTile().position

        viewingCiv.getCapital()?.location?.let { return it.toHexCoord() }
        viewingCiv.cities.firstOrNull()?.location?.let { return it.toHexCoord() }
        viewingCiv.units.getCivUnits().firstOrNull()?.getTile()?.position?.let { return it }
        return HexCoord.Zero
    }

    private fun buildErrorMessage(ex: Throwable): String {
        val head = buildList {
            add(ex::class.simpleName ?: "RenderFailure")
            ex.message?.takeIf { it.isNotBlank() }?.let { add(it) }
        }
        val stack = ex.stackTrace
            .take(5)
            .joinToString(" | ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
        return (head + listOf(stack).filter { it.isNotBlank() }).joinToString(" :: ")
    }

    private fun writeResult(result: AgentBattlefieldRenderResult) {
        val resultPath = request.resultPath ?: return
        val path = Paths.get(resultPath)
        ensureParent(path)
        Files.writeString(
            path,
            AgentEvaluationJson.json.encodeToString(AgentBattlefieldRenderResult.serializer(), result),
            StandardCharsets.UTF_8,
        )
    }

    private fun ensureParent(path: Path) {
        path.parent?.let(Files::createDirectories)
    }
}
