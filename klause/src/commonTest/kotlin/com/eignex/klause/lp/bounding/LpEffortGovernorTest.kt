package com.eignex.klause.lp.bounding

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * When the node LP stops earning its keep, and on what evidence.
 *
 * Work per node decides the adaptive effort; a cumulative work allowance caps optional LP work.
 */
class LpEffortGovernorTest {

    private fun governor(opsPerNode: Long = 1_000L, allowance: Long = 5_000_000L, warmup: Int = 4) =
        LpEffortGovernor(opsPerNodeCap = opsPerNode, workAllowance = allowance, warmupSolves = warmup)

    private fun LpEffortGovernor.nodes(n: Int) = repeat(n) { observeNode() }

    @Test
    fun `an LP costing less than its per-node cap is left alone`() {
        val g = governor()
        g.nodes(100)

        repeat(10) { g.observeSolve(opsSpent = 5_000L, pruned = false) }

        assertFalse(g.isDemoted, "50k ops over 100 nodes is well inside a 1000-per-node cap")
    }

    @Test
    fun `an LP outspending its per-node cap is demoted`() {
        val g = governor()
        g.nodes(10)

        repeat(10) { g.observeSolve(opsSpent = 5_000L, pruned = false) }

        assertTrue(g.isDemoted, "50k ops over 10 nodes is five times the cap")
    }

    @Test
    fun `demotion waits for the warmup`() {
        val g = governor(warmup = 100)
        g.nodes(1)

        repeat(10) { g.observeSolve(opsSpent = 1_000_000L, pruned = false) }

        assertFalse(g.isDemoted, "too few solves yet to judge the relaxation")
    }

    @Test
    fun `a prune spares the LP however much it costs`() {
        val g = governor()
        g.nodes(1)
        g.observeSolve(opsSpent = 10L, pruned = true)

        repeat(50) { g.observeSolve(opsSpent = 1_000_000L, pruned = false) }

        assertFalse(g.isDemoted, "a relaxation that prunes is worth its cost")
    }

    @Test
    fun `the ratio rule demotes without spending the allowance`() {
        val g = governor()
        g.nodes(10)

        repeat(10) { g.observeSolve(opsSpent = 5_000L, pruned = false) }

        assertTrue(g.isDemoted)
        assertFalse(g.allowanceSpent)
    }

    @Test
    fun `spending the allowance demotes a cheap LP`() {
        val g = governor(opsPerNode = Long.MAX_VALUE / 2, allowance = 1_000L)

        g.chargeWork(1_000L)

        assertTrue(g.isDemoted)
        assertTrue(g.allowanceSpent)
    }

    @Test
    fun `work short of the allowance leaves the LP alone`() {
        val g = governor(opsPerNode = Long.MAX_VALUE / 2, allowance = 1_000L)

        g.chargeWork(999L)

        assertFalse(g.isDemoted)
    }

    @Test
    fun `a disabled allowance is never spent`() {
        val g = governor(opsPerNode = Long.MAX_VALUE / 2, allowance = 0L)

        g.chargeWork(Long.MAX_VALUE)

        assertFalse(g.allowanceSpent)
    }

    @Test
    fun `the remaining allowance shrinks with charges and floors at zero`() {
        val g = governor(allowance = 1_000L)
        g.chargeWork(400L)
        assertEquals(600L, g.remainingWork())

        g.chargeWork(5_000L)

        assertEquals(0L, g.remainingWork(), "a solve capped by this must never see it go negative")
    }

    @Test
    fun `a disabled allowance reports no remaining work`() {
        assertNull(governor(allowance = 0L).remainingWork())
    }

    @Test
    fun `charges saturate rather than wrap`() {
        val g = governor(allowance = Long.MAX_VALUE)
        g.chargeWork(Long.MAX_VALUE - 1L)

        g.chargeWork(Long.MAX_VALUE)

        assertTrue(g.allowanceSpent)
    }

    @Test
    fun `a negative charge refunds nothing`() {
        val g = governor(allowance = 1_000L)
        g.chargeWork(400L)

        g.chargeWork(-300L)

        assertEquals(600L, g.remainingWork())
    }

    @Test
    fun `a demoted LP that starts pruning is restored`() {
        val g = governor()
        g.nodes(10)
        repeat(10) { g.observeSolve(opsSpent = 5_000L, pruned = false) }
        assertTrue(g.isDemoted)

        g.observeSolve(opsSpent = 5_000L, pruned = true)

        assertFalse(g.isDemoted, "a prune is the demotion being proved wrong, so it has to be reversible")
    }

    @Test
    fun `an LP that prunes and then turns expensive is demoted again`() {
        val g = governor()
        g.nodes(1)
        g.observeSolve(opsSpent = 10L, pruned = true)

        g.nodes(10)
        repeat(10) { g.observeSolve(opsSpent = 5_000L, pruned = false) }

        assertTrue(g.isDemoted, "a prune restores the LP; it does not excuse whatever it costs afterwards")
    }

    @Test
    fun `a prune measures the next demotion on what followed it`() {
        val g = governor()
        g.nodes(2)
        repeat(4) { g.observeSolve(opsSpent = 100_000L, pruned = false) }
        g.observeSolve(opsSpent = 10L, pruned = true)

        g.nodes(100)
        repeat(10) { g.observeSolve(opsSpent = 5_000L, pruned = false) }

        assertFalse(g.isDemoted, "the 400k ops before the prune are not evidence against the solves after it")
    }

    @Test
    fun `a pruning LP still spends the shared allowance`() {
        val g = governor(allowance = 1_000L)
        g.nodes(1)
        g.observeSolve(opsSpent = 10L, pruned = true)

        g.chargeWork(1_000L)

        assertTrue(g.isDemoted)
    }

    @Test
    fun `a prune does not restore an LP whose allowance is spent`() {
        val g = governor(allowance = 1_000L)
        g.chargeWork(1_000L)

        g.observeSolve(opsSpent = 10L, pruned = true)

        assertTrue(g.isDemoted, "the allowance is cumulative across useful solves too")
    }
}
