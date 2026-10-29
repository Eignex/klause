package com.eignex.klause.lp.bounding

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.backtrack.selector.RandomVariable
import com.eignex.klause.config.KlauseConfig
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.formats.mps.Mps
import com.eignex.klause.formats.mps.toExactSourceProblem
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.engine.Basis
import com.eignex.klause.lp.engine.FloatLpResult
import com.eignex.klause.lp.engine.LpCertificationPolicy
import com.eignex.klause.lp.engine.LpCertifier
import com.eignex.klause.lp.engine.LpEngineFactory
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.LpPricingOptions
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.lp.engine.ProductionLpEngineFactory
import com.eignex.klause.lp.engine.TableauCutSolver
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.bake
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.objective.toLinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.util.Cancellation
import kotlin.math.nextDown
import kotlin.math.nextUp
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LpBoundingTest {
    // Optimum 3 at (1,1,1); the separable per-term bound sees only zero minima, so only the LP prunes.
    private fun triangle(rhs: Int = 2): Problem = Problem(
        numBoolVars = 0,
        numIntVars = 3,
        intDomains = arrayOf(IntDomain(0, 5), IntDomain(0, 5), IntDomain(0, 5)),
        factors = arrayOf<Factor>(
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, rhs),
            Linear(intArrayOf(1, 1), intArrayOf(1, 2), LinearOp.GE, rhs),
            Linear(intArrayOf(1, 1), intArrayOf(0, 2), LinearOp.GE, rhs),
        ),
    )

    private val sumObjective = LinearObjective(intCoefficients = longArrayOf(1L, 1L, 1L))

    @Test
    fun `lp bounding should preserve the optimum`() {
        val problem = triangle()
        val off = BacktrackSolver(problem.bake()).minimize(sumObjective, BacktrackParams(randomSeed = 1L))
        val on = BacktrackSolver(problem.bake()).minimize(
            sumObjective,
            BacktrackParams(randomSeed = 1L, lpPlan = LpPlan(bounding = true)),
        )

        assertTrue(off is MinimizeResult.Optimal)
        assertTrue(on is MinimizeResult.Optimal)
        assertEquals(3.0, off.objectiveValue)
        assertEquals(3.0, on.objectiveValue)
    }

    @Test
    fun `lp bounding should prune nodes the separable bound cannot`() {
        val problem = triangle()

        val base = BacktrackParams(randomSeed = 1L, variableSelector = RandomVariable)
        val off = BacktrackSolver(problem.bake()).minimize(sumObjective, base)
        val on = BacktrackSolver(problem.bake()).minimize(
            sumObjective,
            base.copy(lpPlan = base.lpPlan.copy(bounding = true)),
        )

        assertTrue(on.stats.lp.pruned.sum > 0.0, "expected LP-bound prunes, got ${on.stats.lp.pruned.sum}")
        assertTrue(
            on.stats.search.nodes.sum <= off.stats.search.nodes.sum,
            "LP bounding explored more nodes: ${on.stats.search.nodes.sum} vs ${off.stats.search.nodes.sum}",
        )
    }

    @Test
    fun `root cut harvest should preserve the optimum`() {
        val problem = triangle()
        val result = BacktrackSolver(problem.bake()).minimize(
            sumObjective,
            BacktrackParams(randomSeed = 1L, lpPlan = LpPlan(bounding = true, cuts = true)),
        )
        assertTrue(result is MinimizeResult.Optimal)
        assertEquals(3.0, result.objectiveValue)
    }

    @Test
    fun `fractional root cuts should expose construction and containing solve work`() {
        val result = BacktrackSolver(triangle(3).bake()).minimize(sumObjective,
            BacktrackParams(randomSeed = 1L, lpPlan = LpPlan(bounding = true, cuts = true)))

        assertIs<MinimizeResult.Optimal>(result)
        assertEquals(5.0, result.objectiveValue)
        val phases = result.stats.lp.phases
        assertTrue(phases.keys.any { it.startsWith("CUT_ROOT_") && it.endsWith("CONSTRUCTION") })
        assertTrue(phases.any { (key, value) -> key.startsWith("CUT_ROOT_") &&
            key.endsWith("ADMISSION") && (value.outcomes["ADMITTED"] ?: 0L) > 0L })
        assertTrue(phases.any { (key, value) -> key.startsWith("CUT_ROOT_") &&
            key.endsWith("SOLVE") && value.work > 0L })
        assertTrue(phases.keys.any { it.startsWith("CUT_NODE_") && it.endsWith("RETIREMENT") })
    }

    @Test
    fun `objective-variable divisor rounding should preserve the optimum`() {
        // v = 2(a+b+c) and a+b+c >= 2: the LP relaxes v to 3.0, and rounding to the next even value gives 4.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(IntDomain(0, 12), IntDomain(0, 5), IntDomain(0, 5), IntDomain(0, 5)),
            factors = arrayOf<Factor>(
                Linear(intArrayOf(1, -2, -2, -2), intArrayOf(0, 1, 2, 3), LinearOp.EQ, 0),
                Linear(intArrayOf(1, 1), intArrayOf(1, 2), LinearOp.GE, 1),
                Linear(intArrayOf(1, 1), intArrayOf(2, 3), LinearOp.GE, 1),
                Linear(intArrayOf(1, 1), intArrayOf(1, 3), LinearOp.GE, 1),
            ),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(1L, 0L, 0L, 0L))
        val off = BacktrackSolver(problem.bake()).minimize(obj, BacktrackParams(randomSeed = 1L))
        val on = BacktrackSolver(problem.bake()).minimize(
            obj,
            BacktrackParams(randomSeed = 1L, lpPlan = LpPlan(bounding = true)),
        )
        assertTrue(off is MinimizeResult.Optimal)
        assertTrue(on is MinimizeResult.Optimal)
        assertEquals(4.0, off.objectiveValue)
        assertEquals(4.0, on.objectiveValue)
    }

    @Test
    fun `roundUpToResidue should lift to the next congruent value`() {
        assertEquals(4L, roundUpToResidue(3L, 2L, 0L)) // 3 -> next even
        assertEquals(4L, roundUpToResidue(4L, 2L, 0L)) // already even, unchanged
        assertEquals(5L, roundUpToResidue(3L, 3L, 2L)) // next value congruent to 2 mod 3
        assertEquals(3L, roundUpToResidue(3L, 3L, 0L)) // 3 is 0 mod 3, unchanged
        assertEquals(-2L, roundUpToResidue(-3L, 2L, 0L)) // negative lower bound -> next even
    }

    @Test
    fun `wide source constants should not prune an improving node or recovery`() {
        for (constant in listOf(9007199254740995L, -9007199254740993L)) {
            val problem = Problem(0, 1, arrayOf(IntDomain(0, 1)), emptyArray())
            val objective = LinearObjective(intCoefficients = longArrayOf(1L), constant = constant)
            val sink = SolveStatsSink(backend = "source-bound")
            val session = PropagationSession(problem)
            val cutoff = (constant + 1L).toDouble()
            LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true)), sink).use { engine ->
                val relaxer = assertNotNull(engine.lpRelaxer)

                assertFalse(engine.sparseSafePrune(relaxer, session, cutoff, sink, Cancellation.Never, -1, true).prune)
                assertFalse(engine.sparseCertifiedPrune(relaxer, session, cutoff, sink, Cancellation.Never).prune)
                val root = engine.rootLpRelaxationBound(relaxer, emptyList())

                assertTrue(assertNotNull(BigFraction.ofDouble(root)) <= BigFraction.ofLong(constant))
                assertTrue(root < cutoff)
            }
        }
    }

    @Test
    fun `source bounds should retain exact cancellation of wide opposite terms`() {
        val lower = 9007199254740993L
        val constant = 9007199254740995L
        val problem = Problem(0, 1, arrayOf(IntDomain(lower, lower + 1L)), emptyArray())
        val objective = LinearObjective(intCoefficients = longArrayOf(-1L), constant = constant)
        val sink = SolveStatsSink(backend = "source-cancellation")
        val session = PropagationSession(problem)
        LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true)), sink).use { engine ->
            val relaxer = assertNotNull(engine.lpRelaxer)

            val root = engine.rootLpRelaxationBound(relaxer, emptyList())

            assertEquals(BigFraction.ONE, BigFraction.ofLong(constant) - BigFraction.ofLong(lower + 1L))
            assertEquals(1.0, root)
            assertFalse(engine.sparseSafePrune(relaxer, session, 2.0, sink, Cancellation.Never, -1, true).prune)
            assertFalse(engine.sparseCertifiedPrune(relaxer, session, 2.0, sink, Cancellation.Never).prune)
        }
    }

    @Test
    fun `source bound composition should remain below both Long endpoint objectives`() {
        for (constant in listOf(Long.MIN_VALUE, Long.MAX_VALUE)) {
            val coefficient = if (constant < 0L) 1L else -1L
            val problem = Problem(0, 1, arrayOf(IntDomain(Long.MAX_VALUE - 1L, Long.MAX_VALUE)), emptyArray())
            val objective = LinearObjective(intCoefficients = longArrayOf(coefficient), constant = constant)
            val sink = SolveStatsSink(backend = "source-endpoint")
            LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true)), sink).use { engine ->
                val relaxer = assertNotNull(engine.lpRelaxer)

                val root = assertNotNull(BigFraction.ofDouble(engine.rootLpRelaxationBound(relaxer, emptyList())))

                for (point in listOf(Long.MAX_VALUE - 1L, Long.MAX_VALUE)) {
                    val exact = BigFraction.ofLong(constant) +
                        BigFraction.ofLong(coefficient) * BigFraction.ofLong(point)
                    assertTrue(root <= exact)
                }
            }
        }
    }

    @Test
    fun `an overflowing source objective should decline incumbent deductions`() {
        for (constant in listOf(Long.MIN_VALUE, Long.MAX_VALUE)) {
            val coefficient = if (constant < 0L) -1L else 1L
            val problem = Problem(0, 1, arrayOf(IntDomain(0, 1)), emptyArray())
            val objective = LinearObjective(intCoefficients = longArrayOf(coefficient), constant = constant)
            val sink = SolveStatsSink(backend = "source-overflow")
            val session = PropagationSession(problem)
            LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true)), sink).use { engine ->
                val relaxer = assertNotNull(engine.lpRelaxer)

                assertFalse(engine.sparseSafePrune(relaxer, session, 0.0, sink, Cancellation.Never, 0, true).prune)
                assertFalse(engine.sparseCertifiedPrune(relaxer, session, 0.0, sink, Cancellation.Never).prune)
                assertTrue(engine.rootLpRelaxationBound(relaxer, emptyList()).isNaN())
                assertEquals(0L, session.intDomain(0).min)
                assertEquals(1L, session.intDomain(0).max)
            }
        }
    }

    @Test
    fun `an ordinary source cutoff should prune node and recovery`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 1)), emptyArray())
        val objective = LinearObjective(intCoefficients = longArrayOf(1L), constant = 7L)
        val sink = SolveStatsSink(backend = "source-ordinary")
        val session = PropagationSession(problem)
        LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true)), sink).use { engine ->
            val relaxer = assertNotNull(engine.lpRelaxer)

            assertTrue(engine.sparseSafePrune(relaxer, session, 7.0, sink, Cancellation.Never, -1, true).prune)
            assertTrue(engine.sparseCertifiedPrune(relaxer, session, 7.0, sink, Cancellation.Never).prune)
            assertEquals(7.0, engine.rootLpRelaxationBound(relaxer, emptyList()))
        }
    }

    @Test
    fun `affine objective propagation should use source variable units with either reason policy`() {
        for (learn in listOf(false, true)) {
            val problem = Problem(
                0,
                4,
                arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 3)),
                arrayOf<Factor>(
                    Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
                    Linear(intArrayOf(1, 1), intArrayOf(1, 2), LinearOp.GE, 1),
                    Linear(intArrayOf(1, 1), intArrayOf(0, 2), LinearOp.GE, 1),
                    Linear(intArrayOf(1, 1, 1, -1), intArrayOf(0, 1, 2, 3), LinearOp.EQ, 0),
                ),
            )
            val objective = LinearObjective(intCoefficients = longArrayOf(0, 0, 0, 2), constant = 5L)
            val sink = SolveStatsSink(backend = "source-propagation")
            val session = PropagationSession(problem)
            LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true)), sink).use { engine ->
                val relaxer = assertNotNull(engine.lpRelaxer)
                assertEquals(0L, session.intDomain(3).min)

                val result = engine.sparseSafePrune(
                    relaxer,
                    session,
                    Double.POSITIVE_INFINITY,
                    sink,
                    Cancellation.Never,
                    3,
                    true,
                    learn = learn,
                )

                assertFalse(result.prune)
                assertEquals(2L, session.intDomain(3).min)
                assertEquals(3L, session.intDomain(3).max)
            }
        }
    }

    @Test
    fun `starved root LP budget should still find the optimum`() {
        val problem = Problem(
            0,
            3,
            Array(3) { IntDomain(0, 4) },
            arrayOf<Factor>(
                Linear(intArrayOf(1, 1, 2), intArrayOf(0, 1, 2), LinearOp.LE, 7),
                Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.LE, 1),
            ),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(-3L, 2L, -5L))
        val config = KlauseConfig.DEFAULT.copy(lpMaxTableauCells = Long.MAX_VALUE)
        val plan = LpAutoConfig.resolve(problem.withSettings(config.problemSettings()), LpConfig.AGGRESSIVE)
        assertTrue(plan.bounding)
        val starved = BacktrackParams(lpPlan = plan.copy(rootBudgetMillis = 0L), randomSeed = 7L)

        val result = BacktrackSolver(problem.bake()).minimize(obj, starved)

        assertEquals(-18.0, assertIs<MinimizeResult.Optimal>(result).objective)
    }

    private fun infeasibleNodeProblem(): Pair<Problem, LinearObjective> {
        val n = 8
        val vars = IntArray(n) { it }
        val domains = Array(n) { IntDomain(0, 4) }
        val rows = arrayOf<Factor>(
            Linear(LongArray(n) { 1L }, vars, LinearOp.GE, 22L),
            Linear(LongArray(n) { 1L }, vars, LinearOp.LE, 24L),
            Linear(LongArray(n) { if (it % 2 == 0) 3L else 1L }, vars, LinearOp.LE, 40L),
            Linear(LongArray(n) { if (it % 2 == 0) 1L else 3L }, vars, LinearOp.LE, 40L),
        )
        return Problem(0, n, domains, rows) to LinearObjective(intCoefficients = LongArray(n) { (it % 3 + 1).toLong() })
    }

    @Test
    fun `infeasible node relaxations should still charge their pivots`() {
        val (problem, objective) = infeasibleNodeProblem()

        val result = BacktrackSolver(problem.bake())
            .minimize(objective, BacktrackParams(randomSeed = 7L, lpPlan = LpPlan(bounding = true)))

        val lp = result.stats.lp
        assertTrue(lp.infeasible.sum > 0.0)
        assertTrue(lp.pivots.sum > 0.0)
    }

    private fun reuseProblem(): Pair<Problem, LinearObjective> {
        val rng = Random(4242)
        val n = 14
        val domains = Array(n) { IntDomain(0, 6) }
        val vars = IntArray(n) { it }
        val rows = ArrayList<Factor>()
        repeat(7) {
            rows += Linear(
                LongArray(n) { rng.nextLong(1L, 5L) },
                vars,
                LinearOp.GE,
                rng.nextLong(12L, 26L),
            )
        }
        val objective = LinearObjective(intCoefficients = LongArray(n) { rng.nextLong(1L, 6L) })
        return Problem(0, n, domains, rows.toTypedArray()) to objective
    }

    @Test
    fun `node LP solves should reuse the factorization`() {
        val (problem, objective) = reuseProblem()

        val result = BacktrackSolver(problem.bake()).minimize(
            objective,
            BacktrackParams(randomSeed = 11L, lpPlan = LpPlan(bounding = true), nodeBudget = NodeBudget(20)),
        )

        val lp = result.stats.lp
        assertTrue(lp.solves.sum > 1.0)
        assertTrue(
            lp.refactorizations.sum < lp.solves.sum,
            "${lp.refactorizations.sum} refactorizations for ${lp.solves.sum} solves",
        )
    }

    private fun makespanProblem(n: Int, durations: LongArray, resources: LongArray, capacity: Long, hi: Int): Problem {
        val domains = Array(n + 1) { if (it < n) IntDomain(0, hi.toLong()) else IntDomain(0, (hi + 6).toLong()) }
        val factors = ArrayList<Factor>()
        for (i in 0 until n) factors.add(Linear(longArrayOf(1, -1), intArrayOf(n, i), LinearOp.GE, durations[i]))
        factors.add(Cumulative(IntArray(n) { it }, durations, resources, capacity))
        return Problem(0, n + 1, domains, factors.toTypedArray())
    }

    @Test
    fun `LP stats should be populated on a cumulative makespan minimize`() {
        val n = 5
        val p = makespanProblem(n, longArrayOf(3, 2, 4, 2, 3), longArrayOf(1, 2, 1, 2, 1), capacity = 2L, hi = 8)
        val obj = LinearObjective(intCoefficients = LongArray(p.numIntVars) { if (it == n) 1L else 0L })
        val params = BacktrackParams(randomSeed = 7L, lpConfig = LpConfig.AGGRESSIVE)
        val res = BacktrackSolver(p.bake()).minimize(obj, params)
        assertTrue(res is MinimizeResult.Optimal, "got $res")
        val s = res.stats

        assertTrue(s.lp.solves.sum > 0.0)
        assertTrue(
            s.lp.infeasible.sum <= s.lp.pruned.sum,
            "infeasible ${s.lp.infeasible.sum}, pruned ${s.lp.pruned.sum}",
        )
        assertTrue(s.lp.rootBound.isFinite())
        assertTrue(
            s.lp.rootBound <= res.objective + 1e-6,
            "root bound ${s.lp.rootBound}, optimum ${res.objective}",
        )
    }

    @Test
    fun `continuous node pruning should respect independently accepted objective certifiers`() {
        for (safe in listOf(false, true)) {
            for (integer in listOf(false, true)) {
                for ((sign, minimum) in listOf(1L to 3.125, -1L to -4.625)) {
                    val problem = Problem(
                        0,
                        1,
                        arrayOf(IntDomain(0, 1)),
                        emptyArray(),
                        numRealVars = 1,
                        realLower = doubleArrayOf(0.25),
                        realUpper = doubleArrayOf(1.25),
                    )
                    val objective = LinearObjective(
                        intCoefficients = longArrayOf(sign),
                        constant = 3L * sign,
                        realCoefficients = doubleArrayOf(0.5 * sign),
                    )
                    val context = LpSolveContext(
                        certificationPolicy = LpCertificationPolicy { certifier, successful ->
                            successful && when (certifier) {
                                LpCertifier.SAFE_OBJECTIVE -> safe
                                LpCertifier.INTEGER -> integer
                                else -> false
                            }
                        },
                    )
                    val sink = SolveStatsSink(backend = "continuous-policy")
                    val session = PropagationSession(problem)
                    LpEngine(
                        problem,
                        objective,
                        LpParams(lpPlan = LpPlan(bounding = true)),
                        sink,
                        context,
                    ).use { engine ->
                        val result = engine.sparseSafePrune(
                            assertNotNull(engine.lpRelaxer),
                            session,
                            minimum - 0.125,
                            sink,
                            Cancellation.Never,
                            -1,
                            true,
                        )

                        assertEquals(safe || integer, result.prune, "safe=$safe integer=$integer sign=$sign")
                    }
                }
            }
        }
    }

    @Test
    fun `continuous recovery pruning and root publication should retain only accepted safe bounds`() {
        for (safe in listOf(false, true)) {
            for (integer in listOf(false, true)) {
                for ((sign, minimum) in listOf(1L to 3.125, -1L to -4.625)) {
                    val problem = Problem(
                        0,
                        1,
                        arrayOf(IntDomain(0, 1)),
                        emptyArray(),
                        numRealVars = 1,
                        realLower = doubleArrayOf(0.25),
                        realUpper = doubleArrayOf(1.25),
                    )
                    val objective = LinearObjective(
                        intCoefficients = longArrayOf(sign),
                        constant = 3L * sign,
                        realCoefficients = doubleArrayOf(0.5 * sign),
                    )
                    val context = LpSolveContext(
                        certificationPolicy = LpCertificationPolicy { certifier, successful ->
                            successful && when (certifier) {
                                LpCertifier.SAFE_OBJECTIVE -> safe
                                LpCertifier.INTEGER -> integer
                                else -> false
                            }
                        },
                    )
                    val sink = SolveStatsSink(backend = "continuous-recovery")
                    LpEngine(
                        problem,
                        objective,
                        LpParams(lpPlan = LpPlan(bounding = true)),
                        sink,
                        context,
                    ).use { engine ->
                        val relaxer = assertNotNull(engine.lpRelaxer)
                        val result = engine.sparseCertifiedPrune(
                            relaxer,
                            PropagationSession(problem),
                            minimum - 0.125,
                            sink,
                            Cancellation.Never,
                        )
                        val root = engine.rootLpRelaxationBound(relaxer, emptyList())

                        assertEquals(safe, result.prune)
                        if (safe) assertEquals(minimum, root) else assertTrue(root.isNaN())
                    }
                }
            }
        }
    }

    @Test
    fun `safe continuous bounds should conservatively enclose cutoff equality and neighbors`() {
        for ((sign, minimum) in listOf(1L to 3.125, -1L to -4.625)) {
            for (cutoff in listOf(
                minimum.nextDown(),
                minimum,
                minimum.nextUp(),
                Double.NEGATIVE_INFINITY,
                Double.POSITIVE_INFINITY,
                Double.NaN,
            )) {
                val problem = Problem(
                    0,
                    1,
                    arrayOf(IntDomain(0, 1)),
                    emptyArray(),
                    numRealVars = 1,
                    realLower = doubleArrayOf(0.25),
                    realUpper = doubleArrayOf(1.25),
                )
                val objective = LinearObjective(
                    intCoefficients = longArrayOf(sign),
                    constant = 3L * sign,
                    realCoefficients = doubleArrayOf(0.5 * sign),
                )
                val context = LpSolveContext(
                    certificationPolicy = LpCertificationPolicy { certifier, successful ->
                        successful && certifier == LpCertifier.SAFE_OBJECTIVE
                    },
                )
                val sink = SolveStatsSink(backend = "continuous-cutoff")
                LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true)), sink, context).use { engine ->
                    val relaxer = assertNotNull(engine.lpRelaxer)
                    val expected = cutoff.isFinite() && cutoff < minimum

                    assertEquals(
                        expected,
                        engine.sparseSafePrune(
                            relaxer,
                            PropagationSession(problem),
                            cutoff,
                            sink,
                            Cancellation.Never,
                            -1,
                            true,
                        ).prune,
                    )
                    assertEquals(
                        expected,
                        engine.sparseCertifiedPrune(
                            relaxer,
                            PropagationSession(problem),
                            cutoff,
                            sink,
                            Cancellation.Never,
                        ).prune,
                    )
                }
            }
        }
    }

    @Test
    fun `continuous root bounds should preserve fractional source units beside wide constants`() {
        for (constant in listOf(3L, 9007199254740995L, -9007199254740993L, Long.MIN_VALUE, Long.MAX_VALUE)) {
            for (coefficient in listOf(0.125, -0.125)) {
                val problem = Problem(
                    0,
                    1,
                    arrayOf(IntDomain(0, 1)),
                    emptyArray(),
                    numRealVars = 1,
                    realLower = doubleArrayOf(0.25),
                    realUpper = doubleArrayOf(1.25),
                )
                val objective = LinearObjective(
                    intCoefficients = longArrayOf(1L),
                    constant = constant,
                    realCoefficients = doubleArrayOf(coefficient),
                )
                val sink = SolveStatsSink(backend = "continuous-source")
                val exact = BigFraction.ofLong(constant) + assertNotNull(BigFraction.ofDouble(coefficient)) *
                    assertNotNull(BigFraction.ofDouble(if (coefficient > 0.0) 0.25 else 1.25))
                LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true)), sink).use { engine ->
                    val relaxer = assertNotNull(engine.lpRelaxer)
                    val root = engine.rootLpRelaxationBound(relaxer, emptyList())

                    assertTrue(assertNotNull(BigFraction.ofDouble(root)) <= exact)
                    assertTrue(assertNotNull(BigFraction.ofDouble(root.nextUp())) > exact)
                    assertFalse(
                        engine.sparseSafePrune(
                            relaxer,
                            PropagationSession(problem),
                            exact.toDouble(),
                            sink,
                            Cancellation.Never,
                            -1,
                            true,
                        ).prune,
                    )
                    assertFalse(
                        engine.sparseCertifiedPrune(
                            relaxer,
                            PropagationSession(problem),
                            exact.toDouble(),
                            sink,
                            Cancellation.Never,
                        ).prune,
                    )
                }
            }
        }
    }

    @Test
    fun `continuous bounds should retain original MPS objective constants scales and sense`() {
        for ((sense, minimum) in listOf("MIN" to 0.5, "MAX" to -1.125)) {
            val compiled = Mps.parse(
                "NAME SOURCE\nOBJSENSE\n $sense\nROWS\n N COST\nCOLUMNS\n" +
                    " M0 'MARKER' 'INTORG'\n I COST 0.125\n M1 'MARKER' 'INTEND'\n X COST 0.5\n" +
                    "RHS\n R COST -0.375\nBOUNDS\n LO B I 0\n UP B I 1\n" +
                    " LO B X 0.25\n UP B X 1.25\nENDATA",
            ).toExactSourceProblem()
            val sourceObjective = assertNotNull(compiled.objective).toLinearObjective()
            val objective = if (compiled.maximize) sourceObjective.negated() else sourceObjective
            val sink = SolveStatsSink(backend = "continuous-original-source")
            val context = LpSolveContext(
                certificationPolicy = LpCertificationPolicy { certifier, successful ->
                    successful && certifier == LpCertifier.SAFE_OBJECTIVE
                },
            )
            val params = LpParams(lpPlan = LpPlan(bounding = true))
            LpEngine(compiled.model, objective, params, sink, context).use { engine ->
                val relaxer = assertNotNull(engine.lpRelaxer)

                assertEquals(minimum, engine.rootLpRelaxationBound(relaxer, emptyList()))
                assertTrue(
                    engine.sparseSafePrune(
                        relaxer,
                        PropagationSession(compiled.model),
                        minimum - 0.125,
                        sink,
                        Cancellation.Never,
                        -1,
                        true,
                    ).prune,
                )
                assertFalse(
                    engine.sparseCertifiedPrune(
                        relaxer,
                        PropagationSession(compiled.model),
                        minimum,
                        sink,
                        Cancellation.Never,
                    ).prune,
                )
            }
        }
    }

    @Test
    fun `nonfinite dual candidates should not publish a continuous root bound`() {
        for (dual in listOf(Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)) {
            val problem = Problem(
                0,
                0,
                emptyArray(),
                arrayOf(
                    Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0), LinearOp.GE, 0.25),
                ),
                numRealVars = 1,
                realLower = doubleArrayOf(0.0),
                realUpper = doubleArrayOf(1.25),
            )
            val factory = object : LpEngineFactory by ProductionLpEngineFactory {
                override fun newTableauSolver(
                    model: LpModel,
                    cancellation: Cancellation,
                    iterationLimit: Int,
                    workLimit: Long,
                    trackDegeneracy: Boolean,
                    pricing: LpPricingOptions,
                ): TableauCutSolver {
                    val delegate = ProductionLpEngineFactory.newTableauSolver(
                        model,
                        cancellation,
                        iterationLimit,
                        workLimit,
                        trackDegeneracy,
                        pricing,
                    )
                    return object : TableauCutSolver by delegate {
                        override fun solve(warm: Basis?): FloatLpResult? = delegate.solve(warm)?.also {
                            it.duals.fill(dual)
                        }
                    }
                }
            }
            val sink = SolveStatsSink(backend = "continuous-nonfinite")
            LpEngine(
                problem,
                LinearObjective(realCoefficients = doubleArrayOf(0.5)),
                LpParams(lpPlan = LpPlan(bounding = true)),
                sink,
                LpSolveContext(factory),
            ).use { engine ->
                assertTrue(engine.rootLpRelaxationBound(assertNotNull(engine.lpRelaxer), emptyList()).isNaN())
            }
        }
    }

    @Test
    fun `cancelled continuous bounding should neither prune nor publish a root bound`() {
        val problem = Problem(
            0,
            1,
            arrayOf(IntDomain(0, 1)),
            emptyArray(),
            numRealVars = 1,
            realLower = doubleArrayOf(0.25),
            realUpper = doubleArrayOf(1.25),
        )
        val objective = LinearObjective(
            intCoefficients = longArrayOf(1L),
            constant = 3L,
            realCoefficients = doubleArrayOf(0.5),
        )
        val sink = SolveStatsSink(backend = "continuous-cancelled")
        val cancelled = Cancellation { true }
        LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true)), sink).use { engine ->
            val relaxer = assertNotNull(engine.lpRelaxer)

            assertFalse(
                engine.sparseSafePrune(
                    relaxer,
                    PropagationSession(problem),
                    3.0,
                    sink,
                    cancelled,
                    -1,
                    true,
                ).prune,
            )
            assertTrue(engine.rootLpRelaxationBound(relaxer, emptyList(), cancelled).isNaN())
        }
    }

    @Test
    fun `finite optimization should publish a continuous source root bound in either direction`() {
        for ((sign, minimum) in listOf(1L to 3.125, -1L to -4.625)) {
            val problem = Problem(
                0,
                1,
                arrayOf(IntDomain(0, 1)),
                emptyArray(),
                numRealVars = 1,
                realLower = doubleArrayOf(0.25),
                realUpper = doubleArrayOf(1.25),
            )
            val objective = LinearObjective(
                intCoefficients = longArrayOf(sign),
                constant = 3L * sign,
                realCoefficients = doubleArrayOf(0.5 * sign),
            )
            val published = ArrayList<Double>()

            val result = BacktrackSolver(problem.bake()).minimize(
                objective,
                BacktrackParams(
                    randomSeed = 1L,
                    lpPlan = LpPlan(bounding = true),
                    objectiveLowerBoundSink = { published.add(it) },
                ),
            )

            assertEquals(minimum, published.firstOrNull())
            assertEquals(minimum, result.objectiveValue)
        }
    }

    @Test
    fun `a continuous root solver error should close its owner and remain an error`() {
        val problem = Problem(
            0,
            0,
            emptyArray(),
            emptyArray(),
            numRealVars = 1,
            realLower = doubleArrayOf(0.25),
            realUpper = doubleArrayOf(1.25),
        )
        val failure = IllegalStateException("root solve")
        var closes = 0
        val factory = object : LpEngineFactory by ProductionLpEngineFactory {
            override fun newTableauSolver(
                model: LpModel,
                cancellation: Cancellation,
                iterationLimit: Int,
                workLimit: Long,
                trackDegeneracy: Boolean,
                pricing: LpPricingOptions,
            ): TableauCutSolver {
                val delegate = ProductionLpEngineFactory.newTableauSolver(
                    model,
                    cancellation,
                    iterationLimit,
                    workLimit,
                    trackDegeneracy,
                    pricing,
                )
                return object : TableauCutSolver by delegate {
                    override fun solve(warm: Basis?): FloatLpResult? = throw failure
                    override fun close() {
                        closes++
                        delegate.close()
                    }
                }
            }
        }
        val sink = SolveStatsSink(backend = "continuous-error")
        LpEngine(
            problem,
            LinearObjective(realCoefficients = doubleArrayOf(0.5)),
            LpParams(lpPlan = LpPlan(bounding = true)),
            sink,
            LpSolveContext(factory),
        ).use { engine ->
            val caught = assertFailsWith<IllegalStateException> {
                engine.rootLpRelaxationBound(assertNotNull(engine.lpRelaxer), emptyList())
            }

            assertSame(failure, caught)
            assertEquals(1, closes)
        }
    }
}
