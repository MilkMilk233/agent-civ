package com.unciv.app.desktop

import com.unciv.utils.Log
import java.util.concurrent.CountDownLatch

internal object AgentHistoryServerLauncher {
    @OptIn(kotlin.time.ExperimentalTime::class)
    @JvmStatic
    fun main(arg: Array<String>) {
        Log.backend = DesktopLogBackend()
        AgentObservabilityServer.startFromEnvironment(forceEnable = true, allowHistoryReconciliation = false)

        val port = AgentObservabilityServer.configuredPort()
        println("Agent history server started on http://localhost:$port")
        println("Serving stored evaluation data from ${AgentEvaluationStore.storageRootsDescription()}")

        CountDownLatch(1).await()
    }
}
