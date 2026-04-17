package com.unciv.app.desktop

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.unciv.UncivGame
import com.unciv.utils.withGLContext
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import kotlinx.coroutines.runBlocking

object AgentLiveScreenshotService {
    fun capturePng(): ByteArray {
        check(UncivGame.isCurrentInitialized()) { "Unciv game is not initialized yet." }
        check(!UncivGame.Current.isConsoleMode) { "Battlefield screenshots require a render-capable desktop game process." }

        return runBlocking {
            withGLContext {
                val screen = UncivGame.Current.getScreen()
                    ?: error("No active screen is available for screenshot capture.")

                // Render once on demand so the framebuffer reflects the latest loaded board.
                screen.render(Gdx.graphics.deltaTime)
                Gdx.graphics.requestRendering()

                val width = Gdx.graphics.backBufferWidth
                val height = Gdx.graphics.backBufferHeight
                check(width > 0 && height > 0) { "Desktop game window is not drawable right now." }

                val pixmap = Pixmap.createFromFrameBuffer(0, 0, width, height)
                try {
                    encodePixmapToPng(pixmap, width, height)
                } finally {
                    pixmap.dispose()
                }
            }
        }
    }

    private fun encodePixmapToPng(pixmap: Pixmap, width: Int, height: Int): ByteArray {
        val estimatedBytes = ((width.toLong() * height.toLong()) / 2L).coerceIn(32_768L, 8_388_608L).toInt()
        val pngWriter = PixmapIO.PNG(estimatedBytes)
        return try {
            pngWriter.setFlipY(true)
            pngWriter.setCompression(Deflater.BEST_SPEED)
            ByteArrayOutputStream(estimatedBytes).use { output ->
                pngWriter.write(output, pixmap)
                output.toByteArray()
            }
        } finally {
            pngWriter.dispose()
        }
    }
}
