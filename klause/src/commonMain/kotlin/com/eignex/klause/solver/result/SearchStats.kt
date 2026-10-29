package com.eignex.klause.solver.result

import com.eignex.klause.util.LongHashSet
import com.eignex.kumulant.stat.summary.CountStat
import com.eignex.kumulant.stat.summary.MaxResult
import com.eignex.kumulant.stat.summary.MaxStat
import com.eignex.kumulant.stat.summary.MeanStat
import com.eignex.kumulant.stat.summary.SumResult
import com.eignex.kumulant.stat.summary.WeightedMeanResult

/**
 * Core tree-search counters — the CDCL / DFS backbone shared by the complete backends: node and
 * failure counts, restarts, propagations, learned clauses, and the depth distribution. Zero for a
 * pure local-search solve apart from [restarts]. See [SolveStats].
 */
data class SearchStats(
    /** Decision nodes visited. */
    val nodes: SumResult = ZERO_COUNT,
    /** Failed nodes: propagation conflicts plus bound-pruned subtrees. */
    val fails: SumResult = ZERO_COUNT,
    /** Restarts performed (shared field — the LS engine folds its own restart count in here). */
    val restarts: SumResult = ZERO_COUNT,
    /** Finite deductions reported by successful search fixpoints, excluding decision and root pins. */
    val propagations: SumResult = ZERO_COUNT,
    /** Clauses learned by conflict analysis. */
    val learnedClauses: SumResult = ZERO_COUNT,
    /** Literals across [learnedClauses]; their mean size shows whether the explanations behind them are sharp. */
    val learnedLiterals: SumResult = ZERO_COUNT,
    /** Literal block distance summed across [learnedClauses]. */
    val learnedLbd: SumResult = ZERO_COUNT,
    /** Clauses conflict analysis re-derived identically — a livelock indicator when large. */
    val relearned: SumResult = ZERO_COUNT,
    /** Deepest decision level reached. */
    val peakDepth: MaxResult = NO_MAX,
    /** Mean decision depth over visited nodes: deep-and-thin vs shallow-and-wide. */
    val depthMean: WeightedMeanResult = WeightedMeanResult(totalWeights = 0.0, mean = Double.NaN),
    /** Most variables found fixed at a restart, back at the root: progress no later search undoes. */
    val rootFixed: MaxResult = NO_MAX,
    /** Literals inprocessing pinned and propagated to a fixpoint to strengthen a clause. */
    val inprocessProbes: SumResult = ZERO_COUNT,
    /** Clause literals inprocessing scanned looking for subsumed or strengthenable clauses. */
    val inprocessVisits: SumResult = ZERO_COUNT,
    /** Learned clauses with a literal block distance of at most two: the short, reusable ones. */
    val glueClauses: SumResult = ZERO_COUNT,
    /** Propagation dispatches, watcher/level visits, linear term inspections and reasons,
     *  including bounded value-score visits. */
    val propagationWork: SumResult = ZERO_COUNT,
    /** Work in the session constructor's root fixpoint. */
    val rootPropagationWork: SumResult = ZERO_COUNT,
    /** Nanoseconds in propagation fixpoints, including the constructor's root fixpoint. */
    val propagationNanos: SumResult = ZERO_COUNT,
    /** Nanoseconds in the session constructor's root fixpoint. */
    val rootPropagationNanos: SumResult = ZERO_COUNT,
    /** Eligible clausal primal probes created. */
    val clausalPrimalStarts: SumResult = ZERO_COUNT,
    /** Clausal primal feasibility trials started. */
    val clausalPrimalTrials: SumResult = ZERO_COUNT,
    /** Feasible trial models returned by the private satisfaction search. */
    val clausalPrimalModels: SumResult = ZERO_COUNT,
    /** Strictly improving clausal primal models completed by polishing. */
    val clausalPrimalProposals: SumResult = ZERO_COUNT,
    /** Clausal primal proposals verified and installed as arm incumbents. */
    val clausalPrimalAccepted: SumResult = ZERO_COUNT,
    /** Clausal trials rejected by an empty residual clause. */
    val clausalPrimalRejected: SumResult = ZERO_COUNT,
    /** Private clausal trials exhausted without a feasible model. */
    val clausalPrimalInfeasible: SumResult = ZERO_COUNT,
    /** Private clausal trials stopped incompletely without a feasible model. */
    val clausalPrimalIncomplete: SumResult = ZERO_COUNT,
    /** Relation-count and residual-domain value guidance, separate from real search conflicts. */
    val valueOrdering: ValueOrderingStats = ValueOrderingStats(),
    /** Learned-object lifetime and opt-in finite factor-attribution counters. */
    val learning: Map<String, Long> = emptyMap(),

) {
    /** Construct observations without learned-object lifetime telemetry. */
    constructor(
        nodes: SumResult = ZERO_COUNT,
        fails: SumResult = ZERO_COUNT,
        restarts: SumResult = ZERO_COUNT,
        propagations: SumResult = ZERO_COUNT,
        learnedClauses: SumResult = ZERO_COUNT,
        learnedLiterals: SumResult = ZERO_COUNT,
        learnedLbd: SumResult = ZERO_COUNT,
        relearned: SumResult = ZERO_COUNT,
        peakDepth: MaxResult = NO_MAX,
        depthMean: WeightedMeanResult = WeightedMeanResult(totalWeights = 0.0, mean = Double.NaN),
        rootFixed: MaxResult = NO_MAX,
        inprocessProbes: SumResult = ZERO_COUNT,
        inprocessVisits: SumResult = ZERO_COUNT,
        glueClauses: SumResult = ZERO_COUNT,
        propagationWork: SumResult = ZERO_COUNT,
        rootPropagationWork: SumResult = ZERO_COUNT,
        propagationNanos: SumResult = ZERO_COUNT,
        rootPropagationNanos: SumResult = ZERO_COUNT,
        clausalPrimalStarts: SumResult = ZERO_COUNT,
        clausalPrimalTrials: SumResult = ZERO_COUNT,
        clausalPrimalModels: SumResult = ZERO_COUNT,
        clausalPrimalProposals: SumResult = ZERO_COUNT,
        clausalPrimalAccepted: SumResult = ZERO_COUNT,
        clausalPrimalRejected: SumResult = ZERO_COUNT,
        clausalPrimalInfeasible: SumResult = ZERO_COUNT,
        clausalPrimalIncomplete: SumResult = ZERO_COUNT,
        valueOrdering: ValueOrderingStats = ValueOrderingStats(),
    ) : this(
        nodes,
        fails,
        restarts,
        propagations,
        learnedClauses,
        learnedLiterals,
        learnedLbd,
        relearned,
        peakDepth,
        depthMean,
        rootFixed,
        inprocessProbes,
        inprocessVisits,
        glueClauses,
        propagationWork,
        rootPropagationWork,
        propagationNanos,
        rootPropagationNanos,
        clausalPrimalStarts,
        clausalPrimalTrials,
        clausalPrimalModels,
        clausalPrimalProposals,
        clausalPrimalAccepted,
        clausalPrimalRejected,
        clausalPrimalInfeasible,
        clausalPrimalIncomplete,
        valueOrdering,
        emptyMap(),
    )

    /** Copy observations while retaining learned-object lifetime telemetry. */
    fun copy(
        nodes: SumResult = this.nodes,
        fails: SumResult = this.fails,
        restarts: SumResult = this.restarts,
        propagations: SumResult = this.propagations,
        learnedClauses: SumResult = this.learnedClauses,
        learnedLiterals: SumResult = this.learnedLiterals,
        learnedLbd: SumResult = this.learnedLbd,
        relearned: SumResult = this.relearned,
        peakDepth: MaxResult = this.peakDepth,
        depthMean: WeightedMeanResult = this.depthMean,
        rootFixed: MaxResult = this.rootFixed,
        inprocessProbes: SumResult = this.inprocessProbes,
        inprocessVisits: SumResult = this.inprocessVisits,
        glueClauses: SumResult = this.glueClauses,
        propagationWork: SumResult = this.propagationWork,
        rootPropagationWork: SumResult = this.rootPropagationWork,
        propagationNanos: SumResult = this.propagationNanos,
        rootPropagationNanos: SumResult = this.rootPropagationNanos,
        clausalPrimalStarts: SumResult = this.clausalPrimalStarts,
        clausalPrimalTrials: SumResult = this.clausalPrimalTrials,
        clausalPrimalModels: SumResult = this.clausalPrimalModels,
        clausalPrimalProposals: SumResult = this.clausalPrimalProposals,
        clausalPrimalAccepted: SumResult = this.clausalPrimalAccepted,
        clausalPrimalRejected: SumResult = this.clausalPrimalRejected,
        clausalPrimalInfeasible: SumResult = this.clausalPrimalInfeasible,
        clausalPrimalIncomplete: SumResult = this.clausalPrimalIncomplete,
        valueOrdering: ValueOrderingStats = this.valueOrdering,
    ): SearchStats = SearchStats(
        nodes,
        fails,
        restarts,
        propagations,
        learnedClauses,
        learnedLiterals,
        learnedLbd,
        relearned,
        peakDepth,
        depthMean,
        rootFixed,
        inprocessProbes,
        inprocessVisits,
        glueClauses,
        propagationWork,
        rootPropagationWork,
        propagationNanos,
        rootPropagationNanos,
        clausalPrimalStarts,
        clausalPrimalTrials,
        clausalPrimalModels,
        clausalPrimalProposals,
        clausalPrimalAccepted,
        clausalPrimalRejected,
        clausalPrimalInfeasible,
        clausalPrimalIncomplete,
        valueOrdering,
        learning,
    )

    /** Combine two workers' search stats: counters add, peak depth maxes, depth means weight-combine. */
    fun mergedWith(o: SearchStats): SearchStats = SearchStats(
        valueOrdering = valueOrdering.mergedWith(o.valueOrdering),
        learning = mergeLearningCounts(learning, o.learning),
        nodes = SumResult(nodes.sum + o.nodes.sum),
        fails = SumResult(fails.sum + o.fails.sum),
        restarts = SumResult(restarts.sum + o.restarts.sum),
        propagations = SumResult(propagations.sum + o.propagations.sum),
        propagationWork = SumResult(propagationWork.sum + o.propagationWork.sum),
        rootPropagationWork = SumResult(rootPropagationWork.sum + o.rootPropagationWork.sum),
        propagationNanos = SumResult(propagationNanos.sum + o.propagationNanos.sum),
        rootPropagationNanos = SumResult(rootPropagationNanos.sum + o.rootPropagationNanos.sum),
        learnedClauses = SumResult(learnedClauses.sum + o.learnedClauses.sum),
        learnedLiterals = SumResult(learnedLiterals.sum + o.learnedLiterals.sum),
        learnedLbd = SumResult(learnedLbd.sum + o.learnedLbd.sum),
        relearned = SumResult(relearned.sum + o.relearned.sum),
        peakDepth = MaxResult(maxOf(peakDepth.max, o.peakDepth.max)),
        depthMean = mergeDepthMean(depthMean, o.depthMean),
        rootFixed = MaxResult(maxOf(rootFixed.max, o.rootFixed.max)),
        inprocessProbes = SumResult(inprocessProbes.sum + o.inprocessProbes.sum),
        inprocessVisits = SumResult(inprocessVisits.sum + o.inprocessVisits.sum),
        glueClauses = SumResult(glueClauses.sum + o.glueClauses.sum),
        clausalPrimalStarts = SumResult(clausalPrimalStarts.sum + o.clausalPrimalStarts.sum),
        clausalPrimalTrials = SumResult(clausalPrimalTrials.sum + o.clausalPrimalTrials.sum),
        clausalPrimalModels = SumResult(clausalPrimalModels.sum + o.clausalPrimalModels.sum),
        clausalPrimalProposals = SumResult(clausalPrimalProposals.sum + o.clausalPrimalProposals.sum),
        clausalPrimalAccepted = SumResult(clausalPrimalAccepted.sum + o.clausalPrimalAccepted.sum),
        clausalPrimalRejected = SumResult(clausalPrimalRejected.sum + o.clausalPrimalRejected.sum),
        clausalPrimalInfeasible = SumResult(clausalPrimalInfeasible.sum + o.clausalPrimalInfeasible.sum),
        clausalPrimalIncomplete = SumResult(clausalPrimalIncomplete.sum + o.clausalPrimalIncomplete.sum),


    )
}

/** Mutable [SearchStats] accumulator; snapshots into a [SearchStats]. See [SolveStatsSink]. */
internal class SearchStatsSink {
    var learning: () -> Map<String, Long> = { emptyMap() }
    var valueOrdering: () -> ValueOrderingStats = { ValueOrderingStats() }
    var clausalPrimalStarts: Long = 0L
    var clausalPrimalTrials: Long = 0L
    var clausalPrimalModels: Long = 0L
    var clausalPrimalProposals: Long = 0L
    var clausalPrimalAccepted: Long = 0L
    var clausalPrimalRejected: Long = 0L
    var clausalPrimalInfeasible: Long = 0L
    var clausalPrimalIncomplete: Long = 0L


    var propagationWork: () -> Long = { 0L }
    var rootPropagationWork: () -> Long = { 0L }
    var propagationNanos: () -> Long = { 0L }
    var rootPropagationNanos: () -> Long = { 0L }

    val nodes: CountStat = CountStat()
    val fails: CountStat = CountStat()
    val restarts: CountStat = CountStat()
    val propagations: CountStat = CountStat()
    val learnedClauses: CountStat = CountStat()
    private var learnedLiterals = 0L
    private var learnedLbd = 0L
    val relearned: CountStat = CountStat()

    // Fingerprints of the clauses learned so far, to count the ones derived again.
    private val learnedFingerprints = LongHashSet()
    val peakDepth: MaxStat = MaxStat()
    val depthMean: MeanStat = MeanStat()
    val rootFixed: MaxStat = MaxStat()

    /**
     * Nodes visited so far, as a plain counter.
     *
     * [nodes] holds the same total but reading it allocates a result, and this is read on the search's
     * pause check — every node — by a scheduler slicing on node count rather than on a clock.
     */
    var nodeCount: Long = 0L
        private set

    /** [SearchStats.inprocessProbes] as a plain counter, read on the same pause check as [nodeCount]. */
    var inprocessProbes: Long = 0L
        private set

    /** [SearchStats.inprocessVisits] as a plain counter, read on the same pause check as [nodeCount]. */
    var inprocessVisits: Long = 0L
        private set

    /** Call on every visited decision node so [nodes] increments and the depth stats see the observation. */
    fun observeNode(depth: Int) {
        nodeCount++
        nodes.update(1.0)
        peakDepth.update(depth.toDouble())
        depthMean.update(depth.toDouble())
    }
    fun observeFail() = fails.update(1.0)
    fun observeRestart() = restarts.update(1.0)
    fun observePropagation(count: Long = 1L) = repeat(count.toInt()) { propagations.update(1.0) }

    /** Count a learned constraint over [literals] with literal block distance [lbd], and whether it was seen before. */
    fun observeLearned(literals: IntArray, lbd: Int) {
        learnedClauses.update(1.0)
        learnedLiterals += literals.size
        learnedLbd += lbd
        if (!learnedFingerprints.add(fingerprint(literals))) relearned.update(1.0)
        if (lbd <= GLUE_LBD) glueClauses++
    }
    fun observeRootFixed(count: Int) = rootFixed.update(count.toDouble())

    private var glueClauses = 0L

    /** Count an inprocessing slice's [probes] and clause-literal [visits]. */
    fun observeInprocessing(probes: Long, visits: Long) {
        inprocessProbes += probes
        inprocessVisits += visits
    }

    fun snapshot(): SearchStats = SearchStats(
        valueOrdering = valueOrdering(),
        learning = learning(),
        nodes = nodes.read(),
        fails = fails.read(),
        restarts = restarts.read(),
        propagations = propagations.read(),
        propagationWork = SumResult(propagationWork().toDouble()),
        rootPropagationWork = SumResult(rootPropagationWork().toDouble()),
        propagationNanos = SumResult(propagationNanos().toDouble()),
        rootPropagationNanos = SumResult(rootPropagationNanos().toDouble()),
        learnedClauses = learnedClauses.read(),
        learnedLiterals = SumResult(learnedLiterals.toDouble()),
        learnedLbd = SumResult(learnedLbd.toDouble()),
        relearned = relearned.read(),
        peakDepth = peakDepth.read(),
        depthMean = depthMean.read(),
        rootFixed = rootFixed.read(),
        inprocessProbes = SumResult(inprocessProbes.toDouble()),
        inprocessVisits = SumResult(inprocessVisits.toDouble()),
        glueClauses = SumResult(glueClauses.toDouble()),
        clausalPrimalStarts = SumResult(clausalPrimalStarts.toDouble()),
        clausalPrimalTrials = SumResult(clausalPrimalTrials.toDouble()),
        clausalPrimalModels = SumResult(clausalPrimalModels.toDouble()),
        clausalPrimalProposals = SumResult(clausalPrimalProposals.toDouble()),
        clausalPrimalAccepted = SumResult(clausalPrimalAccepted.toDouble()),
        clausalPrimalRejected = SumResult(clausalPrimalRejected.toDouble()),
        clausalPrimalInfeasible = SumResult(clausalPrimalInfeasible.toDouble()),
        clausalPrimalIncomplete = SumResult(clausalPrimalIncomplete.toDouble()),


    )
}

// The largest literal block distance a learned clause can have and still count as glue.
internal const val GLUE_LBD = 2

// An order-independent 64-bit fingerprint of a literal set: equal sets always match, and distinct ones collide
// rarely enough for a diagnostic count.
private fun fingerprint(literals: IntArray): Long {
    var sum = 0L
    var xor = 0L
    for (literal in literals) {
        val mixed = (literal.toLong() + 1) * -0x61c8864680b583ebL
        sum += mixed
        xor = xor xor (mixed ushr 17)
    }
    return sum * 31 + xor + literals.size
}
