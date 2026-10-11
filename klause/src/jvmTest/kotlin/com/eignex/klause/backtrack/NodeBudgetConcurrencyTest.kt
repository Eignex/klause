package com.eignex.klause.backtrack

import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NodeBudgetConcurrencyTest {
    @Test
    fun `parallel quotas retain every completed native charge`() {
        val budget = NodeBudget(400L)
        val quotas = budget.quotas(4)

        val workers = quotas.map { quota -> thread { repeat(100) { quota.spend() } } }
        workers.forEach { it.join() }

        assertEquals(400L, budget.spent)
        assertTrue(quotas.all { it.exhausted() })
    }
}
