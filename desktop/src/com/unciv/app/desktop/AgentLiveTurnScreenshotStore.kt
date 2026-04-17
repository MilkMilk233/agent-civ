package com.unciv.app.desktop

object AgentLiveTurnScreenshotStore {
    private const val maxEntries = 240

    private data class ScreenshotKey(
        val generation: Long,
        val civName: String,
        val turn: Int,
    )

    private val lock = Any()
    private val screenshots = LinkedHashMap<ScreenshotKey, ByteArray>()

    fun save(generation: Long, civName: String, turn: Int, pngBytes: ByteArray) {
        synchronized(lock) {
            screenshots[ScreenshotKey(generation, civName, turn)] = pngBytes
            while (screenshots.size > maxEntries) {
                val oldestKey = screenshots.entries.firstOrNull()?.key ?: break
                screenshots.remove(oldestKey)
            }
        }
    }

    fun load(generation: Long, civName: String, turn: Int): ByteArray? {
        return synchronized(lock) { screenshots[ScreenshotKey(generation, civName, turn)] }
    }
}
