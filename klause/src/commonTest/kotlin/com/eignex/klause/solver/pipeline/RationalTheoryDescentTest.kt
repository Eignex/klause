package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.Term
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceCondition
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.solver.incumbent.Candidate
import com.eignex.klause.solver.incumbent.CompletedEnginePublication
import com.eignex.klause.solver.incumbent.EngineInvocation
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Bits
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RationalTheoryDescentTest {
    @Test
    fun `fallback witness publication should keep the actual bound invocation`() {
        val source = Problem(
            0,
            0,
            emptyArray(),
            emptyArray(),
            numRealVars = 43,
            realLower = DoubleArray(43),
            realUpper = DoubleArray(43) { 1.0 },
        )
        val objective = LinearObjective(realCoefficients = DoubleArray(43) { if (it == 0) 1.0 else 0.0 })
        val context = assertNotNull(
            source.linearAuthorityContext(
                objective,
                ObjectiveSense.MINIMIZE,
                Cancellation.Never,
            ),
        )
        val binding = EngineInvocation(
            source.prepareExecutionPlan(objective),
            ModelIdentity.of(source, objective),
            objective,
            scope = context.scope,
        )
        var candidate: MinimizeResult.WithSample? = null

        RationalTheoryDescent(
            source,
            objective,
            TheoryParams(openWorkLimit = 8),
            context,
            publicationInvocation = binding,
        ).use {
            it.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) { point -> candidate = point }
        }

        val point = assertNotNull(candidate)
        val publication = assertIs<CompletedEnginePublication.Witness>(
            assertNotNull(point.sample.witnessCertificate).publication,
        )
        assertSame(binding, publication.invocation)
        assertEquals(point.sample, publication.sample)
        assertEquals(point.objective, publication.value)
    }

    @Test
    fun `unbound fallback witness checking should withhold a completion certificate`() {
        val source = Problem(
            0,
            0,
            emptyArray(),
            emptyArray(),
            numRealVars = 43,
            realLower = DoubleArray(43),
            realUpper = DoubleArray(43) { 1.0 },
        )
        val objective = LinearObjective(realCoefficients = DoubleArray(43) { if (it == 0) 1.0 else 0.0 })
        var candidate: MinimizeResult.WithSample? = null

        RationalTheoryDescent(source, objective, TheoryParams(openWorkLimit = 8)).use {
            it.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) { point -> candidate = point }
        }

        assertNull(assertNotNull(candidate).sample.witnessCertificate)
    }

    @Test
    fun `attained real optimum should require exclusion of strict improvements`() {
        val source = Problem(
            0,
            0,
            emptyArray(),
            emptyArray(),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(2.0),
        )
        val objective = LinearObjective(realCoefficients = doubleArrayOf(-1.0))

        val result = RationalTheoryDescent(source, objective, TheoryParams()).use {
            it.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) { _ -> }
        }

        assertEquals(-2.0, assertIs<MinimizeResult.Optimal>(result).objective)
    }

    @Test
    fun `retained linear descent should carry a limit outside scalar completion`() {
        val source = Problem(
            0,
            0,
            emptyArray(),
            arrayOf(
                Linear(
                    intArrayOf(),
                    doubleArrayOf(),
                    intArrayOf(0),
                    doubleArrayOf(1.0),
                    LinearOp.GE,
                    0.0,
                    strict = true,
                ),
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY),
        )
        val objective = LinearObjective(realCoefficients = doubleArrayOf(1.0))
        val descent = RationalTheoryDescent(source, objective, TheoryParams())

        val result = descent.use { it.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) { _ -> } }

        assertIs<MinimizeResult.BestFound>(result)
        assertIs<SourceClaim.Limit>(assertNotNull(descent.linearResult?.terminal).claim)
    }

    @Test
    fun `checked declarative witnesses should enter the original source exchange`() {
        val row = ExactSearchRow.of(
            intArrayOf(Term.ofRealVar(0)),
            listOf(BigFraction.ofLong(3)),
            LinearOp.EQ,
            BigFraction.ONE,
        )
        val source = Problem(
            0,
            0,
            emptyArray(),
            arrayOf(row),
            numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY),
        )
        val objective = LinearObjective(realCoefficients = doubleArrayOf(1.0))
        var accepted = false

        RationalTheoryDescent(source, objective, TheoryParams()).use {
            it.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) { candidate ->
                accepted = openWitnessVerifier(source, objective).verify(
                    Candidate(
                        candidate.sample,
                        candidate.objective,
                    ),
                ) is Verification.Accepted
            }
        }

        assertEquals(true, accepted)
    }

    @Test
    fun `closing a paused descent should preserve its typed partial source result`() {
        val open = Bits(1).also { it.set(0) }
        val source = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(0), open, open),
            factors = arrayOf(
                ExactSearchRow.of(
                    intArrayOf(Term.ofIntVar(0)),
                    listOf(BigFraction.ONE),
                    LinearOp.GE,
                    BigFraction.ZERO,
                    strict = true,
                ),
            ),
        )
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val descent = RationalTheoryDescent(source, objective, TheoryParams())

        val paused = descent.runSlice(Cancellation.Never, Long.MAX_VALUE, 1) { _ -> }
        descent.close()

        assertNull(paused)
        val partial = assertNotNull(descent.linearResult)
        assertNull(partial.terminal)
        assertNotNull(partial.incumbent)
        val bound = assertIs<SourceClaim.Bound>(assertNotNull(partial.bound).claim)
        assertEquals(SourceValue.Rational(BigFraction.ZERO), bound.value)
    }

    @Test
    fun `declined numerical authority should keep a checked fallback witness without a terminal`() {
        val source = Problem(
            0,
            0,
            emptyArray(),
            emptyArray(),
            numRealVars = 43,
            realLower = DoubleArray(43),
            realUpper = DoubleArray(43) { 1.0 },
        )
        val objective = LinearObjective(realCoefficients = DoubleArray(43) { if (it == 0) 1.0 else 0.0 })
        var candidate: MinimizeResult.WithSample? = null
        val descent = RationalTheoryDescent(source, objective, TheoryParams(openWorkLimit = 8))

        val result = descent.use {
            it.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) { point -> candidate = point }
        }

        val witness = assertNotNull(candidate)
        assertEquals(BigFraction.ZERO, objective.evaluateExact(witness.sample))
        assertIs<Verification.Accepted<*, *>>(
            openWitnessVerifier(source, objective).verify(
                Candidate(witness.sample, witness.objective),
            ),
        )
        assertIs<MinimizeResult.BestFound>(result)
        val partial = assertNotNull(descent.linearResult)
        assertEquals(BigFraction.ZERO, partial.value)
        assertNull(partial.terminal)
        assertTrue(partial.stats.openTheory.openTheoryChecks >= 2)
    }

    @Test
    fun `declined numerical authority should not renew containing work or checks`() {
        val source = Problem(
            0,
            0,
            emptyArray(),
            emptyArray(),
            numRealVars = 43,
            realLower = DoubleArray(43),
            realUpper = DoubleArray(43) { 1.0 },
        )
        val objective = LinearObjective(realCoefficients = DoubleArray(43) { if (it == 0) 1.0 else 0.0 })

        for (params in listOf(TheoryParams(openWorkLimit = 1), TheoryParams(openWorkLimit = 8, maxLeaves = 1))) {
            val result = RationalTheoryDescent(source, objective, params).use {
                it.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) { _ -> }
            }

            val unknown = assertIs<MinimizeResult.Unknown>(result)
            assertEquals(TerminationReason.BudgetExhausted, unknown.reason)
            assertEquals(1L, unknown.stats.openTheory.openWork)
            assertEquals(1L, unknown.stats.openTheory.openTheoryChecks)
        }
    }

    @Test
    fun `declined numerical authority should not renew a retained slice`() {
        val source = Problem(
            0,
            0,
            emptyArray(),
            emptyArray(),
            numRealVars = 43,
            realLower = DoubleArray(43),
            realUpper = DoubleArray(43) { 1.0 },
        )
        val objective = LinearObjective(realCoefficients = DoubleArray(43) { if (it == 0) 1.0 else 0.0 })
        var candidate: MinimizeResult.WithSample? = null
        val descent = RationalTheoryDescent(source, objective, TheoryParams(openWorkLimit = 8))

        descent.use {
            val paused = it.runSlice(Cancellation.Never, Long.MAX_VALUE, 1) { point -> candidate = point }
            assertNull(paused)
            assertNull(candidate)
            assertEquals(1L, it.work)
            it.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) { point -> candidate = point }
        }

        assertNotNull(candidate)
        assertNull(assertNotNull(descent.linearResult).terminal)
    }

    @Test
    fun `declined numerical authority should continue improving an external source witness`() {
        val source = Problem(
            0,
            0,
            emptyArray(),
            emptyArray(),
            numRealVars = 43,
            realLower = DoubleArray(43),
            realUpper = DoubleArray(43) { 1.0 },
        )
        val objective = LinearObjective(realCoefficients = DoubleArray(43) { if (it == 0) 1.0 else 0.0 })
        val external = Sample(
            booleanArrayOf(),
            longArrayOf(),
            DoubleArray(43) { 1.0 },
            exactReals = List(43) { BigFraction.ONE },
        )
        val values = ArrayList<BigFraction>()
        val descent = RationalTheoryDescent(source, objective, TheoryParams(openWorkLimit = 8)) {
            external to BigFraction.ONE
        }

        descent.use {
            it.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) { point ->
                values += objective.evaluateExact(point.sample)
            }
        }

        assertEquals(BigFraction.ZERO, values.last())
        assertEquals(BigFraction.ZERO, assertNotNull(descent.linearResult).value)
        assertNull(descent.linearResult?.terminal)
    }

    @Test
    fun `fallback publication failure should retire the retained descent`() {
        val source = Problem(
            0,
            0,
            emptyArray(),
            emptyArray(),
            numRealVars = 43,
            realLower = DoubleArray(43),
            realUpper = DoubleArray(43) { 1.0 },
        )
        val objective = LinearObjective(realCoefficients = DoubleArray(43) { if (it == 0) 1.0 else 0.0 })
        val descent = RationalTheoryDescent(source, objective, TheoryParams(openWorkLimit = 8))

        assertFailsWith<IllegalStateException> {
            descent.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) { error("publication failed") }
        }

        assertFailsWith<IllegalStateException> {
            descent.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) { _ -> }
        }
        descent.close()
    }

    @Test
    fun `fallback feasibility should install the original numeric query condition`() {
        val source = Problem(
            0,
            0,
            emptyArray(),
            emptyArray(),
            numRealVars = 43,
            realLower = DoubleArray(43),
            realUpper = DoubleArray(43) { 1.0 },
        )
        val objective = LinearObjective(realCoefficients = DoubleArray(43) { if (it == 0) 1.0 else 0.0 })
        val original = assertNotNull(
            source.linearAuthorityContext(
                objective,
                ObjectiveSense.MINIMIZE,
                Cancellation.Never,
            ),
        )
        val real = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
        val scoped = SourceProofContext(
            original.scope.copy(
                condition = SourceCondition(
                    lower = mapOf(real to BigFraction.ONE),
                ),
            ),
            original.coordinates,
            original.premises,
            original.objective,
            original.objectiveConstant,
            original.semanticsChecker,
        )
        val descent = RationalTheoryDescent(source, objective, TheoryParams(openWorkLimit = 8), scoped)

        descent.use { it.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) { _ -> } }

        val partial = assertNotNull(descent.linearResult)
        assertEquals(scoped.scope, partial.scope)
        assertEquals(BigFraction.ONE, partial.value)
        assertEquals(BigFraction.ONE, assertNotNull(partial.incumbent).exactReals?.get(0))
        assertNull(partial.terminal)
    }

    @Test
    fun `declined numerical authority should find a bounded integer witness within a short descent`() {
        val source = Problem(
            0,
            1,
            arrayOf(IntDomain(0, 1_000_000)),
            arrayOf(
                ExactSearchRow.of(
                    intArrayOf(Term.ofIntVar(0), Term.ofRealVar(0)),
                    listOf(BigFraction.ONE, BigFraction.ONE),
                    LinearOp.GE,
                    BigFraction.ofLong(999_999) +
                        BigFraction.ofLong(2).reciprocal(),
                ),
            ),
            numRealVars = 43,
            realLower = DoubleArray(43),
            realUpper = DoubleArray(43) { 1.0 },
        )
        val objective = LinearObjective(realCoefficients = DoubleArray(43) { if (it == 0) 1.0 else 0.0 })
        var published: MinimizeResult.WithSample? = null

        RationalTheoryDescent(source, objective, TheoryParams(maxDecisions = 4, openWorkLimit = 32)).use {
            it.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) { candidate -> published = candidate }
        }

        val candidate = assertNotNull(published)
        assertIs<Verification.Accepted<*, *>>(
            openWitnessVerifier(source, objective).verify(
                Candidate(candidate.sample, candidate.objective),
            ),
        )
    }

    @Test
    fun `numerical traversal should reject a missing source context before search`() {
        val source = Problem(0, 0, emptyArray(), emptyArray())

        assertFailsWith<IllegalArgumentException> {
            RationalTheoryDescent(source, LinearObjective(), TheoryParams(), context = null, numericalOnly = true)
        }
    }

}
