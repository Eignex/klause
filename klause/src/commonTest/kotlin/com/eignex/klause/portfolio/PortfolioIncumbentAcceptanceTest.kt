package com.eignex.klause.portfolio

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.UnresolvedRealLeafFixture
import com.eignex.klause.backtrack.selector.IndomainMax
import com.eignex.klause.backtrack.selector.VariableSelector
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.engine.LpCertificationPolicy
import com.eignex.klause.lp.engine.LpCertifier
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.bake
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.incumbent.Candidate
import com.eignex.klause.solver.incumbent.EngineInvocation
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.pipeline.prepareExecutionPlan
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.solver.search.VarRef
import com.eignex.klause.solver.validation.finiteWitnessVerifier
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.compareTo
import com.eignex.klause.util.minus
import com.eignex.klause.util.parseBigInt
import com.eignex.klause.util.plus
import com.eignex.klause.util.toDouble
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

class PortfolioIncumbentAcceptanceTest {
    @Test
    fun `exact improvements should install when their floating projections tie`() {
        val bases = listOf(
            "9223372036854775808" to "1",
            "9007199254740992" to "9007199254740991",
        )
        for ((numerator, denominator) in bases) {
            val text = "$numerator/$denominator"
            val best = BigFraction.of(parseBigInt(numerator), parseBigInt(denominator))
            val step = BigFraction.of(BIG_ONE, parseBigInt(denominator))
            val values = listOf(best + step, best, best + step)
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
    fun `an exact improvement should earn credit with a nonfinite projection`() {
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
    fun `accepted callbacks should retain exact values and monotonic versions through rounded ties`() {
        val first = BigFraction.of(parseBigInt("9007199254740993"), BIG_ONE)
        val second = BigFraction.of(parseBigInt("9007199254740992"), BIG_ONE)
        val values = listOf(first, second, first)
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
        val accepted = mutableListOf<AcceptedPortfolioIncumbent<BigFraction>>()

        Portfolio.thompson(listOf(worker)).use { portfolio ->
            portfolio.minimize(Cancellation.Never, null, incumbents, onAccepted = {
                assertEquals(it.objective, incumbents.exchange.current()?.objective)
                accepted += it
            })
        }

        assertEquals(listOf(1L, 2L), accepted.map { it.version })
        assertEquals(listOf(first, second), accepted.map { it.objective })
        assertEquals(listOf(0L, 1L), accepted.map { it.assignment.ints.single() })
    }

    @Test
    fun `a conditional completed refutation should not finish a weaker runtime invocation`() {
        val source = Problem(1, 0, emptyArray(), arrayOf(Clause(intArrayOf(Lit.make(0, true)))))
        val plan = source.prepareExecutionPlan()
        val identity = ModelIdentity.of(source)
        val expected = EngineInvocation(plan, identity)
        val conditional = EngineInvocation(
            plan,
            identity,
            executionAssumptions = Assumptions(bools = mapOf(0 to false)),
        )
        val worker = PortfolioWorker.ofSolve("conditional", 0) { _, _ -> SolveResult.Unsat() }
            .bindSource(SourceWorkerBinding(conditional))
        val portfolio = Portfolio.thompson(listOf(worker)).also {
            it.evidenceVerification = PortfolioEvidence(identity, finiteWitnessVerifier(source.bake(), null))
                .bindInvocation(expected)
        }

        val result = portfolio.use { it.solve(Cancellation.Never) }

        assertIs<SolveResult.Unknown>(result)
        assertEquals(1L, result.stats.portfolio.arms.single().faults)
    }

    @Test
    fun `real unresolved arms should terminate without claiming complete coverage`() {
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
                assertTrue(polls < 100_000)
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
    fun `an exact real arm should prove the optimum after an unresolved arm retires`() {
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
    fun `a verified nonoptimal real point should reach the sequential incumbent`() {
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
    fun `a rejected improvement should not become the sequential optimum`() {
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
    fun `a rejected first improvement should not produce a sequential verdict`() {
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
    fun `nonimproving candidates should not reach the output callback`() {
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
    fun `unresolved leaf should not be shared as a conflict with a fresh arm`() {
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
        }
    }

    @Test
    fun `an unsound arm should be quarantined while a sound arm answers`() {
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

        assertEquals(1, assertIs<SolveResult.Sat>(r).assignment.bools.count { it })
        assertEquals(listOf("unsound"), faults.map { it.workerLabel })
        assertNull(r.stats.portfolio.arms.first().failure)
    }

    @Test
    fun `an arm claiming a refuted model should be quarantined while the run carries on`() {
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
    fun `a slow incumbent check should run less often than incumbents arrive and still install the best`() {
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
    fun `a refuted incumbent should never reach the improvement callback`() {
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
    fun `claiming infeasibility against a verified incumbent should quarantine the arm`() {
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
    fun `accepted native terminal should end the shared schedule without claiming scalar optimality`() {
        var siblingEntered = false
        var accepted = 0
        val complete = trackingWorker(
            "complete",
            0,
            TrackingResumableSearch(MinimizeResult.Unknown(TerminationReason.BudgetExhausted)),
        )
        val sibling = trackingWorker(
            "sibling",
            1,
            TrackingResumableSearch(
                MinimizeResult.Unknown(TerminationReason.BudgetExhausted),
                onRun = { siblingEntered = true },
            ),
        )
        val checker = PortfolioTerminalCheck { worker, _ ->
            if (worker.armId == 0) {
                PortfolioTerminalPublication(Verification.Accepted(Candidate(Unit, Unit))) {
                    accepted++
                }
            } else {
                null
            }
        }

        val result = Portfolio.thompson(listOf(complete, sibling)).use {
            it.minimize(Cancellation.Never, null, PortfolioIncumbents.floating(), checker)
        }

        assertIs<MinimizeResult.Unknown>(result)
        assertEquals(1, accepted)
        assertFalse(siblingEntered)
    }

    @Test
    fun `rejected native terminal should quarantine its producer and admit its sibling`() {
        var siblingEntered = false
        var accepted = false
        val complete = trackingWorker(
            "foreign",
            0,
            TrackingResumableSearch(MinimizeResult.Unknown(TerminationReason.BudgetExhausted)),
        )
        val sibling = trackingWorker(
            "sibling",
            1,
            TrackingResumableSearch(
                MinimizeResult.Unknown(TerminationReason.BudgetExhausted),
                onRun = { siblingEntered = true },
            ),
        )
        val checker = PortfolioTerminalCheck { worker, _ ->
            if (worker.armId == 0) {
                PortfolioTerminalPublication(Verification.Rejected("foreign source scope")) {
                    accepted = true
                }
            } else {
                null
            }
        }

        val result = Portfolio.thompson(listOf(complete, sibling)).use {
            it.minimize(Cancellation.Never, null, PortfolioIncumbents.floating(), checker)
        }

        assertIs<MinimizeResult.Unknown>(result)
        assertEquals(true, siblingEntered)
        assertFalse(accepted)
        assertEquals(1L, result.stats.portfolio.arms.first().faults)
    }
}
