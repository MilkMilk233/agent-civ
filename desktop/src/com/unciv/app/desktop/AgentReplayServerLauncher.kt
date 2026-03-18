package com.unciv.app.desktop

import com.unciv.utils.Log
import java.util.concurrent.CountDownLatch

internal object AgentReplayServerLauncher {
    @OptIn(kotlin.time.ExperimentalTime::class)
    @JvmStatic
    fun main(arg: Array<String>) {
        Log.backend = DesktopLogBackend()
        AgentBatchRunnerService.enableDashboardLaunches()
        AgentObservabilityServer.startFromEnvironment(forceEnable = true)

        val port = AgentObservabilityServer.configuredPort()
        println("Agent replay server started on http://localhost:$port")
        println("Serving stored evaluation data from ${AgentEvaluationStore.rootDir()}")

        CountDownLatch(1).await()
    }
}
