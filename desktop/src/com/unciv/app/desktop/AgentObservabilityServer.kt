package com.unciv.app.desktop

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.unciv.logic.automation.agent.AgentObservability
import com.unciv.utils.Log
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import kotlinx.serialization.encodeToString

object AgentObservabilityServer {
    private var server: HttpServer? = null

    fun startFromEnvironment(forceEnable: Boolean = false) {
        val enabled = (System.getenv("UNCIV_AGENT_OBS_ENABLED") ?: "true").lowercase() !in setOf("0", "false", "no")
        if (!enabled && !forceEnable) return

        start(configuredPort())
    }

    fun configuredPort(): Int {
        return (System.getenv("UNCIV_AGENT_OBS_PORT") ?: "7071").toIntOrNull() ?: 7071
    }

    @OptIn(kotlin.time.ExperimentalTime::class)
    private fun start(port: Int) {
        if (server != null) return

        val httpServer = HttpServer.create(InetSocketAddress("0.0.0.0", port), 0)
        httpServer.executor = Executors.newSingleThreadExecutor()

        httpServer.createContext("/") { exchange ->
            when (exchange.requestURI.path) {
                "/" -> respond(exchange, 200, htmlPage, "text/html; charset=utf-8")
                "/api/snapshot" -> respond(
                    exchange,
                    200,
                    AgentObservability.snapshotJson(parseSnapshotLimit(exchange)),
                    "application/json; charset=utf-8",
                )
                "/api/history/batches" -> respond(
                    exchange,
                    200,
                    AgentEvaluationJson.json.encodeToString(run {
                        reconcileHistoryState()
                        AgentEvaluationStore.listBatches()
                    }),
                    "application/json; charset=utf-8",
                )
                "/api/history/runner/status" -> respond(
                    exchange,
                    200,
                    AgentEvaluationJson.json.encodeToString(AgentBatchRunnerService.status()),
                    "application/json; charset=utf-8",
                )
                "/api/history/runner/template" -> respond(
                    exchange,
                    200,
                    AgentBatchRunnerService.templateConfigJson(),
                    "application/json; charset=utf-8",
                )
                "/api/history/runner/options" -> {
                    val response = runCatching { AgentBatchRunnerService.formOptions() }
                    response.fold(
                        onSuccess = { options ->
                            respond(
                                exchange,
                                200,
                                AgentEvaluationJson.json.encodeToString(options),
                                "application/json; charset=utf-8",
                            )
                        },
                        onFailure = { error ->
                            respond(
                                exchange,
                                if (error is IllegalStateException) 409 else 400,
                                """{"error":${AgentEvaluationJson.compactJson.encodeToString(error.message ?: "Failed to load batch form options")}}""",
                                "application/json; charset=utf-8",
                            )
                        },
                    )
                }
                "/api/history/runner/start" -> {
                    if (exchange.requestMethod.uppercase() != "POST") {
                        respond(exchange, 405, """{"error":"Method not allowed"}""", "application/json; charset=utf-8")
                    } else {
                        val rawConfigJson = readBody(exchange)
                        if (rawConfigJson.isBlank()) {
                            respond(exchange, 400, """{"error":"Missing config body"}""", "application/json; charset=utf-8")
                        } else {
                            val response = runCatching { AgentBatchRunnerService.submit(rawConfigJson) }
                            response.fold(
                                onSuccess = { status ->
                                    respond(
                                        exchange,
                                        200,
                                        AgentEvaluationJson.json.encodeToString(status),
                                        "application/json; charset=utf-8",
                                    )
                                },
                                onFailure = { error ->
                                    respond(
                                        exchange,
                                        if (error is IllegalStateException) 409 else 400,
                                        """{"error":${AgentEvaluationJson.compactJson.encodeToString(error.message ?: "Failed to start batch")}}""",
                                        "application/json; charset=utf-8",
                                    )
                                },
                            )
                        }
                    }
                }
                "/api/history/runner/cancel" -> {
                    if (exchange.requestMethod.uppercase() != "POST") {
                        respond(exchange, 405, """{"error":"Method not allowed"}""", "application/json; charset=utf-8")
                    } else {
                        val response = runCatching { AgentBatchRunnerService.cancel() }
                        response.fold(
                            onSuccess = { status ->
                                respond(
                                    exchange,
                                    200,
                                    AgentEvaluationJson.json.encodeToString(status),
                                    "application/json; charset=utf-8",
                                )
                            },
                            onFailure = { error ->
                                respond(
                                    exchange,
                                    if (error is IllegalStateException) 409 else 400,
                                    """{"error":${AgentEvaluationJson.compactJson.encodeToString(error.message ?: "Failed to cancel batch")}}""",
                                    "application/json; charset=utf-8",
                                )
                            },
                        )
                    }
                }
                "/api/history/matches" -> {
                    val batchId = queryParam(exchange, "batchId")
                    if (batchId.isNullOrBlank()) {
                        respond(exchange, 400, """{"error":"Missing batchId"}""", "application/json; charset=utf-8")
                    } else {
                        respond(
                            exchange,
                            200,
                            AgentEvaluationJson.json.encodeToString(run {
                                reconcileHistoryState()
                                AgentEvaluationStore.listMatches(batchId)
                            }),
                            "application/json; charset=utf-8",
                        )
                    }
                }
                "/api/history/replay" -> {
                    val batchId = queryParam(exchange, "batchId")
                    val matchId = queryParam(exchange, "matchId")
                    if (batchId.isNullOrBlank() || matchId.isNullOrBlank()) {
                        respond(exchange, 400, """{"error":"Missing batchId or matchId"}""", "application/json; charset=utf-8")
                    } else {
                        reconcileHistoryState()
                        val replay = AgentEvaluationStore.loadReplay(batchId, matchId)
                        if (replay == null) {
                            respond(exchange, 404, """{"error":"Replay not found"}""", "application/json; charset=utf-8")
                        } else {
                            respond(
                                exchange,
                                200,
                                AgentEvaluationJson.json.encodeToString(replay),
                                "application/json; charset=utf-8",
                            )
                        }
                    }
                }
                else -> respond(exchange, 404, "Not found", "text/plain; charset=utf-8")
            }
        }

        httpServer.start()
        server = httpServer
        Log.error("AI (agent) observability dashboard started on port %s", port)
    }

    @OptIn(kotlin.time.ExperimentalTime::class)
    private fun reconcileHistoryState() {
        val activeBatchId = AgentBatchRunnerService.status().takeIf { it.running }?.currentBatchId
        AgentEvaluationStore.reconcileStaleRunningEntries(activeBatchId = activeBatchId)
    }

    private fun respond(exchange: HttpExchange, status: Int, body: String, contentType: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", contentType)
        exchange.responseHeaders.add("Cache-Control", "no-store")
        exchange.responseHeaders.add("Access-Control-Allow-Origin", "*")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { output -> output.write(bytes) }
    }

    private fun parseSnapshotLimit(exchange: HttpExchange): Int {
        val maxLimit = AgentObservability.maxBufferedEvents()
        val defaultLimit = minOf(2000, maxLimit)
        return queryParam(exchange, "limit")
            ?.toIntOrNull()
            ?.coerceIn(1, maxLimit)
            ?: defaultLimit
    }

    private fun readBody(exchange: HttpExchange): String {
        return exchange.requestBody.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
    }

    private fun queryParam(exchange: HttpExchange, name: String): String? {
        val rawQuery = exchange.requestURI.rawQuery ?: return null
        return rawQuery
            .split('&')
            .firstNotNullOfOrNull { entry ->
                val parts = entry.split('=', limit = 2)
                if (parts.firstOrNull() != name) return@firstNotNullOfOrNull null
                parts.getOrNull(1)
            }
    }

    private val htmlPage: String by lazy {
        AgentObservabilityServer::class.java
            .getResourceAsStream("/agent-observability-dashboard.html")
            ?.bufferedReader(StandardCharsets.UTF_8)
            ?.use { it.readText() }
            ?: error("Missing observability dashboard resource")
    }
}
