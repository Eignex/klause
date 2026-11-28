package com.eignex.klause.bench.target

internal object Targets {
    // The `suite=core` selection token: every suite resolvable without the `minizinc` compile step.
    val IN_PROCESS_CORE = listOf(
        "handwritten-core",
        "dimacs-core",
        "opb-core",
        "schema-core",
    )
}
