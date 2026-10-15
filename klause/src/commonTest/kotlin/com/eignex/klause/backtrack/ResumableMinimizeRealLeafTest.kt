package com.eignex.klause.backtrack

import com.eignex.klause.backtrack.selector.IndomainMin
import com.eignex.klause.backtrack.selector.ValueSelector
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.formats.mps.Mps
import com.eignex.klause.formats.mps.toExactSourceProblem
import com.eignex.klause.formats.mps.toProblem
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.bounding.LpPlan
import com.eignex.klause.lp.engine.LpCertificationPolicy
import com.eignex.klause.lp.engine.LpCertifier
import com.eignex.klause.lp.engine.LpEngineFactory
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.LpPricingOptions
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.lp.engine.PersistentLpSolver
import com.eignex.klause.lp.engine.ProductionLpEngineFactory
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.bake
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.IncumbentExchange
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.objective.toLinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration.Companion.seconds

class ResumableMinimizeRealLeafTest {
    @Test
    fun `constant real leaf completion should require its independently accepted bound`() {
        for (acceptBound in listOf(false, true)) {
            val fixture = UnresolvedRealLeafFixture(false)
            val leafCapped = object : LpEngineFactory by ProductionLpEngineFactory {
                override fun newPersistentSolver(
                    model: LpModel,
                    cancellation: Cancellation,
                    refactorUpdateLimit: Int,
                    iterationLimit: Int,
                    workLimit: Long,
                    trackDegeneracy: Boolean,
                    pricing: LpPricingOptions,
                ): PersistentLpSolver {
                    val leaf = workLimit == 0L && iterationLimit == 0
                    return ProductionLpEngineFactory.newPersistentSolver(
                        model,
                        cancellation,
                        refactorUpdateLimit,
                        if (leaf) 1 else iterationLimit,
                        if (leaf) 1L else workLimit,
                        trackDegeneracy,
                        pricing,
                    )
                }
            }
            val policy = LpCertificationPolicy { certifier, success ->
                success && (acceptBound || certifier != LpCertifier.INTEGER)
            }

            val result = BacktrackSolver(fixture.problem, LpSolveContext(leafCapped, policy))
                .resumable(LinearObjective(), fixture.params)
                .use { assertNotNull(it.runSlice(Cancellation.Never, 1000L, 256L) {}) }

            val sample = if (acceptBound) {
                assertIs<MinimizeResult.Optimal>(result).sample
            } else {
                val incomplete = assertIs<MinimizeResult.BestFound>(result)
                assertEquals(TerminationReason.Unsupported, incomplete.reason)
                incomplete.sample
            }
            assertEquals(0.5, sample.reals.single())
        }
    }

    @Test
    fun `mixed rounding should publish completed reals without tree decisions`() {
        val fixture = UnresolvedRealLeafFixture(true)
        fixture.acceptProof = { _, _ -> true }
        val offered = ArrayList<Sample>()
        val nodesAtPublication = ArrayList<Double>()
        val params = fixture.params.copy(
            maxDecisions = 0L,
            lpPlan = fixture.params.lpPlan.copy(bounding = true, probe = true, rootCutHarvest = false),
        )

        fixture.solver.resumable(fixture.objective, params).use { search ->
            search.runSlice(Cancellation.Never, 1000L, 100_000L) {
                offered += it.sample
                nodesAtPublication += search.stats.search.nodes.sum
            }
            val sample = offered.single()
            assertEquals(1L, sample.ints.single())
            assertEquals(0.5, sample.reals.single())
            assertEquals(BigFraction.ofDouble(0.5), sample.exactReals?.single())
            assertEquals(listOf(0.0), nodesAtPublication)
            assertTrue(search.stats.lp.standaloneWorkOps.sum > 0.0)
        }
        assertEquals(fixture.opened, fixture.closed)
    }

    @Test
    fun `completed mixed leaves should publish only an improvement over the peer cutoff`() {
        for ((cutoff, expected) in listOf(0.5 to 0, 1.0 to 1)) {
            val fixture = UnresolvedRealLeafFixture(true)
            fixture.acceptProof = { _, _ -> true }
            val offered = ArrayList<Sample>()
            val params = fixture.params.copy(
                maxDecisions = 0L,
                objectiveBoundSupplier = { cutoff },
                assumptions = Assumptions.None.withInt(0, 1L),
            )

            fixture.solver.resumable(fixture.objective, params).use { search ->
                search.runSlice(Cancellation.Never, 1000L, 100_000L) { offered += it.sample }
            }

            assertEquals(expected, offered.size)
            assertTrue(offered.all { fixture.objective.evaluate(it) < cutoff })
            for (sample in offered) {
                assertEquals(1L, sample.ints.single())
                assertEquals(0.5, sample.reals.single())
            }
            assertEquals(fixture.opened, fixture.closed)
        }
    }

    @Test
    fun `mixed root probes should admit only an improvement over the peer cutoff`() {
        for ((cutoff, expected) in listOf(0.5 to 0, 1.0 to 1)) {
            val fixture = UnresolvedRealLeafFixture(true)
            fixture.acceptProof = { _, _ -> true }
            val offered = ArrayList<Sample>()
            val params = fixture.params.copy(
                maxDecisions = 0L,
                objectiveBoundSupplier = { cutoff },
                lpPlan = fixture.params.lpPlan.copy(bounding = true, probe = true, rootCutHarvest = false),
            )

            fixture.solver.resumable(fixture.objective, params).use { search ->
                search.runSlice(Cancellation.Never, 1000L, 100_000L) { offered += it.sample }
            }

            assertEquals(expected, offered.size)
            assertTrue(offered.all { fixture.objective.evaluate(it) < cutoff })
            assertEquals(fixture.opened, fixture.closed)
        }
    }

    @Test
    fun `mixed root proposals should improve a declined completion before tree branching`() {
        val problem = Problem(
            0,
            3,
            Array(3) { IntDomain(0, 1) },
            arrayOf<Factor>(
                Linear(
                    intArrayOf(0, 1),
                    doubleArrayOf(1.0, 1.0),
                    intArrayOf(0),
                    doubleArrayOf(1.0),
                    LinearOp.GE,
                    1.0,
                ),
                Linear(
                    intArrayOf(1, 2),
                    doubleArrayOf(1.0, 1.0),
                    intArrayOf(0),
                    doubleArrayOf(1.0),
                    LinearOp.GE,
                    1.0,
                ),
                Linear(
                    intArrayOf(0, 2),
                    doubleArrayOf(1.0, 1.0),
                    intArrayOf(0),
                    doubleArrayOf(1.0),
                    LinearOp.GE,
                    1.0,
                ),
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY),
        ).bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 1, 1), realCoefficients = doubleArrayOf(10.0))
        for (initial in listOf(null, Sample(BooleanArray(0), longArrayOf(0, 1, 1), doubleArrayOf(0.3)))) {
            val offered = ArrayList<Sample>()
            val nodesAtPublication = ArrayList<Double>()
            val pool = initial?.let { sample -> IncumbentExchange.minimizing<Sample>().apply { offer(sample, 5.0) } }
            val params = BacktrackParams(
                randomSeed = 0L,
                maxDecisions = 0L,
                objectiveBoundSupplier = { 5.0 },
                pooledIncumbents = pool,
                lpPlan = LpPlan(bounding = true, probe = true, rootCutHarvest = false),
            )

            BacktrackSolver(problem).resumable(objective, params).use { search ->
                search.runSlice(Cancellation.Never, 1000L, 100_000L) {
                    offered += it.sample
                    nodesAtPublication += search.stats.search.nodes.sum
                }
            }

            val sample = offered.single()
            assertEquals(if (initial == null) 3.0 else 2.0, objective.evaluate(sample))
            assertEquals(listOf(0.0), nodesAtPublication)
            assertEquals(BigFraction.ZERO, sample.exactReals?.single())
        }
    }

    @Test
    fun `mixed directed rounding should complete fractional capacity proposals before branching`() {
        for (sign in listOf(1L, -1L)) {
            val domain = if (sign > 0L) IntDomain(0L, 5L) else IntDomain(-5L, 0L)
            val problem = Problem(
                0,
                1,
                arrayOf(domain),
                arrayOf<Factor>(
                    Linear(
                        intArrayOf(0),
                        doubleArrayOf(sign.toDouble()),
                        intArrayOf(0),
                        doubleArrayOf(-1.0),
                        LinearOp.GE,
                        0.0,
                    ),
                    Linear(
                        IntArray(0),
                        DoubleArray(0),
                        intArrayOf(0),
                        doubleArrayOf(1.0),
                        LinearOp.GE,
                        0.4,
                    ),
                ),
                numRealVars = 1,
                realLower = doubleArrayOf(0.0),
                realUpper = doubleArrayOf(1.0),
            ).bake()
            val objective = LinearObjective(intCoefficients = longArrayOf(sign))
            val params = BacktrackParams(
                maxDecisions = 0L,
                objectiveBoundSupplier = { 5.0 },
                lpPlan = LpPlan(bounding = true, probe = true, rootCutHarvest = false),
            )
            val offered = ArrayList<Sample>()
            val nodesAtPublication = ArrayList<Double>()

            BacktrackSolver(problem).resumable(objective, params).use { search ->
                search.runSlice(Cancellation.Never, 1000L, 100_000L) {
                    offered += it.sample
                    nodesAtPublication += search.stats.search.nodes.sum
                }
            }

            val sample = offered.single()
            assertEquals(sign, sample.ints.single())
            assertEquals(1.0, objective.evaluate(sample))
            assertTrue(sample.reals.single() >= 0.4)
            assertEquals(1, sample.exactReals?.size)
            assertEquals(listOf(0.0), nodesAtPublication)
        }
    }

    @Test
    fun `mixed root rounding should admit source-checked float coordinates without native completion`() {
        val fixture = UnresolvedRealLeafFixture(true)
        fixture.acceptProof = { _, _ -> false }
        val params = fixture.params.copy(
            maxDecisions = 0L,
            objectiveBoundSupplier = { 1.0 },
            toleranceCheck = { sample ->
                sample.numRealVars == 1 && sample.reals.single() in 0.0..1.0 &&
                    sample.ints.single() + 2.0 * sample.reals.single() == 2.0
            },
            lpPlan = fixture.params.lpPlan.copy(bounding = true, probe = true, rootCutHarvest = false),
        )
        val offered = ArrayList<Sample>()
        val nodesAtPublication = ArrayList<Double>()

        fixture.solver.resumable(fixture.objective, params).use { search ->
            search.runSlice(Cancellation.Never, 1000L, 100_000L) {
                offered += it.sample
                nodesAtPublication += search.stats.search.nodes.sum
            }
        }

        val sample = offered.single()
        assertEquals(0.5, fixture.objective.evaluate(sample))
        assertEquals(2.0, sample.ints.single() + 2.0 * sample.reals.single())
        assertNull(sample.exactReals)
        assertEquals(0, fixture.visited)
        assertEquals(listOf(0.0), nodesAtPublication)
        assertEquals(fixture.opened, fixture.closed)
    }

    @Test
    fun `declined float coordinates should retain the discrete real completion fallback`() {
        val problem = Problem(
            0,
            1,
            arrayOf(IntDomain(0, 2)),
            arrayOf<Factor>(
                Linear(
                    intArrayOf(0),
                    doubleArrayOf(1.0),
                    intArrayOf(0),
                    doubleArrayOf(1.0),
                    LinearOp.EQ,
                    1.3,
                ),
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(0.5),
        ).bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val params = BacktrackParams(
            maxDecisions = 0L,
            objectiveBoundSupplier = { 2.0 },
            toleranceCheck = { sample ->
                sample.numRealVars == 1 && sample.reals.single() in 0.0..0.5 &&
                    kotlin.math.abs(sample.ints.single() + sample.reals.single() - 1.3) < 1e-8
            },
            lpPlan = LpPlan(bounding = true, probe = true, rootCutHarvest = false),
        )
        val offered = ArrayList<Sample>()
        val nodesAtPublication = ArrayList<Double>()

        BacktrackSolver(problem).resumable(objective, params).use { search ->
            search.runSlice(Cancellation.Never, 1000L, 100_000L) {
                offered += it.sample
                nodesAtPublication += search.stats.search.nodes.sum
            }
        }

        val sample = offered.single()
        assertEquals(1.0, objective.evaluate(sample))
        assertTrue(kotlin.math.abs(sample.reals.single() - 0.3) < 1e-8)
        assertTrue(assertNotNull(params.toleranceCheck)(sample))
        assertEquals(listOf(0.0), nodesAtPublication)
    }

    @Test
    fun `mixed rounding should not publish an uncertified completion`() {
        val fixture = UnresolvedRealLeafFixture(true)
        fixture.acceptProof = { _, _ -> false }
        val offered = ArrayList<Sample>()
        val params = fixture.params.copy(
            maxDecisions = 0L,
            lpPlan = fixture.params.lpPlan.copy(bounding = true, probe = true, rootCutHarvest = false),
        )

        fixture.solver.resumable(fixture.objective, params).use { search ->
            search.runSlice(Cancellation.Never, 1000L, 100_000L) { offered += it.sample }
        }

        assertTrue(offered.isEmpty())
        assertEquals(fixture.opened, fixture.closed)
    }

    @Test
    fun `mixed rounding should not publish after completion cancellation`() {
        val fixture = UnresolvedRealLeafFixture(true)
        var cancelled = false
        fixture.acceptProof = { leaf, _ ->
            if (leaf > 0) cancelled = true
            true
        }
        val offered = ArrayList<Sample>()
        val params = fixture.params.copy(
            maxDecisions = 0L,
            lpPlan = fixture.params.lpPlan.copy(bounding = true, probe = true, rootCutHarvest = false),
        )

        fixture.solver.resumable(fixture.objective, params).use { search ->
            search.runSlice(Cancellation { cancelled }, 1000L, 100_000L) { offered += it.sample }
        }

        assertTrue(fixture.visited > 0)
        assertTrue(offered.isEmpty())
        assertEquals(fixture.opened, fixture.closed)
    }

    @Test
    fun `a real leaf LP should be handed the run deadline`() {
        val deadlines = ArrayList<ComparableTimeMark?>()
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Linear(longArrayOf(), intArrayOf(), doubleArrayOf(2.0), intArrayOf(0), LinearOp.EQ, 3L),
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(10.0),
        ).bake()
        val run = Cancellation.after(60.seconds)
        val params = BacktrackParams(lpPlan = LpPlan(bounding = false, componentSplit = false))

        BacktrackSolver(problem, LpSolveContext(deadlineRecordingFactory(deadlines)))
            .resumable(LinearObjective(realCoefficients = doubleArrayOf(1.0)), params)
            .use { it.runSlice(run, 1000L, 256L) {} }

        assertTrue(deadlines.isNotEmpty())
        assertTrue(deadlines.all { it == run.deadline() })
    }

    @Test
    fun `node LPs should be handed the run deadline`() {
        val deadlines = ArrayList<ComparableTimeMark?>()
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0L, 9L), IntDomain(0L, 9L)),
            factors = arrayOf<Factor>(Linear(longArrayOf(2L, 3L), intArrayOf(0, 1), LinearOp.GE, 7L)),
        ).bake()
        val run = Cancellation.after(60.seconds)
        val params = BacktrackParams(lpPlan = LpPlan(bounding = true, componentSplit = false))

        BacktrackSolver(problem, LpSolveContext(deadlineRecordingFactory(deadlines)))
            .resumable(LinearObjective(intCoefficients = longArrayOf(1L, 1L)), params)
            .use { it.runSlice(run, 1000L, 256L) {} }

        assertTrue(deadlines.isNotEmpty())
        assertTrue(deadlines.all { it == run.deadline() })
    }

    @Test
    fun `a shared cutoff should retain exhaustive coverage without a local incumbent`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0L, 3L)), emptyArray()).bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(1L))
        val result = BacktrackSolver(problem).minimize(
            objective,
            BacktrackParams(objectiveBoundSupplier = { 0.0 }),
        )
        assertEquals(TerminationReason.SearchExhausted, assertIs<MinimizeResult.Unknown>(result).reason)
    }

    @Test
    fun `optimization guidance should receive complete admitted incumbents`() {
        val fixture = UnresolvedRealLeafFixture(true)
        val observed = ArrayList<Sample>()
        val selector = object : ValueSelector by IndomainMin {
            override fun fresh(): ValueSelector = this
            override fun onSolution(snapshot: Sample) {
                observed += snapshot
            }
        }

        fixture.solver.minimize(fixture.objective, fixture.params.copy(valueSelector = selector))

        fixture.assertIncumbent(observed.single())
    }

    @Test
    fun `a verified real point without an optimum should be published once before termination`() {
        val fixture = UnresolvedRealLeafFixture(false)
        fixture.acceptProof = { _, certifier -> certifier == LpCertifier.EXACT_POINT }
        ResumableMinimize(fixture.solver, fixture.objective, fixture.params, rebindable = true).use { search ->
            val offered = ArrayList<Double>()
            val result = assertIs<MinimizeResult.BestFound>(
                search.runSlice(Cancellation.Never, 1000L, 256L) {
                    offered += it.objectiveValue
                },
            )
            assertEquals(TerminationReason.Unsupported, result.reason)
            assertEquals(listOf(0.5), offered)
            assertEquals(0.5, result.sample.reals.single())
            fixture.assertVisitedLeaves(ownersReleased = false)
            assertSame(result, search.runSlice(Cancellation.Never, 1000L, 256L) { error("duplicate incumbent") })
            search.rebind(Assumptions.None, 256L)
            fixture.acceptProof = { _, _ -> false }
            assertIs<MinimizeResult.Unknown>(
                search.runSlice(Cancellation.Never, 1000L, 256L) { error("stale incumbent") },
            )
            assertEquals(1, fixture.opened)
            assertEquals(0, fixture.closed)
        }
        assertEquals(fixture.opened, fixture.closed)
    }

    @Test
    fun `rebind should recertify an unresolved improving region without blocking it`() {
        val fixture = UnresolvedRealLeafFixture(true)
        ResumableMinimize(fixture.solver, fixture.objective, fixture.params, rebindable = true).use { search ->
            assertIs<MinimizeResult.BestFound>(search.runSlice(Cancellation.Never, 1000L, 256L) {})
            fixture.assertVisitedLeaves(ownersReleased = false)
            search.rebind(Assumptions.None.withInt(0, 1L), 256L)
            fixture.acceptProof = { _, _ -> true }
            val offered = ArrayList<Double>()
            val result = assertIs<MinimizeResult.Optimal>(
                search.runSlice(Cancellation.Never, 1000L, 256L) {
                    offered += it.objectiveValue
                },
            )
            assertEquals(listOf(0.5), offered)
            assertEquals(1L, result.sample.ints.single())
            assertEquals(0.5, result.sample.reals.single())
            assertEquals(3, fixture.visited)
            assertEquals(1, fixture.opened)
            assertEquals(0, fixture.closed)
        }
        assertEquals(fixture.opened, fixture.closed)
    }

    @Test
    fun `a cancelled slice should resume to an unresolved real terminal`() {
        val fixture = UnresolvedRealLeafFixture(false)
        fixture.solver.resumable(fixture.objective, fixture.params).use { search ->
            assertNull(search.runSlice(Cancellation { true }, 1000L, 256L) {})
            assertEquals(0, fixture.opened)
            val result = assertIs<MinimizeResult.Unknown>(search.runSlice(Cancellation.Never, 1000L, 256L) {})
            assertEquals(TerminationReason.Unsupported, result.reason)
            fixture.assertVisitedLeaves()
        }
    }

    @Test
    fun `a decision cap should not become real infeasibility`() {
        val fixture = UnresolvedRealLeafFixture(true)
        val result = fixture.solver.minimize(fixture.objective, fixture.params.copy(maxDecisions = 0L))
        assertEquals(TerminationReason.BudgetExhausted, assertIs<MinimizeResult.Unknown>(result).reason)
        assertEquals(0, fixture.opened)
    }

    @Test
    fun `exact real exhaustion should retain complete shared coverage`() {
        for (shared in listOf(false, true)) {
            for (feasible in listOf(false, true)) {
                val rhs = if (feasible) 1L else 3L
                val problem = Problem(
                    0,
                    0,
                    emptyArray(),
                    arrayOf(Linear(longArrayOf(), intArrayOf(), doubleArrayOf(2.0), intArrayOf(0), LinearOp.EQ, rhs)),
                    numRealVars = 1,
                    realLower = doubleArrayOf(0.0),
                    realUpper = doubleArrayOf(1.0),
                ).bake()
                val objective = LinearObjective(realCoefficients = doubleArrayOf(1.0))
                val params = BacktrackParams(
                    objectiveBoundSupplier = if (shared) ({ Double.POSITIVE_INFINITY }) else null,
                )
                val result = BacktrackSolver(problem).minimize(objective, params)
                when {
                    shared && feasible -> assertEquals(
                        TerminationReason.SearchExhausted,
                        assertIs<MinimizeResult.BestFound>(result).reason,
                    )

                    shared -> assertEquals(
                        TerminationReason.SearchExhausted,
                        assertIs<MinimizeResult.Unknown>(result).reason,
                    )

                    feasible -> assertEquals(0.5, assertIs<MinimizeResult.Optimal>(result).sample.reals.single())

                    else -> assertIs<MinimizeResult.Infeasible>(result)
                }
            }
        }
    }

    @Test
    fun `an unresolved real leaf should not prove infeasibility under shared bounds`() {
        for (shared in listOf(false, true)) {
            val fixture = UnresolvedRealLeafFixture(false)
            val params = fixture.params.copy(
                objectiveBoundSupplier = if (shared) ({ Double.POSITIVE_INFINITY }) else null,
            )
            fixture.solver.resumable(fixture.objective, params).use { search ->
                val result = assertIs<MinimizeResult.Unknown>(search.runSlice(Cancellation.Never, 1000L, 256L) {})
                assertEquals(TerminationReason.Unsupported, result.reason)
                fixture.assertVisitedLeaves()
                assertTrue(search.isDone)
                assertSame(result, search.runSlice(Cancellation.Never, 1000L, 256L) {})
            }
        }
    }

    @Test
    fun `an unresolved improving region should preserve an independently verified incumbent`() {
        for (shared in listOf(false, true)) {
            val fixture = UnresolvedRealLeafFixture(true)
            var bound = Double.POSITIVE_INFINITY
            val params = fixture.params.copy(objectiveBoundSupplier = if (shared) ({ bound }) else null)
            fixture.solver.resumable(fixture.objective, params).use { search ->
                val result = assertIs<MinimizeResult.BestFound>(
                    search.runSlice(Cancellation.Never, 1000L, 256L) {
                        fixture.assertIncumbent(it.sample)
                        bound = it.objectiveValue
                    },
                )
                assertEquals(TerminationReason.Unsupported, result.reason)
                fixture.assertIncumbent(result.sample)
                fixture.assertVisitedLeaves()
            }
        }
    }

    @Test
    fun `mixed tree probes should preserve original MPS coordinates objective and sense`() {
        for (sense in listOf("MIN", "MAX")) {
            for (exact in listOf(false, true)) {
                val source = Mps.parse(
                    "NAME TREE\nOBJSENSE\n $sense\nROWS\n N COST\n E R\nCOLUMNS\n" +
                        " M0 'MARKER' 'INTORG'\n I COST 1 R 1\n M1 'MARKER' 'INTEND'\n" +
                        " X COST 10 R 2\nRHS\n RHS R 2 COST -3\nBOUNDS\n" +
                        " BV B I\n LO B X 0.25\n UP B X 1.25\nENDATA",
                )
                val compiled = if (exact) source.toExactSourceProblem() else source.toProblem()
                val sourceObjective = assertNotNull(compiled.objective).toLinearObjective()
                val objective = if (compiled.maximize) sourceObjective.negated() else sourceObjective
                val offered = ArrayList<Sample>()
                val params = BacktrackParams(
                    maxDecisions = 0L,
                    toleranceCheck = if (exact) {
                        null
                    } else {
                        { sample ->
                            compiled.withinTolerance(sample.ints, sample.reals)
                        }
                    },
                    lpPlan = LpPlan(bounding = true, probe = false, lbTreeSearch = true, rootCutHarvest = false),
                )

                BacktrackSolver(compiled.model.bake()).resumable(objective, params).use { search ->
                    search.runSlice(Cancellation.Never, 1000L, 100_000L) { offered += it.sample }
                    assertEquals(0.0, search.stats.search.nodes.sum)
                }

                val sample = offered.single()
                val reals = sample.exactReals ?: sample.reals.map { assertNotNull(BigFraction.ofDouble(it)) }
                val witness = compiled.sourceWitness(sample.ints, reals)
                assertEquals(if (compiled.maximize) "13" else "9", witness.objective.toString())
                assertEquals(
                    if (compiled.maximize) listOf("0", "1") else listOf("1", "1/2"),
                    witness.values.map { it.toString() },
                )
                if (exact) {
                    assertNotNull(sample.exactReals)
                } else {
                    assertNull(sample.exactReals)
                }
            }
        }
    }

    @Test
    fun `explicit mixed tree probing should find a completed incumbent before ordinary decisions`() {
        for ((coefficient, expectedInteger, expectedReal) in listOf(Triple(1.0, 1L, 0.5), Triple(-1.0, 0L, 1.0))) {
            for (enabled in listOf(false, true)) {
                val fixture = UnresolvedRealLeafFixture(true)
                fixture.acceptProof = { _, _ -> true }
                val objective = LinearObjective(realCoefficients = doubleArrayOf(coefficient))
                val offered = ArrayList<Sample>()
                val nodesAtPublication = ArrayList<Double>()
                val params = fixture.params.copy(
                    maxDecisions = 0L,
                    lpPlan = fixture.params.lpPlan.copy(
                        bounding = true,
                        lbTreeSearch = enabled,
                        probe = false,
                        rootCutHarvest = false,
                    ),
                )

                fixture.solver.resumable(objective, params).use { search ->
                    val result = search.runSlice(Cancellation.Never, 1000L, 100_000L) {
                        offered += it.sample
                        nodesAtPublication += search.stats.search.nodes.sum
                    }
                    assertTrue(result !is MinimizeResult.Infeasible)
                }

                assertEquals(if (enabled) 1 else 0, offered.size)
                if (enabled) {
                    assertEquals(listOf(0.0), nodesAtPublication)
                    val sample = offered.single()
                    assertEquals(expectedInteger, sample.ints.single())
                    assertEquals(expectedReal, sample.reals.single())
                    assertEquals(BigFraction.ofDouble(expectedReal), sample.exactReals?.single())
                }
                assertEquals(fixture.opened, fixture.closed)
                assertEquals(fixture.persistentOpened, fixture.persistentClosed)
            }
        }
    }

    @Test
    fun `mixed tree decisions should spend the containing solve allowance`() {
        val problem = Problem(
            0,
            3,
            Array(3) { IntDomain(0, 1) },
            arrayOf(
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
                Linear(intArrayOf(1, 1), intArrayOf(1, 2), LinearOp.GE, 1),
                Linear(intArrayOf(1, 1), intArrayOf(0, 2), LinearOp.GE, 1),
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(0.5),
            realUpper = doubleArrayOf(1.5),
        ).bake()
        val objective = LinearObjective(
            intCoefficients = longArrayOf(1, 1, 1),
            realCoefficients = doubleArrayOf(0.5),
        )
        for (limit in listOf(0L, 1L)) {
            val budget = NodeBudget(limit)
            val offered = ArrayList<Sample>()
            val params = BacktrackParams(
                nodeBudget = budget,
                maxDecisions = 0L,
                lpPlan = LpPlan(bounding = true, lbTreeSearch = true, probe = false, rootCutHarvest = false),
            )

            BacktrackSolver(problem).resumable(objective, params).use { search ->
                val result = search.runSlice(Cancellation.Never, 1000L, 100_000L) { offered += it.sample }
                assertIs<MinimizeResult.Unknown>(result)
                assertEquals(0.0, search.stats.search.nodes.sum)
                assertTrue(search.work >= limit)
            }

            assertEquals(limit, budget.spent)
            assertTrue(offered.isEmpty())
        }
    }

    @Test
    fun `mixed tree probing should keep tolerance witnesses separate from exact certification`() {
        for (acceptance in listOf(null, false, true)) {
            val tolerated = acceptance == true
            val fixture = UnresolvedRealLeafFixture(true)
            fixture.acceptProof = { _, _ -> false }
            val offered = ArrayList<Sample>()
            val check: ((Sample) -> Boolean)? = if (acceptance != null) {
                { sample ->
                    acceptance && sample.ints.single() + 2.0 * sample.reals.single() == 2.0 &&
                        sample.reals.single() in 0.0..1.0
                }
            } else {
                null
            }
            val params = fixture.params.copy(
                maxDecisions = 0L,
                toleranceCheck = check,
                lpPlan = fixture.params.lpPlan.copy(
                    bounding = true,
                    lbTreeSearch = true,
                    probe = false,
                    rootCutHarvest = false,
                ),
            )

            fixture.solver.resumable(fixture.objective, params).use { search ->
                val result = search.runSlice(Cancellation.Never, 1000L, 100_000L) { offered += it.sample }
                assertTrue(result !is MinimizeResult.Optimal)
            }

            assertEquals(if (tolerated) 1 else 0, offered.size)
            if (tolerated) {
                assertEquals(1L, offered.single().ints.single())
                assertEquals(0.5, offered.single().reals.single())
                assertNull(offered.single().exactReals)
            }
            assertEquals(fixture.opened, fixture.closed)
            assertEquals(fixture.persistentOpened, fixture.persistentClosed)
        }
    }
}

private fun deadlineRecordingFactory(deadlines: MutableList<ComparableTimeMark?>) =
    object : LpEngineFactory by ProductionLpEngineFactory {
        override fun newPersistentSolver(
            model: LpModel,
            cancellation: Cancellation,
            refactorUpdateLimit: Int,
            iterationLimit: Int,
            workLimit: Long,
            trackDegeneracy: Boolean,
            pricing: LpPricingOptions,
        ): PersistentLpSolver {
            deadlines += cancellation.deadline()
            return ProductionLpEngineFactory.newPersistentSolver(
                model,
                cancellation,
                refactorUpdateLimit,
                iterationLimit,
                workLimit,
                trackDegeneracy,
                pricing,
            )
        }
    }
