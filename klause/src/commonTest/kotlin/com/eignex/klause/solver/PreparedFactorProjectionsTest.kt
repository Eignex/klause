package com.eignex.klause.solver

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.circuit.Circuit
import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.localsearch.BuiltInScoringProjectionProvider
import com.eignex.klause.localsearch.Completion
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.LocalSearchModel
import com.eignex.klause.localsearch.LocalSearchProblem
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.NoInvariant
import com.eignex.klause.localsearch.ScoringProjection
import com.eignex.klause.localsearch.ScoringProjectionProvider
import com.eignex.klause.localsearch.UnscoredCandidateCheck
import com.eignex.klause.lp.RelaxationProjection
import com.eignex.klause.lp.RelaxationProjectionProvider
import com.eignex.klause.lp.relaxation.CpToLpRelaxation
import com.eignex.klause.lp.relaxation.RootDomains
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.BuiltInPropagationProjectionProvider
import com.eignex.klause.propagation.NoPropagator
import com.eignex.klause.propagation.PropagationProblem
import com.eignex.klause.propagation.PropagationProjection
import com.eignex.klause.propagation.PropagationProjectionProvider
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.propagation.bake
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PreparedFactorProjectionsTest {
    @Test
    fun `alternate clause deduction accepts exactly the source truth table`() {
        val source = Problem(2, 0, emptyArray(), arrayOf<Factor>(Clause(intArrayOf(0, 2))))
        val providers = FactorProjectionProviders(propagation = PropagationProjectionProvider { factor ->
            PropagationProjection(PropagationCapability.SOUND_FILTERING, AssignmentCheckCapability.EXACT) { view ->
                val clause = view as Clause
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
            ScoringProjection(ScoringCapability.GRADED, AssignmentCheckCapability.EXACT) { _, _ ->
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
                "Checked by pinned source propagation.") { _, _ -> NoInvariant }
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
            ScoringProjection(ScoringCapability.GRADED, AssignmentCheckCapability.EXACT) { _, _ -> NoInvariant }
        }).prepare(source)

        assertFailsWith<IllegalStateException> { LocalSearchProblem(source, selected) }
    }

    @Test
    fun `prepared factories isolate mutable sibling scores`() {
        val source = Problem(1, 0, emptyArray(), arrayOf<Factor>(Clause(intArrayOf(0))))
        val selected = FactorProjectionProviders(scoring = ScoringProjectionProvider {
            ScoringProjection(ScoringCapability.GRADED, AssignmentCheckCapability.EXACT) { _, _ ->
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
            RelaxationProjection(RelaxationCapability.SOUND) { _, builder ->
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
    fun `omitted scheduling and circuit projections do not reinstall default family rows`() {
        val source = Problem(0, 5, arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(2, 6),
            IntDomain(0, 1), IntDomain(0, 1)), arrayOf<Factor>(
            Linear(intArrayOf(1, -1), intArrayOf(2, 0), LinearOp.GE, 2),
            Linear(intArrayOf(1, -1), intArrayOf(2, 1), LinearOp.GE, 2),
            Cumulative(intArrayOf(0, 1), longArrayOf(2, 2), longArrayOf(1, 1), 1),
            Circuit(intArrayOf(3, 4)),
        ))
        val selected = FactorProjectionProviders(relaxation = RelaxationProjectionProvider { null }).prepare(source)

        val default = CpToLpRelaxation(source, null, cumulative = true, cumulativeTimeIndexed = true,
            circuitArcs = true).build(RootDomains(source))
        val omitted = CpToLpRelaxation(source, null, cumulative = true, cumulativeTimeIndexed = true,
            circuitArcs = true, projections = selected).build(RootDomains(source))

        assertTrue(default.model.m > 0)
        assertEquals(0, omitted.model.m)
    }

    @Test
    fun `omitted binary rows do not reinstall default Boolean RLT rows`() {
        val source = Problem(0, 2, Array(2) { IntDomain(0, 1) },
            arrayOf<Factor>(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 1)))
        val selected = FactorProjectionProviders(relaxation = RelaxationProjectionProvider { null }).prepare(source)

        val default = CpToLpRelaxation(source, null, booleanRlt = true).build(RootDomains(source))
        val omitted = CpToLpRelaxation(source, null, booleanRlt = true, projections = selected)
            .build(RootDomains(source))

        assertTrue(default.model.m > 0)
        assertEquals(0, omitted.model.m)
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
    @Test
    fun `invocation providers retain source solutions with weaker root filtering`() {
        val source = Problem(0, 1, arrayOf(IntDomain(0, 2)),
            arrayOf<Factor>(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 1)))
        val providers = FactorProjectionProviders(propagation = PropagationProjectionProvider {
            PropagationProjection(PropagationCapability.SOUND_FILTERING, AssignmentCheckCapability.EXACT) {
                object : Propagator {
                    override fun propagate(state: PropagationState, factorId: Int): Boolean {
                        val domain = state.intDomains[0]
                        return domain.min != domain.max || domain.min <= 1
                    }
                }
            }
        })

        val baked = source.bake(providers)
        val witnesses = BacktrackSolver(baked).enumerate(BacktrackParams()).map { it.intValue(0) }.toSet()

        assertEquals(2L, baked.rootIntDomain(0).max)
        assertEquals(setOf(0L, 1L), witnesses)
    }

    @Test
    fun `inert scoring candidates are checked against source propagation`() {
        val source = Problem(2, 0, emptyArray(), arrayOf<Factor>(Clause(intArrayOf(0, 2))))
        val providers = FactorProjectionProviders(scoring = ScoringProjectionProvider {
            ScoringProjection(ScoringCapability.INERT, AssignmentCheckCapability.UNSUPPORTED,
                "The original clause is checked at the pinned candidate.") { _, _ -> NoInvariant }
        })
        val baked = source.bake(providers)
        val checker = UnscoredCandidateCheck(baked)

        val rejected = checker.check(Sample(booleanArrayOf(false, false), longArrayOf()),
            Assumptions.None, Cancellation.Never)
        val accepted = checker.check(Sample(booleanArrayOf(true, false), longArrayOf()),
            Assumptions.None, Cancellation.Never)

        assertTrue(rejected is Completion.Refuted)
        assertTrue(accepted is Completion.Witness)
    }

    @Test
    fun `selected defaults retain exact scoring beyond machine integer sums`() {
        val domains = Array(2) { IntDomain(Long.MAX_VALUE, Long.MAX_VALUE) }
        val source = Problem(0, 2, domains,
            arrayOf<Factor>(Linear(longArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, Long.MAX_VALUE)))
        val selected = FactorProjectionProviders.Default.prepare(source)
        val state = LocalSearchState(LocalSearchModel.open(source, domains), Random(1),
            projection = LocalSearchProblem(source, selected, domains))
        state.assignment.setInt(0, Long.MAX_VALUE)
        state.assignment.setInt(1, Long.MAX_VALUE)

        state.recompute()

        assertTrue(state.cost > 0L)
    }

    @Test
    fun `unsupported deduction refuses finite baking before source execution`() {
        val source = Problem(1, 0, emptyArray(), arrayOf<Factor>(Clause(intArrayOf(0))))
        val providers = FactorProjectionProviders(propagation = PropagationProjectionProvider { null })

        assertFailsWith<IllegalStateException> { source.bake(providers) }
        assertTrue(PropagationSession(source).boolValue(0) == true)
    }

    @Test
    fun `inert deduction cannot declare an exact assignment check`() {
        assertFailsWith<IllegalArgumentException> {
            PropagationProjection(PropagationCapability.INERT, AssignmentCheckCapability.EXACT,
                "No deduction mechanism.") { NoPropagator }
        }
    }

    @Test
    fun `inert scoring cannot declare an exact assignment check`() {
        assertFailsWith<IllegalArgumentException> {
            ScoringProjection(ScoringCapability.INERT, AssignmentCheckCapability.EXACT,
                "No score mechanism.") { _, _ -> NoInvariant }
        }
    }

    @Test
    fun `inert finite deduction retains the original source solution set`() {
        val source = Problem(0, 1, arrayOf(IntDomain(0, 2)),
            arrayOf<Factor>(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 1)))
        val providers = FactorProjectionProviders(propagation = PropagationProjectionProvider {
            PropagationProjection(PropagationCapability.INERT, AssignmentCheckCapability.SOURCE_CERTIFICATION,
                "The original source relation is checked at a complete point.") { NoPropagator }
        })

        val witnesses = BacktrackSolver(source.bake(providers)).enumerate(BacktrackParams())
            .map { it.intValue(0) }.toSet()

        assertEquals(setOf(0L, 1L), witnesses)
    }

    @Test
    fun `an inert factor without an original source checker stays unknown`() {
        val factor = object : Factor by Clause(intArrayOf(0)) {}
        val source = Problem(1, 0, emptyArray(), arrayOf<Factor>(factor))
        val providers = FactorProjectionProviders(propagation = PropagationProjectionProvider {
            PropagationProjection(PropagationCapability.INERT, AssignmentCheckCapability.SOURCE_CERTIFICATION,
                "No complete original-source checker is available.") { NoPropagator }
        })

        val result = BacktrackSolver(source.bake(providers)).solve(BacktrackParams())

        assertEquals(TerminationReason.Unsupported, assertIs<SolveResult.Unknown>(result).reason)
    }

    @Test
    fun `omitted real relaxation rows do not weaken original source completion`() {
        val row = Linear(longArrayOf(), intArrayOf(), doubleArrayOf(1.0), intArrayOf(0), LinearOp.GE, 1L)
        val source = Problem(0, 0, emptyArray(), arrayOf<Factor>(row), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(2.0))
        val providers = FactorProjectionProviders(relaxation = RelaxationProjectionProvider { null })

        val result = BacktrackSolver(source.bake(providers)).solve(BacktrackParams())
        val point = assertIs<SolveResult.Sat>(result).assignment

        assertTrue(point.exactReals!!.single() >= BigFraction.ONE)
    }

    @Test
    fun `delegated built in clauses retain the native fast path`() {
        val source = Problem(1, 0, emptyArray(), arrayOf<Factor>(Clause(intArrayOf(0))))
        val providers = FactorProjectionProviders(propagation = PropagationProjectionProvider {
            BuiltInPropagationProjectionProvider.select(it)
        })

        val projection = PropagationProblem(source, providers.prepare(source))

        assertTrue(projection.isNativeSatEligible)
    }

    @Test
    fun `inert scoring and deduction cannot publish a rejected source point`() {
        val source = Problem(2, 0, emptyArray(), arrayOf<Factor>(Clause(intArrayOf(0, 2))))
        val providers = FactorProjectionProviders(
            propagation = PropagationProjectionProvider {
                PropagationProjection(PropagationCapability.INERT, AssignmentCheckCapability.SOURCE_CERTIFICATION,
                    "Original source certification enforces the clause.") { NoPropagator }
            },
            scoring = ScoringProjectionProvider {
                ScoringProjection(ScoringCapability.INERT, AssignmentCheckCapability.UNSUPPORTED,
                    "A pinned source check validates the candidate.") { _, _ -> NoInvariant }
            },
        )
        val checker = UnscoredCandidateCheck(source.bake(providers))

        val result = checker.check(Sample(booleanArrayOf(false, false), longArrayOf()),
            Assumptions.None, Cancellation.Never)

        assertTrue(result is Completion.Refuted)
    }

    @Test
    fun `projected deduction retains its selected factory and uses mapped coordinates`() {
        val factor = Linear(intArrayOf(1), intArrayOf(2), LinearOp.LE, 1)
        val source = Problem(0, 3, Array(3) { IntDomain(0, 2) }, arrayOf<Factor>(factor))
        val selected = FactorProjectionProviders(propagation = PropagationProjectionProvider { original ->
            if (original !== factor) null else
                PropagationProjection(PropagationCapability.SOUND_FILTERING, AssignmentCheckCapability.EXACT) { view ->
                    val row = view as Linear
                    val column = row.vars.single()
                    val bound = row.integerConstants!!.bound
                    object : Propagator {
                        override fun propagate(state: PropagationState, factorId: Int): Boolean {
                            val domain = state.intDomains[column]
                            return domain.min != domain.max || domain.min <= bound
                        }
                    }
                }
        }).prepare(source)
        val view = Problem(0, 1, arrayOf(IntDomain(0, 2)), arrayOf<Factor>(
            factor.remap(VarRemap(intArrayOf(), intArrayOf(-1, -1, 0)))))

        val baked = view.bake(selected.projected(view, intArrayOf(0)))
        val witnesses = BacktrackSolver(baked).enumerate(BacktrackParams()).map { it.intValue(0) }.toSet()

        assertEquals(2L, baked.rootIntDomain(0).max)
        assertEquals(setOf(0L, 1L), witnesses)
    }

    @Test
    fun `projected scoring retains its selected factory and uses mapped coordinates`() {
        val factor = Linear(intArrayOf(1), intArrayOf(2), LinearOp.LE, 1)
        val source = Problem(0, 3, Array(3) { IntDomain(0, 2) }, arrayOf<Factor>(factor))
        val selected = FactorProjectionProviders(scoring = ScoringProjectionProvider { original ->
            if (original !== factor) null else
                ScoringProjection(ScoringCapability.GRADED, AssignmentCheckCapability.EXACT) { view, _ ->
                    val row = view as Linear
                    val column = row.vars.single()
                    val bound = row.integerConstants!!.bound
                    object : Invariant {
                        override fun isViolated(state: LocalSearchState, factorId: Int): Boolean =
                            state.assignment.intValue(column) > bound
                        override fun violationDegree(state: LocalSearchState, factorId: Int): Int =
                            if (isViolated(state, factorId)) 9 else 0
                        override fun deltaIfIntSet(
                            state: LocalSearchState, factorId: Int, intVar: Int, newValue: Long,
                        ): Int =
                            if (intVar == column) (if (newValue > bound) 9 else 0) - violationDegree(state, factorId)
                            else 0
                    }
                }
        }).prepare(source)
        val view = Problem(0, 1, arrayOf(IntDomain(0, 2)), arrayOf<Factor>(
            factor.remap(VarRemap(intArrayOf(), intArrayOf(-1, -1, 0)))))
        val state = LocalSearchState(LocalSearchModel.open(view), Random(1),
            projection = LocalSearchProblem(view, selected.projected(view, intArrayOf(0))))
        state.assignment.setInt(0, 2)

        state.recompute()

        assertEquals(9L, state.cost)
    }

    @Test
    fun `projected relaxation retains its selected emitter and uses mapped coordinates`() {
        val factor = Linear(intArrayOf(1), intArrayOf(2), LinearOp.LE, 1)
        val source = Problem(0, 3, Array(3) { IntDomain(0, 2) }, arrayOf<Factor>(factor))
        val selected = FactorProjectionProviders(relaxation = RelaxationProjectionProvider { original ->
            if (original !== factor) null else RelaxationProjection(RelaxationCapability.SOUND) { view, builder ->
                val row = view as Linear
                builder.linearRow(row.op, row.vars, longArrayOf(3), row.integerConstants!!.bound * 3)
            }
        }).prepare(source)
        val view = Problem(0, 1, arrayOf(IntDomain(0, 2)), arrayOf<Factor>(
            factor.remap(VarRemap(intArrayOf(), intArrayOf(-1, -1, 0)))))

        val relaxation = CpToLpRelaxation(view, null, projections = selected.projected(view, intArrayOf(0)))
            .build(RootDomains(view))

        assertEquals(3L, relaxation.model.rhs[0])
    }

}
