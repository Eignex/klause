package com.eignex.klause.lp.cut

import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.factor.global.Inverse
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.engine.FloatLpResult
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.lp.engine.RevisedSimplex
import com.eignex.klause.lp.relaxation.CpToLpRelaxation
import com.eignex.klause.lp.relaxation.LpRelaxation
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.test.Test
import kotlin.test.assertEquals

class AssignmentObjectiveCutTest {

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
    fun `cut rhs should equal the weighted assignment minimum`() {
        // min 1·x0 + 2·x1 + 3·x2 over AllDifferent[0,4]: assign 2→x0, 1→x1, 0→x2 = 2+2+0 = 4.
        val coef = longArrayOf(1, 2, 3)
        val (p, r, sol) = setup(coef, 4)
        val cuts = AssignmentObjectiveCut(coef).separate(CutContext(p, r, sol.primal, PropagationSession(p)))
        assertEquals(1, cuts.size)
        assertEquals(Relation.GE, cuts[0].rel)
        assertEquals(4L, cuts[0].rhs)
    }

    @Test
    fun `cut should reach an inverse side`() {
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 6,
            intDomains = Array(6) { IntDomain(0, 4) },
            factors = arrayOf<Factor>(Inverse(f = intArrayOf(0, 1, 2), g = intArrayOf(3, 4, 5))),
        )
        val coef = longArrayOf(3L, 1L, 2L, 0L, 0L, 0L)
        val relaxation = CpToLpRelaxation(p, LinearObjective(intCoefficients = coef)).build(PropagationSession(p))
        val solution = requireNotNull(RevisedSimplex(relaxation.model).solve())

        val cut = AssignmentObjectiveCut(coef)
            .separate(CutContext(p, relaxation, solution.primal, PropagationSession(p)))
            .first { it.rel == Relation.GE }

        assertEquals(4L, cut.rhs)
        assertEquals(3, cut.cols.size)
    }
}
