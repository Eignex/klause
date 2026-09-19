package com.eignex.klause.theory.qflra

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.bounding.LpPropagator
import com.eignex.klause.lp.bounding.LpSearchPolicy
import com.eignex.klause.lp.bounding.RowPropagation
import com.eignex.klause.lp.bounding.RowPropagationResult
import com.eignex.klause.lp.engine.ExactLpNumber
import com.eignex.klause.lp.engine.ExactLpSide
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.RegisteredTheoryDecision
import com.eignex.klause.solver.search.SearchAtomRegistry
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchExplanation
import com.eignex.klause.solver.search.SearchIntValue
import com.eignex.klause.solver.search.SearchSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RowPropagationCallerTest {
    @Test
    fun `row bounds map unshifted integer values and exact complements`() {
        val source = Problem(0, intBounds = IntBounds.fromModelBounds(longArrayOf(10L), longArrayOf(20L), null, null), factors = emptyArray())
        val context = SearchSession(emptyList(), atoms = SearchAtomRegistry(0))
        LpPropagator(object : LpSearchPolicy {}).use { lp ->
            val system = LiveQfLraSystem(source, lp)
            assertTrue(system.install())
            val state = assertNotNull(lp.state)
            for (upper in listOf(false, true)) for (strict in listOf(false, true)) {
                val side = ExactLpSide(ExactLpNumber.of(12L), strict)
                val decision = assertIs<SearchDecision.Theory>(system.rowDecision(state, 0, upper, side, context))
                val registered = assertIs<RegisteredTheoryDecision>(decision.decision)
                val atom = assertIs<SourceBoundAtom>(registered.payload)
                assertEquals(listOf(SourceBoundTerm(SearchIntValue(0), BigFraction.ONE)), atom.terms)
                assertEquals(BigFraction.ofLong(12L), atom.threshold)
                assertEquals(upper, atom.upper)
                assertEquals(strict, atom.strict)
            }
            assertNull(system.rowDecision(state, source.numIntVars, true, ExactLpSide(ExactLpNumber.of(0L)), context))
        }
    }

    @Test
    fun `a foreign installation cannot grant source mapping authority`() {
        val source = Problem(0, intBounds = IntBounds.fromModelBounds(longArrayOf(0L), longArrayOf(1L), null, null), factors = emptyArray())
        LpPropagator(object : LpSearchPolicy {}).use { lp ->
            val first = LiveQfLraSystem(source, lp)
            assertTrue(first.install())

            val foreign = LiveQfLraSystem(source, lp)

            assertFalse(foreign.install())
            assertNull(foreign.rowDecision(
                assertNotNull(lp.state), 0, true, ExactLpSide(ExactLpNumber.of(0L)),
                SearchSession(emptyList(), atoms = SearchAtomRegistry(0)),
            ))
        }
    }

    @Test
    fun `reset revokes a previously installed mapping token`() {
        val source = Problem(0, intBounds = IntBounds.fromModelBounds(longArrayOf(0L), longArrayOf(1L), null, null), factors = emptyArray())
        val context = SearchSession(emptyList(), atoms = SearchAtomRegistry(0))
        LpPropagator(object : LpSearchPolicy {}).use { lp ->
            val system = LiveQfLraSystem(source, lp)
            assertTrue(system.install())
            val state = assertNotNull(lp.state)
            lp.reset()

            assertNull(system.rowDecision(state, 0, true, ExactLpSide(ExactLpNumber.of(0L)), context))
        }
    }

    @Test
    fun `row configuration cannot be ignored after lazy owner creation`() {
        val source = Problem(0, intBounds = IntBounds.fromModelBounds(longArrayOf(0L), longArrayOf(1L), null, null), factors = emptyArray())
        ExactLiraSearchComponent(source).use { component ->
            component.lpMetrics

            assertFailsWith<IllegalStateException> {
                component.propagateRowsWith(object : RowPropagation {
                    override fun propagate(lp: LpPropagator, context: SearchContext) = RowPropagationResult.Skipped
                })
            }
        }
    }

    @Test
    fun `a published source row atom is applied by the shared queue before solving`() {
        val source = Problem(
            0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(), longArrayOf(), null, null),
            numRealVars = 2,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY, 1.0),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY),
            factors = arrayOf(Linear(
                intArrayOf(), doubleArrayOf(), intArrayOf(0, 1), doubleArrayOf(1.0, 1.0), LinearOp.LE, 1.0,
            )),
        )
        var publications = 0
        var applied = 0
        ExactLiraSearchComponent(source).use { component ->
            component.propagateRowsWith(object : RowPropagation {
                override fun propagate(lp: LpPropagator, context: SearchContext): RowPropagationResult {
                    val state = assertNotNull(lp.state)
                    if (publications > 0) {
                        assertEquals(BigFraction.ZERO, state.activeSide(0, true)?.side?.number?.value)
                        applied++
                        return RowPropagationResult.Skipped
                    }
                    val decision = assertNotNull(lp.rowDecision(
                        state, 0, true, ExactLpSide(ExactLpNumber.of(0L)), context,
                    ))
                    val literal = assertNotNull(context.atomLiteral(decision))
                    assertIs<ComponentResult.Consistent>(context.imply(literal, SearchExplanation(intArrayOf(literal))))
                    publications++
                    return RowPropagationResult.Published
                }
            })
            val session = SearchSession(listOf(component), branchers = listOf(component), atoms = SearchAtomRegistry(0))

            assertIs<ComponentResult.Consistent>(session.initialize())

            assertEquals(1, publications)
            assertEquals(1, applied)
        }
    }
}
