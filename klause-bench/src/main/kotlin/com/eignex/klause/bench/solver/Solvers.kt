package com.eignex.klause.bench.solver

import com.eignex.klause.logicng.LogicNGParams
import com.eignex.klause.logicng.LogicNGSolver
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.brute.BruteForceParams
import com.eignex.klause.solver.brute.BruteForceSolver
import com.eignex.klause.solver.localsearch.LocalSearchParams
import com.eignex.klause.solver.localsearch.LocalSearchSolver

/**
 * The solver axis. A [Backend] names an engine; a [SolverConfig] pre-binds a backend with
 * its params; [Solvers.build] turns a config into an [InProcessSolver] bound to a problem.
 *
 * All backends run **in-process** — there are no external solver binaries. The native klause
 * engines (LS, backtrack) plus the SAT/CP side-door adapters (LogicNG now; Choco and
 * OscaR.cbls reference adapters wired in phase 2) all implement the same shape.
 */
enum class Backend {
    /** klause local-search engine (stochastic sampling, large domains). */
    KLAUSE_LS,
    /** klause complete CSP backtracking engine (proofs, optima, enumeration). */
    KLAUSE_COMPLETE,
    /** LogicNG bit-blasted SAT side-door. */
    LOGICNG,
    /** Exhaustive enumeration; only viable when the assignment space is tiny. */
    BRUTE_FORCE,
    /** Choco Solver, complete-search reference adapter (phase 2, `klause-choco`). */
    CHOCO,
    /** OscaR.cbls, local-search reference adapter (phase 2, `klause-oscar`). */
    OSCAR_CBLS,
}

/** A named solver configuration: a [Backend] plus its pre-bound params. */
data class SolverConfig(val id: String, val backend: Backend)

/**
 * Type-erased solver wrapper for the bench metrics. Each impl pre-binds its params and
 * exposes the four call kinds the metrics consume (single solve, bounded samples/enumerate,
 * and the lazy sequence views used under wall-time budgets).
 */
interface InProcessSolver {
    val name: String
    val problem: Problem
    fun solve(): SolveResult
    fun samples(n: Int): List<Sample>
    fun enumerated(n: Int): List<Sample>
    fun enumerateSequence(): Sequence<Sample>
    fun samplesSequence(): Sequence<Sample>
}

private class LocalSearchBench(
    override val problem: Problem,
    private val params: LocalSearchParams = LocalSearchParams(maxFlips = 10_000L, randomSeed = 0L),
) : InProcessSolver {
    private val s = LocalSearchSolver(problem)
    override val name = "local-search"
    override fun solve() = s.solve(params)
    override fun samples(n: Int) = s.samples(params).take(n).toList()
    override fun enumerated(n: Int) = s.enumerate(params).take(n).toList()
    override fun enumerateSequence() = s.enumerate(params)
    override fun samplesSequence() = s.samples(params)
}

private class LogicNGBench(
    override val problem: Problem,
    private val params: LogicNGParams = LogicNGParams(),
) : InProcessSolver {
    private val s = LogicNGSolver(problem)
    override val name = "logicng"
    override fun solve() = s.solve(params)
    override fun samples(n: Int) = s.samples(params).take(n).toList()
    override fun enumerated(n: Int) = s.enumerate(params).take(n).toList()
    override fun enumerateSequence() = s.enumerate(params)
    override fun samplesSequence() = s.samples(params)
}

private class BacktrackBench(
    override val problem: Problem,
    private val params: BacktrackParams = BacktrackParams(maxDecisions = 100_000L, randomSeed = 0L),
) : InProcessSolver {
    private val s = BacktrackSolver(problem)
    override val name = "backtrack"
    override fun solve() = s.solve(params)
    override fun samples(n: Int) = s.samples(params).take(n).toList()
    override fun enumerated(n: Int) = s.enumerate(params).take(n).toList()
    override fun enumerateSequence() = s.enumerate(params)
    override fun samplesSequence() = s.samples(params)
}

private class BruteForceBench(
    override val problem: Problem,
    private val params: BruteForceParams = BruteForceParams(randomSeed = 0L),
) : InProcessSolver {
    private val s = BruteForceSolver(problem)
    override val name = "brute-force"
    override fun solve() = s.solve(params)
    override fun samples(n: Int) = s.samples(params).take(n).toList()
    override fun enumerated(n: Int) = s.enumerate(params).take(n).toList()
    override fun enumerateSequence() = s.enumerate(params)
    override fun samplesSequence() = s.samples(params)
}

object Solvers {
    val KLAUSE_LS = SolverConfig("local-search", Backend.KLAUSE_LS)
    val KLAUSE_COMPLETE = SolverConfig("backtrack", Backend.KLAUSE_COMPLETE)
    val LOGICNG = SolverConfig("logicng", Backend.LOGICNG)
    val BRUTE_FORCE = SolverConfig("brute-force", Backend.BRUTE_FORCE)

    /** Build a bound solver for [problem]. */
    fun build(config: SolverConfig, problem: Problem): InProcessSolver = when (config.backend) {
        Backend.KLAUSE_LS -> LocalSearchBench(problem)
        Backend.KLAUSE_COMPLETE -> BacktrackBench(problem)
        Backend.LOGICNG -> LogicNGBench(problem)
        Backend.BRUTE_FORCE -> BruteForceBench(problem)
        Backend.CHOCO, Backend.OSCAR_CBLS ->
            error("${config.backend} reference adapter is wired in phase 2")
    }

    /** The default in-process portfolio, mirroring the legacy `defaultSolvers`: LS +
     *  backtrack + LogicNG, plus brute force only when the space fits. */
    fun defaultPortfolio(problem: Problem): List<InProcessSolver> = buildList {
        add(LocalSearchBench(problem))
        add(BacktrackBench(problem))
        add(LogicNGBench(problem))
        if (BruteForceSolver.fits(problem)) add(BruteForceBench(problem))
    }
}
