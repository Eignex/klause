package com.eignex.klause.lp.relaxation

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.engine.CutAuxiliaryDefinition
import com.eignex.klause.lp.engine.CutExpression
import com.eignex.klause.lp.engine.CutPremise
import com.eignex.klause.lp.engine.CutProvenance
import com.eignex.klause.lp.engine.CutSource
import com.eignex.klause.lp.engine.CutSourceKind
import com.eignex.klause.lp.engine.LpBuilder
import com.eignex.klause.lp.engine.Sense
import com.eignex.klause.propagation.PropagationSession
import com.eignex.util.numbers.BigFraction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CutSourceMapTest {
    @Test
    fun `failed source capture should retain its failure and reported work`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 10)), emptyList())
        val primary = IllegalStateException("binding")
        val reporting = IllegalArgumentException("reporting")
        var charged: CutSourceWork? = null

        val failure = assertFailsWith<IllegalStateException> {
            cpCutSources(problem, intArrayOf(0), booleanArrayOf(false), intArrayOf(-1), intArrayOf(1),
                emptyMap(), listOf(null), LpAuxiliarySources(), null, onWork = {
                    charged = it
                    throw reporting
                }) { throw primary }
        }

        assertSame(primary, failure)
        assertSame(reporting, failure.suppressedExceptions.single())
        assertTrue(assertNotNull(charged).structural > 0L)
        assertTrue(assertNotNull(charged).exact > 0L)
    }

    @Test
    fun `a failed work report should withhold a completed bound refresh`() {
        val source = CutSource(CutSourceKind.INTEGER, 0)
        val root = CutSourceMap(Any(), 0L, listOf(CutColumnSource(source)))
        val expression = CutExpression(mapOf(source to BigFraction.ONE))
        val model = LpBuilder().apply { addVar(0L, 1L) }.build(Sense.MINIMIZE)
        val reporting = IllegalStateException("reporting")

        val failure = assertFailsWith<IllegalStateException> {
            root.withBounds(model, onWork = { throw reporting })
        }

        assertSame(reporting, failure)
        assertFalse(root.isActive(CutPremise.Bound(expression, true, BigFraction.ONE)))
    }

    @Test
    fun `cp rebinding should discard a retracted Boolean pin`() {
        val problem = Problem(1, 0, emptyArray(), emptyList())
        val session = PropagationSession(problem)
        val source = CutSource(CutSourceKind.BOOLEAN, 0)
        val root = CutSourceMap(problem, 0L, listOf(CutColumnSource(source)))
        val model = LpBuilder().apply { addVar(0L, 1L) }.build(Sense.MINIMIZE)
        session.pinBool(0, true)
        val child = root.withCpBounds(model, session)
        session.popLast()

        val restored = child.withCpBounds(model, session)

        assertTrue(child.isActive(CutPremise.Literal(0)))
        assertFalse(restored.isActive(CutPremise.Literal(0)))
    }

    @Test
    fun `cp rebinding should discard a retracted auxiliary exclusion`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 1)), emptyList())
        val session = PropagationSession(problem)
        val source = CutSource(CutSourceKind.AUXILIARY, 0)
        val definition = CutAuxiliaryDefinition(listOf(0L), listOf(0L, 1L), 1L, true)
        val root = CutSourceMap(problem, 0L, listOf(CutColumnSource(source)),
            auxiliaryDefinitions = mapOf(source to definition))
        val model = LpBuilder().apply { addVar(0L, 1L) }.build(Sense.MINIMIZE)
        session.pinInt(0, 0L)
        val child = root.withCpBounds(model, session)
        session.popLast()
        val excluded = CutPremise.Excluded(CutSource(CutSourceKind.INTEGER, 0), BigFraction.ONE)

        val restored = child.withCpBounds(model, session)

        assertTrue(child.isActive(excluded))
        assertFalse(restored.isActive(excluded))
    }

    @Test
    fun `source maps capture borrowed input collections`() {
        val source = CutSource(CutSourceKind.INTEGER, 0)
        val terms = mutableMapOf(source to BigFraction.ONE)
        val lower = CutPremise.Bound(CutExpression(terms), false, BigFraction.ZERO)
        val globals = mutableSetOf<CutPremise>(lower)
        val columns = mutableListOf<CutColumnSource?>(CutColumnSource(source))
        val map = CutSourceMap(Any(), 0, columns, globalPremises = globals)

        terms.clear()
        globals.clear()
        columns.clear()

        assertTrue(map.isGlobal(CutPremise.Bound(CutExpression(mapOf(source to BigFraction.ONE)),
            false, BigFraction.ZERO)))
        assertEquals(source, map.column(0)?.source)
    }

    @Test
    fun `sibling bounds do not inherit each others active premises`() {
        val source = CutSource(CutSourceKind.INTEGER, 0)
        val expression = CutExpression(mapOf(source to BigFraction.ONE))
        val root = CutSourceMap(Any(), 0, listOf(CutColumnSource(source)))
        val firstBuilder = LpBuilder().apply { addVar(2L, 7L) }
        val secondBuilder = LpBuilder().apply { addVar(6L, 9L) }

        val first = root.withBounds(firstBuilder.build(Sense.MINIMIZE))
        val second = root.withBounds(secondBuilder.build(Sense.MINIMIZE))

        val upper = CutPremise.Bound(expression, true, BigFraction.ofLong(7L))
        assertTrue(first.isActive(upper))
        assertFalse(second.isActive(upper))
        assertFalse(root.isActive(upper))
    }

    @Test
    fun `derived row parents capture their input without changing the source map`() {
        val root = CutSourceMap(Any(), 0, emptyList())
        val proof = CutProvenance(root.model, 0, emptyList())
        val parents = mutableMapOf(0 to proof)

        val derived = root.withParentRows(parents)
        parents.clear()

        assertSame(proof, derived.parent(0))
        assertNull(root.parent(0))
    }

    @Test
    fun `cp bounds retain source units when an lp column is affine`() {
        val source = CutSource(CutSourceKind.INTEGER, 0)
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 10)), emptyList())
        val session = PropagationSession(problem)
        session.implyIntAtLeast(0, 3L)
        val map = CutSourceMap(problem, 0,
            listOf(CutColumnSource(source, BigFraction.ofLong(2L), BigFraction.ONE)))
        val builder = LpBuilder().apply { addVar(1L, 2L) }

        val rebound = map.withCpBounds(builder.build(Sense.MINIMIZE), session)

        val expression = CutExpression(mapOf(source to BigFraction.ONE))
        assertTrue(rebound.isActive(CutPremise.Bound(expression, false, BigFraction.ofLong(3L))))
        assertFalse(rebound.isActive(CutPremise.Bound(expression, false, BigFraction.ofLong(4L))))
    }
}
