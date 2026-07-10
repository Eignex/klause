package com.eignex.klause.backtrack.lp

import com.eignex.klause.backtrack.selector.VarRef
import com.eignex.klause.lp.LpBuilder
import com.eignex.klause.lp.Relation
import com.eignex.klause.lp.RevisedSimplex
import com.eignex.klause.lp.Sense
import com.eignex.klause.lp.relaxation.LpRelaxation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** #246: LP-guided value ordering (round-toward-LP diving), on the sparse revised-simplex path (#705). */
class LpHintsTest {

    @Test
    fun `order puts the value nearest the LP value first`() {
        // Pure LP (no propagation, which would integer-fix x): max x s.t. 3x <= 2 -> x = 2/3, round -> 1.
        val b = LpBuilder()
        val x = b.addVar(0, 5, cost = 1)
        b.addRow(mapOf(x to 3L), Relation.LE, 2)
        val model = b.build(Sense.MAXIMIZE)
        val result = assertNotNull(RevisedSimplex(model).solve())
        val relaxation = LpRelaxation(
            model = model,
            colVarId = intArrayOf(0),
            colIsBool = booleanArrayOf(false),
            objectiveConstant = 0L,
            intColOf = intArrayOf(0),
            boolColOf = IntArray(0),
        )
        val hints = LpHints(1, 0)
        hints.record(relaxation, result.primal, result.duals)

        val ordered = hints.order(VarRef.IntVar(0), sequenceOf(0, 1, 2, 3, 4, 5)).toList()
        // round(2/3)=1 first; ties (0,2 both dist 1) keep input order.
        assertEquals(listOf(1L, 0L, 2L, 3L, 4L, 5L), ordered)
    }

    @Test
    fun `branchScore is positive for a fractional variable and absent for an unrecorded one`() {
        // max x s.t. 3x <= 2 -> x = 2/3 fractional ⇒ a positive branch score (fractionality 1/3).
        val b = LpBuilder()
        val x = b.addVar(0, 5, cost = 1)
        b.addRow(mapOf(x to 3L), Relation.LE, 2)
        val model = b.build(Sense.MAXIMIZE)
        val result = assertNotNull(RevisedSimplex(model).solve())
        val relaxation = LpRelaxation(
            model = model,
            colVarId = intArrayOf(0),
            colIsBool = booleanArrayOf(false),
            objectiveConstant = 0L,
            intColOf = intArrayOf(0),
            boolColOf = IntArray(0),
        )
        val hints = LpHints(1, 0)
        hints.record(relaxation, result.primal, result.duals)
        assertTrue(hints.branchScore(VarRef.IntVar(0)) > 0.0, "a fractional LP variable must score > 0")
        assertTrue(hints.branchScore(VarRef.Bool(0)).isNaN(), "an unrecorded variable has no score")
    }

    @Test
    fun `order is a no-op without a hint`() {
        val hints = LpHints(2, 0)
        assertEquals(listOf(5L, 0L, 3L), hints.order(VarRef.IntVar(1), sequenceOf(5, 0, 3)).toList())
    }
}
