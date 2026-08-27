package com.eignex.klause.lp.bounding

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * When the node LP stops earning its keep, and on what evidence.
 *
 * The demotion rule has to be a function of work and nodes alone — anything read off a clock makes two
 * identical runs diverge, which is the defect this replaces.
 */
class LpEffortGovernorTest {

    private fun governor(opsPerNode: Long = 1_000L, wallMillis: Long = 5_000L, warmup: Int = 4) =
        LpEffortGovernor(opsPerNodeCap = opsPerNode, wallBackstopMillis = wallMillis, warmupSolves = warmup)

    private fun LpEffortGovernor.nodes(n: Int) = repeat(n) { observeNode() }

    @Test
    fun `an LP costing less than its allowance per node is left alone`() {
        val g = governor()
        g.nodes(100)

        repeat(10) { g.observeSolve(opsSpent = 5_000L, pruned = false) }

        assertFalse(g.isDemoted, "50k ops over 100 nodes is well inside a 1000-per-node allowance")
    }

    @Test
    fun `an LP outspending its allowance per node is demoted`() {
        val g = governor()
        g.nodes(10)

        repeat(10) { g.observeSolve(opsSpent = 5_000L, pruned = false) }

        assertTrue(g.isDemoted, "50k ops over 10 nodes is five times the allowance")
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
    fun `the deterministic rule demotes without the clock being charged at all`() {
        val g = governor()
        g.nodes(10)

        repeat(10) { g.observeSolve(opsSpent = 5_000L, pruned = false) }

        assertTrue(g.isDemoted)
        assertFalse(g.backstopFired, "work decided this, so the run is still reproducible")
    }

    @Test
    fun `the wall backstop demotes what the work meter cannot see`() {
        val g = governor(opsPerNode = Long.MAX_VALUE / 2)
        g.nodes(1000)
        repeat(10) { g.observeSolve(opsSpent = 1L, pruned = false) }

        g.chargeWall(6_000L)

        assertTrue(g.isDemoted, "time the meter never charged still has to be survivable")
        assertTrue(g.backstopFired, "and the run must say the clock decided, not the work")
    }

    @Test
    fun `a disabled backstop never fires`() {
        val g = governor(opsPerNode = Long.MAX_VALUE / 2, wallMillis = 0L)

        g.chargeWall(1_000_000L)

        assertFalse(g.isDemoted)
        assertFalse(g.backstopFired)
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
    fun `the backstop allowance shrinks with charges and floors at zero`() {
        val g = governor(wallMillis = 1_000L)

        g.chargeWall(400L)
        assertEquals(600L, g.remainingMillis())
        g.chargeWall(5_000L)

        assertEquals(0L, g.remainingMillis(), "the root work shares this allowance and must not see it go negative")
    }

    @Test
    fun `a disabled backstop reports no allowance`() {
        assertNull(governor(wallMillis = 0L).remainingMillis())
    }

    @Test
    fun `a pruning LP is spared by the backstop too`() {
        val g = governor()
        g.nodes(1)
        g.observeSolve(opsSpent = 10L, pruned = true)

        g.chargeWall(1_000_000L)

        assertFalse(g.isDemoted, "the clock does not overrule a relaxation that demonstrably pays")
    }
}
