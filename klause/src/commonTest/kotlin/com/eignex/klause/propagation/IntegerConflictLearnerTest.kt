package com.eignex.klause.propagation

import com.eignex.klause.backtrack.toBacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.selector.VariableSelector
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearPropagator
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.ClausePropagator
import com.eignex.klause.factor.table.Table
import com.eignex.klause.formats.flatzinc.linearObjective
import com.eignex.klause.formats.flatzinc.parseFlatZinc
import com.eignex.klause.formats.flatzinc.toSearchHints
import com.eignex.klause.formats.json.ProblemJson
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.FinitePipeline
import com.eignex.klause.solver.pipeline.FinitePipelineRequest
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchAtomRegistry
import com.eignex.klause.solver.search.SearchComponent
import com.eignex.klause.solver.search.SearchConflictResolution
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchLearnedConflictResult
import com.eignex.klause.solver.search.SearchResult
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.solver.search.VarRef
import com.eignex.klause.util.Cancellation
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class IntegerConflictLearnerTest {
    @Test
    fun `a composed temporary child should propagate in another branch after its parents retire`() {
        val padding = IntArray(13) { it + 7 }
        val problem = Problem(0, 21, Array(21) {
            if (it < 7 || it == 20) IntDomain(0, 1) else IntDomain(0, 0)
        }, arrayOf(
            Linear(intArrayOf(1, 1, -1), intArrayOf(0, 3, 2), LinearOp.LE, 0),
            Linear(intArrayOf(1, -1), intArrayOf(1, 3), LinearOp.LE, 0),
            Linear(intArrayOf(1, -1), intArrayOf(5, 0), LinearOp.LE, 0),
            Linear(intArrayOf(1, -1), intArrayOf(5, 4), LinearOp.LE, 0),
            Linear(intArrayOf(1, -1, -1) + IntArray(13) { 1 },
                intArrayOf(4, 1, 6) + padding, LinearOp.LE, 0),
        ))
        val native = PropagationSession(PropagationProblem(problem), pbLearning = false, attribution = true)
            .apply { enableIntegerRowLearning() }
        native.addLearnedIntegerRow(requireNotNull(IntegerConflictRow.normalized(
            mapOf(0 to 1L, 1 to 1L, 2 to -1L), 0L, Lit.NONE)), 1)
        val cp = CpSearchComponent(native, IntArray(21) { it + 5 })
        val shared = SearchSession(listOf(cp), atoms = SearchAtomRegistry(0))
        assertIs<ComponentResult.Consistent>(shared.initialize())
        assertIs<ComponentResult.Consistent>(shared.push(SearchDecision.IntAtMost(25, 0)))
        assertIs<ComponentResult.Consistent>(shared.push(SearchDecision.IntAtMost(11, 0)))
        assertIs<ComponentResult.Conflict>(shared.push(SearchDecision.IntAtLeast(10, 1)))
        val learned = assertIs<ConflictAnalyzer.AnalysisResult.LearnedIntegerRow>(cp.lastConflict?.learnedClause)
        val backjump = assertIs<SearchConflictResolution.Backjump>(cp.resolveConflict(shared)).conflict
        shared.popTo(backjump.decisionLevel)
        assertIs<SearchLearnedConflictResult.Resume>(backjump.apply(shared))
        val state = native.explanationState
        val child = requireNotNull(state.learned.integerRows).single { it.row.retainedSources > 0 }
        assertEquals(2, child.row.transientSteps)
        shared.popTo(0)
        assertEquals(1L, shared.intUpperBound(10))
        assertIs<ComponentResult.Consistent>(shared.push(SearchDecision.IntAtLeast(25, 1)))
        assertTrue(native.compactLearnedClauses(1_000_000L, 1_048_576L, { false }) { index, _ ->
            state.learned.store[index] === child
        })
        assertSame(child, requireNotNull(state.learned.integerRows).single())
        assertTrue(state.learned.store.none {
            it is ClausePropagator && it.literals.contentEquals(learned.fallbackClause.literals)
        })

        assertIs<ComponentResult.Consistent>(shared.push(SearchDecision.IntAtMost(11, 0)))

        assertEquals(0L, shared.intUpperBound(10))
        assertSame(child, state.factorAt(state.intMaxReason[5]))
        assertEquals(1L, native.learnedLifecycle()["learningIntegerRetired"])
        assertEquals(1L, native.learnedLifecycle()["learningIntegerChainedTransientConsumed"])
    }

    @Test
    fun `a compact owned derivation should cross temporary width before asserting in mapped coordinates`() {
        val padding = IntArray(14) { it + 6 }
        val problem = Problem(0, 20, Array(20) { if (it < 6) IntDomain(0, 1) else IntDomain(0, 0) }, arrayOf(
            Linear(intArrayOf(1, 1, -1), intArrayOf(0, 3, 2), LinearOp.LE, 0),
            Linear(intArrayOf(1, -1), intArrayOf(1, 3), LinearOp.LE, 0),
            Linear(intArrayOf(1, -1), intArrayOf(5, 0), LinearOp.LE, 0),
            Linear(intArrayOf(1, -1), intArrayOf(5, 4), LinearOp.LE, 0),
            Linear(intArrayOf(1, -1) + IntArray(14) { 1 }, intArrayOf(4, 1) + padding, LinearOp.LE, 0),
        ))
        val native = PropagationSession(PropagationProblem(problem), pbLearning = false, attribution = true)
            .apply { enableIntegerRowLearning() }
        native.addLearnedIntegerRow(requireNotNull(IntegerConflictRow.normalized(
            mapOf(0 to 1L, 1 to 1L, 2 to -1L), 0L, Lit.NONE)), 1)
        val cp = CpSearchComponent(native, IntArray(20) { it + 5 })
        val shared = SearchSession(listOf(cp), atoms = SearchAtomRegistry(0))
        assertIs<ComponentResult.Consistent>(shared.initialize())

        assertIs<ComponentResult.Conflict>(shared.push(SearchDecision.IntAtLeast(10, 1)))
        val learned = assertIs<ConflictAnalyzer.AnalysisResult.LearnedIntegerRow>(cp.lastConflict?.learnedClause)
        val backjump = assertIs<SearchConflictResolution.Backjump>(cp.resolveConflict(shared)).conflict
        shared.popTo(backjump.decisionLevel)
        assertIs<SearchLearnedConflictResult.Resume>(backjump.apply(shared))

        assertContentEquals(intArrayOf(2, 5) + padding, learned.row.variables)
        assertContentEquals(longArrayOf(-1, 2) + LongArray(14) { 1 }, learned.row.coefficients)
        assertEquals(2, learned.row.transientSteps)
        assertEquals(1, learned.row.retainedSources)
        assertEquals(0L, shared.intUpperBound(10))
        assertEquals(17L, native.learnedLifecycle()["learningIntegerTransientMaxTerms"])
        assertEquals(1L, native.learnedLifecycle()["learningIntegerChainedAdmitted"])
        assertEquals(1L, native.learnedLifecycle()["learningIntegerChainedConsumed"])
        assertEquals(1L, native.learnedLifecycle()["learningIntegerChainedTransientProduced"])
        assertEquals(1L, native.learnedLifecycle()["learningIntegerChainedTransientAdmitted"])
        assertEquals(1L, native.learnedLifecycle()["learningIntegerChainedTransientConsumed"])
        val before = native.learnedLifecycle()
        native.releaseRetainedState()
        assertEquals(before, native.learnedLifecycle())
    }

    @Test
    fun `a guarded compact seed should decline an oversized owned intermediate beside clause fallback`() {
        val padding = IntArray(14) { it + 6 }
        val native = PropagationSession(Problem(1, 20,
            Array(20) { if (it < 6) IntDomain(0, 1) else IntDomain(0, 0) }, arrayOf(
                Linear(intArrayOf(1, 1, -1), intArrayOf(0, 3, 2), LinearOp.LE, 0),
                Linear(intArrayOf(1, -1), intArrayOf(1, 3), LinearOp.LE, 0),
                Linear(intArrayOf(1, -1), intArrayOf(5, 0), LinearOp.LE, 0),
                Linear(intArrayOf(1, -1), intArrayOf(5, 4), LinearOp.LE, 0),
                ReifiedLinear(0, intArrayOf(1, -1) + IntArray(14) { 1 },
                    intArrayOf(4, 1) + padding, LinearOp.LE, 0),
            )), pbLearning = false).apply {
            enableIntegerRowLearning()
            configureAttribution(true, false)
        }
        native.addLearnedIntegerRow(requireNotNull(IntegerConflictRow.normalized(
            mapOf(0 to 1L, 1 to 1L, 2 to -1L), 0L, Lit.NONE)), 1)
        assertIs<PropagationResult.Implied>(native.pinBool(0, true))

        val conflict = assertIs<PropagationResult.Unsat>(native.pinIntAtLeast(5, 1))

        val fallback = assertIs<ConflictAnalyzer.AnalysisResult.Learned>(conflict.learnedClause)
        assertTrue(fallback.literals.contains(Lit.make(0, false)))
        assertEquals(1L, native.learnedLifecycle()["learningIntegerChainedSources"])
        assertEquals(0L, native.learnedLifecycle()["learningIntegerChainedProduced"])
        assertEquals(0L, native.learnedLifecycle()["learningIntegerChainedAdmitted"])
        assertEquals(1L, requireNotNull(native.explanationState.arithmeticLearningTrace).declineCensus[
            "integer.retained.decline.combination-shape-or-guard"])
        assertEquals(1, native.explanationState.learned.integerRows?.size)
    }

    @Test
    fun `an owned at most one conflict should produce and consume a composed row beside its fallback`() {
        val native = PropagationSession(Problem(0, 5, Array(5) { IntDomain(0, 1) }, arrayOf(
            Linear(intArrayOf(1, 1, -1), intArrayOf(0, 3, 2), LinearOp.LE, 0),
            Linear(intArrayOf(1, -1), intArrayOf(1, 3), LinearOp.LE, 0),
            Linear(intArrayOf(1, -1), intArrayOf(4, 0), LinearOp.LE, 0),
            Linear(intArrayOf(1, -1), intArrayOf(4, 1), LinearOp.LE, 0),
        )), pbLearning = false).apply { enableIntegerRowLearning() }
        val parent = requireNotNull(IntegerConflictRow.normalized(
            mapOf(0 to 1L, 1 to 1L, 2 to -1L), 0L, Lit.NONE))
        native.addLearnedIntegerRow(parent, 1)

        val conflict = assertIs<PropagationResult.Unsat>(native.pinIntAtLeast(4, 1))
        val learned = assertIs<ConflictAnalyzer.AnalysisResult.LearnedIntegerRow>(conflict.learnedClause)
        native.popToLevel(learned.backjumpLevel)
        assertIs<PropagationResult.Implied>(native.addLearnedIntegerRow(learned))

        assertContentEquals(intArrayOf(2, 4), learned.row.variables)
        assertContentEquals(longArrayOf(-1, 2), learned.row.coefficients)
        assertEquals(0L, learned.row.bound)
        assertEquals(1, learned.row.retainedSources)
        assertEquals(learned.fallbackClause.backjumpLevel, learned.backjumpLevel)
        assertEquals(0L, native.intDomain(4).max)
        val snapshot = native.learnedLifecycle()
        assertEquals(1L, snapshot["learningIntegerChainedSources"])
        assertEquals(1L, snapshot["learningIntegerChainedProduced"])
        assertEquals(1L, snapshot["learningIntegerChainedAdmitted"])
        assertEquals(1L, snapshot["learningIntegerChainedConsumed"])
        assertTrue(native.explanationState.learned.store.any {
            it is ClausePropagator && it.literals.contentEquals(learned.fallbackClause.literals)
        })
        native.releaseRetainedState()
        assertEquals(snapshot, native.learnedLifecycle())
    }

    @Test
    fun `an owned arithmetic parent should precede a qualified original endpoint`() {
        val native = PropagationSession(Problem(0, 6, Array(6) { IntDomain(0, 1) }, arrayOf(
            Linear(intArrayOf(1, 1, -1), intArrayOf(0, 3, 2), LinearOp.LE, 0),
            Linear(intArrayOf(1, -1), intArrayOf(1, 3), LinearOp.LE, 0),
            Linear(intArrayOf(1, -1), intArrayOf(5, 0), LinearOp.LE, 0),
            Linear(intArrayOf(1, -1), intArrayOf(5, 4), LinearOp.LE, 0),
            Linear(intArrayOf(1, -1), intArrayOf(4, 1), LinearOp.LE, 0),
        )), pbLearning = false).apply {
            enableIntegerRowLearning()
            configureAttribution(true, false)
        }
        native.addLearnedIntegerRow(requireNotNull(IntegerConflictRow.normalized(
            mapOf(0 to 1L, 1 to 1L, 2 to -1L), 0L, Lit.NONE)), 1)

        val trace = ArithmeticLearningTrace(1024, 65536).apply { collectDeclines = true }
        native.explanationState.arithmeticLearningTrace = trace

        val conflict = assertIs<PropagationResult.Unsat>(native.pinIntAtLeast(5, 1))
        val learned = assertIs<ConflictAnalyzer.AnalysisResult.LearnedIntegerRow>(conflict.learnedClause)

        assertContentEquals(intArrayOf(2, 5), learned.row.variables)
        assertContentEquals(longArrayOf(-1, 2), learned.row.coefficients)
        assertEquals(0L, learned.row.bound)
        assertEquals(1, learned.row.retainedSources)
        assertEquals(1L, native.learnedLifecycle()["learningIntegerChainedProduced"])
        assertEquals(0L, native.learnedLifecycle()["learningIntegerChainedAdmitted"])
        val census = requireNotNull(native.explanationState.arithmeticLearningTrace).declineCensus
        assertEquals("integer.retained-parent-row", trace.events.first {
            it.kind == "integer.retained-parent-row" || it.kind == "integer.pivot-row"
        }.kind)
        assertTrue(requireNotNull(census["integer.retained.preferred"]) > 0L)
        assertTrue(requireNotNull(census["integer.retained.qualified"]) > 0L)
    }

    @Test
    fun `either guarded retained source should preserve ordinary clause fallback`() {
        for (positive in listOf(false, true)) {
            val native = PropagationSession(Problem(1, 5, Array(5) { IntDomain(0, 1) }, arrayOf(
                Linear(intArrayOf(1, 1, -1), intArrayOf(0, 3, 2), LinearOp.LE, 0),
                Linear(intArrayOf(1, -1), intArrayOf(1, 3), LinearOp.LE, 0),
                Linear(intArrayOf(1, -1), intArrayOf(4, 0), LinearOp.LE, 0),
                Linear(intArrayOf(1, -1), intArrayOf(4, 1), LinearOp.LE, 0),
            )), pbLearning = false).apply {
                enableIntegerRowLearning()
                configureAttribution(true, false)
            }
            val guard = Lit.make(0, positive)
            native.addLearnedIntegerRow(requireNotNull(IntegerConflictRow.normalized(
                mapOf(0 to 1L, 1 to 1L, 2 to -1L), 0L, guard)), 1)
            assertIs<PropagationResult.Implied>(native.pinBool(0, positive))

            val conflict = assertIs<PropagationResult.Unsat>(native.pinIntAtLeast(4, 1))

            val fallback = assertIs<ConflictAnalyzer.AnalysisResult.Learned>(conflict.learnedClause)
            assertTrue(fallback.literals.contains(guard xor 1))
            assertEquals(null, native.learnedLifecycle()["learningIntegerChainedSources"])
            val census = requireNotNull(native.explanationState.arithmeticLearningTrace).declineCensus
            assertTrue(requireNotNull(census["integer.retained.reject.guard"]) > 0L)
        }
    }

    @Test
    fun `a live binary box should not qualify a wider declared retained source`() {
        val native = PropagationSession(Problem(0, 5, Array(5) { IntDomain(0, 2) }, arrayOf(
            Linear(intArrayOf(1, 1, -1), intArrayOf(0, 3, 2), LinearOp.LE, 0),
            Linear(intArrayOf(1, -1), intArrayOf(1, 3), LinearOp.LE, 0),
            Linear(intArrayOf(1, -1), intArrayOf(4, 0), LinearOp.LE, 0),
            Linear(intArrayOf(1, -1), intArrayOf(4, 1), LinearOp.LE, 0),
            Linear(intArrayOf(1), intArrayOf(2), LinearOp.LE, 1),
        )), pbLearning = false).apply {
            enableIntegerRowLearning()
            configureAttribution(true, false)
        }
        native.addLearnedIntegerRow(requireNotNull(IntegerConflictRow.normalized(
            mapOf(0 to 1L, 1 to 1L, 2 to -1L), 0L, Lit.NONE)), 1)
        assertEquals(1L, native.intDomain(0).max)
        assertEquals(1L, native.intDomain(1).max)

        val conflict = assertIs<PropagationResult.Unsat>(native.pinIntAtLeast(4, 1))

        assertIs<ConflictAnalyzer.AnalysisResult.Learned>(conflict.learnedClause)
        assertEquals(null, native.learnedLifecycle()["learningIntegerChainedSources"])
        val census = requireNotNull(native.explanationState.arithmeticLearningTrace).declineCensus
        assertTrue(requireNotNull(census["integer.retained.reject.declared-unit-binary"]) > 0L)
    }

    @Test
    fun `a retained higher cardinality source should preserve clause fallback`() {
        val native = PropagationSession(Problem(0, 5, Array(5) { IntDomain(0, 1) }, arrayOf(
            Linear(intArrayOf(1, 1, 1), intArrayOf(0, 3, 2), LinearOp.LE, 2),
            Linear(intArrayOf(1, -1), intArrayOf(1, 3), LinearOp.LE, 0),
            Linear(intArrayOf(1, -1), intArrayOf(4, 0), LinearOp.LE, 0),
            Linear(intArrayOf(1, -1), intArrayOf(4, 1), LinearOp.LE, 0),
            Linear(intArrayOf(1, -1), intArrayOf(4, 2), LinearOp.LE, 0),
        )), pbLearning = false).apply {
            enableIntegerRowLearning()
            configureAttribution(true, false)
        }
        native.addLearnedIntegerRow(requireNotNull(IntegerConflictRow.normalized(
            mapOf(0 to 1L, 1 to 1L, 2 to 1L), 2L, Lit.NONE)), 1)

        val conflict = assertIs<PropagationResult.Unsat>(native.pinIntAtLeast(4, 1))

        assertIs<ConflictAnalyzer.AnalysisResult.Learned>(conflict.learnedClause)
        assertEquals(null, native.learnedLifecycle()["learningIntegerChainedSources"])
        val census = requireNotNull(native.explanationState.arithmeticLearningTrace).declineCensus
        assertTrue(requireNotNull(census["integer.retained.reject.cardinality"]) > 0L)
    }

    @Test
    fun `an owned at most one source should compose after its factor id moves`() {
        val native = PropagationSession(Problem(1, 5, Array(5) { IntDomain(0, 1) }, arrayOf(
            Linear(intArrayOf(1, 1, -1), intArrayOf(0, 3, 2), LinearOp.LE, 0),
            Linear(intArrayOf(1, -1), intArrayOf(1, 3), LinearOp.LE, 0),
            Linear(intArrayOf(1, -1), intArrayOf(4, 0), LinearOp.LE, 0),
            Linear(intArrayOf(1, -1), intArrayOf(4, 1), LinearOp.LE, 0),
        )), pbLearning = false).apply { enableIntegerRowLearning() }
        native.addLearnedClause(Clause(intArrayOf(Lit.make(0, true), Lit.make(0, false))), 3)
        native.addLearnedIntegerRow(requireNotNull(IntegerConflictRow.normalized(
            mapOf(0 to 1L, 1 to 1L, 2 to -1L), 0L, Lit.NONE)), 1)
        native.forgetLearnedClauses { index, _ -> index != 0 }

        val conflict = assertIs<PropagationResult.Unsat>(native.pinIntAtLeast(4, 1))
        val learned = assertIs<ConflictAnalyzer.AnalysisResult.LearnedIntegerRow>(conflict.learnedClause)

        assertEquals(1, learned.row.retainedSources)
        assertContentEquals(intArrayOf(2, 4), learned.row.variables)
        assertContentEquals(longArrayOf(-1, 2), learned.row.coefficients)
        assertEquals(1L, native.learnedLifecycle()["learningIntegerChainedProduced"])
    }

    @Test
    fun `a composed child should retain its bound after its parent retires`() {
        val native = PropagationSession(Problem(0, 5, Array(5) { IntDomain(0, 1) }, arrayOf(
            Linear(intArrayOf(1, 1, -1), intArrayOf(0, 3, 2), LinearOp.LE, 0),
            Linear(intArrayOf(1, -1), intArrayOf(1, 3), LinearOp.LE, 0),
            Linear(intArrayOf(1, -1), intArrayOf(4, 0), LinearOp.LE, 0),
            Linear(intArrayOf(1, -1), intArrayOf(4, 1), LinearOp.LE, 0),
        )), pbLearning = false).apply { enableIntegerRowLearning() }
        native.addLearnedIntegerRow(requireNotNull(IntegerConflictRow.normalized(
            mapOf(0 to 1L, 1 to 1L, 2 to -1L), 0L, Lit.NONE)), 1)
        val conflict = assertIs<PropagationResult.Unsat>(native.pinIntAtLeast(4, 1))
        val learned = assertIs<ConflictAnalyzer.AnalysisResult.LearnedIntegerRow>(conflict.learnedClause)
        native.popToLevel(learned.backjumpLevel)
        native.addLearnedIntegerRow(learned)

        native.forgetLearnedClauses { index, _ -> index != 0 }

        val child = requireNotNull(native.explanationState.learned.integerRows).single()
        assertEquals(1, child.row.retainedSources)
        assertContentEquals(intArrayOf(2, 4), child.row.variables)
        assertContentEquals(longArrayOf(-1, 2), child.row.coefficients)
        assertEquals(0L, native.intDomain(4).max)
        assertEquals(1L, native.learnedLifecycle()["learningIntegerRetired"])
        assertEquals(1L, native.learnedLifecycle()["learningIntegerChainedConsumed"])
    }

    @Test
    fun `a retired at most one row should supply no composition source`() {
        val native = PropagationSession(Problem(0, 5, Array(5) { IntDomain(0, 1) }, arrayOf(
            Linear(intArrayOf(1, 1, -1), intArrayOf(0, 3, 2), LinearOp.LE, 0),
            Linear(intArrayOf(1, -1), intArrayOf(1, 3), LinearOp.LE, 0),
            Linear(intArrayOf(1, -1), intArrayOf(4, 0), LinearOp.LE, 0),
            Linear(intArrayOf(1, -1), intArrayOf(4, 1), LinearOp.LE, 0),
        )), pbLearning = false).apply { enableIntegerRowLearning() }
        native.addLearnedIntegerRow(requireNotNull(IntegerConflictRow.normalized(
            mapOf(0 to 1L, 1 to 1L, 2 to -1L), 0L, Lit.NONE)), 1)
        native.forgetLearnedClauses { _, _ -> false }

        val conflict = assertIs<PropagationResult.Unsat>(native.pinIntAtLeast(4, 1))
        val learned = assertIs<ConflictAnalyzer.AnalysisResult.LearnedIntegerRow>(conflict.learnedClause)

        assertEquals(0, learned.row.retainedSources)
        assertEquals(null, native.learnedLifecycle()["learningIntegerChainedSources"])
    }

    @Test
    fun `a composed row should assert through mapped shared coordinates`() {
        val native = PropagationSession(Problem(0, 6, Array(6) { IntDomain(0, 1) }, arrayOf(
            Linear(intArrayOf(1, 1, -1), intArrayOf(0, 3, 2), LinearOp.LE, 0),
            Linear(intArrayOf(1, -1), intArrayOf(1, 3), LinearOp.LE, 0),
            Linear(intArrayOf(1, -1), intArrayOf(5, 0), LinearOp.LE, 0),
            Linear(intArrayOf(1, -1), intArrayOf(5, 4), LinearOp.LE, 0),
            Linear(intArrayOf(1, -1), intArrayOf(4, 1), LinearOp.LE, 0),
        )), pbLearning = false).apply { enableIntegerRowLearning() }
        native.addLearnedIntegerRow(requireNotNull(IntegerConflictRow.normalized(
            mapOf(0 to 1L, 1 to 1L, 2 to -1L), 0L, Lit.NONE)), 1)
        val cp = CpSearchComponent(native, IntArray(6) { it + 7 })
        val shared = SearchSession(listOf(cp), atoms = SearchAtomRegistry(0))
        assertIs<ComponentResult.Consistent>(shared.initialize())
        assertIs<ComponentResult.Conflict>(shared.push(SearchDecision.IntAtLeast(12, 1)))
        val learned = assertIs<SearchConflictResolution.Backjump>(cp.resolveConflict(shared)).conflict
        shared.popTo(learned.decisionLevel)

        assertIs<SearchLearnedConflictResult.Resume>(learned.apply(shared))

        assertEquals(0L, shared.intUpperBound(12))
        assertEquals(1L, native.learnedLifecycle()["learningIntegerChainedAdmitted"])
        assertEquals(1L, native.learnedLifecycle()["learningIntegerChainedConsumed"])
    }

    @Test
    fun `a composed row with an unavailable shared bound should retain clause fallback`() {
        val native = PropagationSession(Problem(0, 7, Array(7) { IntDomain(0, 1) }, arrayOf(
            Linear(intArrayOf(1, 1, -1), intArrayOf(0, 3, 2), LinearOp.LE, 0),
            Linear(intArrayOf(1, -1), intArrayOf(1, 3), LinearOp.LE, 0),
            Linear(intArrayOf(1, -1), intArrayOf(5, 0), LinearOp.LE, 0),
            Linear(intArrayOf(1, 1, -1), intArrayOf(5, 6, 4), LinearOp.LE, 1),
            Linear(intArrayOf(1, -1), intArrayOf(4, 1), LinearOp.LE, 0),
        )), pbLearning = false).apply { enableIntegerRowLearning() }
        native.addLearnedIntegerRow(requireNotNull(IntegerConflictRow.normalized(
            mapOf(0 to 1L, 1 to 1L, 2 to -1L), 0L, Lit.NONE)), 1)
        val peer = object : SearchComponent {
            override fun initialize(context: SearchContext): ComponentResult =
                context.publish(SearchDecision.IntAtLeast(6, 1))
        }
        val cp = CpSearchComponent(native)
        val shared = SearchSession(listOf(peer, cp), atoms = SearchAtomRegistry(0))
        assertIs<ComponentResult.Consistent>(shared.initialize())

        assertIs<ComponentResult.Conflict>(shared.push(SearchDecision.IntAtLeast(5, 1)))

        val proposed = assertIs<ConflictAnalyzer.AnalysisResult.LearnedIntegerRow>(cp.lastConflict?.learnedClause)
        assertEquals(1, proposed.row.retainedSources)
        assertFalse(cp.prefersNativeConflictAnalysis)
        assertEquals(0L, native.learnedLifecycle()["learningIntegerChainedAdmitted"])
        assertEquals(1, native.explanationState.learned.integerRows?.size)
    }

    @Test
    fun `the latest historical endpoint should yield a reusable cut within the source allowance`() {
        val fixedVariables = IntArray(22) { it + 10 }
        val reasons = List(8) { index ->
            Linear(intArrayOf(2, -1) + IntArray(22) { -1 },
                intArrayOf(0, index + 2) + fixedVariables, LinearOp.LE, 0)
        }
        val seed = Linear(IntArray(32) { if (it == 0) 2 else if (it == 1) -1 else 1 },
            IntArray(32) { it }, LinearOp.LE, 0)
        val native = PropagationSession(Problem(0, 32, Array(32) {
            when (it) {
                0 -> IntDomain(0, 6)
                1 -> IntDomain(0, 40)
                in 2..9 -> IntDomain(0, 12)
                else -> IntDomain(0, 0)
            }
        }, reasons + seed), pbLearning = false).apply { enableIntegerRowLearning() }
        assertIs<PropagationResult.Implied>(native.pinIntAtMost(1, 7))
        val conflict = assertIs<PropagationResult.Unsat>(native.pinIntAtLeast(0, 2))

        val learned = assertIs<ConflictAnalyzer.AnalysisResult.LearnedIntegerRow>(conflict.learnedClause)
        native.popToLevel(learned.backjumpLevel)
        assertIs<PropagationResult.Implied>(native.addLearnedIntegerRow(learned.row, learned.lbd))

        assertContentEquals(intArrayOf(0, 1) + IntArray(7) { it + 2 }, learned.row.variables)
        assertContentEquals(longArrayOf(4, -1) + LongArray(7) { 1 }, learned.row.coefficients)
        assertEquals(1L, native.intDomain(0).max)
        native.popToLevel(0)
        assertIs<PropagationResult.Implied>(native.pinIntAtMost(1, 11))
        assertEquals(2L, native.intDomain(0).max)
    }

    @Test
    fun `successive wide reasons should preserve the historical fallback assertion`() {
        val variables = IntArray(18) { it }
        val state = PropagationState(Problem(0, 18, Array(18) {
            when (it) {
                1 -> IntDomain(0, 12)
                2 -> IntDomain(0, 40)
                else -> IntDomain(0, 6)
            }
        }, arrayOf(
            Linear(intArrayOf(1, -1), intArrayOf(0, 3), LinearOp.LE, 0),
            Linear(intArrayOf(2, -1), intArrayOf(0, 1), LinearOp.LE, 0),
            Linear(IntArray(18) { if (it == 0) 2 else if (it == 2) -1 else 1 }, variables, LinearOp.LE, 0),
        )), Assumptions.None)
        state.undoLogging = true
        state.integerRowLearning = true
        assertTrue(state.setIntMaxAsDecision(2, 7))
        val target = state.undo.size
        state.integerLearningTrailAtLevel = { level -> if (level == 1) target else null }
        assertTrue(state.setIntMinAsDecision(0, 2))
        state.currentFactor = 0
        assertTrue(state.factorAt(0).propagate(state, 0))
        state.currentFactor = 1
        assertTrue(state.factorAt(1).propagate(state, 1))
        state.currentFactor = 2
        assertFalse(state.factorAt(2).propagate(state, 2))

        val learned = assertIs<ConflictAnalyzer.AnalysisResult.LearnedIntegerRow>(state.conflictAnalyzer.analyze(2))

        assertContentEquals(intArrayOf(0, 2) + IntArray(14) { it + 4 }, learned.row.variables)
        assertContentEquals(longArrayOf(5, -1) + LongArray(14) { 1 }, learned.row.coefficients)
        assertEquals(0L, learned.row.bound)
        assertEquals(learned.fallbackClause.backjumpLevel, learned.backjumpLevel)
        assertTrue(learned.row.assertsAt(state, target))
    }

    @Test
    fun `a compact consequence after successive wide steps should assert in shared coordinates`() {
        val variables = IntArray(18) { it }
        val problem = Problem(0, 18, Array(18) {
            when (it) {
                1 -> IntDomain(0, 12)
                2 -> IntDomain(0, 40)
                else -> IntDomain(0, 6)
            }
        }, arrayOf(
            Linear(intArrayOf(1, -1), intArrayOf(0, 3), LinearOp.LE, 0),
            Linear(intArrayOf(2, -1), intArrayOf(0, 1), LinearOp.LE, 0),
            Linear(IntArray(18) { if (it == 0) 2 else if (it == 2) -1 else 1 }, variables, LinearOp.LE, 0),
        ))
        val native = PropagationSession(PropagationProblem(problem), pbLearning = false, attribution = true)
            .apply { enableIntegerRowLearning() }
        val cp = CpSearchComponent(native, IntArray(18) { it + 5 })
        val shared = SearchSession(listOf(cp), atoms = SearchAtomRegistry(0))
        assertIs<ComponentResult.Consistent>(shared.initialize())
        assertIs<ComponentResult.Consistent>(shared.push(SearchDecision.IntAtMost(7, 7)))
        assertIs<ComponentResult.Conflict>(shared.push(SearchDecision.IntAtLeast(5, 2)))
        val backjump = assertIs<SearchConflictResolution.Backjump>(cp.resolveConflict(shared)).conflict
        shared.popTo(backjump.decisionLevel)

        assertIs<SearchLearnedConflictResult.Resume>(backjump.apply(shared))

        assertEquals(1L, shared.intUpperBound(5))
        assertTrue((native.learnedLifecycle()["learningIntegerConsumed"] ?: 0L) > 0L)
        assertEquals(1L, native.learnedLifecycle()["learningIntegerTransientCombinations"])
        assertEquals(17L, native.learnedLifecycle()["learningIntegerTransientMaxTerms"])
        assertEquals(1L, native.learnedLifecycle()["learningIntegerTransientAdmitted"])
        assertEquals(1L, native.learnedLifecycle()["learningIntegerTransientConsumed"])
        val before = native.learnedLifecycle()
        native.releaseRetainedState()
        assertEquals(before, native.learnedLifecycle())
    }


    @Test
    fun `a compact conflict should preserve its compact pivot when a later wide reason is active`() {
        val latestVariables = intArrayOf(2, 3) + IntArray(15) { it + 4 }
        val state = PropagationState(Problem(0, 19, Array(19) {
            when (it) {
                0 -> IntDomain(0, 6)
                1 -> IntDomain(0, 12)
                2, 3 -> IntDomain(0, 24)
                else -> IntDomain(0, 0)
            }
        }, arrayOf(
            Linear(intArrayOf(2, 1, -1), intArrayOf(0, 1, 2), LinearOp.LE, 0),
            Linear(intArrayOf(2, -1), intArrayOf(0, 1), LinearOp.LE, 0),
            Linear(intArrayOf(1, -1) + IntArray(15) { 1 }, latestVariables, LinearOp.LE, 0),
        )), Assumptions.None)
        state.undoLogging = true
        state.integerRowLearning = true
        assertTrue(state.setIntMaxAsDecision(2, 7))
        val firstTarget = state.undo.size
        assertTrue(state.setIntMaxAsDecision(3, 6))
        val secondTarget = state.undo.size
        state.integerLearningTrailAtLevel = { level ->
            when (level) {
                1 -> firstTarget
                2 -> secondTarget
                else -> null
            }
        }
        assertTrue(state.setIntMinAsDecision(0, 2))
        state.currentFactor = 1
        assertTrue(state.factorAt(1).propagate(state, 1))
        state.currentFactor = 2
        assertTrue(state.factorAt(2).propagate(state, 2))
        state.currentFactor = 0
        assertFalse(state.factorAt(0).propagate(state, 0))

        val learned = assertIs<ConflictAnalyzer.AnalysisResult.LearnedIntegerRow>(state.conflictAnalyzer.analyze(0))

        assertContentEquals(intArrayOf(0, 2), learned.row.variables)
        assertContentEquals(longArrayOf(4, -1), learned.row.coefficients)
        assertEquals(0L, learned.row.bound)
        assertEquals(learned.fallbackClause.backjumpLevel, learned.backjumpLevel)
    }

    @Test
    fun `wide original sources should learn a compact asserting inequality`() {
        val leftVars = IntArray(20) { it }
        val rightVars = intArrayOf(0, 1) + IntArray(17) { it + 3 }
        val domains = arrayOf(IntDomain(0, 6), IntDomain(0, 12), IntDomain(0, 8)) + Array(17) { IntDomain(0, 1) }
        val native = PropagationSession(Problem(0, 20, domains, arrayOf(
            Linear(IntArray(20) { if (it == 0) 2 else if (it == 2) -1 else 1 }, leftVars, LinearOp.LE, 0),
            Linear(IntArray(19) { if (it == 0) 2 else -1 }, rightVars, LinearOp.LE, 0),
        )), pbLearning = false).apply { enableIntegerRowLearning() }
        for (variable in 3..19) assertIs<PropagationResult.Implied>(native.pinIntAtMost(variable, 0))
        assertIs<PropagationResult.Implied>(native.pinIntAtMost(2, 7))

        val conflict = assertIs<PropagationResult.Unsat>(native.pinIntAtLeast(0, 2))

        val learned = assertIs<ConflictAnalyzer.AnalysisResult.LearnedIntegerRow>(conflict.learnedClause)
        assertContentEquals(intArrayOf(0, 2), learned.row.variables)
        assertContentEquals(longArrayOf(4, -1), learned.row.coefficients)
        assertEquals(0L, learned.row.bound)
        assertEquals(learned.fallbackClause.backjumpLevel, learned.backjumpLevel)
    }

    @Test
    fun `compact rows from wide sources should assert through mapped shared coordinates`() {
        val leftVars = IntArray(20) { it }
        val rightVars = intArrayOf(0, 1) + IntArray(17) { it + 3 }
        val domains = arrayOf(IntDomain(0, 6), IntDomain(0, 12), IntDomain(0, 8)) + Array(17) { IntDomain(0, 1) }
        val native = PropagationSession(Problem(0, 20, domains, arrayOf(
            Linear(IntArray(20) { if (it == 0) 2 else if (it == 2) -1 else 1 }, leftVars, LinearOp.LE, 0),
            Linear(IntArray(19) { if (it == 0) 2 else -1 }, rightVars, LinearOp.LE, 0),
        )), pbLearning = false).apply { enableIntegerRowLearning() }
        val cp = CpSearchComponent(native, IntArray(20) { it + 5 })
        val shared = SearchSession(listOf(cp), atoms = SearchAtomRegistry(0))
        assertIs<ComponentResult.Consistent>(shared.initialize())
        for (variable in 3..19) {
            assertIs<ComponentResult.Consistent>(shared.push(SearchDecision.IntAtMost(variable + 5, 0)))
        }
        assertIs<ComponentResult.Consistent>(shared.push(SearchDecision.IntAtMost(7, 7)))
        assertIs<ComponentResult.Conflict>(shared.push(SearchDecision.IntAtLeast(5, 2)))
        val backjump = assertIs<SearchConflictResolution.Backjump>(cp.resolveConflict(shared)).conflict
        shared.popTo(backjump.decisionLevel)

        assertIs<SearchLearnedConflictResult.Resume>(backjump.apply(shared))

        assertEquals(1L, shared.intUpperBound(5))
        assertTrue((native.learnedLifecycle()["learningIntegerConsumed"] ?: 0L) > 0L)
    }

    @Test
    fun `either equality conflict direction should retain the same asserting row`() {
        for (sign in listOf(-1, 1)) {
            val native = PropagationSession(Problem(0, 3,
                arrayOf(IntDomain(0, 6), IntDomain(0, 12), IntDomain(0, 8)), arrayOf(
                    Linear(intArrayOf(2, -1), intArrayOf(0, 1), LinearOp.LE, 0),
                    Linear(intArrayOf(2 * sign, sign, -sign), intArrayOf(0, 1, 2), LinearOp.EQ, 0),
                )), pbLearning = false).apply { enableIntegerRowLearning() }
            assertIs<PropagationResult.Implied>(native.pinIntAtMost(2, 7))

            val conflict = assertIs<PropagationResult.Unsat>(native.pinIntAtLeast(0, 2))

            val learned = assertIs<ConflictAnalyzer.AnalysisResult.LearnedIntegerRow>(conflict.learnedClause)
            assertContentEquals(intArrayOf(0, 2), learned.row.variables)
            assertContentEquals(longArrayOf(4, -1), learned.row.coefficients)
            assertEquals(0L, learned.row.bound)
            assertEquals(1, learned.backjumpLevel)
        }
    }

    @Test
    fun `a learned row should preserve the fallback backjump target`() {
        val native = PropagationSession(Problem(0, 3,
            arrayOf(IntDomain(0, 6), IntDomain(0, 12), IntDomain(0, 8)), arrayOf(
                Linear(intArrayOf(2, 1, -1), intArrayOf(0, 1, 2), LinearOp.LE, 0),
                Linear(intArrayOf(2, -1), intArrayOf(0, 1), LinearOp.LE, 0),
            )), pbLearning = false).apply { enableIntegerRowLearning() }
        assertIs<PropagationResult.Implied>(native.pinIntAtMost(2, 7))

        val conflict = assertIs<PropagationResult.Unsat>(native.pinIntAtLeast(0, 2))

        val learned = assertIs<ConflictAnalyzer.AnalysisResult.LearnedIntegerRow>(conflict.learnedClause)
        assertEquals(1, learned.fallbackClause.backjumpLevel)
        assertEquals(learned.fallbackClause.backjumpLevel, learned.backjumpLevel)
    }

    @Test
    fun `a cascaded row with an unavailable shared premise should retain clause fallback`() {
        val native = PropagationSession(Problem(0, 6,
            arrayOf(IntDomain(0, 6), IntDomain(0, 12), IntDomain(0, 24),
                IntDomain(0, 6), IntDomain(0, 12), IntDomain(0, 24)), arrayOf(
                Linear(intArrayOf(2, 1, -1), intArrayOf(0, 1, 2), LinearOp.LE, 0),
                Linear(intArrayOf(2, -1), intArrayOf(0, 1), LinearOp.LE, 0),
                Linear(intArrayOf(2, 1, -1), intArrayOf(3, 4, 5), LinearOp.LE, 0),
                Linear(intArrayOf(2, -1), intArrayOf(3, 4), LinearOp.LE, 0),
                Linear(intArrayOf(1, 1), intArrayOf(0, 3), LinearOp.GE, 3),
            )), pbLearning = false).apply { enableIntegerRowLearning() }
        val peer = object : SearchComponent {
            override fun initialize(context: SearchContext): ComponentResult =
                context.publish(SearchDecision.IntAtMost(5, 7))
        }
        val cp = CpSearchComponent(native)
        val shared = SearchSession(listOf(peer, cp), atoms = SearchAtomRegistry(0))
        assertIs<ComponentResult.Consistent>(shared.initialize())
        assertIs<ComponentResult.Consistent>(shared.push(SearchDecision.IntAtMost(2, 7)))
        assertIs<ComponentResult.Conflict>(shared.push(SearchDecision.IntAtLeast(0, 2)))
        val first = assertIs<SearchConflictResolution.Backjump>(cp.resolveConflict(shared)).conflict
        shared.popTo(first.decisionLevel)

        val cascade = assertIs<SearchLearnedConflictResult.Backjump>(first.apply(shared)).conflict
        shared.popTo(cascade.decisionLevel)
        assertIs<SearchLearnedConflictResult.Resume>(cascade.apply(shared))

        assertEquals(1, native.explanationState.learned.integerRows?.size)
        assertEquals(8L, shared.intLowerBound(2))
    }

    @Test
    fun `a paired arithmetic assertion should publish both row and clause deductions`() {
        val native = PropagationSession(Problem(2, 3,
            arrayOf(IntDomain(0, 6), IntDomain(0, 12), IntDomain(0, 24)), arrayOf(
                Linear(intArrayOf(2, 1, -1), intArrayOf(0, 1, 2), LinearOp.LE, 0),
                Linear(intArrayOf(2, -1), intArrayOf(0, 1), LinearOp.LE, 0),
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, false))),
            )), pbLearning = false).apply { enableIntegerRowLearning() }
        assertIs<PropagationResult.Implied>(native.pinIntAtMost(2, 7))
        val row = requireNotNull(IntegerConflictRow.source(LinearPropagator(intArrayOf(), intArrayOf(0, 2),
            longArrayOf(4, -1), intArrayOf(0, 2), LinearOp.LE, 0), useLo = true))
        val fallback = ConflictAnalyzer.AnalysisResult.Learned(intArrayOf(Lit.make(0, true)),
            backjumpLevel = 0, lbd = 1, decisionLevels = intArrayOf(0))
        val learned = ConflictAnalyzer.AnalysisResult.LearnedIntegerRow(row,
            backjumpLevel = 1, lbd = 2, decisionLevels = intArrayOf(0, 1), fallbackClause = fallback)

        val result = assertIs<PropagationResult.Implied>(native.addLearnedIntegerRow(learned))

        assertEquals(1L, result.intMaxOrNullCompat(0))
        assertEquals(true, result.boolValueOrNull(0))
    }

    @Test
    fun `prepared optimization should consume a guarded arithmetic consequence`() {
        val program = parseFlatZinc("""
            var bool: active;
            var 7..12: z;
            var 0..3: x;
            var 0..6: y;
            constraint int_lin_le_reif([2,1,-1],[x,y,z],0,active);
            constraint int_lin_le([2,-1],[x,y],0);
            solve :: seq_search([
                bool_search([active],input_order,indomain_max,complete),
                int_search([z],input_order,indomain_min,complete),
                int_search([x,y],input_order,indomain_max,complete)
            ]) maximize x;
        """.trimIndent())
        val prepared = FinitePipeline.prepare(FinitePipelineRequest(
            program.problem, FiniteEngine.BACKTRACK, program.linearObjective(), randomSeed = 3L,
        ))
        val base = assertNotNull(program.searchHints).toSearchHints().toBacktrackParams(
            prepared.problem.numBoolVars, prepared.problem.numIntVars)
        val params = base.copy(integerRowLearning = true, pbLearning = false,
            objectiveGuidedValues = false, randomSeed = 3L, maxDecisions = 1_000)

        val result = BacktrackSolver(assertIs<BakedProblem>(prepared.problem)).minimize(
            assertNotNull(prepared.objective), params)

        val optimum = assertIs<MinimizeResult.Optimal>(result)
        val point = prepared.reconstruct(optimum.sample)
        val x = point.intValue(program.intVarsByName.getValue("x"))
        val y = point.intValue(program.intVarsByName.getValue("y"))
        val z = point.intValue(program.intVarsByName.getValue("z"))
        assertEquals(3L, x)
        assertEquals(2 * x + y <= z, point.boolValue(program.boolVarsByName.getValue("active")))
        assertTrue(2 * x <= y)
        assertTrue((result.stats.search.learning["learningIntegerConsumed"] ?: 0L) > 0L)
    }

    @Test
    fun `a prepared enumeration should reuse and retire its guarded arithmetic consequence`() {
        val program = parseFlatZinc("""
            var bool: active;
            var 7..12: z;
            var 0..3: x;
            var 0..6: y;
            constraint int_lin_le_reif([2,1,-1],[x,y,z],0,active);
            constraint int_lin_le([2,-1],[x,y],0);
            solve :: seq_search([
                bool_search([active],input_order,indomain_max,complete),
                int_search([z],input_order,indomain_min,complete),
                int_search([x,y],input_order,indomain_max,complete)
            ]) satisfy;
        """.trimIndent())
        val prepared = FinitePipeline.prepare(FinitePipelineRequest(
            program.problem, FiniteEngine.BACKTRACK, solutionSetSensitive = true, randomSeed = 3L,
        ))
        var native: PropagationSession? = null
        val trace = ArithmeticLearningTrace(1024, 65536)
        val uses = mutableMapOf<Pair<LearnedIntegerPropagator, Int>, MutableSet<Long>>()
        val base = assertNotNull(program.searchHints).toSearchHints().toBacktrackParams(
            prepared.problem.numBoolVars, prepared.problem.numIntVars)
        fun observing(delegate: VariableSelector): VariableSelector = object : VariableSelector by delegate {
            override fun fresh(): VariableSelector = observing(delegate.fresh())
            override fun pick(session: PropagationSession, rng: Random): VarRef? {
                if (native == null) session.explanationState.arithmeticLearningTrace = trace
                native = session
                val state = session.explanationState
                for (variable in state.intMaxReason.indices) {
                    val reason = state.intMaxReason[variable]
                    if (reason < state.baseFactorCount) continue
                    val row = state.factorAt(reason) as? LearnedIntegerPropagator ?: continue
                    uses.getOrPut(row to variable) { mutableSetOf() }.add(state.intDomains[variable].max)
                }
                return delegate.pick(session, rng)
            }
        }
        val params = base.copy(variableSelector = observing(base.variableSelector),
            integerRowLearning = true, pbLearning = false, randomSeed = 3L, maxDecisions = 1_000)
        BacktrackSolver(assertIs<BakedProblem>(prepared.problem)).openEnumerate(params).use { stream ->
            repeat(20) {
                assertTrue(stream.hasNext())
                val point = prepared.reconstruct(stream.next())
                val x = point.intValue(program.intVarsByName.getValue("x"))
                val y = point.intValue(program.intVarsByName.getValue("y"))
                val z = point.intValue(program.intVarsByName.getValue("z"))
                assertEquals(2 * x + y <= z, point.boolValue(program.boolVarsByName.getValue("active")))
                assertTrue(2 * x <= y)
            }
            val session = assertNotNull(native)
            val retained = uses.entries.firstOrNull { it.value.size >= 2 }?.key?.first
            assertNotNull(retained, "prepared=" + ProblemJson().encodeToString(prepared.problem) +
                "; learning=" + session.learnedLifecycle() + "; uses=" + uses.values +
                "; declines=" + trace.events.filter { "decline" in it.kind }.groupingBy { it.kind }.eachCount() +
                "; tail=" + trace.events.takeLast(20).map { it.kind })
            assertTrue(retained.row.guard != Lit.NONE)
            session.popToLevel(0)
            assertIs<PropagationResult.Implied>(session.pinBool(Lit.variable(retained.row.guard),
                !Lit.isPositive(retained.row.guard)))
            assertTrue(session.explanationState.forgetLearnedClauses(maxWork = 16_000_000L,
                maxScratchBytes = 16_000_000L, cancelled = { session.cancelRequested }) { _, _ -> false })
            assertFalse(session.explanationState.learned.integerRows?.contains(retained) == true)
            assertTrue((session.learnedLifecycle()["learningIntegerRetired"] ?: 0L) > 0L)
        }
    }

    @Test
    fun `a consumed guarded row should retire after its historical reason retracts`() {
        val native = PropagationSession(Problem(2, 3,
            arrayOf(IntDomain(0, 6), IntDomain(0, 12), IntDomain(0, 24)), arrayOf(
                ReifiedLinear(0, intArrayOf(2, 1, -1), intArrayOf(0, 1, 2), LinearOp.LE, 0),
                Linear(intArrayOf(2, -1), intArrayOf(0, 1), LinearOp.LE, 0),
            )), pbLearning = false).apply { enableIntegerRowLearning() }
        assertIs<PropagationResult.Implied>(native.pinBool(0, true))
        assertIs<PropagationResult.Implied>(native.pinIntAtMost(2, 7))
        val conflict = assertIs<PropagationResult.Unsat>(native.pinIntAtLeast(0, 2))
        val learned = assertIs<ConflictAnalyzer.AnalysisResult.LearnedIntegerRow>(conflict.learnedClause)
        native.popToLevel(learned.backjumpLevel)
        assertIs<PropagationResult.Implied>(native.addLearnedIntegerRow(learned.row, learned.lbd))
        val state = native.explanationState
        val retained = assertNotNull(state.learned.integerRows).single()
        assertSame(retained, state.factorAt(state.intMaxReason[0]))
        assertTrue(assertNotNull(state.reasonOf(state.intMaxAntecedents[0])).contains(Lit.make(0, false)))

        native.forgetLearnedClauses { _, _ -> false }
        assertSame(retained, assertNotNull(state.learned.integerRows).single())
        native.popToLevel(0)
        assertIs<PropagationResult.Implied>(native.pinBool(1, true))
        native.forgetLearnedClauses { _, _ -> false }

        assertTrue(state.learned.integerRows.isNullOrEmpty())
        assertEquals(1L, native.learnedLifecycle()["learningIntegerRetired"])
        assertIs<PropagationResult.Implied>(native.pinIntAtMost(2, 11))
        assertIs<PropagationResult.Implied>(native.pinBool(0, true))
        assertEquals(5L, native.intDomain(0).max)
    }

    @Test
    fun `an incidental hole should preserve a learned row guard`() {
        val native = PropagationSession(Problem(1, 3,
            arrayOf(IntDomain(0, 6), IntDomain(0, 12).excludeValue(2), IntDomain(0, 24)), arrayOf(
                ReifiedLinear(0, intArrayOf(2, 1, -1), intArrayOf(0, 1, 2), LinearOp.LE, 0),
                Linear(intArrayOf(2, -1), intArrayOf(0, 1), LinearOp.LE, 0),
            ))).apply { enableIntegerRowLearning() }
        assertIs<PropagationResult.Implied>(native.pinBool(0, true))
        assertIs<PropagationResult.Implied>(native.pinIntAtMost(2, 7))

        val conflict = assertIs<PropagationResult.Unsat>(native.pinIntAtLeast(0, 2))

        val learned = assertIs<ConflictAnalyzer.AnalysisResult.LearnedIntegerRow>(conflict.learnedClause)
        assertEquals(Lit.make(0, true), learned.row.guard)
        assertTrue(learned.fallbackClause.literals.contains(Lit.make(0, false)))
        native.popToLevel(learned.backjumpLevel)
        assertIs<PropagationResult.Implied>(native.addLearnedIntegerRow(learned.row, learned.lbd))
        assertEquals(1L, native.intDomain(0).max)
    }

    @Test
    fun `original rows should retain a reusable consequence of the scaling conflict`() {
        for (scale in listOf(2, 8, 32)) {
            for (order in listOf(listOf(0, 1, 2), listOf(0, 2, 1), listOf(1, 0, 2),
                listOf(1, 2, 0), listOf(2, 0, 1), listOf(2, 1, 0))) {
                val factors = arrayOf(
                    Linear(intArrayOf(scale, 1, -1), intArrayOf(0, 1, 2), LinearOp.LE, 0),
                    Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.LE, 0),
                    Linear(intArrayOf(1, -scale, -1), intArrayOf(2, 0, 3), LinearOp.LE, 0),
                )
                val native = PropagationSession(Problem(0, 4, arrayOf(IntDomain(0, scale.toLong()),
                    IntDomain(0, scale.toLong()), IntDomain(0, scale.toLong() * (scale + 1)),
                    IntDomain(0, scale.toLong() * (scale + 1))), order.map { factors[it] }.toTypedArray()),
                    pbLearning = false).apply { enableIntegerRowLearning() }
                assertIs<PropagationResult.Implied>(native.pinIntAtLeast(0, 1))

                val conflict = assertIs<PropagationResult.Unsat>(native.pinIntAtMost(3, 0))

                val learned = assertIs<ConflictAnalyzer.AnalysisResult.LearnedIntegerRow>(conflict.learnedClause,
                    "scale=$scale order=$order")
                native.popToLevel(learned.backjumpLevel)
                assertIs<PropagationResult.Implied>(native.addLearnedIntegerRow(learned.row, learned.lbd))
                assertEquals(1L, native.intDomain(3).min, "scale=$scale order=$order")
            }
        }
    }

    @Test
    fun `an incidental hole should retain a row across sibling endpoints without filling the domain`() {
        for (sign in listOf(1, -1)) {
            val native = PropagationSession(Problem(0, 3, arrayOf(
                IntDomain(if (sign > 0) 0L else -6L, if (sign > 0) 6L else 0L),
                IntDomain(if (sign > 0) 0L else -12L, if (sign > 0) 12L else 0L).excludeValue(2L * sign),
                IntDomain(if (sign > 0) 0L else -24L, if (sign > 0) 24L else 0L),
            ), arrayOf(
                Linear(intArrayOf(2 * sign, sign, -sign), intArrayOf(0, 1, 2), LinearOp.LE, 0),
                Linear(intArrayOf(2 * sign, -sign), intArrayOf(0, 1), LinearOp.LE, 0),
            )), pbLearning = false).apply { enableIntegerRowLearning() }
            val cp = CpSearchComponent(native, intArrayOf(4, 7, 9))
            val shared = SearchSession(listOf(cp), atoms = SearchAtomRegistry(0))
            assertIs<ComponentResult.Consistent>(shared.initialize())
            assertIs<ComponentResult.Consistent>(shared.push(if (sign > 0)
                SearchDecision.IntAtMost(9, 7) else SearchDecision.IntAtLeast(9, -7)))
            assertIs<ComponentResult.Conflict>(shared.push(if (sign > 0)
                SearchDecision.IntAtLeast(4, 2) else SearchDecision.IntAtMost(4, -2)))
            val learned = assertIs<ConflictAnalyzer.AnalysisResult.LearnedIntegerRow>(cp.lastConflict?.learnedClause)
            assertEquals(4L * sign, learned.row.coefficient(0))
            assertEquals(-sign.toLong(), learned.row.coefficient(2))
            val backjump = assertIs<SearchConflictResolution.Backjump>(cp.resolveConflict(shared)).conflict
            shared.popTo(backjump.decisionLevel)

            assertIs<SearchLearnedConflictResult.Resume>(backjump.apply(shared))

            assertEquals(sign.toLong(), if (sign > 0) native.intDomain(0).max else native.intDomain(0).min)
            for (upper in listOf(11L, 15L, 23L)) {
                shared.popTo(0)
                assertFalse(2L * sign in native.intDomain(1))
                assertIs<ComponentResult.Consistent>(shared.push(if (sign > 0)
                    SearchDecision.IntAtMost(9, upper) else SearchDecision.IntAtLeast(9, -upper)))
                assertEquals(sign * (upper / 4),
                    if (sign > 0) native.intDomain(0).max else native.intDomain(0).min)
                val state = native.explanationState
                val reason = if (sign > 0) state.intMaxReason[0] else state.intMinReason[0]
                assertIs<LearnedIntegerPropagator>(state.factorAt(reason))
                assertNotNull(state.reasonOf(if (sign > 0) state.intMaxAntecedents[0] else state.intMinAntecedents[0]))
            }
            shared.popTo(0)
            assertFalse(2L * sign in native.intDomain(1))
            assertIs<ComponentResult.Consistent>(shared.push(SearchDecision.IntEqual(4, 2L * sign)))
            assertIs<ComponentResult.Consistent>(shared.push(SearchDecision.IntEqual(7, 4L * sign)))
            assertIs<ComponentResult.Consistent>(shared.push(SearchDecision.IntEqual(9, 8L * sign)))
        }
    }

    @Test
    fun `a retained row reason should keep its historical premises after factor ids move`() {
        val native = PropagationSession(Problem(1, 3,
            arrayOf(IntDomain(0, 6), IntDomain(0, 12), IntDomain(0, 24)), arrayOf(
                Linear(intArrayOf(2, 1, -1), intArrayOf(0, 1, 2), LinearOp.LE, 0),
                Linear(intArrayOf(2, -1), intArrayOf(0, 1), LinearOp.LE, 0),
            ))).apply { enableIntegerRowLearning() }
        assertIs<PropagationResult.Implied>(native.seed(Assumptions.None.withInt(2, 7)))
        val dummy = native.learnedClauseCount
        val tautology = Clause(intArrayOf(Lit.make(0, true), Lit.make(0, false)))
        native.addLearnedClause(tautology, lbd = 3)
        val cp = CpSearchComponent(native)
        cp.rebase()
        val shared = SearchSession(listOf(cp), atoms = SearchAtomRegistry(1))
        assertIs<ComponentResult.Consistent>(shared.initialize())
        assertIs<ComponentResult.Conflict>(shared.push(SearchDecision.IntAtLeast(0, 2)))
        val backjump = assertIs<SearchConflictResolution.Backjump>(cp.resolveConflict(shared)).conflict
        shared.popTo(backjump.decisionLevel)
        assertIs<SearchLearnedConflictResult.Resume>(backjump.apply(shared))
        val state = native.explanationState
        val row = requireNotNull(state.learned.integerRows).single()
        val prior = assertNotNull(state.reasonOf(state.intMaxAntecedents[0])).copyOf()
        assertTrue(prior.isNotEmpty())

        native.forgetLearnedClauses { index, _ -> index != dummy }
        native.addLearnedClause(tautology, lbd = 3)
        state.lazyReasonMemo.clear()

        assertSame(row, state.factorAt(state.intMaxReason[0]))
        assertContentEquals(prior, state.reasonOf(state.intMaxAntecedents[0]))
    }

    @Test
    fun `a full row store should fall back until forgetting releases capacity`() {
        val native = PropagationSession(Problem(0, 4,
            arrayOf(IntDomain(0, 6), IntDomain(0, 12), IntDomain(0, 24), IntDomain(0, 6)), arrayOf(
                Linear(intArrayOf(2, 1, -1), intArrayOf(0, 1, 2), LinearOp.LE, 0),
                Linear(intArrayOf(2, -1), intArrayOf(0, 1), LinearOp.LE, 0),
            ))).apply { enableIntegerRowLearning() }
        repeat(INTEGER_LEARNING_ROWS) { index ->
            val row = requireNotNull(IntegerConflictRow.source(LinearPropagator(intArrayOf(), intArrayOf(3),
                longArrayOf(1), intArrayOf(3), LinearOp.LE, 6L + index), useLo = true))
            native.addLearnedIntegerRow(row, lbd = 3)
        }
        assertIs<PropagationResult.Implied>(native.pinIntAtMost(2, 7))
        val capped = assertIs<PropagationResult.Unsat>(native.pinIntAtLeast(0, 2))
        assertIs<ConflictAnalyzer.AnalysisResult.Learned>(capped.learnedClause)
        native.popToLevel(0)
        native.forgetLearnedClauses { index, _ -> index != 0 }
        assertEquals(INTEGER_LEARNING_ROWS - 1, native.explanationState.learned.integerRows?.size)
        assertIs<PropagationResult.Implied>(native.pinIntAtMost(2, 7))

        val retried = assertIs<PropagationResult.Unsat>(native.pinIntAtLeast(0, 2))

        assertIs<ConflictAnalyzer.AnalysisResult.LearnedIntegerRow>(retried.learnedClause)
    }

    @Test
    fun `an unavailable shared bound premise should decline native row admission`() {
        val native = PropagationSession(Problem(0, 3,
            arrayOf(IntDomain(0, 6), IntDomain(0, 12), IntDomain(0, 24)), arrayOf(
                Linear(intArrayOf(2, 1, -1), intArrayOf(0, 1, 2), LinearOp.LE, 0),
                Linear(intArrayOf(2, -1), intArrayOf(0, 1), LinearOp.LE, 0),
            ))).apply { enableIntegerRowLearning() }
        val peer = object : SearchComponent {
            override fun initialize(context: SearchContext): ComponentResult =
                context.publish(SearchDecision.IntAtMost(2, 7))
        }
        val cp = CpSearchComponent(native)
        val shared = SearchSession(listOf(peer, cp), atoms = SearchAtomRegistry(0))
        assertIs<ComponentResult.Consistent>(shared.initialize())
        assertIs<ComponentResult.Conflict>(shared.push(SearchDecision.IntAtLeast(0, 2)))
        assertIs<ConflictAnalyzer.AnalysisResult.LearnedIntegerRow>(cp.lastConflict?.learnedClause)

        assertFalse(cp.prefersNativeConflictAnalysis)

        val fallback = assertIs<SearchConflictResolution.Backjump>(cp.resolveConflict(shared)).conflict
        shared.popTo(fallback.decisionLevel)
        fallback.apply(shared)
        assertTrue(native.explanationState.learned.integerRows.isNullOrEmpty())
    }

    @Test
    fun `cancellation before row analysis should publish no learned result`() {
        val state = PropagationState(Problem(0, 3,
            arrayOf(IntDomain(0, 6), IntDomain(0, 12), IntDomain(0, 24)), arrayOf(
                Linear(intArrayOf(2, 1, -1), intArrayOf(0, 1, 2), LinearOp.LE, 0),
                Linear(intArrayOf(2, -1), intArrayOf(0, 1), LinearOp.LE, 0),
            )), Assumptions.None)
        state.undoLogging = true
        state.integerRowLearning = true
        assertTrue(state.setIntMaxAsDecision(2, 7))
        state.runToFixpoint(allFactors = true)
        assertTrue(state.setIntMinAsDecision(0, 2))
        assertNotNull(state.runToFixpoint(allFactors = false))
        state.finiteWork.cancellation = Cancellation { true }

        val result = state.conflictAnalyzer.analyze(1)

        assertIs<ConflictAnalyzer.AnalysisResult.NotApplicable>(result)
        assertTrue(state.learned.integerRows.isNullOrEmpty())
    }

    @Test
    fun `a stored row should wake beside a delta consumer after compaction and late atoms`() {
        val native = PropagationSession(Problem(1, 4,
            arrayOf(IntDomain(0, 6), IntDomain(0, 12), IntDomain(0, 24), IntDomain(0, 6)), arrayOf(
                Linear(intArrayOf(2, 1, -1), intArrayOf(0, 1, 2), LinearOp.LE, 0),
                Linear(intArrayOf(2, -1), intArrayOf(0, 1), LinearOp.LE, 0),
                Table(intArrayOf(0, 3), LongArray(14) { (it / 2).toLong() }),
            ))).apply { enableIntegerRowLearning() }
        assertIs<PropagationResult.Implied>(native.seed(Assumptions.None.withBool(0, true)))
        native.addLearnedClause(Clause(intArrayOf(Lit.make(0, true), Lit.make(0, false))), lbd = 3)
        val cp = CpSearchComponent(native)
        cp.rebase()
        val shared = SearchSession(listOf(cp), atoms = SearchAtomRegistry(1))
        assertIs<ComponentResult.Consistent>(shared.initialize())
        assertIs<ComponentResult.Consistent>(shared.push(SearchDecision.IntAtMost(2, 7)))
        assertIs<ComponentResult.Conflict>(shared.push(SearchDecision.IntAtLeast(0, 2)))
        val backjump = assertIs<SearchConflictResolution.Backjump>(cp.resolveConflict(shared)).conflict
        shared.popTo(backjump.decisionLevel)
        assertIs<SearchLearnedConflictResult.Resume>(backjump.apply(shared))
        shared.popTo(0)
        val state = native.explanationState
        val row = requireNotNull(state.learned.integerRows).single()
        native.forgetLearnedClauses { index, _ ->
            native.learnedClausePermanent(index) || state.learned.store[index] === row
        }
        val lateBound = state.atomVarGe(0, 3)
        val lateEquality = state.atomVarEq(0, 4)

        assertIs<ComponentResult.Consistent>(shared.push(SearchDecision.IntAtMost(2, 11)))

        assertEquals(2L, native.intDomain(0).max)
        assertEquals(2L, native.intDomain(3).max)
        assertEquals(false, state.litTruth(Lit.make(lateBound, true)))
        assertEquals(false, state.litTruth(Lit.make(lateEquality, true)))
        assertSame(row, state.factorAt(state.intMaxReason[0]))
        assertEquals(true, native.boolValue(0))
    }

    @Test
    fun `a stored source row should assert and tighten several sibling endpoints`() {
        for (sign in listOf(1, -1)) {
            for ((op, orientation) in listOf(LinearOp.LE to 1, LinearOp.EQ to 1, LinearOp.EQ to -1)) {
                val native = PropagationSession(Problem(0, 3, arrayOf(
                    IntDomain(if (sign > 0) 0L else -6L, if (sign > 0) 6L else 0L),
                    IntDomain(if (sign > 0) 0L else -12L, if (sign > 0) 12L else 0L),
                    IntDomain(if (sign > 0) 0L else -24L, if (sign > 0) 24L else 0L),
                ), arrayOf(
                    Linear(intArrayOf(2 * sign * orientation, sign * orientation, -sign * orientation),
                        intArrayOf(0, 1, 2), op, 0),
                    Linear(intArrayOf(2 * sign, -sign), intArrayOf(0, 1), LinearOp.LE, 0),
                )), pbLearning = false).apply { enableIntegerRowLearning() }
                val cp = CpSearchComponent(native, intArrayOf(4, 7, 9))
                val shared = SearchSession(listOf(cp), atoms = SearchAtomRegistry(0))
                assertIs<ComponentResult.Consistent>(shared.initialize())
                assertIs<ComponentResult.Consistent>(shared.push(if (sign > 0)
                    SearchDecision.IntAtMost(9, 7) else SearchDecision.IntAtLeast(9, -7)))
                assertIs<ComponentResult.Conflict>(shared.push(if (sign > 0)
                    SearchDecision.IntAtLeast(4, 2) else SearchDecision.IntAtMost(4, -2)))
                val learned = assertIs<ConflictAnalyzer.AnalysisResult.LearnedIntegerRow>(
                    cp.lastConflict?.learnedClause,
                )
                assertEquals(4L * sign, learned.row.coefficient(0))
                assertEquals(-sign.toLong(), learned.row.coefficient(2))
                val backjump = assertIs<SearchConflictResolution.Backjump>(cp.resolveConflict(shared)).conflict
                shared.popTo(backjump.decisionLevel)

                assertIs<SearchLearnedConflictResult.Resume>(backjump.apply(shared))

                assertEquals(if (sign > 0) 1L else -1L,
                    if (sign > 0) native.intDomain(0).max else native.intDomain(0).min)
                assertFalse(native.learnedConstraintSatisfied(learned))
                for (upper in listOf(11L, 15L, 23L)) {
                    shared.popTo(0)
                    assertIs<ComponentResult.Consistent>(shared.push(if (sign > 0)
                        SearchDecision.IntAtMost(9, upper) else SearchDecision.IntAtLeast(9, -upper)))
                    assertEquals(if (sign > 0) upper / 4 else -(upper / 4),
                        if (sign > 0) native.intDomain(0).max else native.intDomain(0).min)
                    val reason = if (sign > 0) native.explanationState.intMaxReason[0]
                        else native.explanationState.intMinReason[0]
                    assertIs<LearnedIntegerPropagator>(native.explanationState.factorAt(reason))
                }
            }
        }
    }

    @Test
    fun `the production traversal should consume an integer row with PB disabled`() {
        val native = PropagationSession(Problem(0, 3, arrayOf(IntDomain(0, 6), IntDomain(0, 12), IntDomain(0, 24)),
            arrayOf(
                Linear(intArrayOf(2, 1, -1), intArrayOf(0, 1, 2), LinearOp.LE, 0),
                Linear(intArrayOf(2, -1), intArrayOf(0, 1), LinearOp.LE, 0),
            )), pbLearning = false).apply { enableIntegerRowLearning() }
        var consumed = false
        val branching = CpBranching { session, source ->
            val state = session.explanationState
            if (state.learned.integerRows.isNullOrEmpty()) {
                if (session.intDomain(2).max > 7) {
                    listOf(SearchDecision.IntAtMost(2, 7), SearchDecision.IntAtLeast(2, 8))
                } else {
                    listOf(SearchDecision.IntAtLeast(0, 2), SearchDecision.IntAtMost(0, 1))
                }
            } else {
                consumed = consumed || session.intDomain(0).max == 1L
                CpBranching.Middle.alternatives(session, source)
            }
        }
        val shared = SearchSession(
            listOf(CpSearchComponent(native, branching = branching)), atoms = SearchAtomRegistry(0),
        )
        assertIs<ComponentResult.Consistent>(shared.initialize())

        assertIs<SearchResult.Satisfied>(shared.solve(0))

        assertTrue(consumed)
        assertEquals(1, native.explanationState.learned.integerRows?.size)
    }

    @Test
    fun `a root asserting row should retain a feasible parent`() {
        val native = PropagationSession(Problem(0, 3, arrayOf(IntDomain(0, 6), IntDomain(0, 12), IntDomain(0, 7)),
            arrayOf(
                Linear(intArrayOf(2, 1, -1), intArrayOf(0, 1, 2), LinearOp.LE, 0),
                Linear(intArrayOf(2, -1), intArrayOf(0, 1), LinearOp.LE, 0),
            ))).apply { enableIntegerRowLearning() }
        val cp = CpSearchComponent(native)
        val shared = SearchSession(listOf(cp), atoms = SearchAtomRegistry(0))
        assertIs<ComponentResult.Consistent>(shared.initialize())
        assertIs<ComponentResult.Conflict>(shared.push(SearchDecision.IntAtLeast(0, 2)))
        val backjump = assertIs<SearchConflictResolution.Backjump>(cp.resolveConflict(shared)).conflict
        assertEquals(0, backjump.decisionLevel)
        shared.popTo(0)

        assertIs<SearchLearnedConflictResult.Resume>(backjump.apply(shared))

        assertEquals(1L, native.intDomain(0).max)
    }

    @Test
    fun `integer rounding that loses conflict should retain clause fallback`() {
        val native = PropagationSession(Problem(0, 2, arrayOf(IntDomain(0, 3), IntDomain(0, 2)), arrayOf(
            Linear(intArrayOf(1, 2), intArrayOf(0, 1), LinearOp.LE, 3),
            Linear(intArrayOf(-1, -2), intArrayOf(0, 1), LinearOp.LE, -3),
        ))).apply { enableIntegerRowLearning() }

        val conflict = assertIs<PropagationResult.Unsat>(native.pinInt(0, 2))

        assertIs<ConflictAnalyzer.AnalysisResult.Learned>(conflict.learnedClause)
        assertTrue(native.explanationState.learned.integerRows.isNullOrEmpty())
    }

    @Test
    fun `historical holes should withhold a row even after the live interval loses the hole`() {
        for (reverse in listOf(false, true)) {
            val rows = listOf(
                Linear(intArrayOf(2, 1, -1), intArrayOf(0, 1, 2), LinearOp.LE, 0),
                Linear(intArrayOf(2, -1), intArrayOf(0, 1), LinearOp.LE, 0),
            )
            val native = PropagationSession(Problem(0, 3,
                arrayOf(IntDomain(0, 6), IntDomain(0, 12).excludeValue(4), IntDomain(0, 24)),
                (if (reverse) rows.reversed() else rows).toTypedArray())).apply { enableIntegerRowLearning() }
            assertIs<PropagationResult.Implied>(native.pinIntAtMost(2, 7))

            val conflict = assertIs<PropagationResult.Unsat>(native.pinIntAtLeast(0, 2))

            assertIs<ConflictAnalyzer.AnalysisResult.Learned>(conflict.learnedClause)
        }
    }

    @Test
    fun `a reified source reason should retain its Boolean condition in the row and fallback`() {
        val native = PropagationSession(Problem(1, 3,
            arrayOf(IntDomain(0, 6), IntDomain(0, 12), IntDomain(0, 24)), arrayOf(
                ReifiedLinear(0, intArrayOf(2, 1, -1), intArrayOf(0, 1, 2), LinearOp.LE, 0),
                Linear(intArrayOf(2, -1), intArrayOf(0, 1), LinearOp.LE, 0),
            ))).apply { enableIntegerRowLearning() }
        assertIs<PropagationResult.Implied>(native.pinBool(0, true))
        assertIs<PropagationResult.Implied>(native.pinIntAtMost(2, 7))

        val conflict = assertIs<PropagationResult.Unsat>(native.pinIntAtLeast(0, 2))

        val learned = assertIs<ConflictAnalyzer.AnalysisResult.LearnedIntegerRow>(conflict.learnedClause)
        assertEquals(Lit.make(0, true), learned.row.guard)
        assertTrue(learned.fallbackClause.literals.contains(Lit.make(0, false)))
    }
}
