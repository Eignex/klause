package com.eignex.klause.formats.smtlib

import com.eignex.klause.factor.arithmetic.ContinuousProduct
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.objective.toLinearObjective
import com.eignex.klause.solver.pipeline.ExecutionOperation
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.SourceExecutionRequest
import com.eignex.klause.solver.pipeline.SourceExecutionResult
import com.eignex.klause.solver.pipeline.SourceSetup
import com.eignex.klause.solver.pipeline.SourceSetupRequest
import com.eignex.klause.solver.pipeline.execute
import com.eignex.klause.solver.result.QuadraticOptimizationResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SmtLibSourceObjectiveTest {
    @Test
    fun `an affine helper objective beside a retained product should keep original declaration coefficients`() {
        val source = SmtLib.parse("(declare-const x Real) (define-fun cost ((v Real)) Real (+ v 7)) " +
            "(assert (= x 2)) (assert (= (* x x) 4)) (minimize (cost x))")
        val objective = assertNotNull(source.objective).toLinearObjective()
        val invocation = SourceSetup.prepare(SourceSetupRequest(source.source,
            ExecutionOperation.OPTIMIZE, FiniteEngine.BACKTRACK, objective))

        val execution = assertIs<SourceExecutionResult.Quadratic>(
            SourceSetup.execute(SourceExecutionRequest(invocation)))

        assertNull(source.quadraticObjective)
        assertEquals(1, source.model.factors.count { it is ContinuousProduct })
        assertEquals(1.0, objective.realCoefficients[source.realVarNames.getValue("x")])
        assertEquals(BigFraction.ofLong(9),
            assertIs<QuadraticOptimizationResult.Optimal>(execution.result.result).incumbent.value.rational)
    }

    @Test
    fun `quadratic objective capture should not add product auxiliaries to a linear source`() {
        val source = SmtLib.parse("(declare-const x Real) (assert (>= x 0)) (minimize (* x x))")

        assertNotNull(source.quadraticObjective)
        assertNull(source.objective)
        assertEquals(1, source.model.numRealVars)
        assertEquals(0, source.model.factors.count { it is ContinuousProduct })
    }

    @Test
    fun `unused integer helpers should not prevent original affine objective capture`() {
        val source = SmtLib.parse("(declare-const x Int) (define-fun residue ((v Int)) Int (mod v 3)) " +
            "(assert (<= 0 x 2)) (minimize x)")

        assertEquals(1L, assertNotNull(source.objective).intCoefficients[source.intVarNames.getValue("x")])
        assertNull(source.quadraticObjective)
    }

    @Test
    fun `quoted numeric declarations should retain their objective identity`() {
        val source = SmtLib.parse("(declare-const |0| Real) (minimize (* |0| |0|))")

        assertNotNull(source.quadraticObjective)
        assertEquals(mapOf("|0|" to 0), source.realVarNames)
        assertEquals(1, source.model.numRealVars)
    }
}
