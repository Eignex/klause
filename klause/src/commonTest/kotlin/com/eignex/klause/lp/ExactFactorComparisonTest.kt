package com.eignex.klause.lp

import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.RealConstants
import com.eignex.klause.ir.RealConsts
import com.eignex.klause.ir.TaggedLinearRow
import com.eignex.klause.ir.Term
import com.eignex.util.numbers.BigFraction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExactFactorComparisonTest {
    @Test
    fun `Boolean independent comparisons should retain strict complements`() {
        val row = LinearRow.ofColumns(intArrayOf(), intArrayOf(0),
            RealConstants(RealConsts(doubleArrayOf()), RealConsts(doubleArrayOf(2.0)), 3.0, true), LinearOp.LE)
        val form = row.exactForm(1)

        val positive = form.comparison(true) { error("no Boolean input") }
        val negative = form.comparison(false) { error("no Boolean input") }

        assertEquals(BigFraction.ofLong(3), positive.bound)
        assertEquals(LinearOp.LE, positive.op)
        assertTrue(positive.strict)
        assertEquals(positive.bound, negative.bound)
        assertEquals(LinearOp.GE, negative.op)
        assertFalse(negative.strict)
    }

    @Test
    fun `comparisons should use current Boolean contributions`() {
        val row = TaggedLinearRow(intArrayOf(Term.ofLit(Lit.make(0, true)), Term.ofRealVar(0)),
            RealConstants(RealConsts(doubleArrayOf(2.0)), RealConsts(doubleArrayOf(1.0)), 3.0, false), LinearOp.LE)
        val form = row.exactForm(1)

        val bounds = listOf(false, true, false).map { value -> form.comparison(true) { value }.bound }

        assertEquals(listOf(3L, 1L, 3L).map(BigFraction::ofLong), bounds)
    }

    @Test
    fun `retained comparisons should preserve hint scalar admission`() {
        val row = LinearRow.ofColumns(intArrayOf(), intArrayOf(0),
            RealConstants(RealConsts(doubleArrayOf()), RealConsts(doubleArrayOf(2.0)), 3.0, false), LinearOp.LE)
        val form = row.exactForm(1)
        form.comparison(true) { false }

        val comparison = form.hintComparison({ null }, { it != BigFraction.ofLong(2) }, { _, _ -> true })

        assertNull(comparison)
    }
}
