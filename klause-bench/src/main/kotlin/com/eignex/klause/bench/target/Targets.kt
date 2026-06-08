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
 * A pre-configured bench: a set of catalog suites bound to a [metric] (and a [budget]). The
 * single `bench` CLI takes a target id and runs exactly this. Adding/removing problems is a
 * catalog edit; adding a new comparison is a [Target] entry — the two stay independent.
 */
internal data class Target(
    val id: String,
    val description: String,
    val suiteIds: List<String>,
    val metric: MetricKind,
    val budget: Budget = Budget(),
    /** Reference solver for differential metrics (PARITY / ANYTIME). `null` = the metric's
     *  own default (Choco for parity, OR-Tools for anytime). Overridable at run time with
     *  `-Dklause.bench.parity.reference` / `-Dklause.bench.anytime.reference`. */
    val reference: Backend? = null,
)

internal object Targets {
    /** Suites resolvable fully in-process (everything except the MiniZinc smoke set, which
     *  needs the `minizinc` compile step). */
    private val IN_PROCESS_CORE = listOf(
        "handwritten-core",
        "dimacs-core",
        "opb-core",
        "schema-core",
        "flatzinc-core",
    )

    val all: List<Target> = listOf(
        Target(
            "time-core",
            "Wall-time + propagation microbench over the in-process core",
            IN_PROCESS_CORE,
            MetricKind.TIME,
        ),
        Target(
            "uniformness-core",
            "Sampling uniformness over the in-process core",
            IN_PROCESS_CORE,
            MetricKind.UNIFORMNESS,
        ),
        Target(
            "completeness-core",
            "Enumeration reach-under-budget over the in-process core",
            IN_PROCESS_CORE,
            MetricKind.COMPLETENESS,
        ),
        Target(
            "verify-core",
            "Cross-backend agreement + sample-validity gate over the in-process core",
            IN_PROCESS_CORE,
            MetricKind.VERIFY,
        ),
        Target(
            "parity-core",
            "Differential parity (klause vs Choco) over the in-process core",
            IN_PROCESS_CORE,
            MetricKind.PARITY,
        ),
        Target(
            "mzn-parity-smoke",
            "Differential parity (klause vs Choco) over the MiniZinc smoke set",
            listOf("mzn-smoke"),
            MetricKind.PARITY,
        ),
        Target(
            "satlib-parity",
            "Differential parity (klause vs Choco) over the auto-fetched SATLIB sample",
            listOf("satlib-uf20"),
            MetricKind.PARITY,
        ),
        Target(
            "mzn-anytime",
            "Anytime optimization (klause-LS vs OR-Tools) over the MiniZinc smoke set",
            listOf("mzn-smoke"),
            MetricKind.ANYTIME,
            Budget(timeoutMillis = 5_000),
        ),
        Target(
            "smtlib-parity",
            "Differential parity (klause vs Choco) over the SMT-LIB QF_LIA set",
            listOf("smtlib-core"),
            MetricKind.PARITY,
        ),
        Target(
            "xcsp3-parity",
            "Differential parity (klause vs Choco) over the XCSP3 set",
            listOf("xcsp3-core"),
            MetricKind.PARITY,
        ),
        Target(
            "mzn-coverage-smoke",
            "Native-predicate coverage over the MiniZinc smoke set",
            listOf("mzn-smoke"),
            MetricKind.COVERAGE,
        ),
        Target(
            "mzn-coverage",
            "Native-predicate coverage over the MiniZinc Challenge benchmarks (fetched)",
            listOf("mzn-bench"),
            MetricKind.COVERAGE,
        ),
        Target(
            "mzn-audit-smoke",
            "Compile audit (native|decomposed + ingest smoke) over the MiniZinc smoke set",
            listOf("mzn-smoke"),
            MetricKind.AUDIT,
        ),
        Target(
            "mzn-audit",
            "Compile audit over the MiniZinc Challenge benchmarks (fetched)",
            listOf("mzn-bench"),
            MetricKind.AUDIT,
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
        // Portfolio credit campaigns: per-worker attribution (first/best/sole + marginal
        // ranking) over the Challenge corpus. Composition via -Dklause.bench.credit.portfolio
        // (<ls>:<bt>; ls/cp/mixed all supported) and -Dklause.bench.credit.configs (named LS
        // pool selection, e.g. `all`); the targets below bind the common shapes.
        Target(
            "mzn-credit-ls",
            "LS portfolio credit campaign (top-8 palette prefix) over the MiniZinc Challenge benchmarks",
            listOf("mzn-bench"),
            MetricKind.CREDIT,
            Budget(timeoutMillis = 10_000),
        ),
        // OR-Tools-referenced variants (same suites, OR-Tools CP-SAT as the trusted reference).
        Target(
            "parity-core-ortools",
            "Differential parity (klause vs OR-Tools) over the in-process core",
            IN_PROCESS_CORE,
            MetricKind.PARITY,
            reference = Backend.ORTOOLS,
        ),
        Target(
            "mzn-parity-ortools",
            "Differential parity (klause vs OR-Tools) over the MiniZinc smoke set",
            listOf("mzn-smoke"),
            MetricKind.PARITY,
            reference = Backend.ORTOOLS,
        ),
        Target(
            "mzn-anytime-choco",
            "Anytime optimization (klause-LS vs Choco) over the MiniZinc smoke set",
            listOf("mzn-smoke"),
            MetricKind.ANYTIME,
            Budget(timeoutMillis = 5_000),
            reference = Backend.CHOCO,
        ),
        // Yuck-referenced variants (LS-vs-LS; requires a provisioned Yuck distribution, see
        // `:klause-yuck:installYuck`). Temporary, for the LS parity sweep.
        Target(
            "mzn-parity-yuck",
            "Differential parity (klause vs Yuck LS) over the MiniZinc smoke set",
            listOf("mzn-smoke"),
            MetricKind.PARITY,
            reference = Backend.YUCK,
        ),
        Target(
            "mzn-anytime-yuck",
            "Anytime optimization (klause-LS vs Yuck LS) over the MiniZinc smoke set",
            listOf("mzn-smoke"),
            MetricKind.ANYTIME,
            Budget(timeoutMillis = 5_000),
            reference = Backend.YUCK,
        ),
    )

    fun get(id: String): Target =
        all.firstOrNull { it.id == id } ?: error("no such target: $id (have ${all.map { it.id }})")
}
