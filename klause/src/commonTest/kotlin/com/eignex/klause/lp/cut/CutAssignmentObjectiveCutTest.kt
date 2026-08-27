package com.eignex.klause.lp.cut

import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.lp.engine.FloatLpResult
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.lp.engine.RevisedSimplex
import com.eignex.klause.lp.relaxation.CpToLpRelaxation
import com.eignex.klause.lp.relaxation.LpRelaxation
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** #23↔#22: the objective-weighted AllDifferent (assignment) cut. */
class CutAssignmentObjectiveCutTest {

    private fun setup(coef: LongArray, hi: Int): Triple<Problem, LpRelaxation, FloatLpResult> {
        val n = coef.size
        val p = Problem(
            0,
            n,
            Array(n) { IntDomain(0, hi.toLong()) },
            arrayOf<Factor>(AllDifferent(IntArray(n) { it }, domainMin = 0, domainSize = hi + 1)),
        )
        val session = PropagationSession(p)
        val relaxation = CpToLpRelaxation(
            p,
            LinearObjective(intCoefficients = coef),
        ).build(session)
        return Triple(p, relaxation, requireNotNull(RevisedSimplex(relaxation.model).solve()))
    }

    @Test
    fun `cut rhs equals the weighted assignment minimum`() {
        // min 1·x0 + 2·x1 + 3·x2 over AllDifferent[0,4]: assign 2→x0, 1→x1, 0→x2 = 2+2+0 = 4.
        val coef = longArrayOf(1, 2, 3)
        val (p, r, sol) = setup(coef, 4)
        val cuts = AssignmentObjectiveCut(coef).separate(CutContext(p, r, sol.primal, PropagationSession(p)))
        assertEquals(1, cuts.size)
        assertEquals(Relation.GE, cuts[0].rel)
        assertEquals(4L, cuts[0].rhs)
    }

    @Test
    fun `cut excludes no distinct assignment`() {
        val coef = longArrayOf(2, -1, 3) // mixed signs
        val (p, r, sol) = setup(coef, 4)
        val cut = AssignmentObjectiveCut(coef).separate(CutContext(p, r, sol.primal, PropagationSession(p)))
            .firstOrNull() ?: return // if not violated, nothing to check
        // Exhaustively: every distinct (a,b,c) in [0,4] satisfies Σ coef·x ≥ rhs.
        for (a in 0..4) {
            for (b in 0..4) {
                for (c in 0..4) {
                    if (a == b || a == c || b == c) continue
                    val lhs = 2L * a - 1L * b + 3L * c
                    assertTrue(lhs >= cut.rhs, "($a,$b,$c): $lhs < ${cut.rhs}")
                }
            }
        }
    }
}
