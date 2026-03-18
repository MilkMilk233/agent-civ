package com.unciv.app.desktop

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.unciv.logic.automation.agent.AgentObservability
import com.unciv.utils.Log
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

object AgentObservabilityServer {
    private var server: HttpServer? = null

    fun startFromEnvironment() {
        val enabled = (System.getenv("UNCIV_AGENT_OBS_ENABLED") ?: "true").lowercase() !in setOf("0", "false", "no")
        if (!enabled) return

        val port = (System.getenv("UNCIV_AGENT_OBS_PORT") ?: "7071").toIntOrNull() ?: 7071
        start(port)
    }

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
                else -> respond(exchange, 404, "Not found", "text/plain; charset=utf-8")
            }
        }

        httpServer.start()
        server = httpServer
        Log.error("AI (agent) observability dashboard started on port %s", port)
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
        val rawQuery = exchange.requestURI.rawQuery ?: return 500
        return rawQuery
            .split('&')
            .firstNotNullOfOrNull { entry ->
                val parts = entry.split('=', limit = 2)
                if (parts.firstOrNull() != "limit") return@firstNotNullOfOrNull null
                parts.getOrNull(1)?.toIntOrNull()
            }
            ?.coerceIn(1, 500)
            ?: 500
    }

    private val htmlPage: String by lazy {
        AgentObservabilityServer::class.java
            .getResourceAsStream("/agent-observability-dashboard.html")
            ?.bufferedReader(StandardCharsets.UTF_8)
            ?.use { it.readText() }
            ?: error("Missing observability dashboard resource")
    }
}
