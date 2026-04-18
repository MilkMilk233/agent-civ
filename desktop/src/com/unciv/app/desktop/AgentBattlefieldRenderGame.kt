package com.unciv.app.desktop

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.unciv.UncivGame
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.ui.components.tilegroups.TileGroupMap
import com.unciv.ui.screens.worldscreen.worldmap.WorldMapHolder
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

private data class ExploredFrame(
    val left: Float,
    val right: Float,
    val top: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) * 0.5f
    val centerY: Float get() = (top + bottom) * 0.5f
}

private const val VIEWPORT_USAGE_TARGET = 0.72f
private val FRAME_MARGIN = TileGroupMap.groupSizeDiagonal * 3.5f
private val ASSET_SIGHT_MARGIN = TileGroupMap.groupSizeDiagonal * 4.5f
private const val TILE_FRAME_WIDTH = TileGroupMap.groupSize + 4f
private const val TILE_FRAME_HEIGHT = TileGroupMap.groupSize

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
        val fallbackCenter = preferredCenterFor(viewingCiv)
        com.unciv.utils.withGLContext {
            worldScreen.prepareForBattlefieldRender()
            worldScreen.stage.viewport.update(request.width, request.height, true)
            worldScreen.mapHolder.setSize(worldScreen.stage.width, worldScreen.stage.height)
            worldScreen.mapHolder.layout()
            worldScreen.mapHolder.reloadMaxZoom()

            if (!fitToBattlefieldFocus(worldScreen.mapHolder, viewingCiv)) {
                repeat(2) { worldScreen.mapHolder.zoomOut(immediate = true) }
                worldScreen.mapHolder.setCenterPosition(fallbackCenter, immediately = true, selectUnit = false)
            }
            worldScreen.shouldUpdate = true
        }
    }

    private fun fitToBattlefieldFocus(mapHolder: WorldMapHolder, viewingCiv: Civilization): Boolean {
        val initialFrame = focusedVisibleFrameFor(mapHolder, viewingCiv)
            ?: visibleFrameFor(mapHolder, viewingCiv)
            ?: exploredFrameFor(mapHolder, viewingCiv)
            ?: return false
        mapHolder.zoom(mapHolder.minZoom)

        var bestZoom = mapHolder.scaleX
        var bestFrame = focusedVisibleFrameFor(mapHolder, viewingCiv)
            ?: visibleFrameFor(mapHolder, viewingCiv)
            ?: exploredFrameFor(mapHolder, viewingCiv)
            ?: initialFrame
        centerOnFrame(mapHolder, bestFrame)

        if (!fitsInViewport(mapHolder, bestFrame)) {
            centerOnFrame(mapHolder, bestFrame)
            return true
        }

        while (true) {
            val candidateZoom = (mapHolder.scaleX / 0.8f).coerceAtMost(mapHolder.maxZoom)
            if (candidateZoom <= mapHolder.scaleX + 0.0001f) break

            mapHolder.zoom(candidateZoom)
            val candidateFrame = focusedVisibleFrameFor(mapHolder, viewingCiv)
                ?: visibleFrameFor(mapHolder, viewingCiv)
                ?: exploredFrameFor(mapHolder, viewingCiv)
                ?: break
            if (!fitsInViewport(mapHolder, candidateFrame)) {
                mapHolder.zoom(bestZoom)
                centerOnFrame(mapHolder, bestFrame)
                break
            }

            bestZoom = mapHolder.scaleX
            bestFrame = candidateFrame
            centerOnFrame(mapHolder, bestFrame)
        }

        centerOnFrame(mapHolder, bestFrame)
        return true
    }

    private fun focusedVisibleFrameFor(mapHolder: WorldMapHolder, viewingCiv: Civilization): ExploredFrame? {
        val anchorFrame = anchorFrameFor(mapHolder, viewingCiv) ?: return null
        val focusBounds = anchorFrame.expanded(ASSET_SIGHT_MARGIN)

        val focusedVisibleGroups = viewingCiv.viewableTiles
            .asSequence()
            .mapNotNull { tile -> mapHolder.tileGroups[tile] }
            .filter { group ->
                val groupLeft = group.x
                val groupRight = group.x + TILE_FRAME_WIDTH
                val groupTop = group.y
                val groupBottom = group.y + TILE_FRAME_HEIGHT
                groupRight >= focusBounds.left &&
                    groupLeft <= focusBounds.right &&
                    groupBottom >= focusBounds.top &&
                    groupTop <= focusBounds.bottom
            }
            .toList()

        return if (focusedVisibleGroups.isNotEmpty()) {
            frameFromGroups(focusedVisibleGroups)
        } else {
            focusBounds
        }
    }

    private fun anchorFrameFor(mapHolder: WorldMapHolder, viewingCiv: Civilization): ExploredFrame? {
        val anchorGroups = LinkedHashSet<com.unciv.ui.components.tilegroups.WorldTileGroup>()

        fun addGroupForTile(tile: com.unciv.logic.map.tile.Tile?) {
            if (tile == null) return
            mapHolder.tileGroups[tile]?.let(anchorGroups::add)
        }

        viewingCiv.cities.forEach { city ->
            addGroupForTile(city.getCenterTile())
        }
        viewingCiv.units.getCivUnits().forEach { unit ->
            addGroupForTile(unit.getTile())
        }

        viewingCiv.viewableTiles.forEach { tile ->
            if (tile.isCityCenter()) {
                val city = tile.getCity()
                if (city != null && city.civ != viewingCiv && viewingCiv.isAtWarWith(city.civ)) {
                    addGroupForTile(tile)
                }
            }

            tile.getUnits()
                .filter { unit -> unit.civ != viewingCiv && viewingCiv.isAtWarWith(unit.civ) }
                .forEach { unit -> addGroupForTile(unit.getTile()) }
        }

        if (anchorGroups.isEmpty()) return null
        return frameFromGroups(anchorGroups)
    }

    private fun assetFrameFor(mapHolder: WorldMapHolder, viewingCiv: Civilization): ExploredFrame? {
        val assetGroups = buildList {
            viewingCiv.cities.forEach { city ->
                mapHolder.tileGroups[city.getCenterTile()]?.let(::add)
            }
            viewingCiv.units.getCivUnits().forEach { unit ->
                mapHolder.tileGroups[unit.getTile()]?.let(::add)
            }
        }
        if (assetGroups.isEmpty()) return null
        return frameFromGroups(assetGroups)
    }

    private fun visibleFrameFor(mapHolder: WorldMapHolder, viewingCiv: Civilization): ExploredFrame? {
        val visibleGroups = viewingCiv.viewableTiles
            .asSequence()
            .mapNotNull { tile -> mapHolder.tileGroups[tile] }
            .toList()
        if (visibleGroups.isEmpty()) return null

        return frameFromGroups(visibleGroups)
    }

    private fun frameFromGroups(groups: Collection<com.unciv.ui.components.tilegroups.WorldTileGroup>): ExploredFrame {
        val left = groups.minOf { it.x }
        val right = groups.maxOf { it.x + TILE_FRAME_WIDTH }
        val top = groups.minOf { it.y }
        val bottom = groups.maxOf { it.y + TILE_FRAME_HEIGHT }
        return ExploredFrame(left = left, right = right, top = top, bottom = bottom)
    }

    private fun exploredFrameFor(mapHolder: WorldMapHolder, viewingCiv: Civilization): ExploredFrame? {
        val exploredRegion = viewingCiv.exploredRegion
        exploredRegion.calculateStageCoords(mapHolder.maxX, mapHolder.maxY)

        val left = exploredRegion.getLeftX()
        val right = exploredRegion.getRightX()
        val top = exploredRegion.getTopY()
        val bottom = exploredRegion.getBottomY()

        if (!left.isFinite() || !right.isFinite() || !top.isFinite() || !bottom.isFinite()) return null
        if (right < left || bottom < top) return null

        return ExploredFrame(left = left, right = right, top = top, bottom = bottom)
    }

    private fun fitsInViewport(mapHolder: WorldMapHolder, frame: ExploredFrame): Boolean {
        return frame.width + FRAME_MARGIN * 2f <= mapHolder.width * VIEWPORT_USAGE_TARGET &&
            frame.height + FRAME_MARGIN * 2f <= mapHolder.height * VIEWPORT_USAGE_TARGET
    }

    private fun centerOnFrame(mapHolder: WorldMapHolder, frame: ExploredFrame) {
        mapHolder.scrollTo(frame.centerX, frame.centerY, immediately = true)
    }

    private fun ExploredFrame.expanded(margin: Float): ExploredFrame {
        return ExploredFrame(
            left = left - margin,
            right = right + margin,
            top = top - margin,
            bottom = bottom + margin,
        )
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
