package com.eignex.klause.portfolio

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.ResumableOptimizer
import com.eignex.klause.solver.ResumableSearch
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

private class TrackingResumableSearch(
    private val result: MinimizeResult?,
    private val incumbent: MinimizeResult.WithSample? = null,
    private val onRun: () -> Unit = {},
    private val statsFailure: String? = null,
    private val closeFailure: String? = null,
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

private class TrackingResumableOptimizer(private val handle: ResumableSearch) :
    ResumableOptimizer<BacktrackParams> {
    override val problem = Problem(0, 0, emptyArray(), emptyArray()).bake()

    override fun solve(params: BacktrackParams): SolveResult = SolveResult.Unknown(TerminationReason.BudgetExhausted)

    override fun samples(params: BacktrackParams): Sequence<Sample> = emptySequence()
    override fun enumerate(params: BacktrackParams): Sequence<Sample> = emptySequence()

    override fun minimize(objective: LinearObjective, params: BacktrackParams): MinimizeResult = error("not used")

    override fun resumable(objective: LinearObjective, params: BacktrackParams): ResumableSearch = handle
}

private fun trackingWorker(label: String, armId: Int, handle: ResumableSearch): PortfolioWorker = PortfolioWorker.of(
    label,
    armId,
    TrackingResumableOptimizer(handle).session(),
    BacktrackParams(),
    objective = LinearObjective(),
)

class SequentialPortfolioTest {

    @Test
    fun `terminal arm closes a paused sibling handle`() {
        val paused = TrackingResumableSearch(null)
        val sample = Sample(BooleanArray(0), LongArray(0))
        val terminal = TrackingResumableSearch(
            result = MinimizeResult.Optimal(sample, 0.0),
            incumbent = MinimizeResult.BestFound(sample, 0.0, TerminationReason.BudgetExhausted),
        )
        val portfolio = SequentialPortfolio.exp3(
            listOf(trackingWorker("paused", 0, paused), trackingWorker("terminal", 1, terminal)),
        )

        assertIs<MinimizeResult.Optimal>(portfolio.minimize())

        assertEquals(1, paused.closes)
        assertEquals(1, terminal.closes)
    }

    @Test
    fun `global cancellation closes a paused handle`() {
        var cancelled = false
        val paused = TrackingResumableSearch(null, onRun = { cancelled = true })
        val portfolio = SequentialPortfolio.exp3(listOf(trackingWorker("paused", 0, paused)))

        assertIs<MinimizeResult.Unknown>(portfolio.minimize(Cancellation { cancelled }))

        assertEquals(1, paused.closes)
    }

    @Test
    fun `owner failure remains primary when handle close fails`() {
        val handle = TrackingResumableSearch(
            result = null,
            statsFailure = "stats failure",
            closeFailure = "close failure",
        )
        val portfolio = SequentialPortfolio.exp3(listOf(trackingWorker("failing", 0, handle)))

        val failure = assertFailsWith<IllegalStateException> { portfolio.minimize() }

        assertEquals("stats failure", failure.message)
        assertEquals(1, handle.closes)
    }

    @Test
    fun `handle close failure does not prevent closing remaining handles`() {
        val failing = TrackingResumableSearch(null, closeFailure = "close failure")
        val sample = Sample(BooleanArray(0), LongArray(0))
        val terminal = TrackingResumableSearch(
            result = MinimizeResult.Optimal(sample, 0.0),
            incumbent = MinimizeResult.BestFound(sample, 0.0, TerminationReason.BudgetExhausted),
        )
        val portfolio = SequentialPortfolio.exp3(
            listOf(trackingWorker("failing", 0, failing), trackingWorker("terminal", 1, terminal)),
        )

        val failure = assertFailsWith<IllegalStateException> { portfolio.minimize() }

        assertEquals("close failure", failure.message)
        assertEquals(1, failing.closes)
        assertEquals(1, terminal.closes)
    }

    private fun exactlyOneOver(n: Int): Problem = Problem(
        numBoolVars = n,
        numIntVars = 0,
        intDomains = emptyArray(),
        factors = arrayOf<Factor>(
            Cardinality.exactlyOne(IntArray(n) { Lit.make(it, true) }),
        ),
    )

    private fun btArms(problem: Problem, n: Int, objective: LinearObjective? = null): List<PortfolioWorker> =
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

    private fun lsArm(problem: Problem, objective: LinearObjective): PortfolioWorker = PortfolioWorker.of(
        "ls",
        0,
        LocalSearchSolver(problem.bake()).session(),
        LocalSearchParams(randomSeed = 0L),
        objective = objective,
        withInstructionBudget = { p, limit -> p.copy(maxInstructions = limit) },
    )

    @Test
    fun `sequential solve on a satisfiable problem returns sat`() {
        val r = SequentialPortfolio.exp3(btArms(exactlyOneOver(4), 3)).use { it.solve() }
        val sat = assertIs<SolveResult.Sat>(r)
        assertEquals(1, sat.assignment.bools.count { it }, "exactly-one violated")
    }

    @Test
    fun `sequential solve on an unsat problem returns unsat`() {
        val problem = Problem(
            numBoolVars = 1,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, true))),
                Clause(intArrayOf(Lit.make(0, false))),
            ),
        )
        assertIs<SolveResult.Unsat>(SequentialPortfolio.exp3(btArms(problem, 2)).use { it.solve() })
    }

    @Test
    fun `sequential minimize exhausts a small problem and proves the optimum`() {
        // minimize x + 2y subject to x + y >= 3, x,y in [0..5]. Optimum = 3 (x=3, y=0).
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 5), IntDomain(0, 5)),
            factors = arrayOf<Factor>(
                Linear(coeffs = intArrayOf(1, 1), vars = intArrayOf(0, 1), op = LinearOp.GE, bound = 3),
            ),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(1L, 2L))
        val r = SequentialPortfolio.exp3(btArms(problem, 3, obj)).use { it.minimize() }
        assertEquals(3.0, assertIs<MinimizeResult.Optimal>(r).objectiveValue)
    }

    @Test
    fun `aggressive re-seeding does not disrupt proving the optimum`() {
        // The re-seed guard (#3): even with reseedStaleThreshold = 1 (drop a resumable arm's handle the
        // first non-improving segment), a fast optimality proof must still come back as Optimal at the
        // true value — the terminal-verdict and incumbent-exists guards keep re-seed from corrupting it.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 5), IntDomain(0, 5)),
            factors = arrayOf<Factor>(
                Linear(coeffs = intArrayOf(1, 1), vars = intArrayOf(0, 1), op = LinearOp.GE, bound = 3),
            ),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(1L, 2L))
        val r = SequentialPortfolio.exp3(btArms(problem, 3, obj), reseedStaleThreshold = 1).use { it.minimize() }
        assertEquals(3.0, assertIs<MinimizeResult.Optimal>(r).objectiveValue)
    }

    @Test
    fun `ucb1 policy factory also proves the optimum`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 5), IntDomain(0, 5)),
            factors = arrayOf<Factor>(
                Linear(coeffs = intArrayOf(1, 1), vars = intArrayOf(0, 1), op = LinearOp.GE, bound = 3),
            ),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(1L, 2L))
        val r = SequentialPortfolio.ucb1(btArms(problem, 3, obj)).use { it.minimize() }
        assertEquals(3.0, assertIs<MinimizeResult.Optimal>(r).objectiveValue)
    }

    // The LS arm runs first during warmup; the complete arm then exhausts and ends the portfolio,
    // exposing the LS segment counters.
    private fun mixedWorkers(problem: Problem, objective: LinearObjective): List<PortfolioWorker> = listOf(
        lsArm(problem, objective),
        PortfolioWorker.of(
            "bt",
            1,
            BacktrackSolver(problem.bake()).session(),
            BacktrackParams(randomSeed = 1L),
            objective = objective,
            withBound = { p, supplier -> p.copy(objectiveBoundSupplier = supplier) },
        ),
    )

    @Test
    fun `mixed sequential run bounds LS work to its counted segment allowance`() {
        val problem = Problem(0, 0, emptyArray(), emptyArray())
        val objective = LinearObjective()
        val r = SequentialPortfolio.exp3(
            mixedWorkers(problem, objective),
            baseSliceFlips = 7L,
        ).use { it.minimize() }

        val best = assertIs<MinimizeResult.Optimal>(r)
        assertEquals(7.0, best.stats.ls.moves.sum, "LS work must stop at its counted segment allowance")
    }

    @Test
    fun `mixed sequential runs reproduce their counted work`() {
        val problem = Problem(0, 0, emptyArray(), emptyArray())
        val objective = LinearObjective()
        fun run() = SequentialPortfolio.exp3(
            mixedWorkers(problem, objective),
            baseSliceFlips = 7L,
        ).use { it.minimize() }

        val first = run()
        val second = run()

        assertIs<MinimizeResult.Optimal>(first)
        assertIs<MinimizeResult.Optimal>(second)
        assertEquals(first.stats.ls.moves, second.stats.ls.moves, "mixed runs must reproduce LS work")
        assertEquals(first.stats.search.nodes, second.stats.search.nodes, "mixed runs must reproduce CP work")
    }
}
