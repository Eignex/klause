package com.eignex.klause.theory.qfnra

import com.eignex.klause.formats.smtlib.SmtLibPolynomial
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchInvocationResources
import com.eignex.klause.solver.search.SearchSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PolynomialSearchComponentTest {
    @Test
    fun `search disposal releases unfinished polynomial preparation for another check`() {
        val source = SmtLibPolynomial.parse("(declare-const x Real) (assert (= x 1))")
        val service = PolynomialTheoryService(source.formula, source.boolCount, source.realCount)
        service.invocation().use { invocation ->
            val component = PolynomialSearchComponent(service, invocation)
            val resources = SearchInvocationResources()
            resources.ownComponent(component)
            val pending = component.prepareInitialization(SearchSession(listOf(component)))
            assertNull(pending.next())
            val charged = invocation.stats.workSpent

            resources.close()
            resources.close()

            assertTrue(charged > 0)
            assertEquals(charged, invocation.stats.workSpent)
            assertFailsWith<IllegalStateException> { pending.next() }
            val result = assertIs<QfNraResult.Satisfiable>(invocation.check())
            assertEquals(BigFraction.ONE, result.assignment.realValue(0).rational)
        }
    }

    @Test
    fun `disposing completed preparation leaves the containing invocation reusable`() {
        val source = SmtLibPolynomial.parse("(declare-const x Real) (assert (= x 1))")
        val service = PolynomialTheoryService(source.formula, source.boolCount, source.realCount)
        service.invocation().use { invocation ->
            val component = PolynomialSearchComponent(service, invocation)
            val pending = component.prepareInitialization(SearchSession(listOf(component)))
            assertIs<ComponentResult.Consistent>(pending.asSequence().filterNotNull().single())

            component.close()

            assertIs<QfNraResult.Satisfiable>(invocation.check())
        }
    }

    @Test
    fun `disposing an unstarted preparation prevents it from opening a query`() {
        val source = SmtLibPolynomial.parse("(declare-const x Real) (assert (= x 1))")
        val service = PolynomialTheoryService(source.formula, source.boolCount, source.realCount)
        service.invocation().use { invocation ->
            val component = PolynomialSearchComponent(service, invocation)
            val pending = component.prepareInitialization(SearchSession(listOf(component)))

            component.close()

            assertFailsWith<IllegalStateException> { pending.next() }
            assertEquals(0L, invocation.queries)
            assertIs<QfNraResult.Satisfiable>(invocation.check())
        }
    }
}
