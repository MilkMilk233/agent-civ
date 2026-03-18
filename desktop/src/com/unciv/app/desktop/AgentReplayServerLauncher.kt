package com.unciv.app.desktop

import com.unciv.utils.Log
import java.util.concurrent.CountDownLatch

internal object AgentReplayServerLauncher {
    @JvmStatic
    fun main(arg: Array<String>) {
        Log.backend = DesktopLogBackend()
        AgentObservabilityServer.startFromEnvironment(forceEnable = true)

        val port = AgentObservabilityServer.configuredPort()
        println("Agent replay server started on http://localhost:$port")
        println("Serving stored evaluation data from ${AgentEvaluationStore.rootDir()}")

        CountDownLatch(1).await()
    }
}
