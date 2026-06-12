package com.eignex.klause.bench.solver

import com.eignex.klause.choco.ChocoParams
import com.eignex.klause.choco.ChocoSolver
import com.eignex.klause.logicng.LogicNGParams
import com.eignex.klause.logicng.LogicNGSolver
import com.eignex.klause.ortools.OrToolsParams
import com.eignex.klause.ortools.OrToolsSolver
import com.eignex.klause.portfolio.EngineMix
import com.eignex.klause.portfolio.Kind
import com.eignex.klause.portfolio.Portfolio
import com.eignex.klause.portfolio.PortfolioBuilder
import com.eignex.klause.portfolio.PortfolioScenario
import com.eignex.klause.solver.Cancellation
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
import com.eignex.klause.solver.presolve.PresolveConfig
import com.eignex.klause.solver.presolve.PresolveContext
import com.eignex.klause.solver.presolve.Presolver
import com.eignex.klause.yuck.YuckParams
import com.eignex.klause.yuck.YuckSolver
import java.util.concurrent.atomic.AtomicBoolean

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
     *  exposed here so the time / completeness / solve / verify metrics can benchmark the portfolio
     *  itself. The pool width comes from `-Dklause.portfolio.processors` (default: host core count);
     *  the LS:backtrack split is the scenario's kind-derived decision, not a per-engine knob. */
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

/**
 * Wraps an [inner] solver built on a presolved problem so it presents the ORIGINAL [problem] and
 * maps every emitted [Sample] back through [reconstruct] — the bench metrics check samples against
 * the original problem, so they must be in original-variable space.
 */
private class ReconstructingInProcess(
    override val problem: Problem,
    private val inner: InProcessSolver,
    private val reconstruct: (Sample) -> Sample,
) : InProcessSolver {
    override val name get() = inner.name
    override fun solve(): SolveResult = when (val r = inner.solve()) {
        is SolveResult.Sat -> r.copy(assignment = reconstruct(r.assignment))
        else -> r
    }

    override fun samples(n: Int) = inner.samples(n).map(reconstruct)
    override fun enumerated(n: Int) = inner.enumerated(n).map(reconstruct)
    override fun enumerateSequence() = inner.enumerateSequence().map(reconstruct)
    override fun samplesSequence() = inner.samplesSequence().map(reconstruct)
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
 * backtrack engine, now selectable for the time / completeness / solve / verify metrics so the
 * portfolio can be benchmarked *as a solver* across the catalog.
 *
 * The pool width comes from `-Dklause.portfolio.processors` (default: host core count) — the same
 * one-knob model the solve metric uses; the LS:backtrack split is the scenario's (kind-derived) decision, not a
 * per-engine count. The portfolio + sessions are built once and reused across calls (matching the
 * single-engine benches).
 *
 * **Scope:** [solve] and the bounded [samples] / [enumerated] are the intended use. The lazy
 * sequence views are materialised to a bounded cap (`SEQUENCE_CAP`) because LS workers stream
 * unbounded — the portfolio is a solve / time backend, not an enumeration or
 * uniformness oracle.
 */
private class PortfolioBench(
    override val problem: Problem,
    processors: Int = System.getProperty("klause.portfolio.processors")?.toIntOrNull()
        ?: Runtime.getRuntime().availableProcessors(),
) : InProcessSolver {
    // The portfolio-as-solver backend wires no objective, so it benchmarks the CSP-kind mixed
    // composition (satisfaction + sampling) over `processors` workers; the LS:backtrack split is the
    // scenario's (kind-derived) decision, not a per-knob count.
    private val portfolio: Portfolio = Portfolio(
        PortfolioBuilder.build(
            problem,
            PortfolioScenario.parallel(threads = processors, kind = Kind.CSP, engine = EngineMix.MIXED),
        ),
    )
    override val name = "portfolio"

    override fun solve(): SolveResult = portfolio.solve()

    override fun samples(n: Int): List<Sample> = collectSamples(n)
    override fun enumerated(n: Int): List<Sample> = collectSamples(n)
    override fun enumerateSequence(): Sequence<Sample> = collectSamples(SEQUENCE_CAP).asSequence()
    override fun samplesSequence(): Sequence<Sample> = collectSamples(SEQUENCE_CAP).asSequence()

    /** Fan in [n] samples across the worker pool, then flip the shared cancellation so every worker
     *  stops (the blocking sample stream has no upstream to cancel on its own once we stop pulling). */
    private fun collectSamples(n: Int): List<Sample> {
        if (n <= 0) return emptyList()
        val stop = AtomicBoolean(false)
        val out = ArrayList<Sample>(n)
        for (s in portfolio.samples(Cancellation { stop.get() })) {
            out.add(s)
            if (out.size >= n) {
                stop.set(true)
                break
            }
        }
        return out
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

    /**
     * Presolve config for the bench. Defaults to NONE so enumeration / solve / completeness
     * metrics compare the same solution sets as the reference solvers; set `-Dklause.presolve`
     * (none | default | all | comma-list) to measure the shipped presolve on e.g. the time metric.
     */
    private fun benchPresolve(): PresolveConfig =
        System.getProperty("klause.presolve")?.let { PresolveConfig.parse(it) } ?: PresolveConfig.NONE

    /** Build a bound solver for [problem], applying the bench presolve config (default NONE). */
    fun build(config: SolverConfig, problem: Problem): InProcessSolver {
        val pre = Presolver.run(problem, benchPresolve(), PresolveContext.EMPTY)
        val inner = when (config.backend) {
            Backend.KLAUSE_LS -> LocalSearchBench(pre.problem)
            Backend.KLAUSE_COMPLETE -> BacktrackBench(pre.problem)
            Backend.LOGICNG -> LogicNGBench(pre.problem)
            Backend.BRUTE_FORCE -> BruteForceBench(pre.problem)
            Backend.CHOCO -> ChocoBench(pre.problem)
            Backend.ORTOOLS -> OrToolsBench(pre.problem)
            Backend.YUCK -> YuckBench(pre.problem)
            Backend.KLAUSE_PORTFOLIO -> PortfolioBench(pre.problem)
        }
        return if (pre.problem === problem) inner else ReconstructingInProcess(problem, inner, pre.reconstruct)
    }

    /** The default in-process portfolio: LS + backtrack + LogicNG, plus brute force only when
     *  the space fits.
     *
     *  Set `-Dklause.bench.portfolio=true` to also include the unified [Backend.KLAUSE_PORTFOLIO]
     *  parallel pool as a backend (#64) — opt-in because it spawns a multi-thread pool per
     *  instance, which would dominate wall-clock on every metric if always on. With it on,
     *  `bench time|completeness|solve|verify` benchmark the portfolio as a solver alongside
     *  the single engines. */
    fun defaultPortfolio(problem: Problem): List<InProcessSolver> {
        val pre = Presolver.run(problem, benchPresolve(), PresolveContext.EMPTY)
        fun wrap(inner: InProcessSolver): InProcessSolver =
            if (pre.problem === problem) inner else ReconstructingInProcess(problem, inner, pre.reconstruct)
        return buildList {
            add(wrap(LocalSearchBench(pre.problem)))
            add(wrap(BacktrackBench(pre.problem)))
            add(wrap(LogicNGBench(pre.problem)))
            if (BruteForceSolver.fits(pre.problem)) add(wrap(BruteForceBench(pre.problem)))
            if (System.getProperty("klause.bench.portfolio")?.toBoolean() == true) {
                add(wrap(PortfolioBench(pre.problem)))
            }
        }
    }
}
