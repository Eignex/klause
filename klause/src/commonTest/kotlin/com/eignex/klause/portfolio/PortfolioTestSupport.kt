package com.eignex.klause.portfolio

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.ResumableOptimizer
import com.eignex.klause.solver.ResumableSearch
import com.eignex.klause.solver.ResumableSolve
import com.eignex.klause.solver.ResumableSolver
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.Solver
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation
import com.eignex.kumulant.bandit.UnivariateBandit
import kotlin.random.Random

internal class TrackingResumableSearch(
    private val result: MinimizeResult?,
    private val incumbent: MinimizeResult.WithSample? = null,
    private val onRun: () -> Unit = {},
    private val statsFailure: String? = null,
    private val closeFailure: String? = null,
    override val preparationPending: Boolean = false,
) : ResumableSearch {
    var closes = 0
    private var done = false

    override fun runSlice(
        global: Cancellation,
        sliceMillis: Long,
        sliceNodes: Long,
        onIncumbent: (MinimizeResult.WithSample) -> Unit,
    ): MinimizeResult? {
        onRun()
        incumbent?.let(onIncumbent)
        done = result != null
        return result
    }

    override val isDone: Boolean get() = done
    override val stats: SolveStats
        get() {
            statsFailure?.let(::error)
            return SolveStats.EMPTY
        }

    override fun close() {
        closes++
        closeFailure?.let(::error)
    }
}

internal class TrackingResumableOptimizer(private val handle: ResumableSearch) :
    ResumableOptimizer<BacktrackParams> {
    override val problem = Problem(0, 0, emptyArray(), emptyArray()).bake()

    override fun solve(params: BacktrackParams): SolveResult = SolveResult.Unknown(TerminationReason.BudgetExhausted)

    override fun samples(params: BacktrackParams): Sequence<Sample> = emptySequence()
    override fun enumerate(params: BacktrackParams): Sequence<Sample> = emptySequence()

    override fun minimize(objective: LinearObjective, params: BacktrackParams): MinimizeResult = error("not used")

    override fun resumable(objective: LinearObjective, params: BacktrackParams): ResumableSearch = handle
}

internal class CountingResumableSolve(
    private val slicesToVerdict: Int,
    private val model: Sample = Sample(BooleanArray(0), LongArray(0)),
) : ResumableSolve {
    var slices = 0

    override fun runSlice(global: Cancellation, sliceMillis: Long, sliceNodes: Long): SolveResult? =
        if (++slices < slicesToVerdict) null else SolveResult.Sat(model)

    override val isDone: Boolean get() = slices >= slicesToVerdict
    override val stats: SolveStats get() = SolveStats.EMPTY
}

internal class CountingResumableSolver(
    private val slicesToVerdict: Int,
    private val model: Sample = Sample(BooleanArray(0), LongArray(0)),
) : ResumableSolver<BacktrackParams> {
    override val problem = Problem(0, 0, emptyArray(), emptyArray()).bake()
    val opened = ArrayList<CountingResumableSolve>()

    override fun solve(params: BacktrackParams): SolveResult = error("a resumable arm is never solved one-shot")

    override fun samples(params: BacktrackParams): Sequence<Sample> = emptySequence()
    override fun enumerate(params: BacktrackParams): Sequence<Sample> = emptySequence()

    override fun resumableSolve(params: BacktrackParams): ResumableSolve =
        CountingResumableSolve(slicesToVerdict, model).also(opened::add)
}

internal class ScriptedSearch(
    private val offers: (slice: Int) -> Boolean,
    start: Double = 1_000.0,
    private val perSlice: Int = 1,
    private val onSlice: () -> Unit,
) : ResumableSearch {
    private var spent = 0L
    private var slices = 0
    private var objective = start

    override fun runSlice(
        global: Cancellation,
        sliceMillis: Long,
        sliceNodes: Long,
        onIncumbent: (MinimizeResult.WithSample) -> Unit,
    ): MinimizeResult? {
        onSlice()
        spent += if (sliceNodes < 0L) 1L else sliceNodes
        if (offers(slices++)) {
            repeat(perSlice) {
                objective -= 1.0
                val empty = Sample(BooleanArray(0), LongArray(0))
                onIncumbent(MinimizeResult.BestFound(empty, objective, TerminationReason.BudgetExhausted))
            }
        }
        return null
    }

    override val isDone: Boolean get() = false
    override val stats: SolveStats get() = SolveStats.EMPTY
    override val work: Long get() = spent
}

internal class ThrowingSolver : Solver<BacktrackParams> {
    override val problem = Problem(0, 0, emptyArray(), emptyArray()).bake()
    var solves = 0

    override fun solve(params: BacktrackParams): SolveResult {
        solves++
        error("solve failure")
    }

    override fun samples(params: BacktrackParams): Sequence<Sample> = emptySequence()
    override fun enumerate(params: BacktrackParams): Sequence<Sample> = emptySequence()
}

internal fun fixedBandit(arms: Int, arm: Int): UnivariateBandit = object : UnivariateBandit {
    override val nbrArms = arms
    override val random = Random(0)
    override fun choose() = arm
    override fun update(armIndex: Int, value: Double, weight: Double) = Unit
    override fun reset() = Unit
}

internal fun trackingWorker(label: String, armId: Int, handle: ResumableSearch): PortfolioWorker = PortfolioWorker.of(
    label,
    armId,
    TrackingResumableOptimizer(handle).session(),
    BacktrackParams(),
    objective = LinearObjective(),
)

internal fun exactlyOneOver(n: Int): Problem = Problem(
    numBoolVars = n,
    numIntVars = 0,
    intDomains = emptyArray(),
    factors = arrayOf<Factor>(
        Cardinality.exactlyOne(IntArray(n) { Lit.make(it, true) }),
    ),
)

internal fun btArms(problem: Problem, n: Int, objective: LinearObjective? = null): List<PortfolioWorker> =
    List(n) { i ->
        PortfolioWorker.of(
            "bt#$i",
            i,
            BacktrackSolver(problem.bake()).session(),
            BacktrackParams(randomSeed = i.toLong()),
            objective = objective,
            withBound = { p, supplier -> p.copy(objectiveBoundSupplier = supplier) },
        )
    }

internal fun endlessCountedPortfolio(
    reached: MutableList<Int>,
    sliceMillis: Long,
    probeMillis: Long = 20L,
    minShares: DoubleArray = DoubleArray(0),
): Portfolio {
    val problem = Problem(
        numBoolVars = 3,
        numIntVars = 0,
        intDomains = emptyArray(),
        factors = arrayOf<Factor>(Cardinality.atLeastOne(IntArray(3) { Lit.make(it, true) })),
    )
    val objective = LinearObjective(boolWeights = longArrayOf(1L, 1L, 1L))
    val arms = List(2) { i ->
        val session = LocalSearchSolver(problem.bake()).session()
        PortfolioWorker.ofMinimize(
            "ls$i",
            i,
            countsInstructions = true,
        ) { _, _, token, limit ->
            reached += i
            session.improvements(
                objective,
                LocalSearchParams(randomSeed = 0L, cancellation = token, maxInstructions = limit),
            )
        }
    }
    val endless = 1_000_000_000_000L
    return Portfolio(
        arms,
        DiscountedThompson(arms.size, Random(0), Portfolio.DEFAULT_HALF_LIFE),
        baseSliceMillis = sliceMillis,
        maxSliceMillis = sliceMillis,
        baseSliceWork = endless,
        maxSliceWork = endless,
        probeSliceMillis = probeMillis,
        minShares = minShares,
    )
}
