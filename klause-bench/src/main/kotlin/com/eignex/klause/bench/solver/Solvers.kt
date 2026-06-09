package com.eignex.klause.bench.solver

import com.eignex.klause.choco.ChocoParams
import com.eignex.klause.choco.ChocoSolver
import com.eignex.klause.logicng.LogicNGParams
import com.eignex.klause.logicng.LogicNGSolver
import com.eignex.klause.ortools.OrToolsParams
import com.eignex.klause.ortools.OrToolsSolver
import com.eignex.klause.portfolio.Portfolio
import com.eignex.klause.portfolio.PortfolioBuilder
import com.eignex.klause.portfolio.PortfolioSpec
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.backtrack.LpAutoConfig
import com.eignex.klause.solver.brute.BruteForceParams
import com.eignex.klause.solver.brute.BruteForceSolver
import com.eignex.klause.solver.localsearch.LocalSearchParams
import com.eignex.klause.solver.localsearch.LocalSearchSolver
import com.eignex.klause.yuck.YuckParams
import com.eignex.klause.yuck.YuckSolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

/**
 * The solver axis. A [Backend] names an engine; a [SolverConfig] pre-binds a backend with
 * its params; [Solvers.build] turns a config into an [InProcessSolver] bound to a problem.
 *
 * All backends run **in-process** — there are no external solver binaries. The native klause
 * engines (LS, backtrack) plus the SAT/CP side-door adapters all implement the same shape.
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

    /** Choco Solver, complete-search reference adapter (`klause-choco`). */
    CHOCO,

    /** OR-Tools CP-SAT, anytime / local-search reference adapter (`klause-ortools`). */
    ORTOOLS,

    /** Yuck, local-search reference adapter (`klause-yuck`) — temporary, for the LS parity
     *  sweep. Runs an external Yuck subprocess (Yuck is not on Maven Central); provision it
     *  with `./gradlew :klause-yuck:installYuck` or `-Dklause.yuck.home`. */
    YUCK,

    /** klause unified parallel [Portfolio] (mixed LS + backtrack workers) run *as a solver* —
     *  the same engine the anytime metric drives, exposed here so the time / completeness /
     *  parity / verify metrics can benchmark the portfolio itself. Worker counts come from
     *  `-Dklause.portfolio.ls` / `-Dklause.portfolio.bt` (defaults 4 / 2). */
    KLAUSE_PORTFOLIO,
}

/** A named solver configuration: a [Backend] plus its pre-bound params. */
data class SolverConfig(internal val id: String, internal val backend: Backend)

/**
 * Type-erased solver wrapper for the bench metrics. Each impl pre-binds its params and
 * exposes the four call kinds the metrics consume (single solve, bounded samples/enumerate,
 * and the lazy sequence views used under wall-time budgets).
 */
internal interface InProcessSolver {
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

private class LogicNGBench(override val problem: Problem, private val params: LogicNGParams = LogicNGParams()) :
    InProcessSolver {
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
    base: BacktrackParams = BacktrackParams(maxDecisions = 100_000L, randomSeed = 0L),
) : InProcessSolver {
    // #245: `-Dklause.bench.lpauto=true` runs the backtrack backend with the structural LP
    // auto-config applied, so a corpus sweep can measure auto-enable vs the plain backend in one
    // flag. Off by default — the measurement (which needs CPU) is the open empirical half of #245.
    private val params =
        if (System.getProperty("klause.bench.lpauto")?.toBoolean() == true) {
            LpAutoConfig.recommend(problem, base)
        } else {
            base
        }
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

private class ChocoBench(override val problem: Problem, private val params: ChocoParams = ChocoParams()) :
    InProcessSolver {
    private val s = ChocoSolver(problem)
    override val name = "choco"
    override fun solve() = s.solve(params)
    override fun samples(n: Int) = s.samples(params).take(n).toList()
    override fun enumerated(n: Int) = s.enumerate(params).take(n).toList()
    override fun enumerateSequence() = s.enumerate(params)
    override fun samplesSequence() = s.samples(params)
}

private class OrToolsBench(override val problem: Problem, private val params: OrToolsParams = OrToolsParams()) :
    InProcessSolver {
    private val s = OrToolsSolver(problem)
    override val name = "ortools"
    override fun solve() = s.solve(params)
    override fun samples(n: Int) = s.samples(params).take(n).toList()
    override fun enumerated(n: Int) = s.enumerate(params).take(n).toList()
    override fun enumerateSequence() = s.enumerate(params)
    override fun samplesSequence() = s.samples(params)
}

private class YuckBench(override val problem: Problem, private val params: YuckParams = YuckParams()) :
    InProcessSolver {
    private val s = YuckSolver(problem)
    override val name = "yuck"
    override fun solve() = s.solve(params)
    override fun samples(n: Int) = s.samples(params).take(n).toList()
    override fun enumerated(n: Int) = s.enumerate(params).take(n).toList()
    override fun enumerateSequence() = s.enumerate(params)
    override fun samplesSequence() = s.samples(params)
}

/**
 * The unified parallel [Portfolio] exposed as a bench backend (#64): the same mixed LS +
 * backtrack engine the anytime metric drives, now selectable for the time / completeness /
 * parity / verify metrics so the portfolio can be benchmarked *as a solver* across the catalog.
 *
 * Worker counts come from `-Dklause.portfolio.ls` (default 4) and `-Dklause.portfolio.bt`
 * (default 2). The portfolio + sessions are built once and reused across calls (matching the
 * single-engine benches).
 *
 * **Bridge (load-bearing):** the coroutine [Portfolio] → blocking bridge MUST use
 * `runBlocking(Dispatchers.Default)`. Plain `runBlocking` is single-threaded and the CPU-bound,
 * never-suspending worker loops starve each other (symptom: a parallel pool behaves serially).
 *
 * **Scope:** [solve] and the bounded [samples] / [enumerated] are the intended use. The lazy
 * sequence views are materialised to a bounded cap (`SEQUENCE_CAP`) because LS workers stream
 * unbounded — the portfolio is a solve / time / parity backend, not an enumeration or
 * uniformness oracle.
 */
private class PortfolioBench(
    override val problem: Problem,
    ls: Int = System.getProperty("klause.portfolio.ls")?.toIntOrNull() ?: 4,
    bt: Int = System.getProperty("klause.portfolio.bt")?.toIntOrNull() ?: 2,
) : InProcessSolver {
    private val portfolio: Portfolio =
        PortfolioBuilder.build(problem, PortfolioSpec.mixed(localSearchWorkers = ls, backtrackWorkers = bt))
    override val name = "portfolio"

    @Suppress("InjectDispatcher")
    override fun solve(): SolveResult = runBlocking(Dispatchers.Default) { portfolio.solve() }

    override fun samples(n: Int): List<Sample> = collectSamples(n)
    override fun enumerated(n: Int): List<Sample> = collectSamples(n)
    override fun enumerateSequence(): Sequence<Sample> = collectSamples(SEQUENCE_CAP).asSequence()
    override fun samplesSequence(): Sequence<Sample> = collectSamples(SEQUENCE_CAP).asSequence()

    /** Fan in [n] samples across the worker pool on the default dispatcher, then stop (the
     *  `take` cancels the upstream flow, stopping every worker). */
    @Suppress("InjectDispatcher")
    private fun collectSamples(n: Int): List<Sample> = if (n <= 0) {
        emptyList()
    } else {
        runBlocking(Dispatchers.Default) { portfolio.samples().take(n).toList() }
    }

    private companion object {
        /** Cap for the lazy sequence views — LS workers stream unbounded, so a true lazy bridge
         *  is out of scope; these metrics aren't the portfolio backend's purpose. */
        const val SEQUENCE_CAP = 10_000
    }
}

internal object Solvers {
    val KLAUSE_LS = SolverConfig("local-search", Backend.KLAUSE_LS)
    val KLAUSE_COMPLETE = SolverConfig("backtrack", Backend.KLAUSE_COMPLETE)
    val LOGICNG = SolverConfig("logicng", Backend.LOGICNG)
    val BRUTE_FORCE = SolverConfig("brute-force", Backend.BRUTE_FORCE)
    val CHOCO = SolverConfig("choco", Backend.CHOCO)
    val ORTOOLS = SolverConfig("ortools", Backend.ORTOOLS)
    val YUCK = SolverConfig("yuck", Backend.YUCK)
    val KLAUSE_PORTFOLIO = SolverConfig("portfolio", Backend.KLAUSE_PORTFOLIO)

    /** Build a bound solver for [problem]. */
    fun build(config: SolverConfig, problem: Problem): InProcessSolver = when (config.backend) {
        Backend.KLAUSE_LS -> LocalSearchBench(problem)
        Backend.KLAUSE_COMPLETE -> BacktrackBench(problem)
        Backend.LOGICNG -> LogicNGBench(problem)
        Backend.BRUTE_FORCE -> BruteForceBench(problem)
        Backend.CHOCO -> ChocoBench(problem)
        Backend.ORTOOLS -> OrToolsBench(problem)
        Backend.YUCK -> YuckBench(problem)
        Backend.KLAUSE_PORTFOLIO -> PortfolioBench(problem)
    }

    /** The default in-process portfolio: LS + backtrack + LogicNG, plus brute force only when
     *  the space fits.
     *
     *  Set `-Dklause.bench.portfolio=true` to also include the unified [Backend.KLAUSE_PORTFOLIO]
     *  parallel pool as a backend (#64) — opt-in because it spawns a multi-thread pool per
     *  instance, which would dominate wall-clock on every metric if always on. With it on,
     *  `bench run time|completeness|parity|verify` benchmark the portfolio as a solver alongside
     *  the single engines. */
    fun defaultPortfolio(problem: Problem): List<InProcessSolver> = buildList {
        add(LocalSearchBench(problem))
        add(BacktrackBench(problem))
        add(LogicNGBench(problem))
        if (BruteForceSolver.fits(problem)) add(BruteForceBench(problem))
        if (System.getProperty("klause.bench.portfolio")?.toBoolean() == true) add(PortfolioBench(problem))
    }
}
