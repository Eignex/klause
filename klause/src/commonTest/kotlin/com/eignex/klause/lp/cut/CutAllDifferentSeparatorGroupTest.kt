package com.eignex.klause.lp.cut

import com.eignex.klause.factor.global.Inverse
import com.eignex.klause.factor.global.SymmetricAllDifferent
import com.eignex.klause.lp.engine.FloatLpResult
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.lp.engine.RevisedSimplex
import com.eignex.klause.lp.relaxation.CpToLpRelaxation
import com.eignex.klause.lp.relaxation.LpRelaxation
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #22 (assignment-polytope reach): the Hall-sum and assignment-objective separators apply to every
 * all-different group, not just plain `AllDifferent` — covering `SymmetricAllDifferent` (a
 * self-inverse permutation) and each injective side of `Inverse`.
 */
class CutAllDifferentSeparatorGroupTest {

    private fun relax(p: Problem, obj: LinearObjective): Pair<LpRelaxation, FloatLpResult> {
        val relaxation = CpToLpRelaxation(p, obj).build(PropagationSession(p))
        return relaxation to requireNotNull(RevisedSimplex(relaxation.model).solve())
    }

    @Test
    fun `symmetric all-different feeds the Hall cut`() {
        // 3 self-inverse vars in [0,4], minimized -> LP collapses to 0, violating distinctness.
        // Hall lower bound is 0+1+2 = 3.
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = Array(3) { IntDomain(0, 4) },
            factors = arrayOf<Factor>(SymmetricAllDifferent(intArrayOf(0, 1, 2))),
        )
        val (r, sol) = relax(p, LinearObjective(intCoefficients = LongArray(3) { 1L }))

        val geCut = AllDifferentSeparator().separate(CutContext(p, r, sol.primal, PropagationSession(p)))
            .first { it.rel == Relation.GE }
        assertEquals(3L, geCut.rhs)
        assertEquals(3, geCut.cols.size)
        assertTrue(geCut.coeffs.all { it == 1L })

        // Exhaustive: the cut excludes no distinct assignment.
        for (a in 0..4) {
            for (b in 0..4) {
                for (c in 0..4) {
                    if (a == b || a == c || b == c) continue
                    assertTrue((a + b + c).toLong() >= geCut.rhs, "($a,$b,$c) violates Σ >= ${geCut.rhs}")
                }
            }
        }
    }

    @Test
    fun `both inverse sides feed Hall cuts`() {
        // f = vars 0..2, g = vars 3..5, all in [0,5]. Each side is all-different, so each yields a
        // Σ >= 0+1+2 = 3 cut at the all-zero LP point.
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 6,
            intDomains = Array(6) { IntDomain(0, 5) },
            factors = arrayOf<Factor>(Inverse(f = intArrayOf(0, 1, 2), g = intArrayOf(3, 4, 5))),
        )
        val (r, sol) = relax(p, LinearObjective(intCoefficients = LongArray(6) { 1L }))

        val geCuts = AllDifferentSeparator().separate(CutContext(p, r, sol.primal, PropagationSession(p)))
            .filter { it.rel == Relation.GE }
        assertEquals(2, geCuts.size, "one Hall cut per inverse side")
        assertTrue(geCuts.all { it.rhs == 3L && it.cols.size == 3 })
    }

    @Test
    fun `assignment-objective cut reaches an inverse side`() {
        // f = vars 0..2 in [0,4] with objective weights 3,1,2. The min-cost distinct assignment is
        // values {0,1,2} on coeffs {3,2,1} = 3·0 + 2·1 + 1·2 = 4, which the all-zero LP point breaks.
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 6,
            intDomains = Array(6) { IntDomain(0, 4) },
            factors = arrayOf<Factor>(Inverse(f = intArrayOf(0, 1, 2), g = intArrayOf(3, 4, 5))),
        )
        val coef = longArrayOf(3L, 1L, 2L, 0L, 0L, 0L)
        val (r, sol) = relax(p, LinearObjective(intCoefficients = coef))

        val cut = AssignmentObjectiveCut(coef).separate(CutContext(p, r, sol.primal, PropagationSession(p)))
            .first { it.rel == Relation.GE }
        assertEquals(4L, cut.rhs)
        assertEquals(3, cut.cols.size) // only the nonzero-cost f columns
    }
}
