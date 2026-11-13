package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class QfNraWorkTest {
    @Test
    fun `an optional probe should retain a containing stop that clears after observation`() {
        var pulse = false
        val work = QfNraWork(QfNraParams(cancellation = Cancellation {
            val observed = pulse
            pulse = false
            observed
        }))
        val arithmetic = PolynomialArithmetic(work)

        assertFailsWith<PolynomialInterrupted> {
            work.probe {
                arithmetic.execution.operation {
                    repeat(3) { arithmetic.step() }
                    pulse = true
                }
                null
            }
        }

        assertFalse(pulse)
        assertEquals(3L, work.spentWork)
    }
}
