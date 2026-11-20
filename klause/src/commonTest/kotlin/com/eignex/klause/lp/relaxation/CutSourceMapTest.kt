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
import com.eignex.util.numbers.parseBigInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CutSourceMapTest {
    @Test
    fun `source snapshots resist mutation through producers and derived readers`() {
        val x = CutSource(CutSourceKind.INTEGER, 0)
        val y = CutSource(CutSourceKind.INTEGER, 1)
        val first = CutSource(CutSourceKind.AUXILIARY, 0)
        val second = CutSource(CutSourceKind.AUXILIARY, 1)
        val definition = CutAuxiliaryDefinition(listOf(1L), emptyList(), 1L, true)
        val otherDefinition = CutAuxiliaryDefinition(listOf(2L), emptyList(), 1L, true)
        val columns = mutableListOf(CutColumnSource(x), CutColumnSource(y))
        val globals = mutableSetOf<CutPremise>(CutPremise.Literal(0), CutPremise.Literal(2))
        val active = mutableSetOf<CutPremise>(CutPremise.Literal(4), CutPremise.Literal(6))
        val fixed = mutableMapOf(x to BigFraction.ONE, y to BigFraction.ZERO)
        val assumptions = mutableSetOf("first", "second")
        val parent = CutProvenance(Any(), 0L, emptyList())
        val parents = mutableMapOf(0 to parent, 1 to parent)
        val auxiliary = mutableMapOf(first to definition, second to otherDefinition)
        val root = CutSourceMap(Any(), 0L, columns, globals, active, fixed, assumptions, parents, auxiliary)
        val maps = listOf(root, root.withParentRows(parents), root.withActivePremises(emptySet()))
        columns.clear()
        globals.clear()
        active.clear()
        fixed.clear()
        assumptions.clear()
        parents.clear()
        auxiliary.clear()
        for (map in maps) {
            runCatching { (map.columns as? MutableList)?.clear() }
            runCatching { (map.fixed as? MutableMap)?.clear() }
            runCatching { (map.assumptions as? MutableSet)?.clear() }
            runCatching { (map.auxiliaryDefinitions as? MutableMap)?.clear() }
        }

        for (map in maps) {
            assertEquals(listOf(CutColumnSource(x), CutColumnSource(y)), map.columns)
            assertTrue(map.isGlobal(CutPremise.Literal(0)))
            assertTrue(map.isGlobal(CutPremise.Literal(2)))
            assertTrue(map.isActive(CutPremise.Literal(4)))
            assertTrue(map.isActive(CutPremise.Literal(6)))
            assertFalse(map.isGlobal(CutPremise.Literal(4)))
            assertEquals(mapOf(x to BigFraction.ONE, y to BigFraction.ZERO), map.fixed)
            assertEquals(setOf("first", "second"), map.assumptions)
            assertEquals(parent, map.parent(0))
            assertEquals(parent, map.parent(1))
            assertEquals(mapOf(first to definition, second to otherDefinition), map.auxiliaryDefinitions)
        }
    }

    @Test
    fun `parent row replacement captures caller rows without changing preceding views`() {
        val old = CutProvenance(Any(), 0L, emptyList())
        val replacement = CutProvenance(Any(), 1L, emptyList())
        val root = CutSourceMap(Any(), 0L, emptyList(), parentRows = mapOf(0 to old, 1 to old))
        val parents = mutableMapOf(1 to replacement, 2 to replacement)

        val derived = root.withParentRows(parents)
        parents.clear()

        assertEquals(old, root.parent(0))
        assertEquals(old, root.parent(1))
        assertEquals(null, root.parent(2))
        assertEquals(null, derived.parent(0))
        assertEquals(replacement, derived.parent(1))
        assertEquals(replacement, derived.parent(2))
    }

    @Test
    fun `premise derivations capture caller facts without retroactive activation`() {
        for (lpRefresh in listOf(false, true)) {
            val original = CutPremise.Literal(0)
            val added = CutPremise.Literal(2)
            val later = CutPremise.Literal(4)
            val root = CutSourceMap(Any(), 0L, emptyList(), activePremises = setOf(original))
            val facts = mutableSetOf<CutPremise>(added)
            val model = LpBuilder().build(Sense.MINIMIZE)

            val derived = if (lpRefresh) root.withBounds(model, facts) else root.withActivePremises(facts)
            facts.clear()
            facts.add(later)

            assertTrue(root.isActive(original))
            assertFalse(root.isActive(added))
            assertTrue(derived.isActive(added))
            assertEquals(!lpRefresh, derived.isActive(original))
            assertFalse(derived.isActive(later))
        }
    }

    @Test
    fun `derived auxiliary lookups preserve unique and ambiguous definitions`() {
        for (ambiguous in listOf(false, true)) {
            val first = CutSource(CutSourceKind.AUXILIARY, 0)
            val second = CutSource(CutSourceKind.AUXILIARY, 1)
            val unknown = CutSource(CutSourceKind.AUXILIARY, 2)
            val definition = CutAuxiliaryDefinition(listOf(1L), emptyList(), 1L, true)
            val definitions = if (ambiguous) mapOf(first to definition, second to definition)
                else mapOf(first to definition)
            val root = CutSourceMap(Any(), 0L, emptyList(), auxiliaryDefinitions = definitions)
            val expected = if (ambiguous) null else first
            assertEquals(expected, root.auxiliarySource(unknown, definition))

            val maps = listOf(root.withParentRows(emptyMap()), root.withActivePremises(emptySet()))

            for (map in maps) {
                assertEquals(first, map.auxiliarySource(first, definition))
                assertEquals(expected, map.auxiliarySource(unknown, definition))
            }
        }
    }

    @Test
    fun `copied column bindings should evaluate their own source geometry`() {
        val x = CutSource(CutSourceKind.INTEGER, 0)
        val y = CutSource(CutSourceKind.INTEGER, 1)
        val original = CutColumnSource(x)
        val expression = original.expression()
        val variants = listOf(
            original.copy(source = y) to 7L,
            original.copy(scale = BigFraction.ofLong(2)) to 10L,
            original.copy(offset = BigFraction.ofLong(3)) to 8L,
        )

        for ((column, expected) in variants) {
            val value = column.expression().value { if (it == x) BigFraction.ofLong(5) else BigFraction.ofLong(7) }

            assertEquals(BigFraction.ofLong(expected), value)
            assertEquals(BigFraction.ofLong(5), expression.value { BigFraction.ofLong(5) })
        }
    }

    @Test
    fun `CP bounds should retain raw integer meaning for transformed or absent columns`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 10) }, emptyArray())
        val session = PropagationSession(problem)
        session.pinIntAtLeast(0, 3)
        val builder = LpBuilder()
        builder.addVar(0, 10)
        val model = builder.build(Sense.MINIMIZE)
        val x = CutSource(CutSourceKind.INTEGER, 0)
        val unit = CutColumnSource(x)
        val variants = listOf(
            unit,
            null,
            unit.copy(source = CutSource(CutSourceKind.INTEGER, 1)),
            unit.copy(source = CutSource(CutSourceKind.BOOLEAN, 0)),
            unit.copy(scale = BigFraction.ofLong(2)),
            unit.copy(offset = BigFraction.ONE),
        )
        val expression = CutExpression(mapOf(x to BigFraction.ONE))

        for (column in variants) {
            val source = CutSourceMap(problem, 0, listOf(column))

            val active = source.withCpBounds(model, session)

            assertTrue(active.isActive(CutPremise.Bound(expression, false, BigFraction.ofLong(3))))
            assertFalse(active.isActive(CutPremise.Bound(expression, false, BigFraction.ofLong(4))))
            assertFalse(active.isGlobal(CutPremise.Bound(expression, false, BigFraction.ofLong(3))))
        }
    }

    @Test
    fun `refreshed CP bounds should not retain a stronger preceding view`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 10)), emptyArray())
        val builder = LpBuilder()
        builder.addVar(0, 10)
        val model = builder.build(Sense.MINIMIZE)
        val x = CutSource(CutSourceKind.INTEGER, 0)
        val source = CutSourceMap(problem, 0, listOf(CutColumnSource(x)))
        val tightSession = PropagationSession(problem)
        tightSession.pinIntAtLeast(0, 7)
        val tight = source.withCpBounds(model, tightSession)
        val looseSession = PropagationSession(problem)
        looseSession.pinIntAtLeast(0, 2)
        val expression = CutExpression(mapOf(x to BigFraction.ONE))
        val strong = CutPremise.Bound(expression, false, BigFraction.ofLong(7))

        val loose = tight.withCpBounds(model, looseSession)

        assertFalse(loose.isActive(strong))
        assertTrue(loose.isActive(CutPremise.Bound(expression, false, BigFraction.ofLong(2))))
        assertTrue(tight.isActive(strong))
        assertFalse(source.isActive(strong))
        assertEquals(tight.epoch + 1, loose.epoch)
    }

    @Test
    fun `active fixed facts remain available without separate bounds`() {
        val source = CutSource(CutSourceKind.INTEGER, 0)
        val fixed = CutPremise.Fixed(source, BigFraction.ONE)
        val root = CutSourceMap(Any(), 0L, emptyList())

        val derived = root.withActivePremises(setOf(fixed))

        assertTrue(derived.isActive(fixed))
        assertFalse(root.isActive(fixed))
    }

    @Test
    fun `fixed premises combine lp and cp bound directions`() {
        for (cpUpper in listOf(false, true)) {
            val source = CutSource(CutSourceKind.INTEGER, 0)
            val problem = Problem(0, 1, arrayOf(if (cpUpper) IntDomain(0, 3) else IntDomain(3, 10)), emptyArray())
            val root = CutSourceMap(problem, 0L, listOf(CutColumnSource(source)))
            val model = LpBuilder().apply { if (cpUpper) addVar(3L, 10L) else addVar(0L, 3L) }
                .build(Sense.MINIMIZE)

            val rebound = root.withCpBounds(model, PropagationSession(problem))

            assertTrue(rebound.isActive(CutPremise.Fixed(source, BigFraction.ofLong(3L))))
        }
    }

    @Test
    fun `global and active bound directions should retain separate fixed inference`() {
        val source = CutSource(CutSourceKind.INTEGER, 0)
        val problem = Problem(0, 1, arrayOf(IntDomain(3, 10)), emptyArray())
        val expression = CutExpression(mapOf(source to BigFraction.ONE))
        val root = CutSourceMap(problem, 0L, emptyList(),
            globalPremises = setOf(CutPremise.Bound(expression, true, BigFraction.ofLong(3L))))
        val model = LpBuilder().build(Sense.MINIMIZE)

        val rebound = root.withCpBounds(model, PropagationSession(problem))

        assertFalse(rebound.isActive(CutPremise.Fixed(source, BigFraction.ofLong(3L))))
    }

    @Test
    fun `cp bound checks preserve exact endpoint strictness without lp columns`() {
        for (origin in listOf(Long.MIN_VALUE, 0L, Long.MAX_VALUE)) {
            for (upper in listOf(false, true)) {
                val problem = Problem(0, 1, arrayOf(IntDomain(origin, origin)), emptyArray())
                val source = CutSource(CutSourceKind.INTEGER, 0)
                val expression = CutExpression(mapOf(source to BigFraction.ONE))
                val exact = BigFraction.ofLong(origin)
                val half = BigFraction.of(parseBigInt("1"), parseBigInt("2"))
                val weaker = if (upper) exact + half else exact - half
                val stronger = if (upper) exact - half else exact + half
                val map = CutSourceMap(problem, 0L, emptyList())
                    .withCpBounds(LpBuilder().build(Sense.MINIMIZE), PropagationSession(problem))

                assertTrue(map.isActive(CutPremise.Bound(expression, upper, exact)))
                assertFalse(map.isActive(CutPremise.Bound(expression, upper, exact, strict = true)))
                assertTrue(map.isActive(CutPremise.Bound(expression, upper, weaker, strict = true)))
                assertFalse(map.isActive(CutPremise.Bound(expression, upper, stronger)))
                assertFalse(map.isGlobal(CutPremise.Bound(expression, upper, exact)))
            }
        }
    }

    @Test
    fun `cp rebinding captures absent column bounds without retaining the session`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 10)), emptyArray())
        val session = PropagationSession(problem)
        val expression = CutExpression(mapOf(CutSource(CutSourceKind.INTEGER, 0) to BigFraction.ONE))
        val lower = CutPremise.Bound(expression, false, BigFraction.ofLong(3L))
        val model = LpBuilder().build(Sense.MINIMIZE)
        session.pinInt(0, 3L)
        val child = CutSourceMap(problem, 0L, emptyList()).withCpBounds(model, session)
        session.popLast()

        val restored = child.withCpBounds(model, session)

        assertTrue(child.isActive(lower))
        assertFalse(restored.isActive(lower))
    }

    @Test
    fun `source derivations preserve captured cp bounds`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 10)), emptyArray())
        val session = PropagationSession(problem)
        session.pinIntAtLeast(0, 3L)
        val expression = CutExpression(mapOf(CutSource(CutSourceKind.INTEGER, 0) to BigFraction.ONE))
        val lower = CutPremise.Bound(expression, false, BigFraction.ofLong(3L))
        val root = CutSourceMap(problem, 0L, emptyList())
            .withCpBounds(LpBuilder().build(Sense.MINIMIZE), session)
        val derived = listOf(root.withParentRows(emptyMap()), root.withActivePremises(emptySet()))
        session.popLast()

        for (map in derived) {
            assertTrue(map.isActive(lower))
        }
    }

    @Test
    fun `lp bound refresh discards captured cp bounds`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(3, 10)), emptyArray())
        val expression = CutExpression(mapOf(CutSource(CutSourceKind.INTEGER, 0) to BigFraction.ONE))
        val lower = CutPremise.Bound(expression, false, BigFraction.ofLong(3L))
        val model = LpBuilder().build(Sense.MINIMIZE)
        val root = CutSourceMap(problem, 0L, emptyList()).withCpBounds(model, PropagationSession(problem))

        val rebound = root.withBounds(model)

        assertFalse(rebound.isActive(lower))
        assertTrue(root.isActive(lower))
    }

    @Test
    fun `captured Boolean pins preserve polarity and distinguish unknown values`() {
        for (value in listOf(false, true)) {
            val problem = Problem(1, 0, emptyArray(), emptyArray())
            val session = PropagationSession(problem)
            val model = LpBuilder().build(Sense.MINIMIZE)
            val root = CutSourceMap(problem, 0L, emptyList())
            session.pinBool(0, value)
            val child = root.withCpBounds(model, session)
            session.popLast()

            val restored = child.withCpBounds(model, session)

            assertTrue(child.isActive(CutPremise.Literal(if (value) 0 else 1)))
            assertFalse(child.isActive(CutPremise.Literal(if (value) 1 else 0)))
            for (literal in listOf(0, 1, 2, -1)) {
                assertFalse(restored.isActive(CutPremise.Literal(literal)))
            }
        }
    }

    @Test
    fun `cp snapshots do not imply bounds in transformed coordinates`() {
        val source = CutSource(CutSourceKind.INTEGER, 0)
        val problem = Problem(0, 1, arrayOf(IntDomain(3, 10)), emptyArray())
        val root = CutSourceMap(problem, 0L, emptyList())
            .withCpBounds(LpBuilder().build(Sense.MINIMIZE), PropagationSession(problem))

        for (expression in listOf(CutExpression(mapOf(source to BigFraction.ofLong(2L))),
            CutExpression(mapOf(source to BigFraction.ONE), BigFraction.ONE))) {
            assertFalse(root.isActive(CutPremise.Bound(expression, false, BigFraction.ofLong(3L))))
        }
    }
}
