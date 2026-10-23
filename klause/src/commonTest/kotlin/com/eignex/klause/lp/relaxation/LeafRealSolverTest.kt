package com.eignex.klause.lp.relaxation

import com.eignex.klause.factor.arithmetic.ContinuousProduct
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.Power
import com.eignex.klause.factor.arithmetic.RealProduct
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.lp.engine.LpCertificationPolicy
import com.eignex.klause.lp.engine.LpEngineFactory
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.LpPricingOptions
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.lp.engine.LpSolveStep
import com.eignex.klause.lp.engine.LpVerdict
import com.eignex.klause.lp.engine.PersistentLpSolver
import com.eignex.klause.lp.engine.ProductionLpEngineFactory
import com.eignex.klause.lp.engine.solveAndCertify
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LeafRealSolverTest {
    @Test
    fun `releasing pending completion should permit fresh pins without stale coordinates`() {
        val problem = Problem(
            0, 1, arrayOf(IntDomain(0L, 1L)),
            arrayOf<Factor>(Linear(intArrayOf(0), doubleArrayOf(1.0), intArrayOf(0), doubleArrayOf(2.0), LinearOp.EQ, 2.0)),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(1.0),
        )
        val objective = LinearObjective(realCoefficients = doubleArrayOf(1.0))
        val first = Sample(booleanArrayOf(), longArrayOf(0L))
        val next = Sample(booleanArrayOf(), longArrayOf(1L))
        LeafRealSolver(problem, objective).use { owner ->
            val pending = owner.openSolve(first)
            assertIs<LpSolveStep.Paused>(pending.advance(shouldPause = { true }))
            assertFailsWith<IllegalStateException> { owner.solve(next) }

            owner.releaseSolvers()
            val result = owner.solve(next)

            assertEquals(LpVerdict.ATTAINED_OPTIMUM, result.verdict)
            assertEquals(0.5, result.reals.single())
            assertEquals(BigFraction.ofDouble(0.5), result.exactReals?.single())
            assertFailsWith<IllegalStateException> { pending.advance() }
        }
    }

    @Test
    fun `leaf completion should enforce affine powers before accepting tolerance`() {
        for (exponent in listOf(0, 1)) {
            val factor = Power(
                Power.Coordinate.Real(SourceRealId(0)),
                exponent,
                Power.Coordinate.Real(SourceRealId(1)),
            )
            val problem = Problem(
                0,
                0,
                emptyArray(),
                arrayOf(factor),
                numRealVars = 2,
                realLower = doubleArrayOf(2.0, 3.0),
                realUpper = doubleArrayOf(2.0, 3.0),
            )
            val sample = Sample(booleanArrayOf(), longArrayOf())

            val fresh = leafRealFeasibility(problem, null, sample, toleranceCheck = { true })
            LeafRealSolver(problem, null).use { owner ->
                val retained = owner.solve(sample, toleranceCheck = { true })

                assertEquals(LpVerdict.INFEASIBLE, fresh.verdict)
                assertEquals(LpVerdict.INFEASIBLE, retained.verdict)
            }
        }
    }

    @Test
    fun `leaf completion should stay indeterminate on a nonlinear real power`() {
        val factor = Power(
            Power.Coordinate.Real(SourceRealId(0)),
            2,
            Power.Coordinate.Real(SourceRealId(1)),
        )
        val problem = Problem(
            0,
            0,
            emptyArray(),
            arrayOf(factor),
            numRealVars = 2,
            realLower = doubleArrayOf(1.0, 2.0),
            realUpper = doubleArrayOf(1.0, 2.0),
        )
        val sample = Sample(booleanArrayOf(), longArrayOf())

        val fresh = leafRealFeasibility(problem, null, sample, toleranceCheck = { true })
        LeafRealSolver(problem, null).use { owner ->
            val retained = owner.solve(sample, toleranceCheck = { true })

            assertEquals(LpVerdict.INDETERMINATE, fresh.verdict)
            assertEquals(LpVerdict.INDETERMINATE, retained.verdict)
        }
    }

    @Test
    fun `leaf feasibility should stay indeterminate on a continuous square`() {
        val problem = Problem(
            0,
            0,
            emptyArray(),
            arrayOf(ContinuousProduct(0, 0, 1)),
            numRealVars = 2,
            realLower = doubleArrayOf(1.0, 2.0),
            realUpper = doubleArrayOf(1.0, 2.0),
        )
        val sample = Sample(booleanArrayOf(), longArrayOf())

        val fresh = leafRealFeasibility(problem, null, sample)
        LeafRealSolver(problem, null).use { owner ->
            val retained = owner.solve(sample, toleranceCheck = { true })

            assertEquals(LpVerdict.INDETERMINATE, fresh.verdict)
            assertEquals(LpVerdict.INDETERMINATE, retained.verdict)
        }
    }

    @Test
    fun `mixed leaf with an unrepresentable integer cost should stay indeterminate`() {
        val problem = Problem(
            0,
            1,
            arrayOf(IntDomain(0L, 1L)),
            emptyArray(),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(1.0),
        )
        val objective = LinearObjective(
            intCoefficients = longArrayOf(9007199254740993L),
            realCoefficients = doubleArrayOf(1.0),
        )
        val sample = Sample(booleanArrayOf(), longArrayOf(0L))

        LeafRealSolver(problem, objective).use { owner ->
            val result = owner.solve(sample)

            assertEquals(LpVerdict.INDETERMINATE, result.verdict)
        }
    }

    @Test
    fun `retained solver should match fresh exact refutations on fractional rows`() {
        val problem = Problem(
            0,
            0,
            emptyArray(),
            arrayOf(
                Linear(
                    intArrayOf(),
                    doubleArrayOf(),
                    intArrayOf(0),
                    doubleArrayOf(0.1),
                    LinearOp.EQ,
                    0.010000000000000002,
                ),
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(0.1),
            realUpper = doubleArrayOf(0.1),
        )
        var captured: LpModel? = null
        val factory = object : LpEngineFactory by ProductionLpEngineFactory {
            override fun newPersistentSolver(
                model: LpModel,
                cancellation: Cancellation,
                refactorUpdateLimit: Int,
                iterationLimit: Int,
                workLimit: Long,
                trackDegeneracy: Boolean,
                pricing: LpPricingOptions,
            ): PersistentLpSolver {
                if (captured == null) captured = model
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
        val sample = Sample(
            BooleanArray(problem.numBoolVars) { true },
            LongArray(problem.numIntVars) { problem.finiteIntDomain(it).min },
        )
        val fresh = leafRealFeasibility(problem, null, sample)

        LeafRealSolver(problem, null, componentSplit = false, context = LpSolveContext(factory)).use { owner ->
            val actual = owner.solve(sample)
            val reconstructed = solveAndCertify(assertNotNull(captured), componentSplit = false)

            assertEquals(LpVerdict.INFEASIBLE, fresh.verdict)
            assertEquals(fresh.verdict, reconstructed.verdict)
            assertEquals(fresh.verdict, actual.verdict)
            assertEquals(fresh.refutingFactors.toSet(), actual.refutingFactors.toSet())
        }
    }

    @Test
    fun `retained solver should match fresh verdicts as leaf product rows change`() {
        val problem = Problem(
            0,
            1,
            arrayOf(IntDomain(1, 3)),
            arrayOf(
                RealProduct(0, 0, 1, 0.0, 5.0),
                Linear(longArrayOf(), intArrayOf(), doubleArrayOf(1.0), intArrayOf(1), LinearOp.EQ, 6L),
            ),
            numRealVars = 2,
            realLower = doubleArrayOf(0.0, 0.0),
            realUpper = doubleArrayOf(5.0, 8.0),
        )
        LeafRealSolver(problem, null).use { owner ->
            for (value in listOf(1L, 3L, 2L, 1L)) {
                val sample = Sample(booleanArrayOf(), longArrayOf(value))
                val actual = owner.solve(sample)
                val fresh = leafRealFeasibility(problem, null, sample)

                assertEquals(fresh.verdict, actual.verdict)
                assertEquals(if (value == 1L) LpVerdict.INFEASIBLE else LpVerdict.ATTAINED_OPTIMUM, actual.verdict)
                if (value != 1L) {
                    assertEquals(fresh.exactReals, actual.exactReals)
                } else {
                    assertEquals(fresh.refutingFactors.toSet(), actual.refutingFactors.toSet())
                }
            }
        }
    }

    @Test
    fun `reified siblings should not reuse a boundary witness`() {
        val problem = Problem(
            1,
            0,
            emptyArray(),
            arrayOf(
                ReifiedRealLinear(
                    0,
                    intArrayOf(),
                    doubleArrayOf(),
                    intArrayOf(0),
                    doubleArrayOf(1.0),
                    LinearOp.LE,
                    0.0,
                ),
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(1.0),
        )
        val objective = LinearObjective(realCoefficients = doubleArrayOf(1.0))
        LeafRealSolver(problem, objective).use { owner ->
            for (pin in listOf(true, false, true, false)) {
                val sample = Sample(booleanArrayOf(pin), longArrayOf())
                val actual = owner.solve(sample)
                val fresh = leafRealFeasibility(problem, objective, sample)

                assertEquals(fresh.verdict, actual.verdict)
                val value = assertNotNull(actual.exactReals).single()
                if (pin) assertEquals(BigFraction.ZERO, value) else assertTrue(value > BigFraction.ZERO)
            }
        }
    }

    @Test
    fun `cancelled solve should leave the solver usable for another solve`() {
        val problem = Problem(
            0,
            1,
            arrayOf(IntDomain(0, 2)),
            arrayOf(Linear(longArrayOf(1), intArrayOf(0), doubleArrayOf(1.0), intArrayOf(0), LinearOp.EQ, 2L)),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(2.0),
        )
        LeafRealSolver(problem, null).use { owner ->
            assertEquals(
                listOf(BigFraction.ofLong(2L)),
                owner.solve(Sample(booleanArrayOf(), longArrayOf(0))).exactReals,
            )
            assertEquals(
                LpVerdict.INDETERMINATE,
                owner.solve(Sample(booleanArrayOf(), longArrayOf(2)), Cancellation { true }).verdict,
            )
            owner.releaseSolvers()
            assertEquals(listOf(BigFraction.ONE), owner.solve(Sample(booleanArrayOf(), longArrayOf(1))).exactReals)
        }
    }

    @Test
    fun `float leaf optimum should stand only when its tolerance check accepts it`() {
        // 3x = 1 with x in [0, 2], minimizing x; every exact certifier is vetoed, so only float evidence can decide.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Linear(longArrayOf(), intArrayOf(), doubleArrayOf(3.0), intArrayOf(0), LinearOp.EQ, 1L),
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(2.0),
        )
        val vetoed = LpSolveContext(certificationPolicy = LpCertificationPolicy { _, _ -> false })
        val cases = listOf<Pair<((Sample) -> Boolean)?, LpVerdict>>(
            null to LpVerdict.INDETERMINATE,
            { _: Sample -> false } to LpVerdict.INDETERMINATE,
            { _: Sample -> true } to LpVerdict.TOLERANCE_OPTIMUM,
        )
        for ((check, verdict) in cases) {
            val result = leafRealFeasibility(
                problem,
                LinearObjective(realCoefficients = doubleArrayOf(1.0)),
                Sample(booleanArrayOf(), longArrayOf()),
                context = vetoed,
                toleranceCheck = check,
            )

            assertEquals(verdict, result.verdict)
            if (verdict == LpVerdict.TOLERANCE_OPTIMUM) assertEquals(1.0 / 3.0, result.reals.single(), 1e-9)
        }
    }

    @Test
    fun `float leaf optimum should be refused when a small reduced cost spans an unbounded column`() {
        // min −9e-13·x with x ≤ 1e18 through a row and no column bound: a float optimum at x = 0 prices x below even
        // the cleanup's tolerance, yet leaves −9e5 unreached.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Linear(
                    longArrayOf(),
                    intArrayOf(),
                    doubleArrayOf(1.0),
                    intArrayOf(0),
                    LinearOp.LE,
                    1_000_000_000_000_000_000L,
                ),
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY),
        )
        val vetoed = LpSolveContext(certificationPolicy = LpCertificationPolicy { _, _ -> false })

        val result = leafRealFeasibility(
            problem,
            LinearObjective(realCoefficients = doubleArrayOf(-9e-13)),
            Sample(booleanArrayOf(), longArrayOf()),
            context = vetoed,
            toleranceCheck = { true },
        )

        assertEquals(LpVerdict.INDETERMINATE, result.verdict)
    }

    @Test
    fun `float leaf optimum should be refused when rounding understates a row implied range`() {
        // min 1e5·y + c·w + c·x − 1e5, y + w + x ≥ 1, 1e-8·x − a − Σu − b ≤ −2e-5, w ≤ 1500: in doubles the u vanish
        // beside a and b, so the row implies x ≤ −2000 below its own lower bound; that negative range must not
        // cancel w's wrong reduced cost.
        val uCount = 100
        val numReals = 5 + uCount
        val realLower = DoubleArray(numReals)
        val realUpper = DoubleArray(numReals) { 2e-7 }
        realLower[0] = Double.NEGATIVE_INFINITY
        realUpper[0] = Double.POSITIVE_INFINITY
        realUpper[1] = 1500.0
        realUpper[2] = Double.POSITIVE_INFINITY
        realUpper[3] = 4e9
        realLower[4] = -4.2e9
        realUpper[4] = -4e9
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Linear(longArrayOf(), intArrayOf(), doubleArrayOf(1.0, 1.0, 1.0), intArrayOf(0, 1, 2), LinearOp.GE, 1L),
                Linear(
                    intArrayOf(),
                    doubleArrayOf(),
                    IntArray(numReals - 2) { it + 2 },
                    DoubleArray(numReals - 2) { if (it == 0) 1e-8 else -1.0 },
                    LinearOp.LE,
                    -2e-5,
                ),
            ),
            numRealVars = numReals,
            realLower = realLower,
            realUpper = realUpper,
        )
        val c = 99999.99999991
        val objective = LinearObjective(
            constant = -100_000L,
            realCoefficients = DoubleArray(numReals) {
                when {
                    it == 0 -> 1e5
                    it <= 2 -> c
                    else -> 0.0
                }
            },
        )
        val vetoed = LpSolveContext(certificationPolicy = LpCertificationPolicy { _, _ -> false })

        val result = leafRealFeasibility(
            problem,
            objective,
            Sample(booleanArrayOf(), longArrayOf()),
            context = vetoed,
            toleranceCheck = { true },
        )

        assertEquals(LpVerdict.INDETERMINATE, result.verdict)
    }
}
