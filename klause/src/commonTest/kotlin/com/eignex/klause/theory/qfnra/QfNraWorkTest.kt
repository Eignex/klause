package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.Polynomial
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class QfNraWorkTest {
    @Test
    fun `an opaque containing meter should remain live after arithmetic construction`() {
        var charged = 0L
        var active: WorkMeter? = null
        val work = QfNraWork(QfNraParams(cancellation = object : Cancellation {
            override fun isCancelled(): Boolean = false
            override fun workMeter(): WorkMeter? = active
        }))
        val arithmetic = PolynomialArithmetic(work)
        active = WorkMeter { charged += it }

        arithmetic.multiply(Polynomial.ONE, Polynomial.ONE)

        assertEquals(2L, charged)
        assertEquals(2L, work.spentWork)
    }

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
                    arithmetic.checkpoint()
                }
                null
            }
        }

        assertFalse(pulse)
        assertEquals(3L, work.spentWork)
    }
}
