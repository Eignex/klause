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
import com.eignex.klause.util.Cancellation
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
            CutPremise.Excluded(source, BigFraction.ofLong(2)),
            CutPremise.ObjectiveCutoff(expression, BigFraction.ofLong(5)),
        )
        val cut = SourceCut(expression, Relation.LE, BigFraction.ofLong(3),
            CutProvenance(model, 0L, premises.map { CutProofFact(it, false) }, family = CutFamily.GOMORY))
        for (prepared in listOf(false, true)) for (missing in premises) {
            val events = ArrayList<Triple<CutFamily, String, String>>()
            val pool = CutPool(prepareSources = prepared,
                observe = { family, stage, outcome -> events.add(Triple(family, stage, outcome)) })
            val active = CutSourceMap(model, 0L, listOf(CutColumnSource(source)), activePremises = premises.toSet())
            assertTrue(pool.add(cut, active))
            repeat(2) { pool.remap(active) }
            pool.remap(CutSourceMap(Any(), 0L, listOf(CutColumnSource(source)),
                activePremises = premises.toSet()))
            assertTrue(pool.cuts().isEmpty())
            pool.remap(active)

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
    fun `projected rows should follow layout changes and release their storage on retirement`() {
        val model = Any()
        val x = CutSource(CutSourceKind.INTEGER, 0)
        val expression = CutExpression(mapOf(x to BigFraction.ONE))
        val facts = listOf(CutProofFact(CutPremise.Bound(expression, false, BigFraction.ZERO), true),
            CutProofFact(CutPremise.Bound(expression, true, BigFraction.ONE), true))
        val source = SourceCut(expression, Relation.LE, BigFraction.ONE, CutProvenance(model, 0, facts,
            conclusion = CutPremise.Row(expression, Relation.LE, BigFraction.ONE)))
        val first = CutSourceMap(model, 0, listOf(null, CutColumnSource(x)))
        val events = ArrayList<Pair<String, String>>()
        val pool = CutPool(maxConsecutiveInactive = 1, prepareSources = true,
            observe = { _, stage, outcome -> events.add(stage to outcome) })
        assertTrue(pool.add(source, first))
        repeat(4) { pool.remap(first) }
        assertTrue("PROJECTION" to "REUSED" in events)
        val current = CutSourceMap(model, 1, listOf(CutColumnSource(x), null))

        repeat(3) { pool.remap(current) }

        assertTrue("PROJECTION" to "INVALIDATED" in events)
        assertEquals(0, pool.cuts().single().cols.single())
        pool.observe(doubleArrayOf(0.0, 0.0))
        assertEquals(0L, pool.preparationStorageUnits)
    }

    @Test
    fun `cancelled projection capture should leave the input reusable without publishing a cut`() {
        val model = Any()
        val x = CutSource(CutSourceKind.INTEGER, 0)
        val expression = CutExpression(mapOf(x to BigFraction.ONE))
        val facts = listOf(CutProofFact(CutPremise.Bound(expression, false, BigFraction.ZERO), true),
            CutProofFact(CutPremise.Bound(expression, true, BigFraction.ONE), true))
        val source = SourceCut(expression, Relation.LE, BigFraction.ONE, CutProvenance(model, 0, facts,
            conclusion = CutPremise.Row(expression, Relation.LE, BigFraction.ONE)))
        val map = CutSourceMap(model, 0, listOf(CutColumnSource(x)))
        var cancelled = false
        var cancelProjection = false
        var work = 0L
        val events = ArrayList<Pair<String, String>>()
        val pool = CutPool(prepareSources = true, cancellation = Cancellation { cancelled },
            onPreparationWork = { work += it; if (cancelProjection && it >= source.projectedPreparationUnits) cancelled = true },
            observe = { _, stage, outcome -> events.add(stage to outcome) })
        assertTrue(pool.add(source, map))
        repeat(2) { pool.remap(map) }
        val before = work
        cancelProjection = true

        pool.remap(map)

        assertTrue(work - before >= source.projectedPreparationUnits)
        assertTrue("PROJECTION" to "CANCELLED" in events)
        assertFalse("PROJECTION" to "RETAINED" in events)
        assertTrue(pool.cuts().isEmpty())
        cancelled = false
        cancelProjection = false
        repeat(2) { pool.remap(map) }
        assertEquals(0, pool.cuts().single().cols.single())
        assertTrue("PROJECTION" to "REUSED" in events)
    }

    @Test
    fun `small immutable inputs should keep their cuts without retained preparation`() {
        val model = Any()
        val x = CutSource(CutSourceKind.INTEGER, 0)
        val expression = CutExpression(mapOf(x to BigFraction.ONE))
        val map = CutSourceMap(model, 0, listOf(CutColumnSource(x)))
        val source = SourceCut(expression, Relation.LE, BigFraction.ONE, CutProvenance(model, 0, emptyList()))
        var work = 0L
        val pool = CutPool(prepareSources = true, onPreparationWork = { work += it })
        assertTrue(pool.add(source, map))

        repeat(3) { pool.remap(map) }

        assertEquals(1, pool.cuts().size)
        assertEquals(0L, pool.preparationStorageUnits)
        assertEquals(0L, work)
    }

    @Test
    fun `cancelled preparation should spend capture work without retaining a prepared input`() {
        val model = Any()
        val x = CutSource(CutSourceKind.INTEGER, 0)
        val expression = CutExpression(mapOf(x to BigFraction.ONE))
        val source = SourceCut(expression, Relation.LE, BigFraction.ONE, CutProvenance(model, 0,
            listOf(CutProofFact(CutPremise.Bound(expression, true, BigFraction.ONE), true)),
            conclusion = CutPremise.Row(expression, Relation.LE, BigFraction.ONE)))
        val map = CutSourceMap(model, 0, listOf(CutColumnSource(x)))
        var cancelled = false
        var cancelOnCharge = true
        var work = 0L
        val pool = CutPool(prepareSources = true, cancellation = Cancellation { cancelled },
            onPreparationWork = { work += it; if (cancelOnCharge) cancelled = true })
        assertTrue(pool.add(source, map))
        pool.remap(map)

        pool.remap(map)

        assertTrue(work > 0L)
        assertEquals(0L, pool.preparationStorageUnits)
        assertTrue(pool.cuts().isEmpty())
        cancelled = false
        cancelOnCharge = false
        pool.remap(map)
        assertTrue(pool.preparationStorageUnits > 0L)
        assertEquals(1, pool.cuts().size)
    }

    @Test
    fun `retiring pooled cuts should release their prepared input storage`() {
        val model = Any()
        val x = CutSource(CutSourceKind.INTEGER, 0)
        val expression = CutExpression(mapOf(x to BigFraction.ONE))
        val map = CutSourceMap(model, 0, listOf(CutColumnSource(x)))
        val pool = CutPool(maxCuts = 1, maxConsecutiveInactive = 1, prepareSources = true)
        for (rhs in listOf(5L, 6L)) {
            assertTrue(pool.add(SourceCut(expression, Relation.LE, BigFraction.ofLong(rhs),
                CutProvenance(model, 0,
                    listOf(CutProofFact(CutPremise.Bound(expression, true, BigFraction.ofLong(rhs)), true)),
                    conclusion = CutPremise.Row(expression, Relation.LE, BigFraction.ofLong(rhs)))), map))
        }
        repeat(2) { pool.remap(map) }
        val before = pool.preparationStorageUnits
        assertTrue(before > 0L)

        pool.retainMostActive()
        assertTrue(pool.preparationStorageUnits in 1L until before)
        pool.observe(doubleArrayOf(0.0))

        assertEquals(0L, pool.preparationStorageUnits)
        assertEquals(0, pool.size)
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
        val expression = CutExpression(mapOf(source to BigFraction.ONE))
        val portable = SourceCut(
            expression,
            Relation.LE,
            BigFraction.ofLong(5),
            CutProvenance(token, 0,
                listOf(CutProofFact(CutPremise.Bound(expression, true, BigFraction.ofLong(5)), true))),
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
        val search = CutPool(prepareSources = true)

        assertEquals(1, search.addAll(root.cuts()))
        repeat(2) { search.remap(map) }
        assertTrue(search.preparationStorageUnits > 0L)
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
