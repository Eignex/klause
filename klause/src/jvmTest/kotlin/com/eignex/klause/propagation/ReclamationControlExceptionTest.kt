package com.eignex.klause.propagation

import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@RunWith(Parameterized::class)
class ReclamationControlExceptionTest(private val operation: String) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "operation={0}")
        fun parameters(): List<Array<String>> = listOf(
            "visit-work", "visit-scratch", "atomic-work", "atomic-scratch", "cancelled", "ready",
        ).map { arrayOf(it) }
    }

    @Test
    fun `ordinary budget deferral should omit JVM stack capture`() {
        var cancelled = false
        val budget = ReclamationPreparation(64L, 64L, { cancelled }, {})

        val deferred = assertFailsWith<ReclamationPreparation.Deferred> {
            when (operation) {
                "visit-work" -> budget.visit(65L)
                "visit-scratch" -> budget.visit(0L, 65L)
                "atomic-work" -> budget.atomic(3L)
                "atomic-scratch" -> budget.atomic(0L, 65L)
                "cancelled" -> {
                    cancelled = true
                    budget.visit()
                }
                "ready" -> {
                    budget.atomic(1L)
                    budget.visit(32L)
                    budget.ready()
                }
                else -> error("unknown operation $operation")
            }
        }

        assertTrue(deferred.stackTrace.isEmpty(), "budget deferral captured ${deferred.stackTrace.size} stack frames")
    }
}
