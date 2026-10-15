package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PortfolioInvocationResourcesTest {
    @Test
    fun `parallel slot quotas should keep aggregate work without taking a sibling allowance`() {
        val containing = NodeBudget(5)
        val resources = PortfolioInvocationResources(3, 2, 17, Cancellation.Never, containing)
        val first = requireNotNull(resources.slots[0].nodeBudget)
        val second = requireNotNull(resources.slots[1].nodeBudget)

        first.spend()
        first.spend()
        second.spend()

        assertTrue(first.exhausted())
        assertFalse(second.exhausted())
        assertEquals(3L, containing.spent)
        assertEquals(0L, resources.slots[0].nodeBudget?.decisionsLeft())
        assertEquals(listOf(17L, 18L, 19L), resources.slots.map { it.seed })
    }

    @Test
    fun `sequential workers should share one containing decision and move ledger`() {
        val containing = NodeBudget(5)
        val resources = PortfolioInvocationResources(2, 1, 1, Cancellation.Never, containing)

        resources.slots[0].nodeBudget?.spendMoves(2)
        resources.slots[1].nodeBudget?.spend()

        assertSame(containing, resources.slots[0].nodeBudget)
        assertSame(containing, resources.slots[1].nodeBudget)
        assertEquals(3L, containing.spent)
    }

    @Test
    fun `complete native reservation should divide its remainder over explicit worker slots`() {
        val resources = PortfolioInvocationResources(4, 2, 1, Cancellation.Never, null)

        val allowances = resources.nativeAllowances(11, setOf(0, 2))

        assertContentEquals(longArrayOf(4, 2, 4, 1), allowances)
        assertEquals(11L, allowances.sum())
    }

    @Test
    fun `heuristic only native allocation should have no implicit complete worker`() {
        val resources = PortfolioInvocationResources(3, 2, 1, Cancellation.Never, null)

        val allowances = resources.nativeAllowances(5, emptySet())

        assertContentEquals(longArrayOf(2, 2, 1), allowances)
    }

    @Test
    fun `worker slot should keep its invocation cancellation through reacquisition`() {
        var cancelled = false
        val lifetime = Cancellation { cancelled }
        val resources = PortfolioInvocationResources(2, 2, 1, lifetime, null)

        cancelled = true

        assertTrue(resources.slots[0].cancellation())
        assertTrue(resources.slots[1].cancellation())
        assertSame(lifetime, resources.slots[0].cancellation)
    }

    @Test
    fun `complete slot should receive no heuristic allowance even with unlimited instructions`() {
        val allowances = heuristicWorkerAllowances(3, Long.MAX_VALUE, setOf(0))

        assertContentEquals(longArrayOf(0, Long.MAX_VALUE, Long.MAX_VALUE), allowances)
    }

    @Test
    fun `source cleanup should preserve failures while releasing every worker slot`() {
        val resources = PortfolioInvocationResources(2, 2, 1, Cancellation.Never, null)
        val closed = mutableListOf<Int>()
        resources.slots[0].own(
            AutoCloseable {
                closed += 0
                throw IllegalStateException("first slot")
            },
        )
        resources.slots[1].own(
            AutoCloseable {
                closed += 1
                throw IllegalStateException("second slot")
            },
        )

        val failure = assertFailsWith<IllegalStateException> { resources.close() }
        resources.close()

        assertEquals(listOf(1, 0), closed)
        assertEquals("second slot", failure.message)
        assertEquals("first slot", failure.suppressedExceptions.single().message)
    }
}
