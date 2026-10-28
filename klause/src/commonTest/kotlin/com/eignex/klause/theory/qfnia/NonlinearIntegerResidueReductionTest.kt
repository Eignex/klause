package com.eignex.klause.theory.qfnia

import com.eignex.klause.ir.PolynomialExpression
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.util.BIG_ONE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class NonlinearIntegerResidueReductionTest {
    @Test
    fun `repeated source atoms should share one completed exclusion`() {
        val atom = PolynomialRealFormula.Atom(PolynomialExpression.Variable(SourceRealId(0)),
            PolynomialRelation.EQ, PolynomialExpression.Constant.of(BIG_ONE))
        val source = PolynomialRealFormula.Or(List(16) { atom })
        var checks = 0
        val reduction = NonlinearIntegerResidueReduction({}, {}) { checks++; true }

        val reduced = reduction.reduce(source)

        assertEquals(false, assertIs<PolynomialRealFormula.Constant>(reduced.formula).value)
        assertEquals(1L, reduced.excluded)
        assertEquals(1, checks)
    }
}
