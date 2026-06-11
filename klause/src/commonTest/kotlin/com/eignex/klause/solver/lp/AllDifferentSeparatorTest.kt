package com.eignex.klause.solver.lp

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.AllDifferent
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.propagation.PropagationSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** #22: the AllDifferent Hall-set cut separator. */
class AllDifferentSeparatorTest {

    private fun setup(domainMin: Int, domainMax: Int, n: Int): Triple<Problem, LpRelaxation, LpSolution> {
        val p = Problem(
            numBoolVars = 0,
            numIntVars = n,
            intDomains = Array(n) { IntDomain(domainMin, domainMax) },
            factors = arrayOf<Factor>(
                AllDifferent(IntArray(n) { it }, domainMin = domainMin, domainSize = domainMax - domainMin + 1),
            ),
        )
        val session = PropagationSession(p)
        val obj = LinearObjective(intCoefficients = LongArray(n) { 1L })
        val relaxation = CpToLpRelaxation(p, obj, generateCuts = true).build(session)
        val solution = DualSimplex(relaxation.model).solve()
        return Triple(p, relaxation, solution)
    }

    @Test
    fun `lower-bound cut is the sum of the smallest distinct values`() {
        // 3 vars in [0,5], minimized: the LP puts them all at 0 (Σ=0), violating all-different.
        // The Hall cut is Σ >= 0+1+2 = 3.
        val (p, relaxation, solution) = setup(0, 5, 3)
        assertEquals(0.0, solution.objectiveValue, 1e-9)

        val cuts = AllDifferentSeparator().separate(CutContext(p, relaxation, solution, PropagationSession(p)))
        val geCut = cuts.first { it.rel == Relation.GE }
        assertEquals(3L, geCut.rhs)
        assertEquals(3, geCut.cols.size)
        assertTrue(geCut.coeffs.all { it == 1L })
    }

    @Test
    fun `cut respects a shifted domain`() {
        // 3 vars in [2,7]: smallest distinct sum = 2+3+4 = 9.
        val (p, relaxation, solution) = setup(2, 7, 3)
        val cuts = AllDifferentSeparator().separate(CutContext(p, relaxation, solution, PropagationSession(p)))
        // The LP minimum puts each at its lower bound 2 (Σ=6), which violates the Hall bound of 9.
        assertEquals(9L, cuts.first { it.rel == Relation.GE }.rhs)
    }

    @Test
    fun `cut is valid for every distinct assignment`() {
        // Exhaustively verify the generated lower-bound cut excludes no feasible (distinct) point.
        val (p, relaxation, solution) = setup(0, 4, 3)
        val cut = AllDifferentSeparator().separate(CutContext(p, relaxation, solution, PropagationSession(p)))
            .first { it.rel == Relation.GE }
        for (a in 0..4) {
            for (b in 0..4) {
                for (c in 0..4) {
                    if (a == b || a == c || b == c) continue // only all-different points
                    assertTrue((a + b + c).toLong() >= cut.rhs, "($a,$b,$c) violates Σ >= ${cut.rhs}")
                }
            }
        }
    }
}
