package com.eignex.klause.lp.relaxation

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.engine.CutAuxiliaryDefinition
import com.eignex.klause.lp.engine.CutExpression
import com.eignex.klause.lp.engine.CutPremise
import com.eignex.klause.lp.engine.CutProvenance
import com.eignex.klause.lp.engine.CutSource
import com.eignex.klause.lp.engine.CutSourceKind
import com.eignex.klause.lp.engine.ExactLpNumber
import com.eignex.klause.lp.engine.ExactLpSide
import com.eignex.klause.lp.engine.LpBoundTrail
import com.eignex.klause.lp.engine.LpBuilder
import com.eignex.klause.lp.engine.LpExactState
import com.eignex.klause.lp.engine.Sense
import com.eignex.klause.lp.engine.authoritativeModel
import com.eignex.klause.propagation.PropagationSession
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.parseBigInt
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
    fun `CP refresh should retain LP bound facts only for the same exact state`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 10)), emptyList())
        val session = PropagationSession(problem)
        val state = LpExactState(assertNotNull(LpBuilder().apply { addVar(0L, 10L) }
            .build(Sense.MINIMIZE).authoritativeModel()))
        val model = assertNotNull(state.ownerWorkingModel())
        val source = CutSource(CutSourceKind.INTEGER, 0)
        val expression = CutExpression(mapOf(source to BigFraction.ONE))
        val root = CutSourceMap(problem, 0L, listOf(CutColumnSource(source))).withBounds(model)
        for (derived in listOf(root, root.withParentRows(emptyMap()), root.withActivePremises(emptySet()))) {
            var charged: CutSourceWork? = null

            val refreshed = derived.withCpBounds(model, session) { charged = it }

            assertTrue(refreshed.isActive(CutPremise.Bound(expression, true, BigFraction.ofLong(10L))))
            assertEquals(0L, assertNotNull(charged).exact)
            assertTrue(assertNotNull(charged).structural > 0L)
        }
    }

    @Test
    fun `a different exact state should replace LP bounds through rollback`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 10)), emptyList())
        val session = PropagationSession(problem)
        val state = LpExactState(assertNotNull(LpBuilder().apply { addVar(0L, 10L) }
            .build(Sense.MINIMIZE).authoritativeModel()))
        val model = assertNotNull(state.ownerWorkingModel())
        val source = CutSource(CutSourceKind.INTEGER, 0)
        val expression = CutExpression(mapOf(source to BigFraction.ONE))
        val root = CutSourceMap(problem, 0L, listOf(CutColumnSource(source))).withBounds(model)
        val trail = LpBoundTrail(state)
        assertTrue(trail.assertBound(0, true, ExactLpSide(ExactLpNumber.of(5L)), 0L))
        var childWork: CutSourceWork? = null
        val child = root.withCpBounds(assertNotNull(trail.state.ownerWorkingModel()), session) { childWork = it }
        var restoredWork: CutSourceWork? = null

        val restored = child.withCpBounds(model, session) { restoredWork = it }

        val premise = CutPremise.Bound(expression, true, BigFraction.ofLong(5L))
        assertTrue(child.isActive(premise))
        assertFalse(restored.isActive(premise))
        assertTrue(assertNotNull(childWork).exact > 0L)
        assertTrue(assertNotNull(restoredWork).exact > 0L)
    }

    @Test
    fun `a model without exact authority should rebuild LP bound facts`() {
        val source = CutSource(CutSourceKind.INTEGER, 0)
        val model = LpBuilder().apply { addVar(0L, 10L) }.build(Sense.MINIMIZE)
        val root = CutSourceMap(Any(), 0L, listOf(CutColumnSource(source))).withBounds(model)
        var charged: CutSourceWork? = null

        root.withBounds(model, onWork = { charged = it })

        assertTrue(assertNotNull(charged).exact > 0L)
    }

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
        val state = LpExactState(assertNotNull(LpBuilder().apply { addVar(0L, 1L) }
            .build(Sense.MINIMIZE).authoritativeModel()))
        val model = assertNotNull(state.ownerWorkingModel())
        session.pinBool(0, true)
        val child = root.withCpBounds(model, session)
        session.popLast()

        val restored = child.withCpBounds(model, session)

        assertTrue(child.isActive(CutPremise.Literal(0)))
        assertFalse(restored.isActive(CutPremise.Literal(0)))
    }

    @Test
    fun `domain rebinding should discard a retracted auxiliary exclusion`() {
        for (cpCapture in listOf(false, true)) {
            val problem = Problem(0, 1, arrayOf(IntDomain(0, 1)), emptyList())
            val session = PropagationSession(problem)
            val source = CutSource(CutSourceKind.AUXILIARY, 0)
            val definition = CutAuxiliaryDefinition(listOf(0L), listOf(0L, 1L), 1L, true)
            val root = CutSourceMap(problem, 0L, listOf(CutColumnSource(source)),
                auxiliaryDefinitions = mapOf(source to definition))
            val state = LpExactState(assertNotNull(LpBuilder().apply { addVar(0L, 1L) }
                .build(Sense.MINIMIZE).authoritativeModel()))
            val model = assertNotNull(state.ownerWorkingModel())
            session.pinInt(0, 0L)
            val child = if (cpCapture) root.withCpBounds(model, session)
            else root.withDomainBounds(model, SessionDomains(session))
            session.popLast()
            val excluded = CutPremise.Excluded(CutSource(CutSourceKind.INTEGER, 0), BigFraction.ONE)

            val restored = if (cpCapture) child.withCpBounds(model, session)
            else child.withDomainBounds(model, SessionDomains(session))

            assertTrue(child.isActive(excluded))
            assertFalse(restored.isActive(excluded))
        }
    }

    @Test
    fun `appended duplicate real coordinates should withhold ambiguous source identities`() {
        val problem = Problem(0, 0, emptyArray(), emptyList(), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0))
        val catalog = LpAuxiliarySources()
        val initial = cpCutSourceLayout(problem, intArrayOf(-1), booleanArrayOf(false),
            intArrayOf(0), intArrayOf(1), listOf(null), catalog)

        val appended = cpCutSourceLayout(problem, intArrayOf(-1, -1), booleanArrayOf(false, false),
            intArrayOf(0, 0), intArrayOf(1, -1), listOf(null, null), catalog)

        assertEquals(CutSource(CutSourceKind.REAL, 0), initial.column(0)?.source)
        assertNull(appended.column(0))
        assertNull(appended.column(1))
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
            val problem = Problem(0, 1, arrayOf(if (cpUpper) IntDomain(0, 3) else IntDomain(3, 10)), emptyList())
            val root = CutSourceMap(problem, 0L, listOf(CutColumnSource(source)))
            val model = LpBuilder().apply { if (cpUpper) addVar(3L, 10L) else addVar(0L, 3L) }
                .build(Sense.MINIMIZE)

            val rebound = root.withCpBounds(model, PropagationSession(problem))

            assertTrue(rebound.isActive(CutPremise.Fixed(source, BigFraction.ofLong(3L))))
        }
    }

    @Test
    fun `cp bound checks preserve exact endpoint strictness without lp columns`() {
        for (origin in listOf(Long.MIN_VALUE, 0L, Long.MAX_VALUE)) {
            for (upper in listOf(false, true)) {
                val problem = Problem(0, 1, arrayOf(IntDomain(origin, origin)), emptyList())
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
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 10)), emptyList())
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
        val problem = Problem(0, 1, arrayOf(IntDomain(3, 10)), emptyList())
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
            val problem = Problem(1, 0, emptyArray(), emptyList())
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
        val problem = Problem(0, 1, arrayOf(IntDomain(3, 10)), emptyList())
        val root = CutSourceMap(problem, 0L, emptyList())
            .withCpBounds(LpBuilder().build(Sense.MINIMIZE), PropagationSession(problem))

        for (expression in listOf(CutExpression(mapOf(source to BigFraction.ofLong(2L))),
            CutExpression(mapOf(source to BigFraction.ONE), BigFraction.ONE))) {
            assertFalse(root.isActive(CutPremise.Bound(expression, false, BigFraction.ofLong(3L))))
        }
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
}
