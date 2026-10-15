package com.eignex.klause.lp.cut

import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.factor.global.SymmetricAllDifferent
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
import kotlin.test.assertTrue

class AllDifferentSeparatorTest {

    private fun setup(
        domainMin: Int,
        domainMax: Int,
        n: Int,
        openAbove: Boolean = false,
    ): Triple<Problem, LpRelaxation, FloatLpResult> {
        val p = Problem(
            numBoolVars = 0,
            numIntVars = n,
            intDomains = Array(n) { IntDomain(domainMin.toLong(), domainMax.toLong()) },
            factors = arrayOf<Factor>(
                AllDifferent(
                    IntArray(n) { it },
                    domainMin = domainMin.toLong(),
                    domainSize = domainMax - domainMin + 1,
                ),
            ),
            openIntHi = BooleanArray(n) { openAbove && it == 0 },
        )
        val session = PropagationSession(p)
        val obj = LinearObjective(intCoefficients = LongArray(n) { 1L })
        val relaxation = CpToLpRelaxation(p, obj).build(session)
        val solution = requireNotNull(RevisedSimplex(relaxation.model).solve())
        return Triple(p, relaxation, solution)
    }

    @Test
    fun `lower bound cut should be the sum of the smallest distinct values`() {
        // The Hall cut is Σ >= 0+1+2 = 3.
        val (p, relaxation, solution) = setup(0, 5, 3)
        assertEquals(0.0, solution.objective, 1e-9)

        val cuts = AllDifferentSeparator().separate(CutContext(p, relaxation, solution.primal, PropagationSession(p)))
        val geCut = cuts.first { it.rel == Relation.GE }
        assertEquals(3L, geCut.rhs)
        assertEquals(3, geCut.cols.size)
        assertTrue(geCut.coeffs.all { it == 1L })
    }

    @Test
    fun `hall cut over an invented endpoint should not be global`() {
        val (open, openRelaxation, openSolution) = setup(0, 5, 3, openAbove = true)
        val openCut = AllDifferentSeparator()
            .separate(CutContext(open, openRelaxation, openSolution.primal, PropagationSession(open)))
            .first { it.rel == Relation.GE }
        assertTrue(!openCut.global)

        val (p, relaxation, solution) = setup(0, 5, 3)
        val cut = AllDifferentSeparator()
            .separate(CutContext(p, relaxation, solution.primal, PropagationSession(p)))
            .first { it.rel == Relation.GE }
        assertTrue(cut.global)
    }

    @Test
    fun `lower bound cut should respect a shifted domain`() {
        val (p, relaxation, solution) = setup(2, 7, 3)
        val cuts = AllDifferentSeparator().separate(CutContext(p, relaxation, solution.primal, PropagationSession(p)))
        assertEquals(9L, cuts.first { it.rel == Relation.GE }.rhs)
    }

    @Test
    fun `symmetric all different should feed the Hall cut`() {
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = Array(3) { IntDomain(0, 4) },
            factors = arrayOf<Factor>(SymmetricAllDifferent(intArrayOf(0, 1, 2))),
        )
        val relaxation = CpToLpRelaxation(p, LinearObjective(intCoefficients = LongArray(3) { 1L }))
            .build(PropagationSession(p))
        val solution = requireNotNull(RevisedSimplex(relaxation.model).solve())

        val geCut = AllDifferentSeparator().separate(CutContext(p, relaxation, solution.primal, PropagationSession(p)))
            .first { it.rel == Relation.GE }

        assertEquals(3L, geCut.rhs)
        assertEquals(3, geCut.cols.size)
        assertTrue(geCut.coeffs.all { it == 1L })
    }
}
