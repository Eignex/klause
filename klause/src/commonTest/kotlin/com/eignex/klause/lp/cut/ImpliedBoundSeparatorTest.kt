package com.eignex.klause.lp.cut

import com.eignex.klause.lp.Relation
import com.eignex.klause.solver.Lit
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Soundness of the implied-bound cut encoding. For an implication `A ⇒ B`, the cut
 * `litVal(A) ≤ litVal(B)` must be satisfied at a 0/1 assignment **exactly when** the implication holds
 * — so it never removes a feasible point (where the implication, being sound, does hold) and does cut
 * points that break it. Proven exhaustively over all four literal polarities and all assignments, which
 * catches any polarity or sign error in [ImpliedBoundSeparator.impliedBoundCut].
 */
class ImpliedBoundSeparatorTest {

    private fun holds(cut: Cut, x: DoubleArray): Boolean {
        var lhs = 0.0
        for (k in cut.cols.indices) lhs += cut.coeffs[k] * x[cut.cols[k]]
        return when (cut.rel) {
            Relation.LE -> lhs <= cut.rhs + 1e-9
            Relation.GE -> lhs >= cut.rhs - 1e-9
            Relation.EQ -> abs(lhs - cut.rhs) <= 1e-9
        }
    }

    @Test
    fun `cut holds exactly when the implication holds over all polarities and assignments`() {
        val boolColOf = intArrayOf(0, 1) // var 0 → col 0, var 1 → col 1
        for (fromPos in booleanArrayOf(true, false)) {
            for (toPos in booleanArrayOf(true, false)) {
                val cut = ImpliedBoundSeparator.impliedBoundCut(
                    Lit.make(0, fromPos),
                    Lit.make(1, toPos),
                    boolColOf,
                ) ?: error("expected a cut for distinct mapped variables")
                assertEquals(Relation.LE, cut.rel)
                assertTrue(cut.global, "implication cuts are problem-wide valid")
                for (xu in 0..1) {
                    for (xv in 0..1) {
                        val x = doubleArrayOf(xu.toDouble(), xv.toDouble())
                        val litFrom = if (fromPos) xu == 1 else xu == 0
                        val litTo = if (toPos) xv == 1 else xv == 0
                        val implicationHolds = !litFrom || litTo
                        assertEquals(
                            implicationHolds,
                            holds(cut, x),
                            "polarity from=$fromPos to=$toPos at (u=$xu, v=$xv)",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `same variable or unmapped column yields no cut`() {
        val boolColOf = intArrayOf(0, -1) // var 1 has no LP column
        assertNull(ImpliedBoundSeparator.impliedBoundCut(Lit.make(0, true), Lit.make(0, false), boolColOf), "same var")
        assertNull(ImpliedBoundSeparator.impliedBoundCut(Lit.make(0, true), Lit.make(1, true), boolColOf), "unmapped")
    }
}
