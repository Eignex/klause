package com.eignex.klause.solver.pipeline

import com.eignex.klause.formats.mps.Mps
import com.eignex.klause.formats.mps.toExactSourceProblem
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.solver.objective.toLinearObjective
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NumericalLinearRequestTest {
    @Test
    fun `an unprojectable nonzero source coefficient declines without numerical work`() {
        val compiled = Mps.parse("ROWS\n N COST\n G R\nCOLUMNS\n X COST 1 R 1e-400\n" +
            "RHS\n RHS R 0.1\nBOUNDS\n UP B X 1\nENDATA\n").toExactSourceProblem()
        val request = NumericalLinearRequest(compiled.model, compiled.objective?.toLinearObjective())

        val result = request.solve()

        assertFalse(request.numericalRowsSupported())
        assertNull(request.finiteRoute())
        assertNull(result.terminal)
        assertNull(result.incumbent)
        assertEquals(TerminationReason.Unsupported, result.reason)
        assertEquals(0L, result.stats.smt.sourceLp.operations)
    }

    @Test
    fun `source fixed indicators retain numerical bound closure and original declarations`() {
        val compiled = Mps.parse("ROWS\n N COST\n L R\nCOLUMNS\n M0 'MARKER' 'INTORG'\n" +
            " I COST 0\n X COST 1 R 1\n M1 'MARKER' 'INTEND'\nRHS\n RHS R 2\n" +
            "BOUNDS\n FX B I 1\n LI B X 0\nINDICATORS\n IF R I 1\nENDATA\n").toExactSourceProblem()
        val request = NumericalLinearRequest(compiled.model, compiled.objective?.toLinearObjective())

        val route = assertIs<SourceProblemRoute.Finite>(request.finiteRoute())

        assertSame(compiled.model, route.preparedPlan.originalSource)
        assertTrue(!compiled.model.intBounds.hasUpper(1))
        assertEquals(2L, route.problem.intBounds.upper(1))
    }

    @Test
    fun `a source fixed indicator can retain a numerical routing refutation`() {
        val compiled = Mps.parse("ROWS\n N COST\n L R\nCOLUMNS\n M0 'MARKER' 'INTORG'\n" +
            " I COST 0\n X COST 1 R 1\n M1 'MARKER' 'INTEND'\nRHS\n RHS R -1\n" +
            "BOUNDS\n FX B I 1\n LI B X 0\nINDICATORS\n IF R I 1\nENDATA\n").toExactSourceProblem()

        val route = NumericalLinearRequest(compiled.model, compiled.objective?.toLinearObjective()).finiteRoute()

        assertEquals(SourceProblemRoute.Refuted, route)
    }

    @Test
    fun `a one sided integer attains a finite numerical optimum beside a free real`() {
        val compiled = Mps.parse("ROWS\n N COST\n G R\nCOLUMNS\n M0 'MARKER' 'INTORG'\n" +
            " I COST 1 R 1\n M1 'MARKER' 'INTEND'\n X R 1\nBOUNDS\n LI B I 0\n FR B X\nENDATA\n")
            .toExactSourceProblem()
        val request = NumericalLinearRequest(compiled.model, compiled.objective?.toLinearObjective())

        val result = request.solve()

        val optimal = assertIs<SourceClaim.Optimal>(assertNotNull(result.terminal).claim)
        assertEquals(SourceValue.Rational(BigFraction.ZERO), optimal.value)
        assertEquals(0L, optimal.sample.intValue(0))
        assertEquals("numerical-mip", result.stats.run.backend)
        assertTrue(result.stats.smt.sourceLp.operations > 0)
        assertTrue(result.stats.smt.sourceLp.floatWork > 0)
        assertNull(request.finiteRoute())
    }

    @Test
    fun `free and one sided integers retain exhaustive fractional splits`() {
        for (bounds in listOf("LI B I 0", "FR B I")) {
            val compiled = Mps.parse("ROWS\n N COST\n G R\nCOLUMNS\n M0 'MARKER' 'INTORG'\n" +
                " I COST 1 R 2\n M1 'MARKER' 'INTEND'\nRHS\n RHS R 1\nBOUNDS\n $bounds\nENDATA\n")
                .toExactSourceProblem()

            val result = NumericalLinearRequest(compiled.model, compiled.objective?.toLinearObjective()).solve()

            val optimal = assertIs<SourceClaim.Optimal>(assertNotNull(result.terminal).claim)
            assertEquals(SourceValue.Rational(BigFraction.ONE), optimal.value)
            assertEquals(1L, optimal.sample.intValue(0))
        }
    }

    @Test
    fun `an upper only integer retains maximization sense and its attained optimum`() {
        val compiled = Mps.parse("OBJSENSE\n MAX\nROWS\n N COST\nCOLUMNS\n M0 'MARKER' 'INTORG'\n" +
            " I COST 1\n M1 'MARKER' 'INTEND'\nBOUNDS\n FR B I\n UI B I 2\nENDATA\n")
            .toExactSourceProblem()

        val result = NumericalLinearRequest(compiled.model, compiled.objective?.toLinearObjective(),
            compiled.maximize).solve()

        val optimal = assertIs<SourceClaim.Optimal>(assertNotNull(result.terminal).claim)
        assertEquals(SourceValue.Rational(BigFraction.ofLong(2)), optimal.value)
        assertEquals(2L, optimal.sample.intValue(0))
    }

    @Test
    fun `wide source bounds retain their original attained integer coordinate`() {
        val compiled = Mps.parse("ROWS\n N COST\nCOLUMNS\n M0 'MARKER' 'INTORG'\n" +
            " I COST 1\n M1 'MARKER' 'INTEND'\nBOUNDS\n LI B I 9223372036854775808\nENDATA\n")
            .toExactSourceProblem()

        val result = NumericalLinearRequest(compiled.model, compiled.objective?.toLinearObjective()).solve()

        val optimal = assertIs<SourceClaim.Optimal>(assertNotNull(result.terminal).claim)
        assertEquals("9223372036854775808", optimal.sample.exactIntValue(0).toString())
        assertEquals("9223372036854775808", result.value.toString())
    }

    @Test
    fun `a free integer equality beyond Long retains its numerical attained coordinate`() {
        val compiled = Mps.parse("ROWS\n N COST\n E R\nCOLUMNS\n M0 'MARKER' 'INTORG'\n" +
            " I COST 1 R 1\n M1 'MARKER' 'INTEND'\nRHS\n RHS R 9223372036854775808\n" +
            "BOUNDS\n FR B I\nENDATA\n").toExactSourceProblem()
        val request = NumericalLinearRequest(compiled.model, compiled.objective?.toLinearObjective())

        val result = request.solve()

        val optimal = assertIs<SourceClaim.Optimal>(assertNotNull(result.terminal).claim)
        assertEquals("9223372036854775808", optimal.sample.exactIntValue(0).toString())
        assertNull(request.finiteRoute())
    }

    @Test
    fun `an unbounded relaxation cannot override integer infeasibility`() {
        val compiled = Mps.parse("ROWS\n N COST\n E R\nCOLUMNS\n M0 'MARKER' 'INTORG'\n" +
            " I R 2\n M1 'MARKER' 'INTEND'\n X COST 1\nRHS\n RHS R 1\n" +
            "BOUNDS\n FR B I\n FR B X\nENDATA\n").toExactSourceProblem()

        val result = NumericalLinearRequest(compiled.model, compiled.objective?.toLinearObjective()).solve()

        assertEquals(SourceClaim.Refutation, assertNotNull(result.terminal).claim)
        assertNull(result.incumbent)
    }

    @Test
    fun `a numerical MIP ray retains an integral feasible base`() {
        val compiled = Mps.parse("ROWS\n N COST\n G R\nCOLUMNS\n M0 'MARKER' 'INTORG'\n" +
            " I R 1\n M1 'MARKER' 'INTEND'\n X COST 1 R 1\n" +
            "BOUNDS\n LI B I 0\n FR B X\nENDATA\n").toExactSourceProblem()

        val result = NumericalLinearRequest(compiled.model, compiled.objective?.toLinearObjective()).solve()

        val ray = assertIs<SourceClaim.Ray>(assertNotNull(result.terminal).claim)
        assertNotNull(compiled.sourceWitness(ray.sample.ints, ray.sample.exactReals))
    }

    @Test
    fun `cancellation withholds numerical terminal authority`() {
        val compiled = Mps.parse("ROWS\n N COST\nCOLUMNS\n X COST 1\nENDATA\n").toExactSourceProblem()

        val result = NumericalLinearRequest(compiled.model, compiled.objective?.toLinearObjective()).solve(
            TheoryParams(cancellation = Cancellation { true }))

        assertNull(result.terminal)
        assertEquals(TerminationReason.Cancelled, result.reason)
    }

    @Test
    fun `interruption after a numerical witness retains progress without terminal authority`() {
        val compiled = Mps.parse("ROWS\n N COST\nCOLUMNS\n X COST 1\nENDATA\n").toExactSourceProblem()
        var cancelled = false

        val result = NumericalLinearRequest(compiled.model, compiled.objective?.toLinearObjective()).solve(
            TheoryParams(cancellation = Cancellation { cancelled })) { _, _ -> cancelled = true }

        assertNotNull(result.incumbent)
        assertEquals(BigFraction.ZERO, result.value)
        assertNull(result.terminal)
        assertEquals(TerminationReason.Cancelled, result.reason)
    }

    @Test
    fun `an incomplete integer split retains only its checked root bound`() {
        val compiled = Mps.parse("ROWS\n N COST\n G R\nCOLUMNS\n M0 'MARKER' 'INTORG'\n" +
            " I COST 1 R 2\n M1 'MARKER' 'INTEND'\nRHS\n RHS R 1\nBOUNDS\n LI B I 0\nENDATA\n")
            .toExactSourceProblem()

        val result = NumericalLinearRequest(compiled.model, compiled.objective?.toLinearObjective()).solve(
            TheoryParams(maxLeaves = 1))

        assertNull(result.terminal)
        val bound = assertIs<SourceClaim.Bound>(assertNotNull(result.bound).claim)
        assertEquals(SourceValue.Rational(BigFraction.ofLong(2).reciprocal()), bound.value)
        assertEquals(TerminationReason.BudgetExhausted, result.reason)
    }

    @Test
    fun `an exhausted numerical traversal cannot claim infeasibility`() {
        val compiled = Mps.parse("ROWS\n N COST\nCOLUMNS\n X COST 1\nENDATA\n").toExactSourceProblem()

        val result = NumericalLinearRequest(compiled.model, compiled.objective?.toLinearObjective()).solve(
            TheoryParams(maxLeaves = 0))

        assertNull(result.terminal)
        assertEquals(TerminationReason.BudgetExhausted, result.reason)
    }
}
