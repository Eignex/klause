package com.eignex.klause.lp.relaxation

import com.eignex.klause.lp.engine.LpBuilder
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.lp.engine.RevisedSimplex
import com.eignex.klause.lp.engine.Sense
import com.eignex.klause.lp.engine.integerFarkasRay
import com.eignex.klause.util.Int128
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LpExplanationInfeasibilityClauseTest {

    private fun rayDotColumnSign(model: LpModel, ray: LongArray, col: Int): Int {
        val acc = Int128()
        model.forEachInColumn(col) { i, a -> acc.addProduct(ray[i], a) }
        return if (acc.hi == 0L && acc.lo == 0L) {
            0
        } else if (acc.isNonNegative()) {
            1
        } else {
            -1
        }
    }

    @Test
    fun `ray proves a tiny infeasible lp`() {
        // x in [2,5], constraint x <= 1: infeasible. The lower bound x>=2 is the reason.
        val b = LpBuilder()
        val x = b.addVar(2, 5, cost = 0)
        b.addRow(mapOf(x to 1L), Relation.LE, 1)
        val model = b.build(Sense.MINIMIZE)
        val simplex = RevisedSimplex(model)
        val result = simplex.solve()
        assertTrue(result == null, "the LP is infeasible, so solve() must return null")
        val ray = assertNotNull(
            integerFarkasRay(model, assertNotNull(simplex.infeasibleRay)),
            "expected a Farkas infeasibility ray",
        )
        // x's seated lower bound participates: ρ·A_x < 0 ⇒ the lower side is load-bearing.
        assertTrue(rayDotColumnSign(model, ray, x) < 0, "x's lower bound must participate in the ray")
    }

}
