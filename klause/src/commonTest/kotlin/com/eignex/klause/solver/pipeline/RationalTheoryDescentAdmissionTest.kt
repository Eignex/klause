package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.Term
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.incumbent.Candidate
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RationalTheoryDescentAdmissionTest {
    @Test
    fun `declined numerical authority finds a bounded integer witness within a short descent`() {
        val source = Problem(0, 1, arrayOf(IntDomain(0, 1_000_000)), arrayOf(ExactSearchRow.of(
            intArrayOf(Term.ofIntVar(0), Term.ofRealVar(0)),
            listOf(BigFraction.ONE, BigFraction.ONE), LinearOp.GE, BigFraction.ofLong(999_999) +
                BigFraction.ofLong(1) / BigFraction.ofLong(2))), numRealVars = 43,
            realLower = DoubleArray(43), realUpper = DoubleArray(43) { 1.0 })
        val objective = LinearObjective(realCoefficients = DoubleArray(43) { if (it == 0) 1.0 else 0.0 })
        var published: MinimizeResult.WithSample? = null

        RationalTheoryDescent(source, objective, TheoryParams(maxDecisions = 4, openWorkLimit = 32)).use {
            it.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) { candidate -> published = candidate }
        }

        val candidate = assertNotNull(published)
        assertIs<Verification.Accepted<*, *>>(openWitnessVerifier(source, objective).verify(
            Candidate(candidate.sample, candidate.objective)))
    }

    @Test
    fun `numerical proof admission decline preserves a source checked incumbent`() {
        val source = Problem(0, 0, emptyArray(), emptyArray(), numRealVars = 43,
            realLower = DoubleArray(43), realUpper = DoubleArray(43) { 1.0 })
        val objective = LinearObjective(realCoefficients = DoubleArray(43) { if (it == 0) 1.0 else 0.0 })
        var published: MinimizeResult.WithSample? = null

        RationalTheoryDescent(source, objective, TheoryParams(openWorkLimit = 8)).use {
            it.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) { candidate -> published = candidate }
        }

        val candidate = assertNotNull(published)
        assertEquals(BigFraction.ZERO, objective.evaluateExact(candidate.sample))
        assertIs<Verification.Accepted<*, *>>(openWitnessVerifier(source, objective).verify(
            Candidate(candidate.sample, candidate.objective)))
    }

    @Test
    fun `numerical proof admission decline keeps the complete work allowance`() {
        val source = Problem(0, 0, emptyArray(), emptyArray(), numRealVars = 43,
            realLower = DoubleArray(43), realUpper = DoubleArray(43) { 1.0 })
        val objective = LinearObjective(realCoefficients = DoubleArray(43) { if (it == 0) 1.0 else 0.0 })

        val result = RationalTheoryDescent(source, objective, TheoryParams(openWorkLimit = 1)).use {
            it.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) { _ -> }
        }

        val unknown = assertIs<MinimizeResult.Unknown>(result)
        assertEquals(TerminationReason.BudgetExhausted, unknown.reason)
        assertEquals(1L, unknown.stats.openTheory.openWork)
    }

    @Test
    fun `numerical proof admission decline retains the portfolio slice allowance`() {
        val source = Problem(0, 0, emptyArray(), emptyArray(), numRealVars = 43,
            realLower = DoubleArray(43), realUpper = DoubleArray(43) { 1.0 })
        val objective = LinearObjective(realCoefficients = DoubleArray(43) { if (it == 0) 1.0 else 0.0 })
        var published: MinimizeResult.WithSample? = null

        RationalTheoryDescent(source, objective, TheoryParams(openWorkLimit = 8)).use { descent ->
            val paused = descent.runSlice(Cancellation.Never, Long.MAX_VALUE, 1) { published = it }

            assertNull(paused)
            assertNull(published)
            assertEquals(1L, descent.work)

            descent.runSlice(Cancellation.Never, Long.MAX_VALUE, 8) { published = it }
            assertNotNull(published)
        }
    }
}
