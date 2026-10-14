package com.eignex.klause.theory.difference

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.theory.TheoryCheck
import com.eignex.klause.theory.TheoryContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DifferenceTheorySolverTest {
    @Test
    fun `source difference checks retract finite assignment conditions between invocations`() {
        val model = Problem(0, 1, arrayOf(IntDomain(0, 1)), arrayOf(
            Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 1),
        ))
        val solver = DifferenceTheorySolver(model)
        var value = 0L
        val context = object : TheoryContext {
            override fun consumeCheck(): Boolean = true
            override fun cancelled(): Boolean = false
            override fun intLowerBound(variable: Int): Long = value
            override fun intUpperBound(variable: Int): Long = value
        }

        assertIs<TheoryCheck.Infeasible>(solver.check(booleanArrayOf(), context))
        value = 1L
        val result = assertIs<TheoryCheck.Sat<*>>(solver.check(booleanArrayOf(), context))

        assertEquals(1L, (result.assignment as com.eignex.klause.solver.Sample).intValue(0))
    }
}
