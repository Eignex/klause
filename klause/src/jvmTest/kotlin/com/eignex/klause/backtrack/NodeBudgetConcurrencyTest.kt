package com.eignex.klause.backtrack

import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NodeBudgetConcurrencyTest {
    @Test
    fun `parallel decisions and moves should preserve fractional aggregate prices`() {
        val budget = NodeBudget(900L)
        val quotas = budget.quotas(4)

        val workers = quotas.map { quota -> thread {
            repeat(100) {
                quota.spend()
                quota.spendMoves(1L)
            }
        } }
        workers.forEach { it.join() }

        assertEquals(900L, budget.spent)
        assertTrue(quotas.all { it.spent == 225L && it.exhausted() })
    }

    @Test
    fun `parallel quotas should retain every completed native charge`() {
        val budget = NodeBudget(400L)
        val quotas = budget.quotas(4)

        val workers = quotas.map { quota -> thread { repeat(100) { quota.spend() } } }
        workers.forEach { it.join() }

        assertEquals(400L, budget.spent)
        assertTrue(quotas.all { it.exhausted() })
    }

    @Test
    fun `parallel facade and resource workers should charge one containing ledger`() {
        val budget = NodeBudget(400)
        val facades = budget.quotas(4)
        val resources = budget.allowance.quotas(4)

        val workers = facades.mapIndexed { index, facade ->
            thread {
                repeat(50) {
                    facade.spend()
                    resources[index].spend()
                }
            }
        }
        workers.forEach { it.join() }

        assertEquals(400L, budget.spent)
        assertTrue(facades.all { it.exhausted() })
    }
}
