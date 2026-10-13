package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.search.ClauseSearchComponent
import com.eignex.klause.solver.search.ComponentCheck
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchBoolValue
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchExactRealValue
import com.eignex.klause.solver.search.SearchLearnedDbParams
import com.eignex.klause.solver.search.SearchResult
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class PreparedSearchRegistrationTest {
    @Test
    fun `registered Boolean peers enforce all source clauses`() {
        val source = Problem(numBoolVars = 2, numIntVars = 0, intDomains = emptyArray(),
            factors = arrayOf(Clause(intArrayOf(0, 2)), Clause(intArrayOf(1, 2))))
        val prepared = PreparedSearchRegistrations(source, source.factors.indices.map { factor ->
            PreparedSearchRegistration(source, ExecutionComponentDescriptor("clauses-$factor",
                ComponentResponsibility.BOOLEAN, listOf(factor), emptyList(), emptyList(),
                if (factor == 0) listOf(0, 1) else emptyList())) {
                listOf(ClauseSearchComponent(listOf(source.factors[factor] as Clause)))
            }
        })

        prepared.open(Long.MAX_VALUE, Cancellation.Never, SearchLearnedDbParams(), null, null).use { search ->
            assertIs<ComponentResult.Consistent>(search.session.initialize())
            val result = assertIs<SearchResult.Satisfied>(search.session.solve(2))

            assertEquals(true, result.model.valueOf(SearchBoolValue(1)))
        }
    }

    @Test
    fun `prepared Boolean openings isolate asserted levels`() {
        val source = Problem(numBoolVars = 1, numIntVars = 0, intDomains = emptyArray(), factors = emptyArray())
        val registration = PreparedSearchRegistration(source, ExecutionComponentDescriptor("boolean",
            ComponentResponsibility.BOOLEAN, emptyList(), emptyList(), emptyList(), listOf(0))) {
            listOf(ClauseSearchComponent(emptyList()))
        }
        val prepared = PreparedSearchRegistrations(source, listOf(registration))

        prepared.open(Long.MAX_VALUE, Cancellation.Never, SearchLearnedDbParams(), null, null).use { first ->
            assertIs<ComponentResult.Consistent>(first.session.initialize())
            assertIs<ComponentResult.Consistent>(first.session.push(SearchDecision.Bool(0)))
            prepared.open(Long.MAX_VALUE, Cancellation.Never, SearchLearnedDbParams(), null, null).use { sibling ->
                assertIs<ComponentResult.Consistent>(sibling.session.initialize())
                assertIs<ComponentResult.Consistent>(sibling.session.push(SearchDecision.Bool(1)))

                assertEquals(true, first.session.boolValue(0))
                assertEquals(false, sibling.session.boolValue(0))
            }
        }
    }

    @Test
    fun `foreign source registration declines before invoking its factory`() {
        val source = Problem(numBoolVars = 0, numIntVars = 0, intDomains = emptyArray(), factors = emptyArray())
        val other = Problem(numBoolVars = 0, numIntVars = 0, intDomains = emptyArray(), factors = emptyArray())
        var openings = 0
        val registration = PreparedSearchRegistration(source, ExecutionComponentDescriptor("boolean",
            ComponentResponsibility.BOOLEAN, emptyList(), emptyList(), emptyList())) {
            openings++
            listOf(ClauseSearchComponent(emptyList()))
        }

        assertFailsWith<IllegalArgumentException> { PreparedSearchRegistrations(other, listOf(registration)) }

        assertEquals(0, openings)
    }

    @Test
    fun `multiple authoritative coordinate owners decline before opening`() {
        val source = Problem(numBoolVars = 1, numIntVars = 0, intDomains = emptyArray(), factors = emptyArray())
        val registrations = List(2) { index ->
            PreparedSearchRegistration(source, ExecutionComponentDescriptor("boolean-$index",
                ComponentResponsibility.BOOLEAN, emptyList(), emptyList(), emptyList(), listOf(0))) {
                listOf(ClauseSearchComponent(emptyList()))
            }
        }

        assertFailsWith<IllegalArgumentException> { PreparedSearchRegistrations(source, registrations) }
    }

    @Test
    fun `prepared exact openings isolate outcomes and retain published coordinates`() {
        val source = Problem(numBoolVars = 1, numIntVars = 0, intDomains = emptyArray(),
            factors = arrayOf(ReifiedRealLinear(0, intArrayOf(), doubleArrayOf(), intArrayOf(0),
                doubleArrayOf(1.0), LinearOp.EQ, 1.0)),
            numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0))
        val prepared = PreparedSearchComponents(source.componentPlan(), source, emptyMap())
        prepared.prepareSource(Cancellation.Never)
        val coordinate = SearchExactRealValue(SourceRealId(0))

        val published = prepared.open().use { first ->
            assertIs<ComponentResult.Consistent>(first.session.initialize())
            assertIs<ComponentResult.Consistent>(first.session.push(SearchDecision.Bool(0)))
            assertIs<ComponentCheck.Feasible>(first.session.check())
            val model = first.session.model()
            prepared.open().use { sibling ->
                assertIs<ComponentResult.Consistent>(sibling.session.initialize())
                assertIs<ComponentResult.Consistent>(sibling.session.push(SearchDecision.Bool(1)))
                assertIs<ComponentCheck.Feasible>(sibling.session.check())

                assertNotEquals(BigFraction.ONE, sibling.session.model().valueOf(coordinate))
            }
            model
        }

        assertEquals(BigFraction.ONE, published.valueOf(coordinate))
    }
}
