package com.eignex.klause.theory.qflra

import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.lp.ExactComparison
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.search.SearchAtomPremise
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.solver.search.explainAtoms
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.shl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PartialLinearImplicationTest {
    @Test
    fun `affine activity should decline when reconstruction exceeds the rational size limit`() {
        val value = BigFraction.of(BIG_ONE shl 4095, BIG_ONE)
        val arithmetic = PartialImplicationArithmetic(Cancellation.Never)

        val activity = arithmetic.affineValue(value, BigFraction.ofLong(2), BigFraction.ZERO)

        assertNull(activity)
    }

    @Test
    fun `a negative coefficient uses a lower bound to explain an upper activity`() {
        val session = SearchSession(emptyList())
        session.initialize()
        session.push(SearchDecision.Bool(Lit.make(0, true)))
        session.push(SearchDecision.Bool(Lit.make(1, true)))
        val comparison = ExactComparison(
            mapOf(0 to BigFraction.ofLong(2), 1 to BigFraction.ofLong(-3)),
            BigFraction.ofLong(-2), LinearOp.LE, strict = false, hasReals = true,
        )

        val implication = partialLinearImplication(comparison, { column, upper ->
            when {
                column == 0 && upper -> ExplainedLinearBound(BigFraction.ofLong(2), false, premise(0))
                column == 1 && !upper -> ExplainedLinearBound(BigFraction.ofLong(2), false, premise(1))
                else -> null
            }
        }, Cancellation.Never)

        assertTrue(assertNotNull(implication).truth)
        assertEquals(setOf(Lit.make(0, false), Lit.make(1, false)),
            assertNotNull(session.explainAtoms(implication.premise)).literals.toSet())
    }

    @Test
    fun `a closed endpoint cannot prove a strict comparison at that endpoint`() {
        val comparison = unary(LinearOp.LE, strict = true)

        val implication = partialLinearImplication(comparison, { _, upper ->
            if (upper) ExplainedLinearBound(BigFraction.ONE, false, axiom) else null
        }, Cancellation.Never)

        assertNull(implication)
    }

    @Test
    fun `a strict endpoint proves disequality`() {
        val comparison = unary(LinearOp.NE)

        val implication = partialLinearImplication(comparison, { _, upper ->
            if (upper) ExplainedLinearBound(BigFraction.ONE, true, axiom) else null
        }, Cancellation.Never)

        assertTrue(assertNotNull(implication).truth)
    }

    @Test
    fun `a closed lower endpoint refutes a strict upper comparison`() {
        val comparison = unary(LinearOp.LE, strict = true)

        val implication = partialLinearImplication(comparison, { _, upper ->
            if (!upper) ExplainedLinearBound(BigFraction.ONE, false, axiom) else null
        }, Cancellation.Never)

        assertFalse(assertNotNull(implication).truth)
    }

    @Test
    fun `equality requires both fixing bounds`() {
        val comparison = unary(LinearOp.EQ)

        val implication = partialLinearImplication(comparison, { _, upper ->
            if (upper) ExplainedLinearBound(BigFraction.ONE, false, axiom) else null
        }, Cancellation.Never)

        assertNull(implication)
    }

    @Test
    fun `a consequence cannot keep its reason after its premise retracts`() {
        val session = SearchSession(emptyList())
        session.initialize()
        session.push(SearchDecision.Bool(Lit.make(0, true)))
        val implication = assertNotNull(partialLinearImplication(unary(LinearOp.LE), { _, upper ->
            if (upper) ExplainedLinearBound(BigFraction.ZERO, false, premise(0)) else null
        }, Cancellation.Never))
        assertNotNull(session.explainAtoms(implication.premise))

        session.popTo(0)

        assertNull(session.explainAtoms(implication.premise))
    }

    @Test
    fun `an interruption while reading a bound supplies no implication`() {
        var stopped = false
        val implication = partialLinearImplication(unary(LinearOp.LE), { _, _ ->
            stopped = true
            ExplainedLinearBound(BigFraction.ZERO, false, axiom)
        }, Cancellation { stopped })

        assertNull(implication)
    }

    @Test
    fun `rational coefficient products preserve an exact comparison endpoint`() {
        val half = BigFraction.ofLong(2).reciprocal()
        val comparison = ExactComparison(mapOf(0 to BigFraction.ofLong(3).reciprocal()), half,
            LinearOp.LE, strict = false, hasReals = true)

        val implication = partialLinearImplication(comparison, { _, upper ->
            if (upper) ExplainedLinearBound(BigFraction.ofLong(3) * half, false, axiom) else null
        }, Cancellation.Never)

        assertTrue(assertNotNull(implication).truth)
    }

    private fun unary(relation: LinearOp, strict: Boolean = false): ExactComparison = ExactComparison(
        mapOf(0 to BigFraction.ONE), BigFraction.ONE, relation, strict, hasReals = true,
    )

    private fun premise(variable: Int): SearchAtomPremise =
        SearchAtomPremise.Asserted(SearchDecision.Bool(Lit.make(variable, true)))

    private val axiom = SearchAtomPremise.All(emptyList())
}
