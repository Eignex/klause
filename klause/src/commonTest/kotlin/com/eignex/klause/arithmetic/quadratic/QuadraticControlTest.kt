package com.eignex.klause.arithmetic.quadratic

import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.util.ExecutionControl
import com.eignex.klause.util.WorkMeter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class QuadraticControlTest {
    @Test
    fun `a vector debit should retain exactly the admitted local prefix`() {
        var charged = 0L
        val control = QuadraticControl(ExecutionControl(accounting = WorkMeter { charged += it }), maximumWork = 7)

        val stop = assertFailsWith<QuadraticInterrupted> { control.operation { control.steps(10) } }

        assertEquals(QuadraticStop.WORK_LIMIT, stop.reason)
        assertEquals(7L, control.work)
        assertEquals(7L, charged)
    }

    @Test
    fun `a quadratic primitive should charge one polynomial unit and pure polls none`() {
        var charged = 0L
        val polynomial = PolynomialArithmetic(object : ExecutionControl() {
            override fun isCancelled(): Boolean = false
            override val accounting: WorkMeter get() = WorkMeter { charged += it }
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
        val control = QuadraticControl(object : ExecutionControl() {
            override fun isCancelled(): Boolean = false
            override val accounting: WorkMeter get() = WorkMeter { charged += it }
        }, maximumWork = 0)

        val stop = assertFailsWith<QuadraticInterrupted> { control.step() }

        assertEquals(QuadraticStop.WORK_LIMIT, stop.reason)
        assertEquals(0L, charged)
    }
}
