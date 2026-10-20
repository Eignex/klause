package com.eignex.klause.backtrack

import com.eignex.klause.backtrack.selector.IndomainMin
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.RealProduct
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.lp.bounding.LpPlan
import com.eignex.klause.lp.engine.LpCertificationPolicy
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.lp.engine.LpVerdict
import com.eignex.klause.lp.relaxation.leafRealFeasibility
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BacktrackSolverRealTest {
    private fun problem(numInt: Int, intDoms: Array<IntDomain>, realLo: Double, realHi: Double, row: Linear) = Problem(
        numBoolVars = 0,
        numIntVars = numInt,
        intDomains = intDoms,
        factors = arrayOf<Factor>(row),
        numRealVars = 1,
        realLower = doubleArrayOf(realLo),
        realUpper = doubleArrayOf(realHi),
    )

    @Test
    fun `a feasible residual real LP should be certified SAT`() {
        val row = Linear(longArrayOf(1L), intArrayOf(0), doubleArrayOf(1.0), intArrayOf(0), LinearOp.LE, 5L)
        val p = problem(1, arrayOf(IntDomain(0, 3)), 0.0, 10.0, row)
        assertIs<SolveResult.Sat>(BacktrackSolver(p.bake()).solve(BacktrackParams()))
    }

    @Test
    fun `a rational continuous vertex should be certified SAT`() {
        val row = Linear(longArrayOf(), intArrayOf(), doubleArrayOf(2.0), intArrayOf(0), LinearOp.EQ, 3L)
        val p = problem(0, emptyArray(), 0.0, 10.0, row)
        assertIs<SolveResult.Sat>(BacktrackSolver(p.bake()).solve(BacktrackParams()))
    }

    @Test
    fun `a rational leaf witness should be read back in real source coordinates`() {
        val row = Linear(longArrayOf(), intArrayOf(), doubleArrayOf(1.0 / 3.0), intArrayOf(0), LinearOp.EQ, 3L)
        val p = problem(0, emptyArray(), 7.0, 10.0, row)

        val result = leafRealFeasibility(p, objective = null, sample = Sample(booleanArrayOf(), longArrayOf()))

        assertEquals(LpVerdict.ATTAINED_OPTIMUM, result.verdict)
        assertEquals(9.0, result.reals[0], absoluteTolerance = 1e-9)
    }

    @Test
    fun `an infeasible system over a lower-unbounded real column should be unsat`() {
        val geSix = Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0), LinearOp.GE, 6.0)
        val leFive = Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0), LinearOp.LE, 5.0)
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(geSix, leFive),
            numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY),
        )
        assertIs<SolveResult.Unsat>(BacktrackSolver(p.bake()).solve(BacktrackParams()))
    }

    @Test
    fun `a negative witness on a lower-unbounded real column should read back through the split`() {
        val leMinusSix = Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0), LinearOp.LE, -6.0)
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(leMinusSix),
            numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY),
        )
        val r = assertIs<SolveResult.Sat>(BacktrackSolver(p.bake()).solve(BacktrackParams()))
        assertEquals(true, r.assignment.reals[0] <= -6.0 + 1e-9)
    }

    @Test
    fun `a finite upper bound on a lower-unbounded real column should be enforced`() {
        val geSeven = Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0), LinearOp.GE, 7.0)
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(geSeven),
            numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(5.0),
        )
        assertIs<SolveResult.Unsat>(BacktrackSolver(p.bake()).solve(BacktrackParams()))
    }

    @Test
    fun `an infeasible residual real LP should be certified UNSAT`() {
        val row = Linear(longArrayOf(), intArrayOf(), doubleArrayOf(1.0), intArrayOf(0), LinearOp.GE, 5L)
        val p = problem(0, emptyArray(), 0.0, 1.0, row)
        assertIs<SolveResult.Unsat>(BacktrackSolver(p.bake()).solve(BacktrackParams()))
    }

    @Test
    fun `a coefficient outside every scaling ladder should be decided by the rational fallback`() {
        // 1/3 fits neither the dyadic nor the decimal ladder, so only the rational fallback decides it.
        val row = Linear(longArrayOf(), intArrayOf(), doubleArrayOf(1.0 / 3.0), intArrayOf(0), LinearOp.GE, 1L)
        val p = problem(0, emptyArray(), 0.0, 2.0, row)
        assertIs<SolveResult.Unsat>(BacktrackSolver(p.bake()).solve(BacktrackParams()))
    }

    @Test
    fun `a tripped deadline should degrade the leaf verdict to indeterminate`() {
        val row = Linear(longArrayOf(), intArrayOf(), doubleArrayOf(2.0), intArrayOf(0), LinearOp.EQ, 3L)
        val p = problem(0, emptyArray(), 0.0, 10.0, row)
        val res = leafRealFeasibility(
            p,
            objective = null,
            sample = Sample(booleanArrayOf(), longArrayOf()),
            cancellation = Cancellation { true },
        )
        assertEquals(LpVerdict.INDETERMINATE, res.verdict)
    }

    @Test
    fun `a leaf no theory could decide should report unknown rather than unsat`() {
        val rows = Array<Factor>(1001) { row ->
            Linear(longArrayOf(), intArrayOf(), doubleArrayOf(1.0, (row + 1).toDouble()), intArrayOf(0, 1), LinearOp.LE, 5L)
        }
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 1)),
            factors = rows,
            numRealVars = 2,
            realLower = doubleArrayOf(0.0, 0.0),
            realUpper = doubleArrayOf(10.0, 10.0),
        )
        val result = BacktrackSolver(p.bake()).solve(BacktrackParams(randomSeed = 1L))
        val unknown = assertIs<SolveResult.Unknown>(result)
        assertEquals(TerminationReason.Unsupported, unknown.reason)
    }

    @Test
    fun `the continuous leaf verdict should not depend on LP bounding`() {
        val feasible = Linear(longArrayOf(1L), intArrayOf(0), doubleArrayOf(1.0), intArrayOf(0), LinearOp.LE, 5L)
        val infeasible = Linear(longArrayOf(), intArrayOf(), doubleArrayOf(1.0), intArrayOf(0), LinearOp.GE, 5L)
        for (bounding in booleanArrayOf(false, true)) {
            val params = BacktrackParams(lpPlan = LpPlan(bounding = bounding))
            assertIs<SolveResult.Sat>(
                BacktrackSolver(problem(1, arrayOf(IntDomain(0, 3)), 0.0, 10.0, feasible).bake()).solve(params),
            )
            assertIs<SolveResult.Unsat>(
                BacktrackSolver(problem(0, emptyArray(), 0.0, 1.0, infeasible).bake()).solve(params),
            )
        }
    }

    @Test
    fun `an objective no row bounds below should end the minimize unbounded with its ray`() {
        val row = Linear(longArrayOf(1L), intArrayOf(0), doubleArrayOf(-1.0), intArrayOf(0), LinearOp.LE, 1L)
        val p = problem(1, arrayOf(IntDomain(0, 3)), 0.0, Double.POSITIVE_INFINITY, row)

        val result = BacktrackSolver(p.bake()).minimize(LinearObjective(realCoefficients = doubleArrayOf(-1.0)))

        val unbounded = assertIs<MinimizeResult.Unbounded>(result)
        assertTrue(unbounded.direction.single().signum() > 0)
        assertTrue(unbounded.sample.ints[0] - unbounded.sample.reals[0] <= 1.0)
    }

    @Test
    fun `a leaf only tolerance accepts should complete the minimize as optimal`() {
        // 3 r = 1 has the exact optimum 1/3, which the vetoing policy keeps every exact certifier from proving.
        val row = Linear(longArrayOf(), intArrayOf(), doubleArrayOf(3.0), intArrayOf(0), LinearOp.EQ, 1L)
        val p = problem(0, emptyArray(), 0.0, 2.0, row).bake()
        val vetoed = LpSolveContext(certificationPolicy = LpCertificationPolicy { _, _ -> false })
        val objective = LinearObjective(realCoefficients = doubleArrayOf(1.0))
        val cases = listOf<Pair<((Sample) -> Boolean)?, Boolean>>(null to false, { _: Sample -> true } to true)
        for ((check, optimal) in cases) {
            val result = BacktrackSolver(p, vetoed).minimize(objective, BacktrackParams(toleranceCheck = check))

            assertEquals(optimal, result is MinimizeResult.Optimal, "tolerance check present: ${check != null}")
        }
    }

    @Test
    fun `local search should decline a model with LP-only continuous variables`() {
        val row = Linear(longArrayOf(1L), intArrayOf(0), doubleArrayOf(1.0), intArrayOf(0), LinearOp.LE, 5L)
        val p = problem(1, arrayOf(IntDomain(0, 3)), 0.0, 10.0, row)
        val r = assertIs<SolveResult.Unknown>(LocalSearchSolver(p.bake()).solve())
        assertEquals(TerminationReason.Unsupported, r.reason)
    }

    @Test
    fun `fractional leaf improvements should retain exact ordering below floating precision`() {
        val row = Linear(longArrayOf(-1), intArrayOf(0), doubleArrayOf(3.0), intArrayOf(0), LinearOp.EQ, 0)
        val source = problem(1, arrayOf(IntDomain(0, 2)), 0.0, 1.0, row).bake()
        val objective = LinearObjective(constant = 1L shl 62, realCoefficients = doubleArrayOf(-1.0))

        val result = BacktrackSolver(source).minimize(
            objective,
            BacktrackParams(randomSeed = 17, valueSelector = IndomainMin),
        )

        val sample = assertIs<MinimizeResult.Optimal>(result).sample
        assertEquals(2L, sample.intValue(0))
        assertEquals("2/3", sample.exactReals?.single().toString())
    }

    // Reals: index 0 = operand, index 1 = result.
    private fun problem(intDoms: Array<IntDomain>, opLo: Double, opHi: Double, vararg factors: Factor) = Problem(
        numBoolVars = 0,
        numIntVars = intDoms.size,
        intDomains = intDoms,
        factors = arrayOf(*factors),
        numRealVars = 2,
        realLower = doubleArrayOf(opLo, 0.0),
        realUpper = doubleArrayOf(opHi, 100.0),
    )

    private fun resultEquals(value: Long): Linear =
        Linear(longArrayOf(), intArrayOf(), doubleArrayOf(1.0), intArrayOf(1), LinearOp.EQ, value)

    @Test
    fun `a fixed integer operand should yield an exact product certified SAT`() {
        val p = problem(arrayOf(IntDomain(2, 2)), 0.0, 5.0, RealProduct(0, 0, 1, 0.0, 5.0), resultEquals(6))
        assertIs<SolveResult.Sat>(BacktrackSolver(p.bake()).solve(BacktrackParams()))
    }

    @Test
    fun `an exact product with no feasible real completion should be UNSAT`() {
        val p = problem(arrayOf(IntDomain(2, 2)), 0.0, 5.0, RealProduct(0, 0, 1, 0.0, 5.0), resultEquals(12))
        assertIs<SolveResult.Unsat>(BacktrackSolver(p.bake()).solve(BacktrackParams()))
    }

    @Test
    fun `an integer operand should be enumerated with every leaf product checked`() {
        val p = problem(arrayOf(IntDomain(1, 3)), 2.0, 2.0, RealProduct(0, 0, 1, 2.0, 2.0), resultEquals(5))
        assertIs<SolveResult.Unsat>(BacktrackSolver(p.bake()).solve(BacktrackParams()))
    }

    @Test
    fun `a feasible bilinear product across a branch should be SAT`() {
        val p = problem(arrayOf(IntDomain(1, 3)), 0.0, 5.0, RealProduct(0, 0, 1, 0.0, 5.0), resultEquals(9))
        assertIs<SolveResult.Sat>(BacktrackSolver(p.bake()).solve(BacktrackParams()))
    }
}
