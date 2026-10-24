package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceDerivation
import com.eignex.klause.solver.evidence.SourceEvidence
import com.eignex.klause.solver.evidence.SourceEvidenceCheck
import com.eignex.klause.solver.evidence.SourceEvidenceVerifier
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JointLinearRepairTest {
    @Test
    fun `joint repair should change coupled integers and complete their real residual after rounding fails`() {
        val model = Problem(0, intBounds = IntBounds.fromModelBounds(longArrayOf(0, 0), longArrayOf(1, 1), null, null),
            factors = arrayOf(
                Linear(intArrayOf(0), doubleArrayOf(1.0), intArrayOf(0), doubleArrayOf(-1.0), LinearOp.EQ, 0.0),
                Linear(intArrayOf(1), doubleArrayOf(1.0), intArrayOf(0), doubleArrayOf(-1.0), LinearOp.EQ, 0.0),
            ), numRealVars = 1, realLower = doubleArrayOf(0.5), realUpper = doubleArrayOf(1.0))
        val objective = LinearObjective(realCoefficients = doubleArrayOf(1.0))
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE, Cancellation.Never))
        val repair = JointLinearRepair(model, source, objective, TheoryParams(maxFlips = 2))

        val result = repair.use {
            it.search(adaptive = false).use { search ->
                while (!search.advance(Cancellation.Never, 1_000_000)) Unit
                search.minimizeResult(TerminationReason.BudgetExhausted)
            }
        }

        val sample = assertNotNull(result.assignment)
        assertEquals(listOf(1L, 1L), sample.ints.toList())
        assertEquals(listOf(BigFraction.ONE), sample.exactReals)
        assertEquals(1.0, result.stats.ls.completionsRefuted.sum)
        assertEquals(1L, result.stats.smt.completeSearchStages?.get("jointRepairCheckedIncumbents"))
        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
            SourceEvidence(source.scope, SourceClaim.Witness(sample), SourceDerivation.SourceWitness)))
    }

    @Test
    fun `exhausted joint neighborhoods should keep an unknown source result`() {
        val model = Problem(0, intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(0), null, null),
            factors = arrayOf(Linear(longArrayOf(1), intArrayOf(0), LinearOp.GE, 1)))
        val source = assertNotNull(model.linearAuthorityContext(LinearObjective(), ObjectiveSense.MINIMIZE,
            Cancellation.Never))
        val repair = JointLinearRepair(model, source, null, TheoryParams(maxFlips = 2))

        val result = repair.use {
            it.search(adaptive = false).use { search ->
                while (!search.advance(Cancellation.Never, 1_000_000)) Unit
                search.solveResult(TerminationReason.BudgetExhausted)
            }
        }

        assertIs<SolveResult.Unknown>(result)
        assertTrue(result.stats.ls.completionsRefuted.sum > 0)
    }
}
