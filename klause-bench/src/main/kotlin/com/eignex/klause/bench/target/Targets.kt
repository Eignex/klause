package com.eignex.klause.bench.target

import com.eignex.klause.bench.runner.Budget
import com.eignex.klause.bench.solver.Backend

/** Which measurement a target runs. */
internal enum class MetricKind {
    TIME,
    UNIFORMNESS,
    COMPLETENESS,
    VERIFY,
    PARITY,
    ANYTIME,
    COVERAGE,
    AUDIT,
    TUNING,
    SEARCH,
    CREDIT,
}

/**
 * A named preset: a set of catalog suites bound to a [metric] (plus a [budget]/[reference]).
 * Presets are saved shorthands for a `bench <metric> [filters]` invocation — the general form
 * always works, so a preset only earns its place by carrying non-obvious config (a tuned
 * budget, a curated multi-suite mix). Per-suite / per-reference variants are *not* presets;
 * spell them with filters, e.g. `bench parity suite=smtlib-core reference=ortools`.
 */
internal data class Target(
    val id: String,
    val description: String,
    val suiteIds: List<String>,
    val metric: MetricKind,
    val budget: Budget = Budget(),
    /** Reference solver for differential metrics (PARITY / ANYTIME). `null` = the metric's
     *  own default (Choco for parity, OR-Tools for anytime). */
    val reference: Backend? = null,
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
     */
    val all: List<Target> = listOf(
        Target(
            "anytime",
            "Anytime optimization (klause-LS vs OR-Tools) over the MiniZinc smoke set",
            listOf("mzn-smoke"),
            MetricKind.ANYTIME,
            Budget(timeoutMillis = 5_000),
        ),
        Target(
            "tune-mixed",
            "Tune klause solver configs over a mixed sat+opt workload (rank by avg dense rank)",
            listOf("handwritten-core", "flatzinc-core", "opb-core", "smtlib-core", "xcsp3-core"),
            MetricKind.TUNING,
            Budget(timeoutMillis = 2_000),
        ),
        Target(
            "search-slack-alldiff",
            "Complete-search effort (conflicts/nodes) over slack all_different instances",
            listOf("slack-alldiff"),
            MetricKind.SEARCH,
            Budget(timeoutMillis = 30_000),
        ),
        Target(
            "mzn-credit-ls",
            "LS portfolio credit campaign (top-8 palette prefix) over the MiniZinc Challenge benchmarks",
            listOf("mzn-bench"),
            MetricKind.CREDIT,
            Budget(timeoutMillis = 10_000),
        ),
    )

    fun get(id: String): Target =
        all.firstOrNull { it.id == id } ?: error("no such target: $id (have ${all.map { it.id }})")
}
