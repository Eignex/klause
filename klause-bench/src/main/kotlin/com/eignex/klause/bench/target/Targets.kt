package com.eignex.klause.bench.target

import com.eignex.klause.bench.runner.Budget

/** Which measurement a target runs. */
internal enum class MetricKind {
    UNIFORMNESS,
    COMPLETENESS,
    SOLVE,
    COVERAGE,
    AUDIT,
}

/**
 * A named preset: a set of catalog suites bound to a [metric] (plus a [budget]/[reference]).
 * Presets are saved shorthands for a `bench <metric> [filters]` invocation — the general form
 * always works, so a preset only earns its place by carrying non-obvious config (a tuned
 * budget, a curated multi-suite mix). Per-suite / per-backend variants are *not* presets;
 * spell them with filters, e.g. `bench solve suite=smtlib-core backend=choco`.
 */
internal data class Target(
    val id: String,
    val description: String,
    val suiteIds: List<String>,
    val metric: MetricKind,
    val budget: Budget = Budget(),
    /** Solver id for the [MetricKind.SOLVE] metric (a registered MiniZinc solver, e.g. `choco`);
     *  `null` = klause. */
    val backend: String? = null,
)

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

    /**
     * The kept presets. A preset earns its place only by carrying config that *isn't* obvious
     * from a one-line filter — a tuned budget or a curated suite mix. A preset that would just
     * be `bench <metric> suite=core` is not kept: spell it with the `suite=core` token instead.
     *
     * Currently none: every measurement is reachable by `bench <metric> <filters>`.
     */
    val all: List<Target> = emptyList()

    fun get(id: String): Target =
        all.firstOrNull { it.id == id } ?: error("no such target: $id (have ${all.map { it.id }})")
}
