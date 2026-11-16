package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Term
import com.eignex.klause.ir.WideConstants
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.bigIntOf
import kotlin.test.Test
import kotlin.test.assertEquals

class ExactSearchRowTest {
    @Test
    fun `fractional coefficients should be cleared by their least common denominator`() {
        val sixth = BigFraction.of(bigIntOf(1), bigIntOf(6))
        val quarter = BigFraction.of(bigIntOf(1), bigIntOf(4))
        val twelfth = BigFraction.of(bigIntOf(1), bigIntOf(12))

        val row = ExactSearchRow.of(intArrayOf(Term.ofRealVar(0), Term.ofRealVar(1)), listOf(sixth, quarter),
            LinearOp.LE, twelfth)

        val constants = row.constants as WideConstants
        assertEquals(listOf(bigIntOf(2), bigIntOf(3), bigIntOf(1)),
            listOf(constants.exactCoeff(0), constants.exactCoeff(1), constants.exactBound))
    }
}
