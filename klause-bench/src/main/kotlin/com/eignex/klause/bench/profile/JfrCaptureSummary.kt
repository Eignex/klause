package com.eignex.klause.bench.profile

import com.eignex.klause.bench.source.CorpusFetcher
import java.io.File

internal object JfrCaptureSummary {
    @JvmStatic
    fun main(args: Array<String>) {
        val script = File(CorpusFetcher.workspaceRoot(), "klause-bench/scripts/summarize-jfr.sh")
        val status = ProcessBuilder(listOf("bash", script.path) + args)
            .inheritIO()
            .start()
            .waitFor()
        check(status == 0) { "JFR capture summary exited with status $status" }
    }
}
