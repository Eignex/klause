package com.eignex.klause.lp.cut

import com.eignex.klause.lp.Relation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The cut selection ([CutPool.select]) must: drop cuts the point already satisfies
 * (efficacy floor), prefer higher-efficacy cuts, skip near-parallel duplicates (orthogonality), honour
 * the `max` cap, and only ever return cuts that are in the pool (sound — it never invents a cut).
 */
class CutPoolSelectTest {

    private fun cut(rel: Relation, rhs: Long, vararg terms: Pair<Int, Long>): Cut =
        Cut(terms.map { it.first }.toIntArray(), terms.map { it.second }.toLongArray(), rel, rhs, global = true)

    @Test
    fun `select drops satisfied cuts and keeps the violated one`() {
        val pool = CutPool()
        // At x0 = 0.5: violated `x0 >= 1` (eff 0.5), satisfied `x0 <= 1`.
        pool.add(cut(Relation.GE, 1, 0 to 1L))
        pool.add(cut(Relation.LE, 1, 0 to 1L))
        val sel = pool.select(doubleArrayOf(0.5), max = 10)
        assertEquals(1, sel.size)
        assertEquals(Relation.GE, sel[0].rel)
    }

    @Test
    fun `select orders by efficacy and skips near-parallel cuts`() {
        val pool = CutPool()
        val strong = cut(Relation.GE, 10, 0 to 1L) // at x0=0: violation 10, eff 10
        val weak = cut(Relation.GE, 2, 1 to 1L) // at x1=0: violation 2, eff 2 (orthogonal support)
        val parallelToStrong = cut(Relation.GE, 8, 0 to 1L) // same column as strong ⇒ cosine 1
        pool.add(weak)
        pool.add(strong)
        pool.add(parallelToStrong)
        val sel = pool.select(doubleArrayOf(0.0, 0.0), max = 10)
        // strong first (highest efficacy); parallelToStrong dropped (cosine 1 with strong); weak kept (orthogonal).
        assertEquals(2, sel.size)
        assertTrue(sel[0] === strong, "highest-efficacy cut comes first")
        assertTrue(sel.contains(weak), "an orthogonal cut is kept")
        assertTrue(sel.none { it === parallelToStrong }, "a near-parallel duplicate is dropped")
    }

    @Test
    fun `select honours the max cap and never invents cuts`() {
        val pool = CutPool()
        val all = (0 until 6).map { cut(Relation.GE, 5, it to 1L) } // 6 orthogonal violated cuts at 0
        all.forEach { pool.add(it) }
        val sel = pool.select(DoubleArray(6), max = 3)
        assertEquals(3, sel.size)
        assertTrue(sel.all { s -> all.any { it === s } }, "every selected cut is from the pool")
    }

    @Test
    fun `select returns empty when nothing is violated`() {
        val pool = CutPool()
        pool.add(cut(Relation.LE, 10, 0 to 1L)) // x0=1 satisfies it with slack
        assertTrue(pool.select(doubleArrayOf(1.0), max = 10).isEmpty())
    }
}
