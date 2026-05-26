package com.eignex.klause.z3

import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.factor.AllEqual
import com.eignex.klause.solver.factor.AllDifferentExcept
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.factor.Member
import com.eignex.klause.solver.factor.Monotone
import com.eignex.klause.solver.factor.ValuePrecede
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Decomposition-routed factor coverage. Each case constructs a [Problem] using a
 * factor that isn't natively translated by the Z3 backend — relying on
 * [com.eignex.klause.solver.decompose.FactorDecomposer] to lower it to mid-IR before
 * translation. SAT/UNSAT verdicts are checked against the factor's semantics.
 */
class Z3DecomposeTest {

    @Test
    fun `AllEqual - sat when all xs can equal`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 5), IntDomain(0, 5), IntDomain(0, 5)),
            factors = arrayOf(AllEqual(intArrayOf(0, 1, 2))),
        )
        val r = Z3Solver(problem).solve(Z3Params())
        val sat = assertIs<SolveResult.Sat>(r)
        assertEquals(sat.assignment.ints[0], sat.assignment.ints[1])
        assertEquals(sat.assignment.ints[0], sat.assignment.ints[2])
    }

    @Test
    fun `AllEqual - unsat when domains disjoint`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 2), IntDomain(5, 7)),
            factors = arrayOf(AllEqual(intArrayOf(0, 1))),
        )
        val r = Z3Solver(problem).solve(Z3Params())
        assertIs<SolveResult.Unsat>(r)
    }

    @Test
    fun `AllDifferentExcept - empty except acts like AllDifferent`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2)),
            factors = arrayOf(AllDifferentExcept(intArrayOf(0, 1, 2), intArrayOf())),
        )
        val r = Z3Solver(problem).solve(Z3Params())
        val sat = assertIs<SolveResult.Sat>(r)
        val vals = sat.assignment.ints.toSet()
        assertEquals(3, vals.size, "expected pairwise-distinct values")
    }

    @Test
    fun `Monotone - strictly increasing chain`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 9), IntDomain(0, 9), IntDomain(0, 9)),
            factors = arrayOf(Monotone(intArrayOf(0, 1, 2), Monotone.Direction.Increasing, strict = true)),
        )
        val r = Z3Solver(problem).solve(Z3Params())
        val sat = assertIs<SolveResult.Sat>(r)
        val xs = sat.assignment.ints
        assertEquals(true, xs[0] < xs[1])
        assertEquals(true, xs[1] < xs[2])
    }

    @Test
    fun `Member - y must equal some xs entry`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            // xs domains restricted to {1, 3, 5}; y domain {2, 3, 4} → only y=3 works.
            intDomains = arrayOf(IntDomain(1, 1), IntDomain(3, 3), IntDomain(2, 4)),
            factors = arrayOf(Member(intArrayOf(0, 1), 2)),
        )
        val r = Z3Solver(problem).solve(Z3Params())
        val sat = assertIs<SolveResult.Sat>(r)
        assertEquals(3, sat.assignment.ints[2])
    }

    @Test
    fun `ValuePrecede - t cannot appear before s`() {
        // ValuePrecede(s=1, t=2, xs). Forbid xs[0]=2.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(1, 2), IntDomain(1, 2), IntDomain(1, 2)),
            factors = arrayOf(
                ValuePrecede(s = 1, t = 2, xs = intArrayOf(0, 1, 2)),
                // Force a 2 to appear so the constraint is non-trivial.
                Linear(intArrayOf(1, 1, 1), intArrayOf(0, 1, 2), LinearOp.GE, 5),
            ),
        )
        val r = Z3Solver(problem).solve(Z3Params())
        val sat = assertIs<SolveResult.Sat>(r)
        val xs = sat.assignment.ints
        // Find first 2; verify a 1 appeared earlier.
        val firstT = xs.indexOfFirst { it == 2 }
        if (firstT >= 0) {
            val firstS = xs.indexOfFirst { it == 1 }
            assertEquals(true, firstS in 0 until firstT, "first s ($firstS) must precede first t ($firstT) in $xs")
        }
    }
}
