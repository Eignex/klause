package com.eignex.klause.formats

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import kotlin.test.Test
import kotlin.test.assertContentEquals

class CnfLoweringTest {

    private class Sink : CnfLowering {
        override val factors = mutableListOf<Factor>()
        override var trueLitCache = -1
        private var nextBool = 0

        override fun newBool(): Int = nextBool++
    }

    @Test
    fun `conjunction lowering constructs its backward clause`() {
        val sink = Sink()
        sink.tseitinAnd(listOf(Lit.make(1, true), Lit.make(2, false)), Lit.make(0, true))

        val clause = sink.factors.last() as Clause
        assertContentEquals(intArrayOf(Lit.make(1, false), Lit.make(2, true), Lit.make(0, true)), clause.literals)
    }

    @Test
    fun `disjunction lowering constructs its forward clause`() {
        val sink = Sink()
        sink.tseitinOr(listOf(Lit.make(1, true), Lit.make(2, false)), Lit.make(0, true))

        val clause = sink.factors.first() as Clause
        assertContentEquals(intArrayOf(Lit.make(1, true), Lit.make(2, false), Lit.make(0, false)), clause.literals)
    }
}
