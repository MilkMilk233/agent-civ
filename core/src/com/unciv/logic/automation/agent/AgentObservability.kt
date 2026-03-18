package com.unciv.logic.automation.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicLong

object AgentObservability {
    private const val maxEvents = 500
    private const val maxDetailValueLength = 4000
    private const val maxMessageLength = 2000

    private val lock = Any()
    private val nextId = AtomicLong(1)
    private val startedAtEpochMs = System.currentTimeMillis()
    private val events = ArrayDeque<AgentObservabilityEvent>()

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
                .associate { it.key.take(80) to it.value.take(maxDetailValueLength) },
        )

        synchronized(lock) {
            events.addLast(event)
            while (events.size > maxEvents) events.removeFirst()
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
