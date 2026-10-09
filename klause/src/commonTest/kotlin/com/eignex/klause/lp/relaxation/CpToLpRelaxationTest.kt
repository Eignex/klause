package com.eignex.klause.lp.relaxation

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.engine.FloatLpStatus
import com.eignex.klause.lp.engine.LpCertificationPolicy
import com.eignex.klause.lp.engine.LpSolution
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.lp.engine.LpVerdict
import com.eignex.klause.lp.engine.solveLp
import com.eignex.klause.model.PbOp
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CpToLpRelaxationTest {

    private val eps = 1e-9

    @Test
    fun `rebinding preserves the source factor of every row`() {
        val problem = Problem(
            0, 1, arrayOf(IntDomain(0, 10)),
            arrayOf<Factor>(Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 3)),
        )
        val relaxation = CpToLpRelaxation(problem, null).build(PropagationSession(problem))

        val rebound = relaxation.withModel(relaxation.model)

        assertContentEquals(intArrayOf(0), rebound.rowFactorIds)
    }

    /** Solve the relaxation of [problem] under [objective] and return (solution, relaxation). */
    private fun solve(problem: Problem, objective: LinearObjective?): Pair<LpSolution, LpRelaxation> {
        val relaxation = CpToLpRelaxation(problem, objective).build(PropagationSession(problem))
        return solveLp(relaxation.model) to relaxation
    }

    /** LP column standing for integer variable [v], or -1. */
    private fun intCol(r: LpRelaxation, v: Int): Int {
        for (c in r.colVarId.indices) if (!r.colIsBool[c] && r.colVarId[c] == v) return c
        return -1
    }

    @Test
    fun `linear constraints bound the objective`() {
        // min x0  s.t.  x0 + x1 >= 5,  x1 <= 3,  x0,x1 in [0,10].  -> x0 >= 2.
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 10), IntDomain(0, 10)),
            factors = arrayOf<Factor>(
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 5),
                Linear(intArrayOf(1), intArrayOf(1), LinearOp.LE, 3),
            ),
        )
        val (sol, r) = solve(p, LinearObjective(intCoefficients = longArrayOf(1L, 0L)))

        assertEquals(FloatLpStatus.OPTIMAL, sol.status)
        assertEquals(2.0, sol.objectiveValue, eps)
        assertEquals(2.0, sol.primal(intCol(r, 0)), eps)
    }

    @Test
    fun `objective constant is carried separately`() {
        val p = Problem(0, 1, arrayOf(IntDomain(0, 10)), arrayOf<Factor>())
        val (sol, r) = solve(p, LinearObjective(intCoefficients = longArrayOf(1L), constant = 100L))

        assertEquals(FloatLpStatus.OPTIMAL, sol.status)
        assertEquals(100L, r.objectiveConstant)
        // LP objective (cost·x) is 0 at x0 = 0; the true bound is that plus the carried constant.
        assertEquals(0.0, sol.objectiveValue, eps)
        assertEquals(100.0, sol.objectiveValue + r.objectiveConstant, eps)
    }

    @Test
    fun `free reification does not over-constrain`() {
        // aux(b0) <-> (x0 >= 8), b0 free, min x0.  The relaxation must still allow x0 = 0 (b0 = 0),
        // i.e. the big-M indicator must not force x0 >= 8 when the aux is unfixed.
        val p = Problem(
            numBoolVars = 1,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 10)),
            factors = arrayOf<Factor>(
                ReifiedLinear(
                    auxBoolVar = 0,
                    coeffs = intArrayOf(1),
                    vars = intArrayOf(0),
                    op = LinearOp.GE,
                    bound = 8,
                ),
            ),
        )
        val (sol, r) = solve(p, LinearObjective(intCoefficients = longArrayOf(1L)))

        assertEquals(FloatLpStatus.OPTIMAL, sol.status)
        assertEquals(0.0, sol.primal(intCol(r, 0)), eps)
    }

    @Test
    fun `pinned reification enforces its linear constraint`() {
        // Unit clause pins b0 = true; aux(b0) <-> (x0 >= 8) then forces x0 >= 8. min x0 -> 8.
        val p = Problem(
            numBoolVars = 1,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 10)),
            factors = arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, true))),
                ReifiedLinear(
                    auxBoolVar = 0,
                    coeffs = intArrayOf(1),
                    vars = intArrayOf(0),
                    op = LinearOp.GE,
                    bound = 8,
                ),
            ),
        )
        val (sol, r) = solve(p, LinearObjective(intCoefficients = longArrayOf(1L)))

        assertEquals(FloatLpStatus.OPTIMAL, sol.status)
        assertEquals(8.0, sol.primal(intCol(r, 0)), eps)
    }

    @Test
    fun `cardinality and clause rows constrain bool fan-in`() {
        // ExactlyTwo over 3 bools, each with objective weight 1 -> the LP objective is >= 2.
        val p = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = arrayOf(),
            factors = arrayOf<Factor>(
                Cardinality(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true)), min = 2, max = 2),
            ),
        )
        val (sol, _) = solve(p, LinearObjective(boolWeights = longArrayOf(1L, 1L, 1L)))

        assertEquals(FloatLpStatus.OPTIMAL, sol.status)
        assertEquals(2.0, sol.objectiveValue, eps)
    }

    @Test
    fun `pseudo boolean upper bound row`() {
        // 2·b0 + 3·b1 <= 4, maximize b0 + b1 (encoded as min -(b0+b1)).  Cheapest unit is b0, so the
        // LP picks b0 = 1, b1 = 2/3 -> obj 5/3.
        val p = Problem(
            numBoolVars = 2,
            numIntVars = 0,
            intDomains = arrayOf(),
            factors = arrayOf<Factor>(
                PseudoBoolean(longArrayOf(2, 3), intArrayOf(Lit.make(0, true), Lit.make(1, true)), PbOp.LE, 4L),
            ),
        )
        val (sol, _) = solve(p, LinearObjective(boolWeights = longArrayOf(-1L, -1L)))

        assertEquals(FloatLpStatus.OPTIMAL, sol.status)
        // min -(b0+b1) = -5/3  ->  max b0+b1 = 5/3.
        assertEquals(-5.0 / 3.0, sol.objectiveValue, eps)
    }

    @Test
    fun `column metadata maps back to cp variables`() {
        val p = Problem(
            numBoolVars = 1,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 10)),
            factors = arrayOf<Factor>(
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 7),
                Clause(intArrayOf(Lit.make(0, true))),
            ),
        )
        val (_, r) = solve(p, null)

        val ci = intCol(r, 0)
        assertTrue(ci >= 0, "int var 0 has a column")
        assertEquals(0, r.colVarId[ci])
        // The bool column exists and is tagged as bool with var id 0.
        var boolCol = -1
        for (c in r.colVarId.indices) if (r.colIsBool[c] && r.colVarId[c] == 0) boolCol = c
        assertTrue(boolCol >= 0, "bool var 0 has a column")
    }

    @Test
    fun `unrecognized factors are skipped soundly`() {
        // No LP-emittable factor and no objective: a trivially feasible, unconstrained relaxation.
        val p = Problem(0, 1, arrayOf(IntDomain(0, 10)), arrayOf<Factor>())
        val (sol, _) = solve(p, null)
        assertEquals(FloatLpStatus.OPTIMAL, sol.status)
    }

    @Test
    fun `a float leaf optimum stands under tolerance semantics only when its check accepts it`() {
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
            if (verdict == LpVerdict.TOLERANCE_OPTIMUM) assertEquals(1.0 / 3.0, result.reals.single(), eps)
        }
    }

    @Test
    fun `a leaf gives a real variable no row touches a value inside its bounds`() {
        // r0 >= 1 is the only row; r1 in [2, 3] sits in none, so the relaxation carries no column for it.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Linear(longArrayOf(), intArrayOf(), doubleArrayOf(1.0), intArrayOf(0), LinearOp.GE, 1L),
            ),
            numRealVars = 2,
            realLower = doubleArrayOf(0.0, 2.0),
            realUpper = doubleArrayOf(5.0, 3.0),
        )

        val result = leafRealFeasibility(problem, null, Sample(booleanArrayOf(), longArrayOf()))

        assertEquals(listOf(2.0, 2.0), listOf(result.reals[1], result.exactReals!![1].toDouble()))
    }

    @Test
    fun `a float leaf optimum is refused when a small reduced cost spans an unbounded column`() {
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

        assertEquals(LpVerdict.INDETERMINATE, result.verdict, "${result.reals.toList()}")
    }

    @Test
    fun `a float leaf optimum is refused when a constant cancels the objective's size`() {
        // min x + 2y − 2e8 with 1.00000002x + 2y ≥ 2e8, the −2e8 either a constant or a fixed integer's cost: a
        // near-tied ratio test can stop at y = 1e8, objective 0, whose remaining improvement is small beside the
        // terms yet the whole of the true optimum near −4.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 1)),
            factors = arrayOf<Factor>(
                Linear(
                    longArrayOf(),
                    intArrayOf(),
                    doubleArrayOf(1.00000002, 2.0),
                    intArrayOf(0, 1),
                    LinearOp.GE,
                    200_000_000L,
                ),
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 1),
            ),
            numRealVars = 2,
            realLower = doubleArrayOf(0.0, 0.0),
            realUpper = doubleArrayOf(3e8, 3e8),
        )
        val vetoed = LpSolveContext(certificationPolicy = LpCertificationPolicy { _, _ -> false })
        val cases = listOf(
            LinearObjective(constant = -200_000_000L, realCoefficients = doubleArrayOf(1.0, 2.0)) to 0L,
            LinearObjective(
                intCoefficients = longArrayOf(-200_000_000L),
                realCoefficients = doubleArrayOf(1.0, 2.0),
            ) to 1L,
        )
        for ((objective, fixed) in cases) {
            val result = leafRealFeasibility(
                problem,
                objective,
                Sample(booleanArrayOf(), longArrayOf(fixed)),
                context = vetoed,
                toleranceCheck = { true },
            )

            assertEquals(LpVerdict.INDETERMINATE, result.verdict, "$fixed: ${result.reals.toList()}")
        }
    }

    @Test
    fun `cleanup pivots carry a float leaf past a cancelling reduced cost to its optimum`() {
        // min 1e4·y + c·x with y + x ≥ 1, x ≤ 1e9 as a row, y free, c = 1e4 − 2⁻²⁶: pricing x cancels to a reduced
        // cost below the engine's tolerance, yet the row lets x reach 1e9 and gain about 15.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Linear(longArrayOf(), intArrayOf(), doubleArrayOf(1.0, 1.0), intArrayOf(0, 1), LinearOp.GE, 1L),
                Linear(longArrayOf(), intArrayOf(), doubleArrayOf(1.0), intArrayOf(1), LinearOp.LE, 1_000_000_000L),
            ),
            numRealVars = 2,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY, 0.0),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY),
        )
        val vetoed = LpSolveContext(certificationPolicy = LpCertificationPolicy { _, _ -> false })

        val result = leafRealFeasibility(
            problem,
            LinearObjective(realCoefficients = doubleArrayOf(1e4, 1e4 - 1.0 / (1 shl 26))),
            Sample(booleanArrayOf(), longArrayOf()),
            context = vetoed,
            toleranceCheck = { true },
        )

        assertEquals(LpVerdict.TOLERANCE_OPTIMUM, result.verdict)
        assertEquals(1e9, result.reals[1])
    }

    @Test
    fun `exact duals carry a float leaf past a degenerate reduced cost on an unbounded column`() {
        // min x0 + x1 with 5·x0 + 5·x1 ≥ 1 and neither column bounded above: at the dual 1/5 both price exactly zero,
        // but fl(1/5) lies above it, leaving the nonbasic column a wrong-signed reduced cost that nothing bounds.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Linear(longArrayOf(), intArrayOf(), doubleArrayOf(5.0, 5.0), intArrayOf(0, 1), LinearOp.GE, 1L),
            ),
            numRealVars = 2,
            realLower = doubleArrayOf(0.0, 0.0),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY),
        )
        val vetoed = LpSolveContext(certificationPolicy = LpCertificationPolicy { _, _ -> false })

        val result = leafRealFeasibility(
            problem,
            LinearObjective(realCoefficients = doubleArrayOf(1.0, 1.0)),
            Sample(booleanArrayOf(), longArrayOf()),
            context = vetoed,
            toleranceCheck = { true },
        )

        assertEquals(LpVerdict.TOLERANCE_OPTIMUM, result.verdict)
        assertEquals(0.2, result.reals.sum(), eps)
    }

    @Test
    fun `a float leaf optimum is refused when rounding understates a row's implied range`() {
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
                if (it == 0) {
                    1e5
                } else if (it <= 2) {
                    c
                } else {
                    0.0
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

        assertEquals(LpVerdict.INDETERMINATE, result.verdict, "${result.reals.take(3)}")
    }

    @Test
    fun `cleanup pivots carry a float leaf past a reduced cost hidden in rounding to its optimum`() {
        // min 1.5e8·x0 − (1.5e8 + 9e-8)·x1 with x0 ≥ x1 and x1 ≤ 1e12 as a row: x1 prices within the rounding of its
        // 1.5e8 terms, yet each unit of x1 gains about 9e-8, some 9e4 in all.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Linear(longArrayOf(), intArrayOf(), doubleArrayOf(1.0, -1.0), intArrayOf(0, 1), LinearOp.GE, 0L),
                Linear(longArrayOf(), intArrayOf(), doubleArrayOf(1.0), intArrayOf(1), LinearOp.LE, 1_000_000_000_000L),
            ),
            numRealVars = 2,
            realLower = doubleArrayOf(0.0, 0.0),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY),
        )
        val vetoed = LpSolveContext(certificationPolicy = LpCertificationPolicy { _, _ -> false })

        val result = leafRealFeasibility(
            problem,
            LinearObjective(realCoefficients = doubleArrayOf(150_000_000.0, -150_000_000.00000009)),
            Sample(booleanArrayOf(), longArrayOf()),
            context = vetoed,
            toleranceCheck = { true },
        )

        assertEquals(LpVerdict.TOLERANCE_OPTIMUM, result.verdict)
        assertEquals(1e12, result.reals[1])
    }

    @Test
    fun `a float leaf optimum is refused when a large dual meets a rounded row`() {
        // min 3e5·x0 − c·x1 with 3·x0 − x1 + w ≥ 0 and x1 ≤ 5e6 as rows: x0 = fl(5e6 / 3) leaves the tight row a
        // residual far below the source tolerance, yet its dual of 1e5 turns it into a gap beside an objective near
        // −4.77. A column bounded at 1e-295 in the row, or none, must not hide the residual.
        val vetoed = LpSolveContext(certificationPolicy = LpCertificationPolicy { _, _ -> false })
        for (tinyUpper in listOf(0.0, 1e-295)) {
            val problem = Problem(
                numBoolVars = 0,
                numIntVars = 0,
                intDomains = emptyArray(),
                factors = arrayOf<Factor>(
                    Linear(
                        longArrayOf(),
                        intArrayOf(),
                        doubleArrayOf(3.0, -1.0, 1.0),
                        intArrayOf(0, 1, 2),
                        LinearOp.GE,
                        0L,
                    ),
                    Linear(longArrayOf(), intArrayOf(), doubleArrayOf(1.0), intArrayOf(1), LinearOp.LE, 5_000_000L),
                ),
                numRealVars = 3,
                realLower = doubleArrayOf(0.0, 0.0, 0.0),
                realUpper = doubleArrayOf(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, tinyUpper),
            )

            val result = leafRealFeasibility(
                problem,
                LinearObjective(realCoefficients = doubleArrayOf(300_000.0, -100_000.00000095367431640625, 0.0)),
                Sample(booleanArrayOf(), longArrayOf()),
                context = vetoed,
                toleranceCheck = { true },
            )

            assertEquals(LpVerdict.INDETERMINATE, result.verdict, "$tinyUpper: ${result.reals.toList()}")
        }
    }
}
