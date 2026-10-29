package com.eignex.klause.lp.cut

import com.eignex.klause.lp.engine.Cut
import com.eignex.klause.lp.engine.CutExpression
import com.eignex.klause.lp.engine.CutFamily
import com.eignex.klause.lp.engine.CutPremise
import com.eignex.klause.lp.engine.CutProofFact
import com.eignex.klause.lp.engine.CutProvenance
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
    fun `remapping should attribute retraction for every missing conditional premise`() {
        val model = Any()
        val source = CutSource(CutSourceKind.INTEGER, 0)
        val expression = CutExpression(mapOf(source to BigFraction.ONE))
        val premises = listOf(
            CutPremise.Bound(expression, false, BigFraction.ONE),
            CutPremise.Literal(1),
            CutPremise.ObjectiveCutoff(expression, BigFraction.ofLong(5)),
        )
        val cut = SourceCut(expression, Relation.LE, BigFraction.ofLong(3),
            CutProvenance(model, 0L, premises.map { CutProofFact(it, false) }, family = CutFamily.GOMORY))
        for (missing in premises) {
            val events = ArrayList<Triple<CutFamily, String, String>>()
            val pool = CutPool(observe = { family, stage, outcome -> events.add(Triple(family, stage, outcome)) })
            val active = CutSourceMap(model, 0L, listOf(CutColumnSource(source)), activePremises = premises.toSet())
            assertTrue(pool.add(cut, active))

            pool.remap(CutSourceMap(model, 1L, listOf(CutColumnSource(source)),
                activePremises = (premises - missing).toSet()))

            assertTrue(pool.cuts().isEmpty())
            assertTrue(Triple(CutFamily.GOMORY, "RETRACTION", "UNAVAILABLE") in events)
            pool.remap(active)
            assertEquals(CutFamily.GOMORY, pool.cuts().single().family)
            assertTrue(Triple(CutFamily.GOMORY, "RETRACTION", "REACTIVATED") in events)
        }
    }


    @Test
    fun `conditional deduplication should ignore premise order and preserve distinct guards`() {
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
        val first = SourceCut(
            expression,
            Relation.LE,
            BigFraction.ofLong(5L),
            CutProvenance(root, 0L, listOf(CutProofFact(lower, false), CutProofFact(upper, false))),
        )
        val reversed = SourceCut(
            expression,
            Relation.LE,
            BigFraction.ofLong(5L),
            CutProvenance(root, 0L, listOf(CutProofFact(upper, false), CutProofFact(lower, false))),
        )
        val different = SourceCut(
            expression,
            Relation.LE,
            BigFraction.ofLong(5L),
            CutProvenance(root, 0L, listOf(CutProofFact(weaker, false), CutProofFact(upper, false))),
        )
        val pool = CutPool()

        assertTrue(pool.add(first, map))
        assertFalse(pool.add(reversed, map))
        assertTrue(pool.add(different, map))

        assertEquals(2, pool.size)
    }

    private fun cut(col: Int, coeff: Long, rhs: Long) =
        Cut(intArrayOf(col), longArrayOf(coeff), Relation.LE, rhs, global = true)

    @Test
    fun `pool should deduplicate cuts by key`() {
        val pool = CutPool()
        assertTrue(pool.add(cut(0, 1, 5)))
        assertFalse(pool.add(cut(0, 1, 5)))
        assertEquals(1, pool.size)
    }

    @Test
    fun `pool should evict the least active cuts when over the cap`() {
        val pool = CutPool(maxCuts = 2)
        pool.addAll(listOf(cut(0, 1, 5), cut(1, 1, 6), cut(2, 1, 7)))
        pool.observe(doubleArrayOf(5.0, 1.0, 0.0))
        pool.retainMostActive()
        assertEquals(2, pool.size)
        val kept = pool.cuts().map { it.rhs }.toSet()
        assertEquals(setOf(5L, 6L), kept)
    }

    @Test
    fun `pool should accept a re-added evicted cut`() {
        val pool = CutPool(maxCuts = 1)
        pool.addAll(listOf(cut(0, 1, 5), cut(1, 1, 6)))
        pool.observe(doubleArrayOf(5.0, 0.0))
        pool.retainMostActive()
        assertEquals(setOf(5L), pool.cuts().map { it.rhs }.toSet())
        assertTrue(pool.add(cut(1, 1, 6)))
    }

    @Test
    fun `decayed activity should retain cuts active across observations`() {
        val pool = CutPool(maxCuts = 1)
        pool.addAll(listOf(cut(0, 1, 5), cut(1, 1, 5)))

        repeat(3) { pool.observe(doubleArrayOf(5.0, 0.0)) }
        pool.observe(doubleArrayOf(0.0, 5.0))
        pool.retainMostActive()

        assertEquals(listOf(0), pool.cuts().map { it.cols.single() })
    }

    @Test
    fun `pool should evict a cut at the consecutive inactivity threshold`() {
        val pool = CutPool(maxConsecutiveInactive = 2)
        val stale = cut(0, 1, 5)
        pool.add(stale)

        pool.observe(doubleArrayOf(0.0))
        assertEquals(1, pool.size)
        pool.observe(doubleArrayOf(0.0))

        assertEquals(0, pool.size)
        assertTrue(pool.add(stale))
    }

    @Test
    fun `raw and portable global duplicates should retain one portable entry in either order`() {
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
    fun `root seeding should preserve portable payload for export and permuted remapping`() {
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

    @Test
    fun `select should put constant contradictions before ordinary violated cuts`() {
        val pool = CutPool()
        pool.add(cut(Relation.GE, 100, 0 to 1L))
        val contradiction = cut(Relation.GE, 1)
        pool.add(contradiction)

        val selected = pool.select(doubleArrayOf(0.0), doubleArrayOf(1.0), max = 1)

        assertTrue(selected.single() === contradiction)
    }

    @Test
    fun `select should exclude constant tautologies`() {
        val pool = CutPool()
        pool.add(cut(Relation.GE, 0))

        val selected = pool.select(doubleArrayOf(), doubleArrayOf(), max = 1)

        assertTrue(selected.isEmpty())
    }

    private fun cut(rel: Relation, rhs: Long, vararg terms: Pair<Int, Long>): Cut =
        Cut(terms.map { it.first }.toIntArray(), terms.map { it.second }.toLongArray(), rel, rhs, global = true)

    @Test
    fun `select should drop satisfied cuts and keep the violated one`() {
        val pool = CutPool()
        pool.add(cut(Relation.GE, 1, 0 to 1L))
        pool.add(cut(Relation.LE, 1, 0 to 1L))
        val sel = pool.select(doubleArrayOf(0.5), doubleArrayOf(0.0), max = 10)
        assertEquals(1, sel.size)
        assertEquals(Relation.GE, sel[0].rel)
    }

    @Test
    fun `select should order by efficacy and skip near-parallel cuts`() {
        val pool = CutPool()
        val strong = cut(Relation.GE, 10, 0 to 1L)
        val weak = cut(Relation.GE, 2, 1 to 1L)
        val parallelToStrong = cut(Relation.GE, 8, 0 to 1L)
        pool.add(weak)
        pool.add(strong)
        pool.add(parallelToStrong)
        val sel = pool.select(doubleArrayOf(0.0, 0.0), doubleArrayOf(0.0, 0.0), max = 10)
        assertEquals(2, sel.size)
        assertTrue(sel[0] === strong)
        assertTrue(sel.contains(weak))
        assertTrue(sel.none { it === parallelToStrong })
    }

    @Test
    fun `select should prefer a cut parallel to the objective when efficacy ties`() {
        val pool = CutPool()
        val orthogonal = cut(Relation.GE, 2, 0 to 1L)
        val parallel = cut(Relation.GE, 2, 1 to 1L)
        pool.add(orthogonal)
        pool.add(parallel)

        val selected = pool.select(DoubleArray(2), doubleArrayOf(0.0, 1.0), max = 1)

        assertTrue(selected.single() === parallel)
    }

    @Test
    fun `select should honour the max cap and never invent cuts`() {
        val pool = CutPool()
        val all = (0 until 6).map { cut(Relation.GE, 5, it to 1L) }
        all.forEach { pool.add(it) }
        val sel = pool.select(DoubleArray(6), DoubleArray(6), max = 3)
        assertEquals(3, sel.size)
        assertTrue(sel.all { s -> all.any { it === s } })
    }

    @Test
    fun `select should treat antiparallel normals as the same direction`() {
        val pool = CutPool()
        val positive = cut(Relation.GE, 2, 0 to 1L)
        val negative = cut(Relation.GE, 2, 0 to -1L)
        pool.add(positive)
        pool.add(negative)

        val selected = pool.select(doubleArrayOf(0.0), doubleArrayOf(0.0), max = 2)

        assertEquals(1, selected.size)
        assertTrue(selected.single() === positive)
    }

    @Test
    fun `select should fall back to efficacy ordering when the objective norm overflows`() {
        val pool = CutPool()
        val first = cut(Relation.GE, 2, 0 to 1L)
        pool.add(first)
        pool.add(cut(Relation.GE, 1, 1 to 1L))

        val selected = pool.select(DoubleArray(2), doubleArrayOf(Double.MAX_VALUE, Double.MAX_VALUE), max = 1)

        assertTrue(selected.single() === first)
    }
}
