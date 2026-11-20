package com.eignex.klause.theory.qflra

import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.RealConstants
import com.eignex.klause.ir.RealConsts
import com.eignex.klause.ir.TaggedLinearRow
import com.eignex.klause.ir.Term
import com.eignex.klause.lp.exactForm
import kotlin.test.Test
import kotlin.test.assertEquals

class ExactLiraSearchNodeTest {
    @Test
    fun `prepared truth should wait for all referenced Boolean inputs`() {
        val row = TaggedLinearRow(intArrayOf(Term.ofLit(Lit.make(1, false)), Term.ofRealVar(0)),
            RealConstants(RealConsts(doubleArrayOf(1.0)), RealConsts(doubleArrayOf(1.0)), 0.0, false),
            LinearOp.LE, activator = 0)
        val prepared = PreparedSourceRow(RowAddress(0, 0), row, row.exactForm(1),
            row.booleanVariables().toIntArray(), disjunction = false)
        val node = SearchNode()
        for (guard in -1..1) {
            for (input in -1..1) {
                val bools = intArrayOf(guard, input, -1)

                val truth = node.rowTruth(prepared, bools)

                val expected = if (guard < 0 || input < 0) null else guard == 1
                assertEquals(expected, truth, "guard=$guard input=$input")
            }
        }
    }
}
