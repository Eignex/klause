package com.eignex.klause.cli

import com.eignex.klause.solver.result.TerminationReason
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(Parameterized::class)
class VerdictContextTest(private val reason: TerminationReason, private val expected: String) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun reasons(): List<Array<Any>> = listOf(
            arrayOf(TerminationReason.Timeout, "wall timeout"),
            arrayOf(TerminationReason.BudgetExhausted, "fixed work exhausted"),
            arrayOf(TerminationReason.Cancelled, "cancelled"),
        )
    }

    @Test
    fun `absent worker admission should retain the actual resource cause`() {
        val context = VerdictContext(terminationReason = reason, completePool = null)

        assertEquals(expected, context.softVerdictCause())
    }
}
