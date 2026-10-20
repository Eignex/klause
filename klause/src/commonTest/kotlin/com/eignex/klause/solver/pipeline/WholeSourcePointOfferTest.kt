package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.Term
import com.eignex.klause.presolve.RebuildStep
import com.eignex.klause.presolve.SourceMapping
import com.eignex.klause.presolve.SourceRebuilds
import com.eignex.klause.presolve.TransformationGuarantees
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchNodeDisposition
import com.eignex.klause.solver.search.SearchNodePolicy
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.theory.qflra.ExactLiraAssignment
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WholeSourcePointOfferTest {
    @Test
    fun `an implied Boolean conflict should omit unrelated trial pins from its cube`() {
        val source = Problem(5, 0, emptyArray(), arrayOf(
            Clause(intArrayOf(Lit.make(0, true), Lit.make(4, true))),
            Clause(intArrayOf(Lit.make(1, false), Lit.make(2, true))),
        ), numRealVars = 1, realLower = doubleArrayOf(-1.0), realUpper = doubleArrayOf(1.0))
        val clauses = ArrayList<IntArray>()
        val offer = WholeSourcePointOffer(source, source,
            SourceMapping(source, source, TransformationGuarantees.IDENTITY), Cancellation.Never,
            onBooleanClause = { clauses += it })

        val result = offer.completeBooleans(intArrayOf(0, 1, 0, -1, -1), Cancellation.Never)

        assertNull(result)
        val clause = clauses.single()
        assertContentEquals(intArrayOf(Lit.make(1, false), Lit.make(2, true)), clause)
        for (mask in 0 until 32) {
            if (source.factors.all { factor ->
                    (factor as Clause).literals.any { literal ->
                        (mask and (1 shl Lit.variable(literal)) != 0) == Lit.isPositive(literal)
                    }
                }
            ) assertTrue(clause.any { literal ->
                (mask and (1 shl Lit.variable(literal)) != 0) == Lit.isPositive(literal)
            })
        }
        assertContentEquals(booleanArrayOf(false, false, false, false, true),
            offer.completeBooleans(intArrayOf(0, 0, 0, 0, 1), Cancellation.Never))
    }

    @Test
    fun `unconditional Boolean root truths should not acquire unrelated cube conditions`() {
        val source = Problem(3, 0, emptyArray(), arrayOf(
            Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
            Clause(intArrayOf(Lit.make(2, true))),
        ), numRealVars = 1, realLower = doubleArrayOf(-1.0), realUpper = doubleArrayOf(1.0))
        val clauses = ArrayList<IntArray>()
        val offer = WholeSourcePointOffer(source, source,
            SourceMapping(source, source, TransformationGuarantees.IDENTITY), Cancellation.Never,
            onBooleanClause = { clauses += it })

        val result = offer.completeBooleans(intArrayOf(0, -1, 0), Cancellation.Never)

        assertNull(result)
        assertContentEquals(intArrayOf(Lit.make(2, true)), clauses.single())
        assertContentEquals(booleanArrayOf(false, true, true),
            offer.completeBooleans(intArrayOf(0, 1, 1), Cancellation.Never))
    }

    @Test
    fun `multi step Boolean cube reasons should retain signed trial conditions`() {
        val source = Problem(6, 0, emptyArray(), arrayOf(
            Clause(intArrayOf(Lit.make(0, true), Lit.make(5, true))),
            Clause(intArrayOf(Lit.make(1, true), Lit.make(2, false))),
            Clause(intArrayOf(Lit.make(2, true), Lit.make(3, true))),
            Clause(intArrayOf(Lit.make(3, false), Lit.make(4, true))),
        ), numRealVars = 1, realLower = doubleArrayOf(-1.0), realUpper = doubleArrayOf(1.0))
        val clauses = ArrayList<IntArray>()
        val offer = WholeSourcePointOffer(source, source,
            SourceMapping(source, source, TransformationGuarantees.IDENTITY), Cancellation.Never,
            onBooleanClause = { clauses += it })

        val result = offer.completeBooleans(intArrayOf(0, 0, -1, -1, 0, -1), Cancellation.Never)

        assertNull(result)
        val clause = clauses.single()
        assertContentEquals(intArrayOf(Lit.make(1, true), Lit.make(4, true)), clause)
        for (mask in 0 until 64) {
            if (source.factors.all { factor ->
                    (factor as Clause).literals.any { literal ->
                        (mask and (1 shl Lit.variable(literal)) != 0) == Lit.isPositive(literal)
                    }
                }
            ) assertTrue(clause.any { literal ->
                (mask and (1 shl Lit.variable(literal)) != 0) == Lit.isPositive(literal)
            })
        }
        assertContentEquals(booleanArrayOf(false, true, true, false, false, true),
            offer.completeBooleans(intArrayOf(0, 1, 1, 0, 0, 1), Cancellation.Never))
    }

    @Test
    fun `cancellation while explaining an implied Boolean should withhold its cube`() {
        val source = Problem(2, 0, emptyArray(), arrayOf(
            Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
        ), numRealVars = 1, realLower = doubleArrayOf(-1.0), realUpper = doubleArrayOf(1.0))
        var cancelled = false
        var clauses = 0
        val offer = WholeSourcePointOffer(source, source,
            SourceMapping(source, source, TransformationGuarantees.IDENTITY), Cancellation.Never,
            onDecline = { if (it == "BOOLEAN_CUBE_CAUSAL_ATTEMPT") cancelled = true },
            onBooleanClause = { clauses++ })

        val result = offer.completeBooleans(intArrayOf(1, 0), Cancellation { cancelled })

        assertNull(result)
        assertTrue(cancelled)
        assertEquals(0, clauses)
        assertContentEquals(BooleanArray(2), offer.completeBooleans(intArrayOf(0, 0), Cancellation.Never))
    }

    @Test
    fun `a rejected Boolean cube should retain every conditioning pin in its source clause`() {
        val source = Problem(4, 0, emptyArray(), arrayOf(
            Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true), Lit.make(2, true))),
            Clause(intArrayOf(Lit.make(0, false), Lit.make(3, true), Lit.make(2, false))),
        ), numRealVars = 1, realLower = doubleArrayOf(-1.0), realUpper = doubleArrayOf(1.0))
        val clauses = ArrayList<IntArray>()
        val offer = WholeSourcePointOffer(source, source,
            SourceMapping(source, source, TransformationGuarantees.IDENTITY), Cancellation.Never,
            onBooleanClause = { clauses += it })

        val result = offer.completeBooleans(intArrayOf(1, 0, -1, 0), Cancellation.Never)

        assertNull(result)
        val clause = clauses.single()
        assertContentEquals(intArrayOf(Lit.make(0, false), Lit.make(1, true), Lit.make(3, true)), clause)
        for (mask in 0 until 16) {
            if (source.factors.all { factor ->
                    (factor as Clause).literals.any { literal ->
                        (mask and (1 shl Lit.variable(literal)) != 0) == Lit.isPositive(literal)
                    }
                }
            ) assertTrue(clause.any { literal ->
                (mask and (1 shl Lit.variable(literal)) != 0) == Lit.isPositive(literal)
            })
        }
        assertContentEquals(BooleanArray(4),
            offer.completeBooleans(intArrayOf(0, 0, 0, 0), Cancellation.Never))
    }

    @Test
    fun `cancellation after a Boolean cube conflict should withhold its source clause`() {
        val source = Problem(2, 0, emptyArray(), arrayOf(
            Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
        ), numRealVars = 1, realLower = doubleArrayOf(-1.0), realUpper = doubleArrayOf(1.0))
        var cancelled = false
        var clauses = 0
        val offer = WholeSourcePointOffer(source, source,
            SourceMapping(source, source, TransformationGuarantees.IDENTITY), Cancellation.Never,
            onDecline = { if (it == "BOOLEAN_ROOT_CONFLICT") cancelled = true },
            onBooleanClause = { clauses++ })

        val result = offer.completeBooleans(intArrayOf(1, 0), Cancellation { cancelled })

        assertNull(result)
        assertTrue(cancelled)
        assertEquals(0, clauses)
    }

    @Test
    fun `only referenced trial pins should count toward Boolean cube support limits`() {
        for (size in listOf(4096, 4097)) {
            val source = Problem(size + 7, 0, emptyArray(), arrayOf(
                Clause(IntArray(size) { Lit.make(it + 7, true) }),
            ), numRealVars = 1, realLower = doubleArrayOf(-1.0), realUpper = doubleArrayOf(1.0))
            var clause: IntArray? = null
            val offer = WholeSourcePointOffer(source, source,
                SourceMapping(source, source, TransformationGuarantees.IDENTITY), Cancellation.Never,
                onBooleanClause = { clause = it })

            val result = offer.completeBooleans(IntArray(size + 7), Cancellation.Never)

            assertNull(result)
            if (size <= 4096) assertContentEquals(IntArray(size) { Lit.make(it + 7, true) }, clause)
            else assertNull(clause)
        }
    }

    @Test
    fun `the original source should be checked after Boolean reconstruction`() {
        val source = Problem(2, 0, emptyArray(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(-1.0), realUpper = doubleArrayOf(1.0))
        val original = Problem(2, 0, emptyArray(), arrayOf(Clause(intArrayOf(Lit.make(1, true)))),
            numRealVars = 1, realLower = doubleArrayOf(-1.0), realUpper = doubleArrayOf(1.0))
        val mapping = SourceMapping(original, source, TransformationGuarantees.IDENTITY,
            rebuild = SourceRebuilds(listOf(RebuildStep.CopyLiteral(1, Lit.make(0, false)))))
        val offer = WholeSourcePointOffer(source, original, mapping, Cancellation.Never)

        assertFalse(offer.offer(ExactLiraAssignment(booleanArrayOf(true, true), emptyArray(),
            listOf(BigFraction.ZERO)), Cancellation.Never))
        assertTrue(offer.offer(ExactLiraAssignment(booleanArrayOf(false, false), emptyArray(),
            listOf(BigFraction.ZERO)), Cancellation.Never))
        assertTrue(assertNotNull(offer.take()).boolValue(1))
    }

    @Test
    fun `a private Boolean fixpoint should complete forced gates and unused variables`() {
        val source = Problem(3, 0, emptyArray(), arrayOf(
            Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
        ), numRealVars = 1, realLower = doubleArrayOf(-1.0), realUpper = doubleArrayOf(1.0))
        val offer = WholeSourcePointOffer(source, source,
            SourceMapping(source, source, TransformationGuarantees.IDENTITY), Cancellation.Never)

        val result = offer.completeBooleans(intArrayOf(1, -1, -1), Cancellation.Never)

        assertContentEquals(booleanArrayOf(true, true, false), result)
    }

    @Test
    fun `an unresolved Boolean used in a source clause should withhold completion`() {
        val source = Problem(2, 0, emptyArray(), arrayOf(
            Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
        ), numRealVars = 1, realLower = doubleArrayOf(-1.0), realUpper = doubleArrayOf(1.0))
        val declines = ArrayList<String>()
        val offer = WholeSourcePointOffer(source, source,
            SourceMapping(source, source, TransformationGuarantees.IDENTITY), Cancellation.Never,
            onDecline = { declines += it })

        val result = offer.completeBooleans(intArrayOf(-1, -1), Cancellation.Never)

        assertNull(result)
        assertEquals(listOf("BOOLEAN_UNRESOLVED"), declines)
        assertContentEquals(booleanArrayOf(false, true),
            offer.completeBooleans(intArrayOf(0, -1), Cancellation.Never))
    }

    @Test
    fun `a private Boolean conflict should not constrain a later compatible proposal`() {
        val source = Problem(2, 0, emptyArray(), arrayOf(
            Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
        ), numRealVars = 1, realLower = doubleArrayOf(-1.0), realUpper = doubleArrayOf(1.0))
        val declines = ArrayList<String>()
        val offer = WholeSourcePointOffer(source, source,
            SourceMapping(source, source, TransformationGuarantees.IDENTITY), Cancellation.Never,
            onDecline = { declines += it })

        assertNull(offer.completeBooleans(intArrayOf(1, 0), Cancellation.Never))
        assertEquals(listOf("BOOLEAN_ROOT_CONFLICT"), declines)
        assertContentEquals(booleanArrayOf(false, false),
            offer.completeBooleans(intArrayOf(0, 0), Cancellation.Never))
    }

    @Test
    fun `whole source checks should reject Boolean values contradicting equality truth`() {
        for (relation in listOf(LinearOp.EQ, LinearOp.NE)) {
            val source = Problem(1, 0, emptyArray(), arrayOf(
                ExactLinear.of(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.ONE), relation,
                    BigFraction.ZERO, activator = 0),
            ), numRealVars = 1, realLower = doubleArrayOf(-1.0), realUpper = doubleArrayOf(1.0))
            val offer = WholeSourcePointOffer(source, source,
                SourceMapping(source, source, TransformationGuarantees.IDENTITY), Cancellation.Never)
            val truth = relation == LinearOp.EQ

            val accepted = offer.offer(ExactLiraAssignment(booleanArrayOf(!truth), emptyArray(),
                listOf(BigFraction.ZERO)), Cancellation.Never)

            assertFalse(accepted)
            assertNull(offer.take())
            assertTrue(offer.offer(ExactLiraAssignment(booleanArrayOf(truth), emptyArray(),
                listOf(BigFraction.ZERO)), Cancellation.Never))
        }
    }

    @Test
    fun `a source clause outside the numerical projection should reject the proposal`() {
        val source = Problem(1, 0, emptyArray(), arrayOf(Clause(intArrayOf(Lit.make(0, true)))),
            numRealVars = 1, realLower = doubleArrayOf(-1.0), realUpper = doubleArrayOf(1.0))
        val offer = WholeSourcePointOffer(source, source,
            SourceMapping(source, source, TransformationGuarantees.IDENTITY), Cancellation.Never)

        val accepted = offer.offer(ExactLiraAssignment(booleanArrayOf(false), emptyArray(),
            listOf(BigFraction.ZERO)), Cancellation.Never)

        assertFalse(accepted)
        assertNull(offer.take())
    }

    @Test
    fun `whole source point publication should consume checked assignments once`() {
        val source = Problem(1, 0, emptyArray(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(-1.0), realUpper = doubleArrayOf(1.0))
        val offer = WholeSourcePointOffer(source, source,
            SourceMapping(source, source, TransformationGuarantees.IDENTITY), Cancellation.Never)
        assertTrue(offer.offer(ExactLiraAssignment(booleanArrayOf(true), emptyArray(),
            listOf(BigFraction.ZERO)), Cancellation.Never))

        val result = assertNotNull(offer.take())

        assertTrue(result.boolValue(0))
        assertEquals("0", result.realValue(0))
        assertNull(offer.take())
    }

    @Test
    fun `invalidating the source scope should discard a pending whole point`() {
        val source = Problem(1, 0, emptyArray(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(-1.0), realUpper = doubleArrayOf(1.0))
        val offer = WholeSourcePointOffer(source, source,
            SourceMapping(source, source, TransformationGuarantees.IDENTITY), Cancellation.Never)
        assertTrue(offer.offer(ExactLiraAssignment(booleanArrayOf(true), emptyArray(),
            listOf(BigFraction.ZERO)), Cancellation.Never))

        offer.invalidate()

        assertNull(offer.take())
    }

    @Test
    fun `caller pauses should precede the checked whole point handoff`() {
        val source = Problem(1, 0, emptyArray(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(-1.0), realUpper = doubleArrayOf(1.0))
        val offer = WholeSourcePointOffer(source, source,
            SourceMapping(source, source, TransformationGuarantees.IDENTITY), Cancellation.Never)
        assertTrue(offer.offer(ExactLiraAssignment(booleanArrayOf(true), emptyArray(),
            listOf(BigFraction.ZERO)), Cancellation.Never))
        var disposition: SearchNodeDisposition = SearchNodeDisposition.Pause
        val policy = offer.policy(object : SearchNodePolicy {
            override fun beforeBranch(context: SearchContext) = disposition
        })
        val context = SearchSession(emptyList())

        assertEquals(SearchNodeDisposition.Pause, policy.beforeBranch(context))
        assertFalse(offer.internalPause)
        disposition = SearchNodeDisposition.Expand
        assertEquals(SearchNodeDisposition.Pause, policy.beforeBranch(context))
        assertTrue(offer.internalPause)
        assertNotNull(offer.take())
        assertEquals(SearchNodeDisposition.Expand, policy.beforeBranch(context))
    }

    @Test
    fun `cancellation after checking should withhold a pending whole point`() {
        val source = Problem(1, 0, emptyArray(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(-1.0), realUpper = doubleArrayOf(1.0))
        var cancelled = false
        val offer = WholeSourcePointOffer(source, source,
            SourceMapping(source, source, TransformationGuarantees.IDENTITY), Cancellation { cancelled })
        assertTrue(offer.offer(ExactLiraAssignment(booleanArrayOf(true), emptyArray(),
            listOf(BigFraction.ZERO)), Cancellation.Never))

        cancelled = true

        assertNull(offer.take())
    }

    @Test
    fun `private Boolean propagation should charge work to the current operation`() {
        val source = Problem(2, 0, emptyArray(), arrayOf(
            Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
        ), numRealVars = 1, realLower = doubleArrayOf(-1.0), realUpper = doubleArrayOf(1.0))
        val offer = WholeSourcePointOffer(source, source,
            SourceMapping(source, source, TransformationGuarantees.IDENTITY), Cancellation.Never)
        var spent = 0L
        val stop = object : Cancellation {
            override fun isCancelled(): Boolean = false
            override fun workMeter(): WorkMeter = WorkMeter { spent += it }
        }

        assertNotNull(offer.completeBooleans(intArrayOf(1, -1), stop))
        val prepared = spent
        assertNotNull(offer.completeBooleans(intArrayOf(1, -1), stop))

        assertTrue(prepared > 0L)
        assertTrue(spent > prepared)
    }
}
