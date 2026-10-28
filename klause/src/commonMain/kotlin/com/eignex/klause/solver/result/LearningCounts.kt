package com.eignex.klause.solver.result

internal fun mergeLearningCounts(first: Map<String, Long>, second: Map<String, Long>): Map<String, Long> =
    (first.keys + second.keys).associateWith { (first[it] ?: 0L) + (second[it] ?: 0L) }
