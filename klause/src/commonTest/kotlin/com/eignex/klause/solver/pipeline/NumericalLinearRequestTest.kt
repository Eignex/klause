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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NumericalLinearRequestTest {
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
    fun `an exhausted numerical traversal cannot claim infeasibility`() {
        val compiled = Mps.parse("ROWS\n N COST\nCOLUMNS\n X COST 1\nENDATA\n").toExactSourceProblem()

        val result = NumericalLinearRequest(compiled.model, compiled.objective?.toLinearObjective()).solve(
            TheoryParams(maxLeaves = 0))

        assertNull(result.terminal)
        assertEquals(TerminationReason.BudgetExhausted, result.reason)
    }
}
