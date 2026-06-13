package com.eignex.klause.bench.target

/** Which measurement to run: a subprocess `solve` (the spine) or a compile-only predicate `audit`. */
internal enum class MetricKind {
    SOLVE,
    AUDIT,
}

internal object Targets {
    /** Suites resolvable fully in-process (everything except the MiniZinc smoke set, which
     *  needs the `minizinc` compile step). Exposed as the `suite=core` selection token. */
    val IN_PROCESS_CORE = listOf(
        "handwritten-core",
        "dimacs-core",
        "opb-core",
        "schema-core",
        "flatzinc-core",
    )
}
