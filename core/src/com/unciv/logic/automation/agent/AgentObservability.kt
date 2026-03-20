package com.unciv.logic.automation.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicLong

object AgentObservability {
    private val maxEvents =
        (System.getenv("UNCIV_AGENT_OBS_MAX_EVENTS") ?: "4000")
            .toIntOrNull()
            ?.coerceIn(500, 20000)
            ?: 4000
    private const val maxDetailValueLength = 20000
    private const val maxStructuredDetailValueLength = 200000
    private const val maxMessageLength = 2000
    private val largeDetailKeys = setOf(
        "observationJson",
        "empireObservationJson",
        "memoryJson",
        "prompt",
        "rawResponse",
        "parsedPlan",
        "failedPlanJson",
        "retryContextJson",
        "validationFailuresJson",
    )

    private val lock = Any()
    private val nextId = AtomicLong(1)
    private val startedAtEpochMs = System.currentTimeMillis()
    private val events = ArrayDeque<AgentObservabilityEvent>()
    private val listeners = LinkedHashMap<Long, (AgentObservabilityEvent) -> Unit>()
    private val nextListenerId = AtomicLong(1)

    private val json = Json {
        prettyPrint = false
        encodeDefaults = true
        explicitNulls = false
    }

    fun record(
        type: String,
        message: String,
        civName: String? = null,
        turn: Int? = null,
        details: Map<String, String> = emptyMap(),
    ) {
        val event = AgentObservabilityEvent(
            id = nextId.getAndIncrement(),
            epochMs = System.currentTimeMillis(),
            type = type.take(80),
            message = message.take(maxMessageLength),
            civName = civName?.take(120),
            turn = turn,
            details = details
                .entries
                .take(30)
                .associate { entry ->
                    val key = entry.key.take(80)
                    val maxLength = if (key in largeDetailKeys || key.endsWith("Json")) {
                        maxStructuredDetailValueLength
                    } else {
                        maxDetailValueLength
                    }
                    key to entry.value.take(maxLength)
                },
        )

        val activeListeners = synchronized(lock) {
            events.addLast(event)
            while (events.size > maxEvents) events.removeFirst()
            listeners.values.toList()
        }

        for (listener in activeListeners) {
            try {
                listener(event)
            } catch (_: Exception) {
                // Observability should not break the turn pipeline.
            }
        }
    }

    fun snapshot(limit: Int = 200): AgentObservabilitySnapshot {
        val boundedLimit = limit.coerceIn(1, maxEvents)
        val recentEvents = synchronized(lock) { events.takeLast(boundedLimit) }
        val counters = recentEvents.groupingBy { it.type }.eachCount()

        return AgentObservabilitySnapshot(
            startedAtEpochMs = startedAtEpochMs,
            generatedAtEpochMs = System.currentTimeMillis(),
            totalBufferedEvents = synchronized(lock) { events.size },
            recentEvents = recentEvents,
            counters = counters,
        )
    }

    fun snapshotJson(limit: Int = 200): String {
        return json.encodeToString(snapshot(limit))
    }

    fun maxBufferedEvents(): Int = maxEvents

    fun clear() {
        synchronized(lock) {
            events.clear()
        }
    }

    fun addListener(listener: (AgentObservabilityEvent) -> Unit): Long {
        val id = nextListenerId.getAndIncrement()
        synchronized(lock) {
            listeners[id] = listener
        }
        return id
    }

    fun removeListener(id: Long) {
        synchronized(lock) {
            listeners.remove(id)
        }
    }
}

@Serializable
data class AgentObservabilitySnapshot(
    val startedAtEpochMs: Long,
    val generatedAtEpochMs: Long,
    val totalBufferedEvents: Int,
    val recentEvents: List<AgentObservabilityEvent>,
    val counters: Map<String, Int>,
)

@Serializable
data class AgentObservabilityEvent(
    val id: Long,
    val epochMs: Long,
    val type: String,
    val message: String,
    val civName: String? = null,
    val turn: Int? = null,
    val details: Map<String, String> = emptyMap(),
)
