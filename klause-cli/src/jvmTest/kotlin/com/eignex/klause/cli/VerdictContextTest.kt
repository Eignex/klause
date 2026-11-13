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
            arrayOf(TerminationReason.Unsupported, "unsupported feature or operation"),
            arrayOf(TerminationReason.RepresentationDeclined, "required representation declined"),
            arrayOf(TerminationReason.SearchExhausted, "search exhausted without a global proof"),
        )
    }

    @Test
    fun `worker admission should retain the actual stop cause`() {
        for (complete in listOf(null, false, true)) {
            val context = VerdictContext(terminationReason = reason, completePool = complete)

            assertEquals(expected, context.softVerdictCause())
        }
    }
}
