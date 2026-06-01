package com.eignex.klause.bench.target

import com.eignex.klause.bench.runner.Budget

/** Which measurement a target runs. */
enum class MetricKind { TIME, UNIFORMNESS, COMPLETENESS, VERIFY, PARITY, ANYTIME }

/**
 * A pre-configured bench: a set of catalog suites bound to a [metric] (and a [budget]). The
 * single `bench` CLI takes a target id and runs exactly this. Adding/removing problems is a
 * catalog edit; adding a new comparison is a [Target] entry — the two stay independent.
 */
data class Target(
    val id: String,
    val description: String,
    val suiteIds: List<String>,
    val metric: MetricKind,
    val budget: Budget = Budget(),
)

object Targets {
    /** Suites resolvable fully in-process today (everything except the MiniZinc smoke set,
     *  which needs the phase-2 `minizinc` compile step). */
    private val IN_PROCESS_CORE = listOf(
        "handwritten-core", "dimacs-core", "opb-core", "schema-core", "flatzinc-core",
    )

    val all: List<Target> = listOf(
        Target("time-core", "Wall-time + propagation microbench over the in-process core", IN_PROCESS_CORE, MetricKind.TIME),
        Target("uniformness-core", "Sampling uniformness over the in-process core", IN_PROCESS_CORE, MetricKind.UNIFORMNESS),
        Target("completeness-core", "Enumeration reach-under-budget over the in-process core", IN_PROCESS_CORE, MetricKind.COMPLETENESS),
        Target("verify-core", "Cross-backend agreement + sample-validity gate over the in-process core", IN_PROCESS_CORE, MetricKind.VERIFY),
        Target("parity-core", "Differential parity (klause vs Choco) over the in-process core", IN_PROCESS_CORE, MetricKind.PARITY),
        Target("mzn-parity-smoke", "Differential parity (klause vs Choco) over the MiniZinc smoke set", listOf("mzn-smoke"), MetricKind.PARITY),
        Target("satlib-parity", "Differential parity (klause vs Choco) over the auto-fetched SATLIB sample", listOf("satlib-uf20"), MetricKind.PARITY),
        Target("mzn-anytime", "Anytime optimization (klause-LS vs OR-Tools) over the MiniZinc smoke set", listOf("mzn-smoke"), MetricKind.ANYTIME, Budget(timeoutMillis = 5_000)),
    )

    fun get(id: String): Target =
        all.firstOrNull { it.id == id } ?: error("no such target: $id (have ${all.map { it.id }})")
}
