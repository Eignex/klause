package com.eignex.klause.portfolio

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.LS_INSTRUCTIONS_PER_WORK
import com.eignex.klause.backtrack.UnresolvedRealLeafFixture
import com.eignex.klause.backtrack.selector.IndomainMax
import com.eignex.klause.backtrack.selector.VariableSelector
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
import com.eignex.klause.lp.engine.LpCertificationPolicy
import com.eignex.klause.lp.engine.LpCertifier
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.bake
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.ResumableOptimizer
import com.eignex.klause.solver.ResumableSearch
import com.eignex.klause.solver.ResumableSolve
import com.eignex.klause.solver.ResumableSolver
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.Solver
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.LocalSearchStats
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.solver.search.VarRef
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.compareTo
import com.eignex.klause.util.minus
import com.eignex.klause.util.parseBigInt
import com.eignex.klause.util.plus
import com.eignex.klause.util.toDouble
import com.eignex.kumulant.bandit.UnivariateBandit
import com.eignex.kumulant.bandit.univariate.MultiArmedBandit
import com.eignex.kumulant.bandit.univariate.UCB1
import com.eignex.kumulant.stat.summary.SumResult
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

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

private class CountingResumableSolve(
    private val slicesToVerdict: Int,
    private val model: Sample = Sample(BooleanArray(0), LongArray(0)),
) : ResumableSolve {
    var slices = 0

    override fun runSlice(global: Cancellation, sliceMillis: Long, sliceNodes: Long): SolveResult? =
        if (++slices < slicesToVerdict) null else SolveResult.Sat(model)

    override val isDone: Boolean get() = slices >= slicesToVerdict
    override val stats: SolveStats get() = SolveStats.EMPTY
}

private class CountingResumableSolver(
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

/**
 * A resumable arm that spends each slice whole and offers [perSlice] incumbents on the slices [offers] picks, lowering
 * its objective by one each time.
 */
private class ScriptedSearch(
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
        spent += sliceNodes.coerceAtLeast(0L)
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

/** A one-shot arm whose every solve throws. */
private class ThrowingSolver : Solver<BacktrackParams> {
    override val problem = Problem(0, 0, emptyArray(), emptyArray()).bake()
    var solves = 0

    override fun solve(params: BacktrackParams): SolveResult {
        solves++
        error("solve failure")
    }

    override fun samples(params: BacktrackParams): Sequence<Sample> = emptySequence()
    override fun enumerate(params: BacktrackParams): Sequence<Sample> = emptySequence()
}

private fun trackingWorker(label: String, armId: Int, handle: ResumableSearch): PortfolioWorker = PortfolioWorker.of(
    label,
    armId,
    TrackingResumableOptimizer(handle).session(),
    BacktrackParams(),
    objective = LinearObjective(),
)

class PortfolioTest {

    @Test
    fun `exact improvements install when their floating projections tie`() {
        val bases = listOf(
            "9007199254740992" to "1",
            "-9007199254740996" to "1",
            "9223372036854775808" to "1",
            "9007199254740992" to "9007199254740991",
        )
        for ((numerator, denominator) in bases) {
            val text = "$numerator/$denominator"
            val best = BigFraction.of(parseBigInt(numerator), parseBigInt(denominator))
            val step = BigFraction.of(BIG_ONE, parseBigInt(denominator))
            val values = listOf(best + step, best, best + step)
            assertEquals(values[0].toDouble(), values[1].toDouble())
            val incumbents = PortfolioIncumbents(
                valueOf = { candidate -> values[candidate.sample.ints.single().toInt()] },
                improves = { candidate, standing -> candidate < standing },
                approximateValue = { it.toDouble() },
                gain = { standing, candidate -> (standing - candidate).toDouble() },
            )
            val worker = PortfolioWorker.ofMinimize("exact", 0) { _, _, _, _ ->
                sequence {
                    for (i in values.indices) {
                        yield(
                            MinimizeResult.BestFound(
                                Sample(BooleanArray(0), longArrayOf(i.toLong())),
                                values[i].toDouble(),
                                TerminationReason.BudgetExhausted,
                            ),
                        )
                    }
                    yield(MinimizeResult.Unknown(TerminationReason.SearchExhausted))
                }
            }
            val reported = ArrayList<Long>()

            val result = Portfolio.thompson(listOf(worker)).use { portfolio ->
                portfolio.minimize(
                    Cancellation.Never,
                    { reported += checkNotNull(it.result.assignment).ints.single() },
                    incumbents,
                )
            }

            assertEquals(1L, assertIs<MinimizeResult.Optimal>(result).sample.ints.single(), text)
            assertEquals(best, incumbents.exchange.current()?.objective, text)
            assertEquals(listOf(0L, 1L), reported, text)
        }
    }

    @Test
    fun `an exact improvement earns credit with a nonfinite projection`() {
        val best = parseBigInt("1" + "0".repeat(400))
        val values = listOf(best + BIG_ONE, best)
        val incumbents = PortfolioIncumbents(
            valueOf = { candidate -> values[candidate.sample.ints.single().toInt()] },
            improves = { candidate, standing -> candidate < standing },
            approximateValue = { Double.POSITIVE_INFINITY },
            gain = { standing, candidate -> (standing - candidate).toDouble() },
        )
        var next = 0
        val worker = PortfolioWorker.ofMinimize("exact", 0) { _, _, _, _ ->
            sequence {
                val i = next++
                yield(
                    MinimizeResult.BestFound(
                        Sample(BooleanArray(0), longArrayOf(i.toLong())),
                        Double.POSITIVE_INFINITY,
                        TerminationReason.BudgetExhausted,
                    ),
                )
                val reason = if (next == values.size) {
                    TerminationReason.SearchExhausted
                } else {
                    TerminationReason.BudgetExhausted
                }
                yield(MinimizeResult.Unknown(reason))
            }
        }

        val result = Portfolio.thompson(listOf(worker)).use {
            it.minimize(Cancellation.Never, onImprovement = null, incumbents)
        }

        assertEquals(1L, assertIs<MinimizeResult.Optimal>(result).sample.ints.single())
        assertEquals(best, incumbents.exchange.current()?.objective)
        assertEquals(1.0, result.stats.portfolio.arms.single().credit["Improvement"])
    }

    @Test
    fun `real unresolved arms terminate without claiming complete coverage`() {
        for (withIncumbent in listOf(false, true)) {
            val fixtures = List(2) { UnresolvedRealLeafFixture(withIncumbent) }
            val workers = fixtures.mapIndexed { index, fixture ->
                PortfolioWorker.of(
                    "real#$index",
                    index,
                    fixture.solver.session(),
                    fixture.params,
                    objective = fixture.objective,
                    withBound = { p, bound -> p.copy(objectiveBoundSupplier = bound) },
                )
            }
            var polls = 0
            Portfolio.thompson(workers).use { portfolio ->
                val offered = ArrayList<Double>()
                val result = portfolio.minimize(
                    Cancellation { ++polls > 100_000 },
                ) { offered += requireNotNull(it.result.objectiveValue) }
                assertTrue(polls < 100_000, "completed arms must terminate without cancellation")
                if (withIncumbent) {
                    fixtures.first().assertIncumbent(assertIs<MinimizeResult.BestFound>(result).sample)
                    assertEquals(listOf(1.0), offered)
                } else {
                    assertIs<MinimizeResult.Unknown>(result)
                    assertTrue(offered.isEmpty())
                }
                fixtures.forEach { it.assertVisitedLeaves() }
            }
        }
    }

    @Test
    fun `an exact real arm can prove the optimum after an unresolved arm retires`() {
        val fixtures = List(2) { UnresolvedRealLeafFixture(false) }
        fixtures.last().acceptProof = { _, _ -> true }
        val workers = fixtures.mapIndexed { index, fixture ->
            PortfolioWorker.of(
                "real#$index",
                index,
                fixture.solver.session(),
                fixture.params,
                objective = fixture.objective,
                withBound = { p, bound -> p.copy(objectiveBoundSupplier = bound) },
            )
        }
        Portfolio.thompson(workers).use { portfolio ->
            val result = assertIs<MinimizeResult.Optimal>(portfolio.minimize())
            assertEquals(0.5, result.sample.reals.single())
            fixtures.forEach { it.assertVisitedLeaves() }
        }
    }

    @Test
    fun `a verified nonoptimal real point reaches the sequential incumbent`() {
        val fixture = UnresolvedRealLeafFixture(false)
        fixture.acceptProof = { _, certifier -> certifier == LpCertifier.EXACT_POINT }
        val worker = PortfolioWorker.of(
            "point",
            0,
            fixture.solver.session(),
            fixture.params,
            objective = fixture.objective,
            withBound = { p, bound -> p.copy(objectiveBoundSupplier = bound) },
        )
        Portfolio.thompson(listOf(worker)).use { portfolio ->
            var offers = 0
            val result = assertIs<MinimizeResult.BestFound>(portfolio.minimize { offers++ })
            assertEquals(0.5, result.sample.reals.single())
            assertEquals(1, offers)
            fixture.assertVisitedLeaves()
        }
    }

    @Test
    fun `retired arm is not reopened when bandit keeps selecting it`() {
        var dirtyRuns = 0
        var activeRuns = 0
        val dirty = TrackingResumableSearch(
            MinimizeResult.Unknown(TerminationReason.Unsupported),
            onRun = { dirtyRuns++ },
        )
        val sample = Sample(BooleanArray(0), LongArray(0))
        val active = object : ResumableSearch {
            var closed = 0
            override val stats: SolveStats get() = SolveStats.EMPTY
            override val isDone: Boolean get() = activeRuns == 2
            override fun runSlice(
                global: Cancellation,
                sliceMillis: Long,
                sliceNodes: Long,
                onIncumbent: (MinimizeResult.WithSample) -> Unit,
            ): MinimizeResult? {
                activeRuns++
                return if (activeRuns == 1) null else MinimizeResult.Optimal(sample, 0.0)
            }
            override fun close() {
                closed++
            }
        }
        val bandit = object : UnivariateBandit {
            override val nbrArms = 2
            override val random = Random(0)
            override fun choose() = 0
            override fun update(armIndex: Int, value: Double, weight: Double) = Unit
            override fun reset() = Unit
        }
        val portfolio = Portfolio(
            listOf(trackingWorker("dirty", 0, dirty), trackingWorker("active", 1, active)),
            bandit,
        )
        var polls = 0
        assertIs<MinimizeResult.Optimal>(portfolio.minimize(Cancellation { ++polls > 20 }))
        assertEquals(1, dirtyRuns)
        assertEquals(2, activeRuns)
        assertEquals(1, dirty.closes)
        assertEquals(1, active.closed)
    }

    @Test
    fun `failed resumable arm is closed without rescheduling`() {
        var runs = 0
        val failed = TrackingResumableSearch(
            null,
            onRun = {
                runs++
                error("run failure")
            },
        )
        var polls = 0
        val portfolio = Portfolio.thompson(listOf(trackingWorker("failed", 0, failed)))
        assertIs<MinimizeResult.Unknown>(portfolio.minimize(Cancellation { ++polls > 20 }))
        assertEquals(1, runs)
        assertEquals(1, failed.closes)
    }

    @Test
    fun `a rejected improvement cannot become the sequential optimum`() {
        val valid = Sample(BooleanArray(0), longArrayOf(10))
        val rejected = Sample(BooleanArray(0), longArrayOf(5))
        val later = Sample(BooleanArray(0), longArrayOf(7))
        val first = TrackingResumableSearch(
            MinimizeResult.Unknown(TerminationReason.Unsupported),
            MinimizeResult.BestFound(valid, 10.0, TerminationReason.BudgetExhausted),
        )
        val second = TrackingResumableSearch(
            MinimizeResult.Unknown(TerminationReason.Unsupported),
            MinimizeResult.BestFound(rejected, 5.0, TerminationReason.BudgetExhausted),
        )
        val third = TrackingResumableSearch(MinimizeResult.Optimal(later, 7.0))
        val seen = mutableListOf<Long>()
        val portfolio = Portfolio.thompson(
            listOf(
                trackingWorker("valid", 0, first),
                trackingWorker("rejected", 1, second),
                trackingWorker("later", 2, third),
            ),
        )

        val failure = assertFailsWith<IllegalArgumentException> {
            portfolio.minimize { improvement ->
                val value = assertIs<MinimizeResult.WithSample>(improvement.result).sample.ints.single()
                if (value == 5L) throw IllegalArgumentException("source witness violates row 'R'")
                seen += value
            }
        }

        assertEquals("source witness violates row 'R'", failure.message)
        assertEquals(listOf(10L), seen)
        assertEquals(1, first.closes)
        assertEquals(1, second.closes)
        assertEquals(0, third.closes)
    }

    @Test
    fun `a rejected first improvement cannot produce a sequential verdict`() {
        val sample = Sample(BooleanArray(0), longArrayOf(5))
        val handle = TrackingResumableSearch(
            MinimizeResult.Optimal(sample, 5.0),
            MinimizeResult.BestFound(sample, 5.0, TerminationReason.BudgetExhausted),
        )
        val portfolio = Portfolio.thompson(listOf(trackingWorker("rejected", 0, handle)))

        val failure = assertFailsWith<IllegalArgumentException> {
            portfolio.minimize { throw IllegalArgumentException("source witness violates row 'R'") }
        }

        assertEquals("source witness violates row 'R'", failure.message)
        assertEquals(1, handle.closes)
    }

    @Test
    fun `nonimproving candidates do not reach the output callback`() {
        val valid = Sample(BooleanArray(0), longArrayOf(10))
        val worse = Sample(BooleanArray(0), longArrayOf(11))
        val first = TrackingResumableSearch(
            MinimizeResult.Unknown(TerminationReason.Unsupported),
            MinimizeResult.BestFound(valid, 10.0, TerminationReason.BudgetExhausted),
        )
        val second = TrackingResumableSearch(
            MinimizeResult.Unknown(TerminationReason.Unsupported),
            MinimizeResult.BestFound(worse, 11.0, TerminationReason.BudgetExhausted),
        )
        val portfolio = Portfolio.thompson(
            listOf(trackingWorker("valid", 0, first), trackingWorker("worse", 1, second)),
        )
        val seen = mutableListOf<Long>()

        val result = assertIs<MinimizeResult.BestFound>(
            portfolio.minimize { improvement ->
                seen += assertIs<MinimizeResult.WithSample>(improvement.result).sample.ints.single()
            },
        )

        assertEquals(10L, result.sample.ints.single())
        assertEquals(listOf(10L), seen)
    }

    @Test
    fun `an output callback failure remains the primary failure during cleanup`() {
        val sample = Sample(BooleanArray(0), longArrayOf(1))
        val handle = TrackingResumableSearch(
            MinimizeResult.Optimal(sample, 1.0),
            MinimizeResult.BestFound(sample, 1.0, TerminationReason.BudgetExhausted),
            closeFailure = "close failure",
        )
        val portfolio = Portfolio.thompson(listOf(trackingWorker("failing", 0, handle)))

        val failure = assertFailsWith<IllegalStateException> {
            portfolio.minimize { error("output failure") }
        }

        assertEquals("output failure", failure.message)
        assertEquals(1, handle.closes)
        assertEquals("close failure", failure.suppressedExceptions.single().message)
    }

    @Test
    fun `unresolved leaf is not shared as a conflict with a fresh arm`() {
        val fixture = UnresolvedRealLeafFixture(true)
        val problem = Problem(
            0,
            1,
            arrayOf(IntDomain(0L, 2L)),
            arrayOf(Linear(longArrayOf(1L), intArrayOf(0), doubleArrayOf(2.0), intArrayOf(0), LinearOp.EQ, 3L)),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(1.5),
        ).bake()
        val pool = SharedClausePool()
        var declined = false
        val donor = BacktrackSolver(
            problem,
            LpSolveContext(
                fixture.factory,
                object : LpCertificationPolicy {
                    override fun accepts(certifier: LpCertifier, successful: Boolean): Boolean {
                        declined = true
                        return false
                    }
                },
            ),
        )
        donor.resumable(
            fixture.objective,
            fixture.params.copy(
                valueSelector = IndomainMax,
                lubyRestartBase = 1L,
                clauseExchange = PoolClauseExchange(pool),
                objectiveBoundSupplier = { Double.POSITIVE_INFINITY },
            ),
        ).use { search ->
            val result = assertIs<MinimizeResult.Unknown>(search.runSlice(Cancellation.Never, 1000L, 256L) {})
            assertEquals(TerminationReason.Unsupported, result.reason)
            assertTrue(search.isDone)
        }
        assertTrue(declined)
        assertTrue(fixture.visited > 1, "the donor passes over each undecided leaf")
        assertEquals(fixture.opened, fixture.closed)
        assertTrue(pool.drainSince(0L).clauses.isEmpty())
        val worker = PortfolioWorker.of(
            "fresh",
            0,
            BacktrackSolver(problem).session(),
            fixture.params.copy(clauseExchange = PoolClauseExchange(pool)),
            objective = fixture.objective,
            withBound = { p, bound -> p.copy(objectiveBoundSupplier = bound) },
        )
        Portfolio.thompson(listOf(worker)).use { portfolio ->
            val result = assertIs<MinimizeResult.Optimal>(portfolio.minimize())
            assertEquals(0.5, result.objectiveValue)
            assertEquals(2L, result.sample.ints.single())
            assertEquals(3.0, result.sample.ints.single() + 2.0 * result.sample.reals.single())
        }
    }

    @Test
    fun `terminal arm closes a paused sibling handle`() {
        val paused = TrackingResumableSearch(null)
        val sample = Sample(BooleanArray(0), LongArray(0))
        val terminal = TrackingResumableSearch(
            result = MinimizeResult.Optimal(sample, 0.0),
            incumbent = MinimizeResult.BestFound(sample, 0.0, TerminationReason.BudgetExhausted),
        )
        val portfolio = Portfolio.thompson(
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
        val portfolio = Portfolio.thompson(listOf(trackingWorker("paused", 0, paused)))

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
        val portfolio = Portfolio.thompson(listOf(trackingWorker("failing", 0, handle)))

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
        val portfolio = Portfolio.thompson(
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
        val r = Portfolio.thompson(btArms(exactlyOneOver(4), 3)).use { it.solve() }
        val sat = assertIs<SolveResult.Sat>(r)
        assertEquals(1, sat.assignment.bools.count { it }, "exactly-one violated")
    }

    @Test
    fun `an unsound arm is quarantined while a sound arm answers`() {
        val stopsEarly = object : VariableSelector {
            override fun pick(session: PropagationSession, rng: Random): VarRef? = null

            override fun fresh(): VariableSelector = this
        }
        val problem = exactlyOneOver(2)
        val unsound = PortfolioWorker.of(
            "unsound",
            0,
            BacktrackSolver(problem.bake()).session(),
            BacktrackParams(variableSelector = stopsEarly),
        )
        val workers = listOf(unsound) + btArms(problem, 1)

        val faults = ArrayList<ArmFault>()

        val r = Portfolio.thompson(workers, onFault = { faults += it }).use { it.solve() }

        assertEquals(1, assertIs<SolveResult.Sat>(r).assignment.bools.count { it }, "exactly-one violated")
        assertEquals(listOf("unsound"), faults.map { it.workerLabel })
    }

    @Test
    fun `a satisfaction arm resumes one handle across its segments`() {
        val solver = CountingResumableSolver(slicesToVerdict = 4)
        val worker = PortfolioWorker.of("bt", 0, solver.session(), BacktrackParams())

        val r = Portfolio.thompson(listOf(worker)).use { it.solve() }

        assertIs<SolveResult.Sat>(r)
        assertEquals(1, solver.opened.size, "the arm must resume, not reopen")
        assertEquals(4, solver.opened.single().slices)
    }

    @Test
    fun `a resumed local search arm respects session pins`() {
        val session = LocalSearchSolver(Problem(1, 0, emptyArray(), emptyArray()).bake()).session()
        session.push(Assumptions(bools = mapOf(0 to true)))
        val worker = PortfolioWorker.of(
            "ls",
            0,
            session,
            LocalSearchParams(initialAssignment = Sample(booleanArrayOf(false), longArrayOf()), randomSeed = 3L),
        )

        val result = Portfolio.thompson(listOf(worker)).use { it.solve() }

        assertTrue(assertIs<SolveResult.Sat>(result).assignment.bools[0])
    }

    @Test
    fun `replicas of one arm are reported under numbered labels`() {
        val workers = List(3) { index ->
            PortfolioWorker.of("bt", index, CountingResumableSolver(slicesToVerdict = 20).session(), BacktrackParams())
        }

        val r = Portfolio.thompson(workers).use { it.solve() }

        assertEquals(listOf("bt", "bt#2", "bt#3"), r.stats.portfolio.arms.map { it.label })
    }

    @Test
    fun `a failing one-shot arm is retired after one segment and reported`() {
        val failing = ThrowingSolver()
        val resumable = CountingResumableSolver(slicesToVerdict = 20)
        val workers = listOf(
            PortfolioWorker.of("failing", 0, failing.session(), BacktrackParams()),
            PortfolioWorker.of("bt", 1, resumable.session(), BacktrackParams()),
        )

        val r = Portfolio.thompson(workers).use { it.solve() }

        assertIs<SolveResult.Sat>(r)
        assertEquals(1, failing.solves)
        assertEquals(listOf(1L, 0L), r.stats.portfolio.arms.map { it.failures })
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
        assertIs<SolveResult.Unsat>(Portfolio.thompson(btArms(problem, 2)).use { it.solve() })
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
        val r = Portfolio.thompson(btArms(problem, 3, obj)).use { it.minimize() }
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
        val r = Portfolio.thompson(btArms(problem, 3, obj), reseedStaleThreshold = 1).use { it.minimize() }
        assertEquals(3.0, assertIs<MinimizeResult.Optimal>(r).objectiveValue)
    }

    @Test
    fun `plateau reseeding is counted and the off control retains its handle`() {
        for (threshold in listOf(0, 2, 3, 4)) {
            var slices = 0
            val handle = ScriptedSearch({ it == 0 }) { slices++ }
            val worker = trackingWorker("plateau", 0, handle)

            val result = Portfolio.thompson(listOf(worker), reseedStaleThreshold = threshold).use {
                it.minimize(Cancellation { slices >= 13 })
            }

            assertEquals(threshold, result.stats.portfolio.reseedStaleThreshold)
            assertEquals(if (threshold == 0) 0L else 12L / threshold, result.stats.portfolio.arms.single().reseeds)
        }
    }

    @Test
    fun `useless arms take a small share of the run however many there are`() {
        for (useless in listOf(1, 4, 12)) {
            val slices = IntArray(useless + 1)
            val workers = List(useless + 1) { arm ->
                trackingWorker("arm$arm", arm, ScriptedSearch({ arm == 0 }) { slices[arm]++ })
            }
            var polls = 0

            Portfolio.thompson(workers).use { it.minimize(Cancellation { ++polls > 2_000 }) }

            val share = slices.drop(1).sum().toDouble() / slices.sum()
            assertTrue(share < 0.05, "$useless useless arms took $share of the slices")
        }
    }

    @Test
    fun `plateaued local search cedes the run to backtrack however many local search arms there are`() {
        val slices = IntArray(5)
        val workers = List(5) { arm ->
            trackingWorker("arm$arm", arm, ScriptedSearch({ arm == 0 && it == 0 }) { slices[arm]++ })
                .also { if (arm > 0) it.family = ArmFamily.LocalSearch }
        }
        var polls = 0

        Portfolio.thompson(workers).use { it.minimize(Cancellation { ++polls > 2_000 }) }

        assertTrue(slices[0] > slices.sum() / 2, "backtrack ran ${slices[0]} of ${slices.sum()} slices")
    }

    @Test
    fun `the arm that found the first solution loses its lead to the arm improving it`() {
        val slices = IntArray(2)
        val finder = ScriptedSearch({ it == 0 }, start = 1_000.0) { slices[0]++ }
        val improver = ScriptedSearch({ it > 0 }, start = 500.0) { slices[1]++ }
        val workers = listOf(trackingWorker("finder", 0, finder), trackingWorker("improver", 1, improver))
        var polls = 0

        Portfolio.thompson(workers, seed = 3L).use { it.minimize(Cancellation { ++polls > 100 }) }

        assertTrue(slices[1] > 3 * slices[0], "finder ${slices[0]} slices, improver ${slices[1]}")
    }

    @Test
    fun `added improvement variants wait until the first incumbent`() {
        val slices = IntArray(2)
        val workers = listOf(
            trackingWorker("finder", 0, ScriptedSearch({ false }) { slices[0]++ }),
            trackingWorker("variant", 1, ScriptedSearch({ true }) { slices[1]++ })
                .also { it.improvementOnly = true },
        )

        val result = Portfolio.thompson(workers).use { it.minimize(Cancellation { slices.sum() >= 4 }) }

        assertIs<MinimizeResult.Unknown>(result)
        assertEquals(listOf(4, 0), slices.toList())
    }

    @Test
    fun `the first incumbent admits improvement variants at their base allowance`() {
        val slices = IntArray(2)
        val workers = listOf(
            trackingWorker("finder", 0, ScriptedSearch({ it == 2 }) { slices[0]++ }),
            trackingWorker("variant", 1, ScriptedSearch({ true }, start = 500.0) { slices[1]++ })
                .also { it.improvementOnly = true },
        )

        val result = Portfolio.thompson(workers, baseSliceWork = 7L).use {
            it.minimize(Cancellation { slices[1] >= 1 })
        }

        assertEquals(499.0, assertIs<MinimizeResult.WithSample>(result).objectiveValue)
        assertEquals(7L, result.stats.portfolio.arms[1].work)
        assertEquals(listOf(3, 1), slices.toList())
    }

    @Test
    fun `retiring the first solution pool admits deferred arms`() {
        val unfinished = TrackingResumableSearch(MinimizeResult.Unknown(TerminationReason.BudgetExhausted))
        val witness = Sample(BooleanArray(0), LongArray(0))
        val deferred = TrackingResumableSearch(MinimizeResult.Optimal(witness, 3.0))
        val workers = listOf(
            trackingWorker("unfinished", 0, unfinished),
            trackingWorker("deferred", 1, deferred).also { it.improvementOnly = true },
        )

        val result = Portfolio.thompson(workers).use { it.minimize() }

        assertEquals(3.0, assertIs<MinimizeResult.Optimal>(result).objectiveValue)
    }

    @Test
    fun `neutral family shares do not grow with the local search arm count`() {
        for (locals in listOf(1, 4, 8)) {
            val slices = IntArray(locals + 1)
            val workers = List(locals + 1) { arm ->
                PortfolioWorker.ofSolve("arm$arm", arm) { _, _ ->
                    slices[arm]++
                    SolveResult.Unknown(TerminationReason.BudgetExhausted)
                }.also { if (arm > 0) it.family = ArmFamily.LocalSearch }
            }

            Portfolio.thompson(workers, seed = 3L).use { it.solve(Cancellation { slices.sum() >= 1_000 }) }

            val localShare = slices.drop(1).sum().toDouble() / slices.sum()
            assertTrue(localShare in 0.44..0.56, "$locals local search arms took $localShare of the segments")
        }
    }

    @Test
    fun `deferred variants preserve the first solution family schedule`() {
        val schedules = listOf(0, 4, 8).map { variants ->
            val chosen = ArrayList<Int>()
            val workers = List(variants + 2) { arm ->
                PortfolioWorker.ofSolve("arm$arm", arm) { _, _ ->
                    chosen += arm
                    SolveResult.Unknown(TerminationReason.BudgetExhausted)
                }.also {
                    if (arm > 0) it.family = ArmFamily.LocalSearch
                    it.improvementOnly = arm >= 2
                }
            }

            Portfolio.thompson(workers, seed = 3L).use { it.solve(Cancellation { chosen.size >= 100 }) }
            chosen
        }

        assertEquals(schedules.first(), schedules[1])
        assertEquals(schedules.first(), schedules[2])
    }

    @Test
    fun `an arm raising the proven floor takes more of the run than one doing nothing`() {
        val slices = IntArray(3)
        val floor = SharedObjectiveBound()
        floor.publish(0.0)
        val pools = SharedPools(clauses = null, cuts = null, bounds = floor)
        val finder = ScriptedSearch({ it == 0 }) { slices[0]++ }
        val raiser = ScriptedSearch({ false }) {
            slices[1]++
            pools.contributions.note(Contribution.Floor, 1, floor.publish(floor.current() + 1.0))
        }
        val idle = ScriptedSearch({ false }) { slices[2]++ }
        val workers = listOf(finder, raiser, idle).mapIndexed { arm, search ->
            trackingWorker("arm$arm", arm, search).also {
                it.sharedPools = pools
                it.sharing = setOf(Contribution.Floor)
            }
        }
        var polls = 0

        Portfolio.thompson(workers).use { it.minimize(Cancellation { ++polls > 300 }) }

        assertTrue(slices[1] > 3 * slices[2], "raiser ${slices[1]} slices, idle ${slices[2]}")
    }

    @Test
    fun `an arm whose shared clauses others use takes more of the run than one sharing nothing`() {
        val slices = IntArray(3)
        val pools = SharedPools(clauses = null, cuts = null)
        val finder = ScriptedSearch({ true }) {
            slices[0]++
            pools.contributions.note(Contribution.Clause, origin = 1, amount = 5.0)
        }
        val sharer = ScriptedSearch({ false }) { slices[1]++ }
        val idle = ScriptedSearch({ false }) { slices[2]++ }
        val workers = listOf(finder, sharer, idle).mapIndexed { arm, search ->
            trackingWorker("arm$arm", arm, search).also {
                it.sharedPools = pools
                it.sharing = setOf(Contribution.Clause)
            }
        }
        var polls = 0

        Portfolio.thompson(workers).use { it.minimize(Cancellation { ++polls > 300 }) }

        assertTrue(slices[1] > 3 * slices[2], "sharer ${slices[1]} slices, idle ${slices[2]}")
    }

    @Test
    fun `an arm claiming a model the problem refutes is quarantined and the run carries on`() {
        val bogus = CountingResumableSolver(slicesToVerdict = 1, model = Sample(booleanArrayOf(false), LongArray(0)))
        val honest = CountingResumableSolver(slicesToVerdict = 3, model = Sample(booleanArrayOf(true), LongArray(0)))
        val workers = listOf(
            PortfolioWorker.of("bogus", 0, bogus.session(), BacktrackParams()),
            PortfolioWorker.of("honest", 1, honest.session(), BacktrackParams()),
        )
        val check = WitnessCheck { sample, _ -> if (sample.bools[0]) null else "x0 must be true" }
        val faults = ArrayList<ArmFault>()

        val r = Portfolio.thompson(workers, witnessCheck = check, onFault = { faults += it }).use { it.solve() }

        assertTrue(assertIs<SolveResult.Sat>(r).assignment.bools[0])
        assertEquals(listOf("bogus"), faults.map { it.workerLabel })
        assertEquals(listOf(1L, 0L), r.stats.portfolio.arms.map { it.faults })
    }

    @Test
    fun `a slow incumbent check runs far less often than incumbents arrive and the best one still installs`() {
        var slices = 0
        val workers = listOf(
            trackingWorker("fast", 0, ScriptedSearch({ true }, perSlice = 200) { slices++ }),
            trackingWorker("idle", 1, ScriptedSearch({ false }) {}),
        )
        var checks = 0
        val slowCheck = WitnessCheck { _, _ ->
            checks++
            val until = TimeSource.Monotonic.markNow() + 2.milliseconds
            while (!until.hasPassedNow()) Unit
            null
        }

        val r = Portfolio.thompson(workers, witnessCheck = slowCheck).use {
            it.minimize(Cancellation { slices >= 3 })
        }

        assertTrue(checks < 20, "$checks checks for ${200 * slices} incumbents")
        assertEquals(1_000.0 - 200 * slices, assertIs<MinimizeResult.WithSample>(r).objectiveValue)
    }

    @Test
    fun `a refuted incumbent never reaches the improvement callback`() {
        val liar = ScriptedSearch({ true }, start = 10.0) {}
        val honest = ScriptedSearch({ true }, start = 1_000.0) {}
        val workers = listOf(trackingWorker("liar", 0, liar), trackingWorker("honest", 1, honest))
        val check = WitnessCheck { _, objective -> if (objective != null && objective < 100.0) "too good" else null }
        val reported = ArrayList<Double>()
        val faults = ArrayList<ArmFault>()
        var polls = 0

        Portfolio.thompson(workers, witnessCheck = check, onFault = { faults += it }).use {
            it.minimize(Cancellation { ++polls > 50 }) { improvement ->
                reported += checkNotNull(improvement.result.objectiveValue)
            }
        }

        assertTrue(reported.isNotEmpty() && reported.all { it >= 100.0 }, "reported $reported")
        assertEquals(listOf("liar"), faults.map { it.workerLabel })
    }

    @Test
    fun `claiming infeasibility against a verified incumbent quarantines the arm`() {
        val empty = Sample(BooleanArray(0), LongArray(0))
        val found = TrackingResumableSearch(
            null,
            incumbent = MinimizeResult.BestFound(empty, 5.0, TerminationReason.BudgetExhausted),
        )
        val denier = TrackingResumableSearch(MinimizeResult.Infeasible())
        val workers = listOf(trackingWorker("found", 0, found), trackingWorker("denier", 1, denier))
        val faults = ArrayList<ArmFault>()
        var polls = 0

        val r = Portfolio.thompson(workers, witnessCheck = { _, _ -> null }, onFault = { faults += it }).use {
            it.minimize(Cancellation { ++polls > 10 })
        }

        assertIs<MinimizeResult.WithSample>(r)
        assertEquals(listOf("denier"), faults.map { it.workerLabel })
    }

    @Test
    fun `any kumulant policy also proves the optimum`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 5), IntDomain(0, 5)),
            factors = arrayOf<Factor>(
                Linear(coeffs = intArrayOf(1, 1), vars = intArrayOf(0, 1), op = LinearOp.GE, bound = 3),
            ),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(1L, 2L))
        val arms = btArms(problem, 3, obj)
        val r = Portfolio(arms, MultiArmedBandit(arms.size, UCB1(), Random(0))).use { it.minimize() }
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
    fun `a counted local search segment reports the work it spent`() {
        val cases = listOf(
            Triple(100L, 0L, 0L),
            Triple(100L, 75L, (75 / LS_INSTRUCTIONS_PER_WORK).toLong()),
            Triple(7L, 10L, 7L),
        )
        for ((allowance, moves, expected) in cases) {
            val arms = listOf(
                PortfolioWorker.ofSolve("ls", 0, countsInstructions = true) { _, _ ->
                    SolveResult.Unknown(
                        TerminationReason.BudgetExhausted,
                        SolveStats(ls = LocalSearchStats(moves = SumResult(moves.toDouble()))),
                    )
                },
                PortfolioWorker.ofSolve("done", 1) { _, _ -> SolveResult.Unsat() },
            )

            val result = Portfolio.thompson(arms, baseSliceWork = allowance).use { it.solve() }

            assertEquals(expected, result.stats.portfolio.arms[0].work)
        }
    }

    @Test
    fun `resumed local search spends the exact counted instruction allowance`() {
        val problem = Problem(
            3,
            0,
            emptyArray(),
            Array<Factor>(8) { mask -> Clause(IntArray(3) { v -> Lit.make(v, mask and (1 shl v) != 0) }) },
        )
        for ((price, instructions) in listOf(1.5 to 10L, 1.0 to 7L, 2.0 to 14L)) {
            val arms = listOf(
                PortfolioWorker.of(
                    "ls",
                    0,
                    LocalSearchSolver(problem.bake()).session(),
                    LocalSearchParams(maxFlips = 100L, randomSeed = 3L),
                    withInstructionBudget = { p, limit -> p.copy(maxInstructions = limit) },
                ),
                PortfolioWorker.ofSolve("done", 1) { _, _ -> SolveResult.Unsat() },
            )

            val result = Portfolio(
                arms,
                DiscountedThompson(arms.size, Random(0), Portfolio.DEFAULT_HALF_LIFE),
                baseSliceWork = 7L,
                lsInstructionsPerWork = price,
            ).use { it.solve() }

            assertEquals(instructions.toDouble(), result.stats.ls.moves.sum, "price=$price")
            assertEquals(7L, result.stats.portfolio.arms[0].work, "price=$price")
        }
    }

    @Test
    fun `an ALNS segment charges its outer allowance rather than inner moves`() {
        val arms = listOf(
            PortfolioWorker.ofSolve("lns", 0, countsInstructions = true) { _, _ ->
                SolveResult.Unknown(
                    TerminationReason.BudgetExhausted,
                    SolveStats(ls = LocalSearchStats(moves = SumResult(75.0))),
                )
            }.also { it.family = ArmFamily.Lns },
            PortfolioWorker.ofSolve("done", 1) { _, _ -> SolveResult.Unsat() },
        )

        val result = Portfolio.thompson(arms, baseSliceWork = 100L).use { it.solve() }

        assertEquals(100L, result.stats.portfolio.arms[0].work)
    }

    @Test
    fun `mixed sequential run bounds LS work to its counted segment allowance`() {
        val problem = Problem(0, 0, emptyArray(), emptyArray())
        val objective = LinearObjective()
        val r = Portfolio.thompson(
            mixedWorkers(problem, objective),
            baseSliceWork = 7L,
        ).use { it.minimize() }

        val best = assertIs<MinimizeResult.Optimal>(r)
        assertEquals(
            (7 * LS_INSTRUCTIONS_PER_WORK).toLong().toDouble(),
            best.stats.ls.moves.sum,
            "LS work must stop at its counted segment allowance",
        )
    }

    @Test
    fun `every arm probes at the base allowance however many run before it`() {
        val problem = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(Cardinality.atLeastOne(IntArray(3) { Lit.make(it, true) })),
        )
        val objective = LinearObjective(boolWeights = longArrayOf(1L, 1L, 1L))
        val allowances = ArrayList<Long>()
        val arms = List(3) { i ->
            PortfolioWorker.of(
                "ls$i",
                i,
                LocalSearchSolver(problem.bake()).session(),
                LocalSearchParams(randomSeed = 0L),
                objective = objective,
                withInstructionBudget = { p, limit -> p.copy(maxInstructions = limit.also(allowances::add)) },
            )
        }

        Portfolio.thompson(arms, baseSliceWork = 7L).use {
            it.minimize(Cancellation { allowances.size >= arms.size })
        }

        assertEquals(List(3) { (7 * LS_INSTRUCTIONS_PER_WORK).toLong() }, allowances)
    }

    // Two counted local-search arms recording each segment they start into [reached], under a portfolio whose
    // work allowance no segment gets through, so only a time cap can end a turn.
    private fun endlessCountedPortfolio(
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
            PortfolioWorker.of(
                "ls$i",
                i,
                LocalSearchSolver(problem.bake()).session(),
                LocalSearchParams(randomSeed = 0L),
                objective = objective,
                withInstructionBudget = { p, limit -> p.copy(maxInstructions = limit).also { reached += i } },
            )
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

    @Test
    fun `a counted arm whose probe outlasts its time cap hands the core to the next arm`() {
        val reached = ArrayList<Int>()
        val fallback = TimeSource.Monotonic.markNow() + 10.seconds

        endlessCountedPortfolio(reached, sliceMillis = 60_000L).use {
            it.minimize(Cancellation { reached.size >= 2 || fallback.hasPassedNow() })
        }

        assertEquals(listOf(0, 1), reached)
    }

    @Test
    fun `a counted segment past the probe ends at its time slice`() {
        val reached = ArrayList<Int>()
        val fallback = TimeSource.Monotonic.markNow() + 10.seconds

        endlessCountedPortfolio(reached, sliceMillis = 20L).use {
            it.minimize(Cancellation { reached.size >= 4 || fallback.hasPassedNow() })
        }

        assertEquals(4, reached.size)
    }

    @Test
    fun `an arm below its owed share is scheduled before the policy chooses`() {
        val reached = ArrayList<Int>()
        val fallback = TimeSource.Monotonic.markNow() + 10.seconds

        endlessCountedPortfolio(reached, sliceMillis = 20L, minShares = doubleArrayOf(1.0, 0.0)).use {
            it.minimize(Cancellation { reached.size >= 5 || fallback.hasPassedNow() })
        }

        assertEquals(listOf(0, 1, 0, 0, 0), reached)
    }

    @Test
    fun `a short budget still splits into segments however long the slices are`() {
        val reached = ArrayList<Int>()
        val deadline = Cancellation.until(TimeSource.Monotonic.markNow() + 200.milliseconds)

        endlessCountedPortfolio(reached, sliceMillis = 60_000L, probeMillis = 60_000L).use {
            it.minimize(deadline or Cancellation { reached.size >= 4 })
        }

        assertEquals(4, reached.size)
    }

    @Test
    fun `mixed sequential runs reproduce their counted work`() {
        val problem = Problem(0, 0, emptyArray(), emptyArray())
        val objective = LinearObjective()
        fun run() = Portfolio.thompson(
            mixedWorkers(problem, objective),
            baseSliceWork = 7L,
        ).use { it.minimize() }

        val first = run()
        val second = run()

        assertIs<MinimizeResult.Optimal>(first)
        assertIs<MinimizeResult.Optimal>(second)
        assertEquals(first.stats.ls.moves, second.stats.ls.moves, "mixed runs must reproduce LS work")
        assertEquals(first.stats.search.nodes, second.stats.search.nodes, "mixed runs must reproduce CP work")
    }

    @Test
    fun `handle construction work is charged once across resumed segments`() {
        var slices = 0
        val scripted = ScriptedSearch({ false }) { slices++ }
        val handle = object : ResumableSearch by scripted {
            override val initialWork: Long = 10L
            override val work: Long get() = initialWork + scripted.work
        }
        val worker = trackingWorker("initialized", 0, handle)

        val result = Portfolio.thompson(listOf(worker)).use {
            it.minimize(Cancellation { slices >= 2 })
        }

        assertEquals(handle.work, result.stats.portfolio.arms.single().work)
    }
}
