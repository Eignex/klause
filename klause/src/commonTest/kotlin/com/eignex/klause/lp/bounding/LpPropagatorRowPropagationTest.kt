package com.eignex.klause.lp.bounding

import com.eignex.klause.lp.engine.ExactLpBounds
import com.eignex.klause.lp.engine.ExactLpColumn
import com.eignex.klause.lp.engine.ExactLpEntry
import com.eignex.klause.lp.engine.ExactLpModel
import com.eignex.klause.lp.engine.ExactLpNumber
import com.eignex.klause.lp.engine.ExactLpObjective
import com.eignex.klause.lp.engine.ExactLpPremises
import com.eignex.klause.lp.engine.ExactLpRow
import com.eignex.klause.lp.engine.ExactLpSide
import com.eignex.klause.lp.engine.LpExactState
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.RegisteredTheoryDecision
import com.eignex.klause.solver.search.SearchAtomPremise
import com.eignex.klause.solver.search.SearchAtomRegistry
import com.eignex.klause.solver.search.SearchComponent
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchRealValue
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.theory.qflra.SourceBoundAtom
import com.eignex.klause.theory.qflra.SourceBoundTerm
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LpPropagatorRowPropagationTest {
    @Test
    fun `shared queue applies each implication once and expands chained source reasons`() {
        val fixture = Fixture()
        fixture.use {
            assertIs<ComponentResult.Consistent>(it.session.initialize())
            it.session.push(SearchDecision.Bool(0))
            assertIs<ComponentResult.Consistent>(it.session.propagate())
            assertEquals(2, it.applied.size)
            assertEquals(it.applied, it.observed)
            for (decision in it.applied) {
                val literal = assertNotNull(it.session.atomLiteral(decision))
                assertEquals(setOf(literal, 1), assertNotNull(it.session.reasonFor(literal ushr 1)).literals.toSet())
            }
            assertEquals(BigFraction.ofLong(7), it.lp.state?.activeSide(0, false)?.side?.number?.value)
            assertEquals(BigFraction.ofLong(5), it.lp.state?.activeSide(2, true)?.side?.number?.value)
            val passes = it.rows.passes
            it.session.propagate()
            assertEquals(passes, it.rows.passes)
        }
    }

    @Test
    fun `pop and equal value reassertion replace the original source witness`() {
        Fixture().use {
            it.session.initialize()
            it.session.push(SearchDecision.Bool(0))
            it.session.propagate()
            it.session.popTo(0)
            it.session.push(SearchDecision.Bool(2))
            it.session.propagate()
            assertEquals(4, it.applied.size)
            for (decision in it.applied.takeLast(2)) {
                val literal = assertNotNull(it.session.atomLiteral(decision))
                assertEquals(setOf(literal, 3), assertNotNull(it.session.reasonFor(literal ushr 1)).literals.toSet())
            }
        }
    }

    @Test
    fun `restart does not renew the retained work receipt`() {
        Fixture().use {
            it.session.initialize()
            it.session.push(SearchDecision.Bool(0))
            it.session.propagate()
            val spent = it.rows.budget.work
            it.session.restart()
            assertTrue(it.rows.budget.work >= spent)
            it.session.push(SearchDecision.Bool(0))
            assertIs<ComponentResult.Consistent>(it.session.propagate())
            assertEquals(4, it.applied.size)
        }
    }

    @Test
    fun `non global row retains its active guard in every source reason`() {
        Fixture(guarded = true).use {
            it.session.initialize()
            it.session.push(SearchDecision.Bool(4))
            it.session.push(SearchDecision.Bool(0))
            it.session.propagate()
            assertEquals(2, it.applied.size)
            for (decision in it.applied) {
                val literal = assertNotNull(it.session.atomLiteral(decision))
                assertEquals(setOf(literal, 1, 5), assertNotNull(it.session.reasonFor(literal ushr 1)).literals.toSet())
            }
        }
    }

    @Test
    fun `an unavailable row guard prevents every dependent publication`() {
        Fixture(guarded = true).use {
            it.session.initialize()
            it.session.push(SearchDecision.Bool(0))
            it.session.propagate()
            assertTrue(it.applied.isEmpty())
            assertTrue(it.rows.reasonDeclines > 0)
        }
    }

    @Test
    fun `owner replacement during mapping invalidates final publication`() {
        Fixture().use {
            it.session.initialize()
            it.onMap = { it.lp.releaseSolver() }
            it.session.push(SearchDecision.Bool(0))
            assertIs<ComponentResult.Indeterminate>(it.session.propagate())
            assertTrue(it.applied.isEmpty())
        }
    }

    @Test
    fun `cancellation during mapping prevents final publication`() {
        Fixture().use {
            it.session.initialize()
            it.onMap = { it.cancelled = true }
            it.session.push(SearchDecision.Bool(0))
            assertIs<ComponentResult.Indeterminate>(it.session.propagate())
            assertTrue(it.applied.isEmpty())
        }
    }

    @Test
    fun `cancellation during queued native application returns indeterminate`() {
        Fixture().use {
            it.session.initialize()
            it.onApply = { it.cancelled = true }
            it.session.push(SearchDecision.Bool(0))
            assertIs<ComponentResult.Indeterminate>(it.session.propagate())
            assertEquals(1, it.applied.size)
        }
    }

    @Test
    fun `shared check denial performs no optional scan`() {
        Fixture(checks = 0).use {
            assertIs<ComponentResult.Indeterminate>(it.session.initialize())
            assertEquals(0L, it.rows.budget.work)
            assertEquals(0L, it.rows.passes)
        }
    }

    @Test
    fun `premise and publication capacity exhaustion skip safely`() {
        for (limits in listOf(RowPropagationLimits(expansion = 0), RowPropagationLimits(publications = 0))) {
            Fixture(limits = limits).use {
                it.session.initialize()
                it.session.push(SearchDecision.Bool(0))
                assertIs<ComponentResult.Consistent>(it.session.propagate())
                assertTrue(it.applied.isEmpty())
            }
        }
    }

    @Test
    fun `a deactivated equation cannot produce a new implication`() {
        Fixture().use {
            it.session.initialize()
            val id = assertNotNull(it.lp.state).rows.row(0).id
            assertTrue(it.lp.deactivate(id))
            it.session.push(SearchDecision.Bool(0))
            it.session.propagate()
            assertTrue(it.applied.isEmpty())
        }
    }

    @Test
    fun `a new installation rejects a token from the same source ids`() {
        Fixture().use {
            it.session.initialize()
            val prior = assertNotNull(it.lp.rowSource(it.source))
            assertTrue(it.lp.install(Any(), it.source))
            assertNull(it.lp.rowSource(it.source))
            assertEquals(false, it.lp.ownsRowSource(it.source, assertNotNull(it.lp.state), prior))
        }
    }

    private class Fixture(
        guarded: Boolean = false,
        checks: Long = Long.MAX_VALUE,
        limits: RowPropagationLimits = RowPropagationLimits(),
    ) : AutoCloseable {
        val rows = ExactRowPropagation(limits)
        var cancelled = false
        var onMap: () -> Unit = {}
        var onApply: () -> Unit = {}
        val applied = ArrayList<SearchDecision>()
        val observed = ArrayList<SearchDecision>()
        private val zero = ExactLpNumber.of(0L)
        val source = ExactLpModel(
            listOf(
                listOf(ExactLpEntry(0, ExactLpNumber.of(1L)), ExactLpEntry(1, ExactLpNumber.of(1L))),
                listOf(ExactLpEntry(0, ExactLpNumber.of(1L))),
                listOf(ExactLpEntry(1, ExactLpNumber.of(1L))),
            ),
            listOf(ExactLpNumber.of(10L), ExactLpNumber.of(12L)),
            List(3) { ExactLpColumn(ExactLpBounds(), integral = false, tag = it) } +
                List(2) { ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(zero)), integral = false) },
            listOf(
                ExactLpRow(global = !guarded, premises = if (guarded) ExactLpPremises(emptyList(), listOf(4)) else null),
                ExactLpRow(),
            ),
            ExactLpObjective(List(5) { zero }),
        )
        private var token: Any? = null
        val lp: LpPropagator = LpPropagator(object : LpSearchPolicy {
            override fun initialize(context: SearchContext) {
                check(lp.install(source, source))
                token = lp.rowSource(source)
            }
            override fun assert(decision: SearchDecision, context: SearchContext): ComponentResult {
                if (decision is SearchDecision.Bool && decision.literal in listOf(0, 2)) {
                    lp.assertBound(1, true, ExactLpSide(ExactLpNumber.of(3L)), SearchAtomPremise.Asserted(decision))
                }
                if (decision is SearchDecision.Theory) {
                    val payload = (decision.decision as? RegisteredTheoryDecision)?.payload as? SourceBoundAtom
                        ?: return ComponentResult.Consistent
                    val column = (payload.terms.single().source as SearchRealValue).variable
                    if (!lp.assertBound(column, payload.upper,
                            ExactLpSide(ExactLpNumber.of(payload.threshold), payload.strict),
                            SearchAtomPremise.Asserted(decision))) return ComponentResult.Indeterminate
                    applied.add(decision)
                    onApply()
                }
                return ComponentResult.Consistent
            }
            override fun propagate(context: SearchContext): ComponentResult = when (val result = lp.propagateRows(context)) {
                is RowPropagationResult.Conflict -> ComponentResult.Conflict(result.explanation)
                RowPropagationResult.Indeterminate -> ComponentResult.Indeterminate
                else -> ComponentResult.Consistent
            }
            override fun rowDecision(
                state: LpExactState,
                column: Int,
                upper: Boolean,
                side: ExactLpSide,
                context: SearchContext,
            ): SearchDecision? {
                if (column !in 0..2 || token?.let { lp.ownsRowSource(source, state, it) } != true) return null
                onMap()
                val atom = SourceBoundAtom.rationalSplit(context,
                    listOf(SourceBoundTerm(SearchRealValue(column), BigFraction.ONE)), side.number.value,
                    if (upper) side.strict else !side.strict) ?: return null
                return SearchDecision.Theory(if (upper) atom.positive else atom.negative)
            }
        }, cancellation = Cancellation { cancelled }, rowPropagation = rows)
        val session = SearchSession(listOf(lp, object : SearchComponent {
            override fun assert(decision: SearchDecision, context: SearchContext): ComponentResult {
                if (decision is SearchDecision.Theory) observed.add(decision)
                return ComponentResult.Consistent
            }
        }), maxChecks = checks, cancellation = Cancellation { cancelled }, atoms = SearchAtomRegistry(3))
        override fun close() = lp.close()
    }
}
