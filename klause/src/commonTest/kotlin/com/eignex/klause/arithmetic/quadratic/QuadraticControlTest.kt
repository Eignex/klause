package com.eignex.klause.arithmetic.quadratic

import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class QuadraticControlTest {
    @Test
    fun `a quadratic primitive should charge one polynomial unit and pure polls none`() {
        var charged = 0L
        val polynomial = PolynomialArithmetic(object : Cancellation {
            override fun isCancelled(): Boolean = false
            override fun workMeter(): WorkMeter = WorkMeter { charged += it }
        })
        val control = QuadraticControl(execution = polynomial.execution)

        control.poll()
        control.step()
        control.poll()

        assertEquals(1L, charged)
        assertEquals(1L, control.work)
    }

    @Test
    fun `local work admission should precede the containing debit`() {
        var charged = 0L
        val control = QuadraticControl(object : Cancellation {
            override fun isCancelled(): Boolean = false
            override fun workMeter(): WorkMeter = WorkMeter { charged += it }
        }, maximumWork = 0)

        val stop = assertFailsWith<QuadraticInterrupted> { control.step() }

        assertEquals(QuadraticStop.WORK_LIMIT, stop.reason)
        assertEquals(0L, charged)
    }
}
