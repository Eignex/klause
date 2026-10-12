package com.eignex.klause.solver.pipeline

import com.eignex.klause.formats.smtlib.SmtLibAdmission
import com.eignex.klause.formats.smtlib.SmtLibInput
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AffineObjectiveOperationTest {
    @Test
    fun `affine operation retains original source declarations objective and direction`() {
        val source = assertIs<SmtLibInput.Polynomial>(SmtLibAdmission.parse(
            "(declare-const x Real) (assert (= (* x x) 2)) (check-sat)",
        )).problem
        val request = source.pipelineRequest()
        val objective = LinearObjective(constant = 7, realCoefficients = doubleArrayOf(1.0))

        val operation = request.affineOperation(objective, ObjectiveSense.MAXIMIZE)

        assertSame(source, operation.problem)
        assertSame(request.preparedPlan.originalSource, operation.preparedPlan.originalSource)
        assertSame(objective, operation.preparedPlan.objective)
        assertTrue(operation.preparedPlan.maximize)
        assertEquals(request.problem.realVarNames, operation.problem.realVarNames)
        assertEquals(request.preparedPlan.sourceRealCount, operation.preparedPlan.sourceRealCount)
    }

    @Test
    fun `changed affine objective opens a distinct operation without rebinding an earlier request`() {
        val source = assertIs<SmtLibInput.Polynomial>(SmtLibAdmission.parse(
            "(declare-const x Real) (assert (= (* x x) 2)) (check-sat)",
        )).problem
        val objective = LinearObjective(constant = 7, realCoefficients = doubleArrayOf(1.0))
        val request = source.pipelineRequest().affineOperation(objective, ObjectiveSense.MINIMIZE)
        val replacement = objective.copy(constant = 13)

        val changed = request.affineOperation(replacement, ObjectiveSense.MAXIMIZE)

        assertSame(objective, request.preparedPlan.objective)
        assertSame(replacement, changed.preparedPlan.objective)
        assertSame(request.problem, changed.problem)
        assertSame(request.preparedPlan.originalSource, changed.preparedPlan.originalSource)
        assertEquals(false, request.preparedPlan.maximize)
        assertEquals(true, changed.preparedPlan.maximize)
    }
}
