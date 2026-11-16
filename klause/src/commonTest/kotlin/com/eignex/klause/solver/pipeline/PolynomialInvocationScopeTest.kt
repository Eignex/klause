package com.eignex.klause.solver.pipeline

import com.eignex.klause.formats.smtlib.SmtLibPolynomial
import com.eignex.klause.theory.qfnra.PolynomialTheoryService
import com.eignex.klause.theory.qfnra.QfNraResult
import com.eignex.klause.util.ExecutionControl
import com.eignex.klause.util.WorkMeter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PolynomialInvocationScopeTest {
    @Test
    fun `cancelling one arithmetic worker should leave its sibling and later queries available`() {
        val formula = SmtLibPolynomial.parse("(declare-const x Real) (assert (= x 1))")
        var charges = 0L
        val meter = object : ExecutionControl() {
            override fun isCancelled() = false
            override val accounting = WorkMeter { units ->
                charges += units
            }
        }
        val scope = PolynomialInvocationScope(
            PolynomialTheoryService(formula.formula, 0, 1),
            PolynomialTheoryParams(maximumWork = 16384, cancellation = meter),
        )
        val resources = PortfolioInvocationResources(2, 1, 17, ExecutionControl.Never, null, setOf(0, 1))
        val first = scope.worker(resources.slots[0])
        val second = scope.worker(resources.slots[1])

        assertIs<QfNraResult.Unknown>(first.check(cancellation = ExecutionControl { true }))
        assertIs<QfNraResult.Satisfiable>(second.check())
        assertIs<QfNraResult.Satisfiable>(first.check())
        resources.close()

        assertEquals(first.stats.workSpent + second.stats.workSpent, scope.stats.workSpent)
        assertEquals(scope.stats.workSpent, charges)
        assertEquals(first.stats.liftedCells + second.stats.liftedCells, scope.stats.liftedCells)
    }
}
