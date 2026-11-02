package com.eignex.klause.bench.profile

import com.eignex.klause.bench.source.CorpusFetcher
import java.io.File

internal data class CorpusStagingControls(
    val order: String = "forward",
    val stagingTarget: String = "cache",
) {
    init {
        require(order in setOf("forward", "reverse"))
        require(stagingTarget in setOf("cache", "temporary"))
    }

    val stagingRoot: File
        get() = when (stagingTarget) {
            "cache" -> File(CorpusFetcher.cacheRoot, ".plain")
            else -> File(System.getProperty("java.io.tmpdir"))
        }
}
