package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.propagation.NoPropagator
import com.eignex.klause.propagation.PropagationProjection
import com.eignex.klause.propagation.PropagationProjectionProvider
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.AssignmentCheckCapability
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.solver.PropagationCapability
import com.eignex.klause.solver.search.ClauseSearchComponent
import com.eignex.klause.solver.search.ComponentCheck
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchBoolValue
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchComponent
import com.eignex.klause.solver.search.SearchExactRealValue
import com.eignex.klause.solver.search.SearchLearnedDbParams
import com.eignex.klause.solver.search.SearchResult
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class PreparedSearchRegistrationTest {
    @Test
    fun `partial factory construction releases earlier peers and scratch resources`() {
        val source = Problem(0, 0, emptyArray(), emptyArray())
        val closed = ArrayList<String>()
        val primary = IllegalArgumentException("construction")
        val cleanup = IllegalStateException("scratch cleanup")
        val registrations = listOf(
            PreparedSearchRegistration(source, ExecutionComponentDescriptor("first",
                ComponentResponsibility.BOOLEAN, emptyList(), emptyList(), emptyList())) {
                listOf(object : SearchComponent, AutoCloseable {
                    override fun close() { closed += "peer" }
                })
            },
            PreparedSearchRegistration(source, ExecutionComponentDescriptor("second",
                ComponentResponsibility.BOOLEAN, emptyList(), emptyList(), emptyList())) { opening ->
                opening.own(AutoCloseable { closed += "scratch"; throw cleanup })
                throw primary
            },
        )
        val prepared = PreparedSearchRegistrations(source, registrations)

        val failure = assertFailsWith<IllegalArgumentException> {
            prepared.open(Long.MAX_VALUE, Cancellation.Never, SearchLearnedDbParams(), null, null)
        }

        assertSame(primary, failure)
        assertSame(cleanup, failure.suppressedExceptions.single())
        assertEquals(listOf("scratch", "peer"), closed)
    }

    @Test
    fun `failed component installation releases each constructed peer once`() {
        val source = Problem(0, 0, emptyArray(), emptyArray())
        var closes = 0
        val peer = object : SearchComponent, AutoCloseable {
            override fun close() { closes++ }
        }
        val registration = PreparedSearchRegistration(source, ExecutionComponentDescriptor("duplicate",
            ComponentResponsibility.BOOLEAN, emptyList(), emptyList(), emptyList())) { listOf(peer, peer) }
        val prepared = PreparedSearchRegistrations(source, listOf(registration))

        assertFailsWith<IllegalArgumentException> {
            prepared.open(Long.MAX_VALUE, Cancellation.Never, SearchLearnedDbParams(), null, null)
        }

        assertEquals(1, closes)
    }

    @Test
    fun `planned search closes every installed peer in reverse order`() {
        val source = Problem(0, 0, emptyArray(), emptyArray())
        val closed = ArrayList<Int>()
        val registration = PreparedSearchRegistration(source, ExecutionComponentDescriptor("peers",
            ComponentResponsibility.BOOLEAN, emptyList(), emptyList(), emptyList())) {
            List(2) { index -> object : SearchComponent, AutoCloseable {
                override fun close() { closed += index }
            } }
        }
        val search = PreparedSearchRegistrations(source, listOf(registration))
            .open(Long.MAX_VALUE, Cancellation.Never, SearchLearnedDbParams(), null, null)

        search.close()
        search.close()

        assertEquals(listOf(1, 0), closed)
    }

    @Test
    fun `a selected inert clause is checked without reinstalling default deduction`() {
        val source = Problem(1, 0, emptyArray(), arrayOf(Clause(intArrayOf(0))))
        val providers = FactorProjectionProviders(propagation = PropagationProjectionProvider {
            PropagationProjection(PropagationCapability.INERT, AssignmentCheckCapability.SOURCE_CERTIFICATION,
                "The original clause is checked at the complete point.") { NoPropagator }
        })
        val ownership = source.componentPlan(providers.prepare(source), preferFinite = true)
        val prepared = PreparedSearchComponents(ownership, source, emptyMap())

        for (value in listOf(false, true)) prepared.open().use { search ->
            assertIs<ComponentResult.Consistent>(search.session.initialize())
            assertNull(search.session.boolValue(0))
            assertIs<ComponentResult.Consistent>(search.session.push(SearchDecision.Bool(Lit.make(0, value))))

            if (value) assertIs<ComponentCheck.Feasible>(search.session.check())
            else assertIs<ComponentCheck.Infeasible>(search.session.check())
        }
    }

    @Test
    fun `prepared openings keep mutable custom deduction private`() {
        val source = Problem(1, 0, emptyArray(), arrayOf(Clause(intArrayOf(0, 1))))
        val providers = FactorProjectionProviders(propagation = PropagationProjectionProvider {
            PropagationProjection(PropagationCapability.SOUND_FILTERING, AssignmentCheckCapability.EXACT) {
                object : Propagator {
                    private var owner: PropagationState? = null
                    override fun propagate(state: PropagationState, factorId: Int): Boolean {
                        if (owner == null) owner = state
                        check(owner === state) { "mutable deduction belongs to one live state" }
                        return true
                    }
                }
            }
        })
        val ownership = source.componentPlan(providers.prepare(source), preferFinite = true)
        val prepared = PreparedSearchComponents(ownership, source, emptyMap())

        prepared.open().use { first ->
            assertIs<ComponentResult.Consistent>(first.session.initialize())
            assertIs<ComponentResult.Consistent>(first.session.push(SearchDecision.Bool(0)))
            prepared.open().use { sibling ->
                assertIs<ComponentResult.Consistent>(sibling.session.initialize())
                assertIs<ComponentResult.Consistent>(sibling.session.push(SearchDecision.Bool(1)))
                assertIs<ComponentCheck.Feasible>(sibling.session.check())
            }

            assertIs<ComponentCheck.Feasible>(first.session.check())
            assertEquals(true, first.session.boolValue(0))
        }
    }

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
    fun `prepared exact openings isolate cached outcomes`() {
        val source = Problem(numBoolVars = 1, numIntVars = 0, intDomains = emptyArray(),
            factors = arrayOf(ReifiedRealLinear(0, intArrayOf(), doubleArrayOf(), intArrayOf(0),
                doubleArrayOf(1.0), LinearOp.GE, 1.0)),
            numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0))
        val prepared = PreparedSearchComponents(source.componentPlan(), source, emptyMap())
        prepared.prepareSource(Cancellation.Never)
        val coordinate = SearchExactRealValue(SourceRealId(0))

        prepared.open().use { first ->
            assertIs<ComponentResult.Consistent>(first.session.initialize())
            assertIs<ComponentResult.Consistent>(first.session.push(SearchDecision.Bool(0)))
            assertIs<ComponentCheck.Feasible>(first.session.check())
            prepared.open().use { sibling ->
                assertIs<ComponentResult.Consistent>(sibling.session.initialize())
                assertIs<ComponentResult.Consistent>(sibling.session.push(SearchDecision.Bool(1)))
                assertIs<ComponentCheck.Feasible>(sibling.session.check())

                assertNotEquals(BigFraction.ONE, sibling.session.model().valueOf(coordinate))
            }
            assertIs<ComponentCheck.Feasible>(first.session.check())
            assertEquals(BigFraction.ONE, first.session.model().valueOf(coordinate))
        }
    }

    @Test
    fun `published exact coordinates outlive their producer`() {
        val source = Problem(numBoolVars = 1, numIntVars = 0, intDomains = emptyArray(),
            factors = arrayOf(ReifiedRealLinear(0, intArrayOf(), doubleArrayOf(), intArrayOf(0),
                doubleArrayOf(1.0), LinearOp.GE, 1.0)),
            numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0))
        val prepared = PreparedSearchComponents(source.componentPlan(), source, emptyMap())
        prepared.prepareSource(Cancellation.Never)

        val published = prepared.open().use { search ->
            assertIs<ComponentResult.Consistent>(search.session.initialize())
            assertIs<ComponentResult.Consistent>(search.session.push(SearchDecision.Bool(0)))
            assertIs<ComponentCheck.Feasible>(search.session.check())
            search.session.model()
        }

        assertEquals(BigFraction.ONE, published.valueOf(SearchExactRealValue(SourceRealId(0))))
    }
}
