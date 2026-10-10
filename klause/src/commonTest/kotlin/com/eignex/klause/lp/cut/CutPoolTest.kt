package com.eignex.klause.lp.cut

import com.eignex.klause.lp.engine.Cut
import com.eignex.klause.lp.engine.CutExpression
import com.eignex.klause.lp.engine.CutProvenance
import com.eignex.klause.lp.engine.CutPremise
import com.eignex.klause.lp.engine.CutProofFact
import com.eignex.klause.lp.engine.CutSource
import com.eignex.klause.lp.engine.CutSourceKind
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.lp.relaxation.CutColumnSource
import com.eignex.klause.lp.relaxation.CutSourceMap
import com.eignex.klause.simplex.exact.BigFraction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CutPoolTest {

    @Test
    fun `conditional deduplication ignores premise order and preserves distinct guards`() {
        val root = Any()
        val x = CutSource(CutSourceKind.INTEGER, 0)
        val y = CutSource(CutSourceKind.INTEGER, 1)
        val expression = CutExpression(mapOf(x to BigFraction.ONE))
        val lower = CutPremise.Bound(expression, false, BigFraction.ONE)
        val upper = CutPremise.Bound(CutExpression(mapOf(y to BigFraction.ONE)), true, BigFraction.ofLong(2L))
        val weaker = lower.copy(value = BigFraction.ZERO)
        val map = CutSourceMap(
            root,
            0L,
            listOf(CutColumnSource(x), CutColumnSource(y)),
            activePremises = setOf(lower, upper),
        )
        val first = SourceCut(expression, Relation.LE, BigFraction.ofLong(5L),
            CutProvenance(root, 0L, listOf(CutProofFact(lower, false), CutProofFact(upper, false))))
        val reversed = SourceCut(expression, Relation.LE, BigFraction.ofLong(5L),
            CutProvenance(root, 0L, listOf(CutProofFact(upper, false), CutProofFact(lower, false))))
        val different = SourceCut(expression, Relation.LE, BigFraction.ofLong(5L),
            CutProvenance(root, 0L, listOf(CutProofFact(weaker, false), CutProofFact(upper, false))))
        val pool = CutPool()

        assertTrue(pool.add(first, map))
        assertFalse(pool.add(reversed, map))
        assertTrue(pool.add(different, map))

        assertEquals(2, pool.size)
    }

    private fun cut(col: Int, coeff: Long, rhs: Long) =
        Cut(intArrayOf(col), longArrayOf(coeff), Relation.LE, rhs, global = true)

    @Test
    fun `deduplicates by key`() {
        val pool = CutPool()
        assertTrue(pool.add(cut(0, 1, 5)))
        assertFalse(pool.add(cut(0, 1, 5)), "an equal cut must not be added twice")
        assertEquals(1, pool.size)
    }

    @Test
    fun `evicts the least active cuts when over the cap`() {
        // Three cuts x_c ≤ rhs against the LP point (x0,x1,x2) = (5, 1, 0):
        //   cut 0: slack |5 − 5| = 0   (tight — most active)
        //   cut 1: slack |6 − 1| = 5
        //   cut 2: slack |7 − 0| = 7   (loosest — least active)
        val pool = CutPool(maxCuts = 2)
        pool.addAll(listOf(cut(0, 1, 5), cut(1, 1, 6), cut(2, 1, 7)))
        pool.observe(doubleArrayOf(5.0, 1.0, 0.0))
        pool.retainMostActive()
        assertEquals(2, pool.size)
        val kept = pool.cuts().map { it.rhs }.toSet()
        assertEquals(setOf(5L, 6L), kept, "the two tightest cuts survive; the loosest is evicted")
    }

    @Test
    fun `a re-added evicted cut is accepted again`() {
        // Eviction rebuilds the dedup set, so a cut dropped for inactivity can re-enter a later harvest.
        val pool = CutPool(maxCuts = 1)
        pool.addAll(listOf(cut(0, 1, 5), cut(1, 1, 6)))
        pool.observe(doubleArrayOf(5.0, 0.0)) // keeps cut 0 (tight), evicts cut 1
        pool.retainMostActive()
        assertEquals(setOf(5L), pool.cuts().map { it.rhs }.toSet())
        assertTrue(pool.add(cut(1, 1, 6)), "the evicted cut is no longer marked seen")
    }

    @Test
    fun `decayed activity retains cuts active across observations`() {
        val pool = CutPool(maxCuts = 1)
        pool.addAll(listOf(cut(0, 1, 5), cut(1, 1, 5)))

        repeat(3) { pool.observe(doubleArrayOf(5.0, 0.0)) }
        pool.observe(doubleArrayOf(0.0, 5.0))
        pool.retainMostActive()

        assertEquals(listOf(0), pool.cuts().map { it.cols.single() })
    }

    @Test
    fun `evicts a cut at the consecutive inactivity threshold`() {
        val pool = CutPool(maxConsecutiveInactive = 2)
        val stale = cut(0, 1, 5)
        pool.add(stale)

        pool.observe(doubleArrayOf(0.0))
        assertEquals(1, pool.size)
        pool.observe(doubleArrayOf(0.0))

        assertEquals(0, pool.size)
        assertTrue(pool.add(stale), "an inactive eviction clears the dedup key")
    }

    @Test
    fun `raw and portable global duplicates retain one portable entry in either order`() {
        val source = CutSource(CutSourceKind.INTEGER, 0)
        val token = Any()
        val map = CutSourceMap(token, 0, listOf(CutColumnSource(source)))
        val portable = SourceCut(
            CutExpression(mapOf(source to BigFraction.ONE)),
            Relation.LE,
            BigFraction.ofLong(5),
            CutProvenance(token, 0, emptyList()),
        )
        for (portableFirst in listOf(false, true)) {
            val pool = CutPool()
            if (portableFirst) {
                assertTrue(pool.add(portable, map))
                assertFalse(pool.add(cut(0, 1, 5)))
            } else {
                assertTrue(pool.add(cut(0, 1, 5)))
                assertFalse(pool.add(portable, map))
            }
            assertEquals(1, pool.size)
            assertEquals(1, pool.exportGlobalCuts().size)
        }
    }

    @Test
    fun `root seeding preserves portable payload for export and permuted remapping`() {
        val source = CutSource(CutSourceKind.INTEGER, 0)
        val token = Any()
        val map = CutSourceMap(token, 0, listOf(CutColumnSource(source)))
        val portable = SourceCut(
            CutExpression(mapOf(source to BigFraction.ONE)),
            Relation.LE,
            BigFraction.ofLong(5),
            CutProvenance(token, 0, emptyList()),
        )
        val root = CutPool()
        root.add(portable, map)
        val search = CutPool()

        assertEquals(1, search.addAll(root.cuts()))
        val declines = search.remap(CutSourceMap(token, 1, listOf(null, CutColumnSource(source))))

        assertTrue(declines.isEmpty())
        assertEquals(1, search.exportGlobalCuts().size)
        assertEquals(1, search.cuts().single().cols.single())
    }

}
