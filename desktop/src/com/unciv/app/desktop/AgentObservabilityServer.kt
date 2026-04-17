package com.unciv.app.desktop

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.unciv.logic.automation.agent.AgentObservability
import com.unciv.utils.Log
import java.net.InetSocketAddress
import java.net.URLDecoder
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
            val path = exchange.requestURI.path
            when {
                path == "/" -> respond(exchange, 200, htmlPage, "text/html; charset=utf-8")
                path == "/agent-dashboard" || path == "/agent-dashboard/" -> respondResource(
                    exchange,
                    resourcePath = "agent-dashboard/index.html",
                    contentType = "text/html; charset=utf-8",
                )
                path.startsWith("/agent-dashboard/") -> respondResource(
                    exchange,
                    resourcePath = path.removePrefix("/"),
                    contentType = contentTypeForResource(path),
                )
                path == "/api/snapshot" -> respond(
                    exchange,
                    200,
                    AgentObservability.snapshotJson(parseSnapshotLimit(exchange)),
                    "application/json; charset=utf-8",
                )
                path == "/api/live-turn-screenshot" -> {
                    val civName = queryParam(exchange, "civName")
                    val turn = queryParam(exchange, "turn")?.toIntOrNull()
                    val generation = queryParam(exchange, "generation")?.toLongOrNull() ?: AgentObservability.currentGeneration()
                    if (civName.isNullOrBlank() || turn == null) {
                        respond(exchange, 400, """{"error":"Missing civName or turn"}""", "application/json; charset=utf-8")
                    } else {
                        val screenshot = AgentLiveTurnScreenshotStore.load(generation, civName, turn)
                        if (screenshot == null) {
                            respond(exchange, 404, """{"error":"Live turn screenshot not found"}""", "application/json; charset=utf-8")
                        } else {
                            respondBytes(exchange, 200, screenshot, "image/png")
                        }
                    }
                }
                path == "/api/history/batches" -> respond(
                    exchange,
                    200,
                    AgentEvaluationJson.json.encodeToString(run {
                        reconcileHistoryState()
                        AgentEvaluationStore.listBatches()
                    }),
                    "application/json; charset=utf-8",
                )
                path == "/api/history/runner/status" -> respond(
                    exchange,
                    200,
                    AgentEvaluationJson.json.encodeToString(AgentBatchRunnerService.status()),
                    "application/json; charset=utf-8",
                )
                path == "/api/history/runner/template" -> respond(
                    exchange,
                    200,
                    AgentBatchRunnerService.templateConfigJson(),
                    "application/json; charset=utf-8",
                )
                path == "/api/history/runner/options" -> {
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
                path == "/api/history/runner/start" -> {
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
                path == "/api/history/runner/cancel" -> {
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
                path == "/api/history/matches" -> {
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
                path == "/api/history/replay" -> {
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
                path == "/api/history/turn-screenshot" -> {
                    val batchId = queryParam(exchange, "batchId")
                    val matchId = queryParam(exchange, "matchId")
                    val fileName = queryParam(exchange, "fileName")
                    if (batchId.isNullOrBlank() || matchId.isNullOrBlank() || fileName.isNullOrBlank()) {
                        respond(exchange, 400, """{"error":"Missing batchId, matchId, or fileName"}""", "application/json; charset=utf-8")
                    } else {
                        val screenshot = AgentEvaluationStore.loadTurnScreenshot(batchId, matchId, fileName)
                        if (screenshot == null) {
                            respond(exchange, 404, """{"error":"Turn screenshot not found"}""", "application/json; charset=utf-8")
                        } else {
                            respondBytes(exchange, 200, screenshot, "image/png")
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
        respondBytes(exchange, status, bytes, contentType)
    }

    private fun respondBytes(exchange: HttpExchange, status: Int, bytes: ByteArray, contentType: String) {
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
                val decodedName = parts.firstOrNull()?.let { decodeQueryComponent(it) } ?: return@firstNotNullOfOrNull null
                if (decodedName != name) return@firstNotNullOfOrNull null
                parts.getOrNull(1)?.let(::decodeQueryComponent)
            }
    }

    private fun decodeQueryComponent(value: String): String {
        return URLDecoder.decode(value, StandardCharsets.UTF_8)
    }

    private fun respondResource(exchange: HttpExchange, resourcePath: String, contentType: String) {
        val bytes = AgentObservabilityServer::class.java
            .getResourceAsStream("/$resourcePath")
            ?.use { it.readBytes() }

        if (bytes == null) {
            respond(exchange, 404, "Not found", "text/plain; charset=utf-8")
            return
        }

        exchange.responseHeaders.add("Content-Type", contentType)
        exchange.responseHeaders.add("Cache-Control", "no-store")
        exchange.responseHeaders.add("Access-Control-Allow-Origin", "*")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { output -> output.write(bytes) }
    }

    private fun contentTypeForResource(path: String): String = when {
        path.endsWith(".html") -> "text/html; charset=utf-8"
        path.endsWith(".js") -> "application/javascript; charset=utf-8"
        path.endsWith(".css") -> "text/css; charset=utf-8"
        path.endsWith(".json") -> "application/json; charset=utf-8"
        path.endsWith(".svg") -> "image/svg+xml"
        path.endsWith(".png") -> "image/png"
        else -> "text/plain; charset=utf-8"
    }

    private val htmlPage: String by lazy {
        AgentObservabilityServer::class.java
            .getResourceAsStream("/agent-observability-dashboard.html")
            ?.bufferedReader(StandardCharsets.UTF_8)
            ?.use { it.readText() }
            ?: error("Missing observability dashboard resource")
    }
}
