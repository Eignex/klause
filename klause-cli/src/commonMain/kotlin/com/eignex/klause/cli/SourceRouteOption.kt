package com.eignex.klause.cli

import com.eignex.klause.solver.pipeline.SourceRoutePreference

internal fun CommonOptions.takeSourceRoutePreference(): SourceRoutePreference {
    val entries = engineParams.filter { it.startsWith("source-route=") }
    require(entries.size <= 1) { "source-route must be specified at most once" }
    val preference = when (val value = entries.singleOrNull()?.substringAfter('=')) {
        null, "automatic" -> SourceRoutePreference.AUTOMATIC
        "exact-linear" -> SourceRoutePreference.EXACT_LINEAR
        else -> throw IllegalArgumentException("unknown source-route '$value'; expected automatic or exact-linear")
    }
    engineParams.removeAll(entries)
    return preference
}
