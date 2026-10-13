package com.eignex.klause.solver

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.BuiltInScoringProjectionProvider
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.LocalSearchModel
import com.eignex.klause.localsearch.LocalSearchProblem
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.NoInvariant
import com.eignex.klause.localsearch.ScoringProjection
import com.eignex.klause.localsearch.ScoringProjectionProvider
import com.eignex.klause.lp.RelaxationProjection
import com.eignex.klause.lp.RelaxationProjectionProvider
import com.eignex.klause.lp.relaxation.CpToLpRelaxation
import com.eignex.klause.lp.relaxation.RootDomains
import com.eignex.klause.propagation.NoPropagator
import com.eignex.klause.propagation.PropagationProblem
import com.eignex.klause.propagation.PropagationProjection
import com.eignex.klause.propagation.PropagationProjectionProvider
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.Propagator
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PreparedFactorProjectionsTest {
    @Test
    fun `alternate clause deduction accepts exactly the source truth table`() {
        val source = Problem(2, 0, emptyArray(), arrayOf<Factor>(Clause(intArrayOf(0, 2))))
        val providers = FactorProjectionProviders(propagation = PropagationProjectionProvider { factor ->
            val clause = factor as Clause
            PropagationProjection(PropagationCapability.SOUND_FILTERING, AssignmentCheckCapability.EXACT) {
                object : Propagator {
                    override fun propagate(state: PropagationState, factorId: Int): Boolean {
                        var free = -1
                        var count = 0
                        for (literal in clause.literals) when (state.litTruth(literal)) {
                            true -> return true
                            false -> Unit
                            null -> { free = literal; count++ }
                        }
                        return count > 1 || (count == 1 && state.pinLit(free, antecedents = null))
                    }
                }
            }
        })
        val prepared = providers.prepare(source)

        for (first in listOf(false, true)) for (second in listOf(false, true)) {
            val projection = PropagationProblem(source, prepared)
            val session = PropagationSession(projection, nativeSat = true)
            val firstPin = session.pinBool(0, first)
            val secondPin = if (firstPin is PropagationResult.Unsat) firstPin else session.pinBool(1, second)

            assertEquals(first || second, secondPin !is PropagationResult.Unsat)
            assertFalse(projection.isNativeSatEligible)
        }
    }

    @Test
    fun `alternate scoring preserves source acceptance and its move delta`() {
        val source = Problem(1, 0, emptyArray(), arrayOf<Factor>(Clause(intArrayOf(0))))
        val providers = FactorProjectionProviders(scoring = ScoringProjectionProvider {
            ScoringProjection(ScoringCapability.GRADED, AssignmentCheckCapability.EXACT) { _ ->
                object : Invariant {
                    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean =
                        !state.assignment.boolValue(0)
                    override fun violationDegree(state: LocalSearchState, factorId: Int): Int =
                        if (isViolated(state, factorId)) 7 else 0
                    override fun deltaIfBoolFlipped(state: LocalSearchState, factorId: Int, boolVar: Int): Int =
                        if (isViolated(state, factorId)) -7 else 7
                }
            }
        })
        val selection = providers.prepare(source)
        val projection = LocalSearchProblem(source, selection)
        val state = LocalSearchState(LocalSearchModel.open(source), Random(1), projection = projection)

        for (value in listOf(false, true)) {
            state.assignment.setBool(0, value)
            state.recompute()

            assertEquals(value, state.cost == 0L)
            assertEquals(if (value) 7 else -7, projection.invariants[0].deltaIfBoolFlipped(state, 0, 0))
        }
        assertEquals(ScoringCapability.GRADED, selection.capabilities(0).scoring)
    }

    @Test
    fun `selected inert scoring remains distinct from unsupported scoring`() {
        val source = Problem(1, 0, emptyArray(), arrayOf<Factor>(Clause(intArrayOf(0))))
        val inert = FactorProjectionProviders(scoring = ScoringProjectionProvider {
            ScoringProjection(ScoringCapability.INERT, AssignmentCheckCapability.UNSUPPORTED,
                "Checked by pinned source propagation.") { NoInvariant }
        }).prepare(source)
        val unsupported = FactorProjectionProviders(scoring = ScoringProjectionProvider { null }).prepare(source)

        assertSame(NoInvariant, LocalSearchProblem(source, inert).invariants[0])
        assertEquals(ScoringCapability.INERT, inert.capabilities(0).scoring)
        assertEquals(AssignmentCheckCapability.UNSUPPORTED, inert.capabilities(0).assignmentCheck)
        assertEquals(ScoringCapability.UNSUPPORTED, unsupported.capabilities(0).scoring)
        assertFailsWith<IllegalStateException> { LocalSearchProblem(source, unsupported) }
    }

    @Test
    fun `declared filtering rejects an inert propagation implementation`() {
        val source = Problem(1, 0, emptyArray(), arrayOf<Factor>(Clause(intArrayOf(0))))
        val selected = FactorProjectionProviders(propagation = PropagationProjectionProvider {
            PropagationProjection(PropagationCapability.SOUND_FILTERING, AssignmentCheckCapability.EXACT) {
                NoPropagator
            }
        }).prepare(source)

        assertFailsWith<IllegalStateException> { PropagationProblem(source, selected).propagators }
    }

    @Test
    fun `declared graded scoring rejects an inert scoring implementation`() {
        val source = Problem(1, 0, emptyArray(), arrayOf<Factor>(Clause(intArrayOf(0))))
        val selected = FactorProjectionProviders(scoring = ScoringProjectionProvider {
            ScoringProjection(ScoringCapability.GRADED, AssignmentCheckCapability.EXACT) { NoInvariant }
        }).prepare(source)

        assertFailsWith<IllegalStateException> { LocalSearchProblem(source, selected) }
    }

    @Test
    fun `prepared factories isolate mutable sibling scores`() {
        val source = Problem(1, 0, emptyArray(), arrayOf<Factor>(Clause(intArrayOf(0))))
        val selected = FactorProjectionProviders(scoring = ScoringProjectionProvider {
            ScoringProjection(ScoringCapability.GRADED, AssignmentCheckCapability.EXACT) { _ ->
                object : Invariant {
                    private var violated = false
                    override fun initialize(state: LocalSearchState, factorId: Int) {
                        violated = !state.assignment.boolValue(0)
                    }
                    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean = violated
                }
            }
        }).prepare(source)
        val first = LocalSearchState(LocalSearchModel.open(source), Random(1),
            projection = LocalSearchProblem(source, selected))
        val sibling = LocalSearchState(LocalSearchModel.open(source), Random(1),
            projection = LocalSearchProblem(source, selected))
        first.assignment.setBool(0, false)
        first.recompute()
        sibling.assignment.setBool(0, true)
        sibling.recompute()

        assertTrue(first.factors[0].isViolated(first, 0))
        assertFalse(sibling.factors[0].isViolated(sibling, 0))
    }

    @Test
    fun `source rewrites select against the transformed factors`() {
        val source = Problem(1, 0, emptyArray(), arrayOf<Factor>(Clause(intArrayOf(0))))
        var selections = 0
        val selected = FactorProjectionProviders(scoring = ScoringProjectionProvider { factor ->
            selections++
            BuiltInScoringProjectionProvider.select(factor)
        }).prepare(source)
        val rewritten = source.withFactors(arrayOf<Factor>(Clause(intArrayOf(1))))

        assertSame(selected, selected.forProblem(source))
        assertFailsWith<IllegalArgumentException> { LocalSearchProblem(rewritten, selected) }
        val replaced = selected.forProblem(rewritten)
        val state = LocalSearchState(LocalSearchModel.open(rewritten), Random(1),
            projection = LocalSearchProblem(rewritten, replaced))
        state.assignment.setBool(0, false)
        state.recompute()

        assertEquals(2, selections)
        assertEquals(0L, state.cost)
    }

    @Test
    fun `alternate relaxation keeps exact source rows and declines persistent reuse`() {
        val source = Problem(0, 1, arrayOf(IntDomain(0, 2)),
            arrayOf<Factor>(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 1)))
        val selected = FactorProjectionProviders(relaxation = RelaxationProjectionProvider {
            RelaxationProjection(RelaxationCapability.SOUND) { builder ->
                builder.linearRow(LinearOp.LE, intArrayOf(0), longArrayOf(2), 2)
            }
        }).prepare(source)

        val relaxation = CpToLpRelaxation(source, null, projections = selected).build(RootDomains(source))

        assertEquals(1, relaxation.model.m)
        assertEquals(2L, relaxation.model.rhs[0])
        assertFalse(relaxation.persistentEligible)
        assertEquals(RelaxationCapability.SOUND, selected.capabilities(0).relaxation)
    }

    @Test
    fun `a provider with no relaxation emits no row`() {
        val source = Problem(0, 1, arrayOf(IntDomain(0, 2)),
            arrayOf<Factor>(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 1)))
        val selected = FactorProjectionProviders(relaxation = RelaxationProjectionProvider { null }).prepare(source)

        val relaxation = CpToLpRelaxation(source, null, projections = selected).build(RootDomains(source))

        assertEquals(0, relaxation.model.m)
        assertEquals(RelaxationCapability.NONE, selected.capabilities(0).relaxation)
    }

    @Test
    fun `default selections preserve finite filtering and native clause eligibility`() {
        val source = Problem(0, 1, arrayOf(IntDomain(0, 2)),
            arrayOf<Factor>(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 1)))
        val selected = FactorProjectionProviders.Default.prepare(source)
        val session = PropagationSession(PropagationProblem(source, selected))
        val clauses = Problem(1, 0, emptyArray(), arrayOf<Factor>(Clause(intArrayOf(Lit.make(0, true)))))

        assertEquals(1L, session.intDomain(0).max)
        assertEquals(source.factors[0].builtInExecutionCapabilities(), selected.capabilities(0))
        assertTrue(PropagationProblem(clauses, FactorProjectionProviders.Default.prepare(clauses)).isNativeSatEligible)
    }
}
