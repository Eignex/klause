package com.eignex.klause.theory.qfnra

import com.eignex.klause.formats.smtlib.SmtLibPolynomial
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchAtomPremise
import com.eignex.klause.solver.search.SearchAtomRegistry
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.solver.search.explainAtoms
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class PolynomialSearchComponentTest {
    @Test
    fun `square consequence should tighten the finite root before a decision`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real) (declare-const r Real)
            (assert (and (>= x 0) (<= x 10) (>= r 3) (= (* r r) x)))
        """.trimIndent())
        val service = PolynomialTheoryService(source.formula, source.boolCount, source.realCount)
        service.invocation().use { invocation ->
            val session = SearchSession(listOf(PolynomialSearchComponent(service, invocation, mapOf(0 to 0))))
            val root = SearchAtomPremise.All(emptyList())
            session.publish(SearchDecision.IntAtLeast(0, 0), root)
            session.publish(SearchDecision.IntAtMost(0, 10), root)

            assertIs<ComponentResult.Consistent>(session.initialize())

            assertEquals(9L, session.intLowerBound(0))
            assertEquals(0, session.decisionLevel)
            assertEquals(emptyList(), session.explainAtoms(session.intLowerBoundPremise(0))?.literals?.toList())
        }
    }

    @Test
    fun `guarded square consequence should explain and retract its guard`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real) (declare-const r Real) (declare-const b Bool)
            (assert (and (>= x 0) (<= x 10) (=> b (>= r 3)) (= (* r r) x)))
        """.trimIndent())
        val service = PolynomialTheoryService(source.formula, source.boolCount, source.realCount)
        service.invocation().use { invocation ->
            val session = SearchSession(listOf(PolynomialSearchComponent(service, invocation, mapOf(0 to 0))))
            val root = SearchAtomPremise.All(emptyList())
            session.publish(SearchDecision.IntAtLeast(0, 0), root)
            session.publish(SearchDecision.IntAtMost(0, 10), root)
            assertIs<ComponentResult.Consistent>(session.initialize())
            assertEquals(0L, session.intLowerBound(0))

            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(0)))

            assertEquals(9L, session.intLowerBound(0))
            assertEquals(listOf(1), session.explainAtoms(session.intLowerBoundPremise(0))?.literals?.toList())
            session.popTo(0)
            assertIs<ComponentResult.Consistent>(session.propagate())
            assertEquals(0L, session.intLowerBound(0))
        }
    }

    @Test
    fun `numeric square consequences should retain their named bound premise through rollback`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real) (declare-const k Real)
            (assert (and (>= x 0) (<= x 10) (>= k 0) (<= k 3) (= (* k k) x)))
        """.trimIndent())
        val service = PolynomialTheoryService(source.formula, source.boolCount, source.realCount)
        service.invocation().use { invocation ->
            val component = PolynomialSearchComponent(service, invocation, mapOf(0 to 0, 1 to 1))
            val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(0))
            assertIs<ComponentResult.Consistent>(session.initialize())
            val branch = SearchDecision.IntAtLeast(1, 3L)

            assertIs<ComponentResult.Consistent>(session.push(branch))

            val literal = assertNotNull(session.atomLiteral(branch))
            assertEquals(9L, session.intLowerBound(0))
            assertEquals(listOf(literal xor 1),
                session.explainAtoms(session.intLowerBoundPremise(0))?.literals?.toList())
            session.popTo(0)
            assertIs<ComponentResult.Consistent>(session.propagate())
            assertEquals(0L, session.intLowerBound(0))
        }
    }

    @Test
    fun `a zero shared check allowance should withhold nonlinear deductions`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real) (declare-const r Real)
            (assert (and (>= x 0) (<= x 10) (>= r 3) (= (* r r) x)))
        """.trimIndent())
        val service = PolynomialTheoryService(source.formula, source.boolCount, source.realCount)
        service.invocation().use { invocation ->
            val session = SearchSession(listOf(PolynomialSearchComponent(service, invocation, mapOf(0 to 0))),
                maxChecks = 0)

            assertIs<ComponentResult.Indeterminate>(session.initialize())

            assertEquals(0L, invocation.stats.envelopeQueries)
        }
    }
}
