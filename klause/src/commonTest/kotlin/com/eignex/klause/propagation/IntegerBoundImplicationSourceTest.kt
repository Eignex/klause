package com.eignex.klause.propagation

import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearPropagator
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.Term
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchAtomRegistry
import com.eignex.klause.solver.search.SearchComponent
import com.eignex.klause.solver.search.SearchConflictResolution
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchLearnedConflictResult
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.util.CheckedLongOverflowException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IntegerBoundImplicationSourceTest {
    @Test
    fun `repeated source terms should preserve a compact complete implication`() {
        val variables = intArrayOf(0) + IntArray(32) { if (it < 16) 1 else 2 }
        val native = PropagationSession(Problem(0, 3, Array(3) { IntDomain(0, 1) }, arrayOf(
            Linear(IntArray(33) { if (it == 0) -1 else 1 }, variables, LinearOp.LE, 31),
        )), pbLearning = false)
        assertIs<PropagationResult.Implied>(native.pinIntAtLeast(1, 1))
        assertIs<PropagationResult.Implied>(native.pinIntAtLeast(2, 1))
        val state = native.explanationState
        val marker = assertNotNull(state.intMinAntecedents[0])

        val row = assertNotNull(
            IntegerBoundImplicationSource(state, IntegerSourceWork(state)).source(0, true, marker))

        assertContentEquals(intArrayOf(0, 1, 2), row.variables)
        assertContentEquals(longArrayOf(-1, 1, 1), row.coefficients)
        assertEquals(1L, row.bound)
        assertFalse(row.hasBoundCover)
    }

    @Test
    fun `a sufficient weighted cover should preserve either historical unit sign`() {
        for (lower in listOf(false, true)) {
            val coefficients = IntArray(33) { if (it == 0) { if (lower) -100 else 100 } else if (it == 1) 100 else 1 }
            val bound = if (lower) 31 else 131
            val native = PropagationSession(Problem(0, 33, Array(33) { IntDomain(0, 1) }, arrayOf(
                Linear(coefficients, IntArray(33) { it }, LinearOp.LE, bound),
            )), pbLearning = false)
            for (v in 2 until 33) assertIs<PropagationResult.Implied>(native.implyIntAtLeast(v, 1))
            assertIs<PropagationResult.Implied>(native.pinIntAtLeast(1, 1))
            val state = native.explanationState
            val marker = assertNotNull(if (lower) state.intMinAntecedents[0] else state.intMaxAntecedents[0])

            val row = assertNotNull(
                IntegerBoundImplicationSource(state, IntegerSourceWork(state)).source(0, lower, marker))

            assertContentEquals(intArrayOf(0, 1), row.variables)
            assertContentEquals(longArrayOf(if (lower) -1 else 1, 1), row.coefficients)
            assertEquals(if (lower) 0L else 1L, row.bound)
            assertTrue(row.hasBoundCover)
            for (x in 0L..1L) for (e in 0L..1L) for (smallCount in 0L..31L) {
                if (coefficients[0] * x + 100L * e + smallCount <= bound) {
                    assertTrue(row.coefficients[0] * x + e <= row.bound)
                }
            }
        }
    }

    @Test
    fun `an original weighted cover should assert a mapped child beyond complete width`() {
        val problem = Problem(0, 35, Array(35) { IntDomain(0, 1) }, Array(3) { index ->
            if (index == 2) Linear(intArrayOf(1, 1, 1), intArrayOf(0, 1, 2), LinearOp.LE, 1)
            else Linear(IntArray(33) { if (it == 0) 100 else if (it == 1) -100 else 1 },
                intArrayOf(3, index) + IntArray(31) { it + 4 }, LinearOp.LE, 31)
        })
        val native = PropagationSession(PropagationProblem(problem), pbLearning = false, attribution = true)
            .apply { enableIntegerRowLearning() }
        for (v in 4 until 35) assertIs<PropagationResult.Implied>(native.implyIntAtLeast(v, 1))
        val cp = CpSearchComponent(native, IntArray(35) { it + 7 })
        val shared = SearchSession(listOf(cp), atoms = SearchAtomRegistry(0))
        assertIs<ComponentResult.Consistent>(shared.initialize())

        assertIs<ComponentResult.Conflict>(shared.push(SearchDecision.IntAtLeast(10, 1)))
        val learned = assertIs<ConflictAnalyzer.AnalysisResult.LearnedIntegerRow>(cp.lastConflict?.learnedClause)
        val backjump = assertIs<SearchConflictResolution.Backjump>(cp.resolveConflict(shared)).conflict
        shared.popTo(backjump.decisionLevel)
        assertIs<SearchLearnedConflictResult.Resume>(backjump.apply(shared))

        assertContentEquals(intArrayOf(2, 3), learned.row.variables)
        assertContentEquals(longArrayOf(1, 2), learned.row.coefficients)
        assertEquals(1L, learned.row.bound)
        assertEquals(0, learned.row.retainedSources)
        assertTrue(learned.row.hasBoundImplication)
        assertTrue(learned.row.hasBoundCover)
        assertEquals(0L, shared.intUpperBound(10))
        val snapshot = native.learnedLifecycle()
        assertEquals(2L, snapshot["learningIntegerOriginalCoverSources"])
        assertEquals(1L, snapshot["learningIntegerOriginalCoverProduced"])
        assertEquals(1L, snapshot["learningIntegerOriginalCoverAdmitted"])
        assertEquals(1L, snapshot["learningIntegerOriginalCoverConsumed"])
        native.releaseRetainedState()
        assertEquals(snapshot, native.learnedLifecycle())
    }

    @Test
    fun `a required unavailable condition should withhold a weighted cover child`() {
        val problem = Problem(0, 36, Array(36) { IntDomain(0, 1) }, Array(3) { index ->
            if (index == 2) Linear(intArrayOf(1, 1, 1), intArrayOf(0, 1, 2), LinearOp.LE, 1)
            else Linear(IntArray(34) { if (it < 2) 100 else if (it == 2) -200 else 1 },
                intArrayOf(3, 4, index) + IntArray(31) { it + 5 }, LinearOp.LE, 131)
        })
        val native = PropagationSession(PropagationProblem(problem), pbLearning = false, attribution = true)
            .apply { enableIntegerRowLearning() }
        for (v in 5 until 36) assertIs<PropagationResult.Implied>(native.implyIntAtLeast(v, 1))
        val peer = object : SearchComponent {
            override fun initialize(context: SearchContext): ComponentResult =
                context.publish(SearchDecision.IntAtLeast(4, 1))
        }
        val cp = CpSearchComponent(native)
        val shared = SearchSession(listOf(peer, cp), atoms = SearchAtomRegistry(0))
        assertIs<ComponentResult.Consistent>(shared.initialize())
        assertIs<ComponentResult.Conflict>(shared.push(SearchDecision.IntAtLeast(3, 1)))
        val learned = assertIs<ConflictAnalyzer.AnalysisResult.LearnedIntegerRow>(cp.lastConflict?.learnedClause)
        assertTrue(learned.row.hasBoundCover)
        assertEquals(0, learned.row.retainedSources)

        assertFalse(cp.prefersNativeConflictAnalysis)

        assertEquals(1L, native.learnedLifecycle()["learningIntegerOriginalCoverProduced"])
        assertEquals(0L, native.learnedLifecycle()["learningIntegerOriginalCoverAdmitted"])
        assertEquals(0L, native.learnedLifecycle()["learningIntegerOriginalCoverConsumed"])
    }

    @Test
    fun `original parents should derive a mapped implication without a retained source`() {
        val problem = Problem(0, 35, Array(35) { IntDomain(0, if (it < 4) 1L else 2L) }, Array(3) { index ->
            if (index == 2) Linear(intArrayOf(1, 1, 1), intArrayOf(0, 1, 2), LinearOp.LE, 1)
            else Linear(IntArray(33) { if (it == 1) -1 else 1 },
                intArrayOf(3, index) + IntArray(31) { it + 4 }, LinearOp.LE, 0)
        })
        val native = PropagationSession(PropagationProblem(problem), pbLearning = false, attribution = true)
            .apply { enableIntegerRowLearning() }
        val cp = CpSearchComponent(native, IntArray(35) { it + 7 })
        val shared = SearchSession(listOf(cp), atoms = SearchAtomRegistry(0))
        assertIs<ComponentResult.Consistent>(shared.initialize())
        assertTrue(native.explanationState.learned.integerRows.orEmpty().isEmpty())

        assertIs<ComponentResult.Conflict>(shared.push(SearchDecision.IntAtLeast(10, 1)))
        val learned = assertIs<ConflictAnalyzer.AnalysisResult.LearnedIntegerRow>(cp.lastConflict?.learnedClause)
        val backjump = assertIs<SearchConflictResolution.Backjump>(cp.resolveConflict(shared)).conflict
        shared.popTo(backjump.decisionLevel)
        assertIs<SearchLearnedConflictResult.Resume>(backjump.apply(shared))

        assertContentEquals(intArrayOf(2, 3), learned.row.variables)
        assertContentEquals(longArrayOf(1, 2), learned.row.coefficients)
        assertEquals(1L, learned.row.bound)
        assertEquals(0, learned.row.retainedSources)
        assertTrue(learned.row.hasBoundImplication)
        assertEquals(0L, shared.intUpperBound(10))
        val snapshot = native.learnedLifecycle()
        assertEquals(0L, snapshot["learningIntegerChainedSources"] ?: 0L)
        assertEquals(2L, snapshot["learningIntegerBoundImplicationSources"])
        assertEquals(1L, snapshot["learningIntegerBoundImplicationProduced"])
        assertEquals(1L, snapshot["learningIntegerBoundImplicationAdmitted"])
        assertEquals(1L, snapshot["learningIntegerBoundImplicationConsumed"])
        assertEquals(2L, snapshot["learningIntegerOriginalImplicationSources"])
        assertEquals(1L, snapshot["learningIntegerOriginalImplicationProduced"])
        assertEquals(1L, snapshot["learningIntegerOriginalImplicationAdmitted"])
        assertEquals(1L, snapshot["learningIntegerOriginalImplicationConsumed"])
        assertFalse(learned.row.hasBoundCover)
        assertEquals(0L, snapshot["learningIntegerOriginalCoverSources"])
        assertEquals(0L, snapshot["learningIntegerOriginalCoverProduced"])
        assertEquals(0L, snapshot["learningIntegerOriginalCoverAdmitted"])
        assertEquals(0L, snapshot["learningIntegerOriginalCoverConsumed"])
        native.releaseRetainedState()
        assertEquals(snapshot, native.learnedLifecycle())
    }

    @Test
    fun `an unavailable root condition should withhold an original implication child`() {
        val problem = Problem(0, 35, Array(35) { IntDomain(0, 1) }, Array(3) { index ->
            if (index == 2) Linear(intArrayOf(1, 1, 1), intArrayOf(0, 1, 2), LinearOp.LE, 1)
            else Linear(IntArray(33) { if (it == 2) -1 else 1 },
                intArrayOf(3, 4, index) + IntArray(30) { it + 5 }, LinearOp.LE, 1)
        })
        val native = PropagationSession(PropagationProblem(problem), pbLearning = false, attribution = true)
            .apply { enableIntegerRowLearning() }
        val peer = object : SearchComponent {
            override fun initialize(context: SearchContext): ComponentResult =
                context.publish(SearchDecision.IntAtLeast(4, 1))
        }
        val cp = CpSearchComponent(native)
        val shared = SearchSession(listOf(peer, cp), atoms = SearchAtomRegistry(0))
        assertIs<ComponentResult.Consistent>(shared.initialize())
        assertIs<ComponentResult.Conflict>(shared.push(SearchDecision.IntAtLeast(3, 1)))
        val learned = assertIs<ConflictAnalyzer.AnalysisResult.LearnedIntegerRow>(cp.lastConflict?.learnedClause)
        assertTrue(learned.row.hasBoundImplication)
        assertEquals(0, learned.row.retainedSources)

        assertFalse(cp.prefersNativeConflictAnalysis)

        assertEquals(0L, native.learnedLifecycle()["learningIntegerBoundImplicationAdmitted"])
    }

    @Test
    fun `an unavailable mapped endpoint should withhold implication child admission`() {
        val problem = Problem(0, 35, Array(35) { IntDomain(0, 1) }, Array(2) { pivot ->
            Linear(IntArray(33) { if (it == 1) -1 else 1 },
                intArrayOf(3, pivot) + IntArray(31) { it + 4 }, LinearOp.LE, 0)
        })
        val native = PropagationSession(PropagationProblem(problem), pbLearning = false, attribution = true)
            .apply { enableIntegerRowLearning() }
        native.addLearnedIntegerRow(assertNotNull(IntegerConflictRow.normalized(
            mapOf(0 to 1L, 1 to 1L, 2 to 1L), 1L, Lit.NONE)), 1)
        val peer = object : SearchComponent {
            override fun initialize(context: SearchContext): ComponentResult =
                context.publish(SearchDecision.IntAtMost(2, 0))
        }
        val cp = CpSearchComponent(native)
        val shared = SearchSession(listOf(peer, cp), atoms = SearchAtomRegistry(0))
        assertIs<ComponentResult.Consistent>(shared.initialize())
        assertIs<ComponentResult.Conflict>(shared.push(SearchDecision.IntAtLeast(3, 1)))
        assertTrue(assertIs<ConflictAnalyzer.AnalysisResult.LearnedIntegerRow>(
            cp.lastConflict?.learnedClause).row.hasBoundImplication)

        assertFalse(cp.prefersNativeConflictAnalysis)

        val fallback = assertIs<SearchConflictResolution.Backjump>(cp.resolveConflict(shared)).conflict
        shared.popTo(fallback.decisionLevel)
        fallback.apply(shared)
        assertTrue(native.explanationState.learned.integerRows.orEmpty().none { it.row.hasBoundImplication })
        assertEquals(0L, native.learnedLifecycle()["learningIntegerBoundImplicationAdmitted"])
    }

    @Test
    fun `complete wide source implications should derive a mapped owned assertion`() {
        for ((exact, paddingUpper) in listOf(false to 1L, true to 1L, false to 2L, true to 2L)) {
            val problem = Problem(0, 35,
                Array(35) { IntDomain(0, if (it < 4) 1L else paddingUpper) }, Array(2) { pivot ->
                    val vars = intArrayOf(3, pivot) + IntArray(31) { it + 4 }
                    val coefficients = IntArray(33) { if (it == 1) -1 else 1 }
                    if (exact) ExactLinear.of(IntArray(33) { Term.ofIntVar(vars[it]) },
                        coefficients.map { BigFraction.ofLong(it.toLong()) }, LinearOp.LE, BigFraction.ZERO)
                    else Linear(coefficients, vars, LinearOp.LE, 0)
                })
            val native = PropagationSession(PropagationProblem(problem), pbLearning = false, attribution = true)
                .apply { enableIntegerRowLearning() }
            assertNull(GuardedIntegerSource(native.explanationState).source(0,
                sourceWork = IntegerSourceWork(native.explanationState)))
            native.addLearnedIntegerRow(assertNotNull(IntegerConflictRow.normalized(
                mapOf(0 to 1L, 1 to 1L, 2 to 1L), 1L, Lit.NONE)), 1)
            val cp = CpSearchComponent(native, IntArray(35) { it + 7 })
            val shared = SearchSession(listOf(cp), atoms = SearchAtomRegistry(0))
            assertIs<ComponentResult.Consistent>(shared.initialize())

            assertIs<ComponentResult.Conflict>(shared.push(SearchDecision.IntAtLeast(10, 1)))
            val learned = assertIs<ConflictAnalyzer.AnalysisResult.LearnedIntegerRow>(cp.lastConflict?.learnedClause)
            val backjump = assertIs<SearchConflictResolution.Backjump>(cp.resolveConflict(shared)).conflict
            shared.popTo(backjump.decisionLevel)
            assertIs<SearchLearnedConflictResult.Resume>(backjump.apply(shared))

            assertContentEquals(intArrayOf(2, 3), learned.row.variables)
            assertContentEquals(longArrayOf(1, 2), learned.row.coefficients)
            assertEquals(1L, learned.row.bound)
            assertTrue(learned.row.hasBoundImplication)
            assertEquals(0L, shared.intUpperBound(10))
            val snapshot = native.learnedLifecycle()
            assertEquals(2L, snapshot["learningIntegerBoundImplicationSources"])
            assertEquals(1L, snapshot["learningIntegerBoundImplicationProduced"])
            assertEquals(1L, snapshot["learningIntegerBoundImplicationAdmitted"])
            assertEquals(1L, snapshot["learningIntegerBoundImplicationConsumed"])
            assertEquals(0L, snapshot["learningIntegerOriginalImplicationSources"])
            assertEquals(0L, snapshot["learningIntegerOriginalImplicationProduced"])
            assertEquals(0L, snapshot["learningIntegerOriginalImplicationAdmitted"])
            assertEquals(0L, snapshot["learningIntegerOriginalImplicationConsumed"])
            native.releaseRetainedState()
            assertEquals(snapshot, native.learnedLifecycle())
        }
    }

    @Test
    fun `unchanged declared endpoints should supply nonbinary baseline activity`() {
        for ((coefficient, domain, bound) in listOf(
            Triple(1, IntDomain(5, 7), 5),
            Triple(-1, IntDomain(-7, -5), 5),
            Triple(2, IntDomain(2, 2), 4),
            Triple(-2, IntDomain(3, 3), -6),
        )) {
            val native = PropagationSession(Problem(0, 3,
                arrayOf(IntDomain(0, 1), IntDomain(0, 1), domain), arrayOf(
                    Linear(intArrayOf(1, -1, coefficient), intArrayOf(1, 0, 2), LinearOp.LE, bound),
                )), pbLearning = false)
            assertIs<PropagationResult.Implied>(native.pinIntAtLeast(1, 1))
            val state = native.explanationState
            val marker = assertNotNull(state.intMinAntecedents[0])

            val row = assertNotNull(
                IntegerBoundImplicationSource(state, IntegerSourceWork(state)).source(0, true, marker))

            assertContentEquals(intArrayOf(0, 1), row.variables)
            assertContentEquals(longArrayOf(-1, 1), row.coefficients)
            assertEquals(0L, row.bound)
            for (x in 0L..1L) for (e in 0L..1L) for (z in domain.min..domain.max) {
                if (e - x + coefficient * z <= bound) assertTrue(e - x <= row.bound)
            }
        }
    }

    @Test
    fun `an open minimizing side should not supply an invented baseline`() {
        val native = PropagationSession(Problem(0, 3,
            arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 2)), arrayOf(
                Linear(intArrayOf(1, -1, 1), intArrayOf(1, 0, 2), LinearOp.LE, 0),
            ), openIntLo = booleanArrayOf(false, false, true)), pbLearning = false)
        assertIs<PropagationResult.Implied>(native.pinIntAtLeast(1, 1))
        val state = native.explanationState
        val marker = assertNotNull(state.intMinAntecedents[0])

        val row = IntegerBoundImplicationSource(state, IntegerSourceWork(state)).source(0, true, marker)

        assertNull(row)
    }

    @Test
    fun `an open unused side should preserve a declared minimizing endpoint`() {
        val native = PropagationSession(Problem(0, 3,
            arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 2)), arrayOf(
                Linear(intArrayOf(1, -1, 1), intArrayOf(1, 0, 2), LinearOp.LE, 0),
            ), openIntHi = booleanArrayOf(false, false, true)), pbLearning = false)
        assertIs<PropagationResult.Implied>(native.pinIntAtLeast(1, 1))
        val state = native.explanationState
        val marker = assertNotNull(state.intMinAntecedents[0])

        val row = assertNotNull(
            IntegerBoundImplicationSource(state, IntegerSourceWork(state)).source(0, true, marker))

        assertContentEquals(intArrayOf(0, 1), row.variables)
        assertContentEquals(longArrayOf(-1, 1), row.coefficients)
        assertEquals(0L, row.bound)
    }

    @Test
    fun `a binary live endpoint should not replace a nonbinary declared conclusion`() {
        val native = PropagationSession(Problem(0, 2,
            arrayOf(IntDomain(0, 2), IntDomain(0, 1)), arrayOf(
                Linear(intArrayOf(1, -1), intArrayOf(1, 0), LinearOp.LE, 0),
            )), pbLearning = false)
        assertIs<PropagationResult.Implied>(native.implyIntAtMost(0, 1))
        assertIs<PropagationResult.Implied>(native.pinIntAtLeast(1, 1))
        val state = native.explanationState
        val marker = assertNotNull(state.intMinAntecedents[0])

        val row = IntegerBoundImplicationSource(state, IntegerSourceWork(state)).source(0, true, marker)

        assertNull(row)
    }

    @Test
    fun `root conditions should remain in the globally valid implication`() {
        val native = PropagationSession(Problem(0, 3, Array(3) { IntDomain(0, 1) }, arrayOf(
            Linear(intArrayOf(1, 1, -1), intArrayOf(1, 2, 0), LinearOp.LE, 1),
        )), pbLearning = false)
        assertIs<PropagationResult.Implied>(native.implyIntAtLeast(2, 1))
        assertIs<PropagationResult.Implied>(native.pinIntAtLeast(1, 1))
        val state = native.explanationState
        val marker = assertNotNull(state.intMinAntecedents[0])

        val row = assertNotNull(IntegerBoundImplicationSource(state, IntegerSourceWork(state)).source(0, true, marker))

        assertContentEquals(intArrayOf(0, 1, 2), row.variables)
        assertContentEquals(longArrayOf(-1, 1, 1), row.coefficients)
        assertEquals(1L, row.bound)
        for (assignment in 0..7) {
            val values = LongArray(3) { (assignment shr it and 1).toLong() }
            if (values[1] + values[2] - values[0] > 1L) continue
            val lhs = row.variables.indices.sumOf { row.coefficients[it] * values[row.variables[it]] }
            assertTrue(lhs <= row.bound)
        }
    }

    @Test
    fun `later bounds should not enter a historical unit implication`() {
        val native = PropagationSession(Problem(0, 3, Array(3) { IntDomain(0, 1) }, arrayOf(
            Linear(intArrayOf(1, 1, -2), intArrayOf(1, 2, 0), LinearOp.LE, 0),
        )), pbLearning = false)
        assertIs<PropagationResult.Implied>(native.pinIntAtLeast(1, 1))
        val state = native.explanationState
        val marker = assertNotNull(state.intMinAntecedents[0])
        assertIs<PropagationResult.Implied>(native.pinIntAtLeast(2, 1))

        val row = assertNotNull(IntegerBoundImplicationSource(state, IntegerSourceWork(state)).source(0, true, marker))

        assertContentEquals(intArrayOf(0, 1), row.variables)
        assertContentEquals(longArrayOf(-1, 1), row.coefficients)
        assertEquals(0L, row.bound)
    }

    @Test
    fun `either inequality orientation should preserve a binary upper implication`() {
        for (op in listOf(LinearOp.LE, LinearOp.GE)) {
            val coefficients = if (op == LinearOp.LE) intArrayOf(1, 1) else intArrayOf(-1, -1)
            val bound = if (op == LinearOp.LE) 1 else -1
            val native = PropagationSession(Problem(0, 2, Array(2) { IntDomain(0, 1) }, arrayOf(
                Linear(coefficients, intArrayOf(0, 1), op, bound),
            )), pbLearning = false)
            assertIs<PropagationResult.Implied>(native.pinIntAtLeast(1, 1))
            val state = native.explanationState
            val marker = assertNotNull(state.intMaxAntecedents[0])

            val row = assertNotNull(
                IntegerBoundImplicationSource(state, IntegerSourceWork(state)).source(0, false, marker))

            assertContentEquals(intArrayOf(0, 1), row.variables)
            assertContentEquals(longArrayOf(1, 1), row.coefficients)
            assertEquals(1L, row.bound)
        }
    }

    @Test
    fun `a live binary projection should not replace nonbinary declared bounds`() {
        val native = PropagationSession(Problem(0, 3,
            arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 2)), arrayOf(
                Linear(intArrayOf(1, -2, 1), intArrayOf(1, 0, 2), LinearOp.LE, 1),
            )), pbLearning = false)
        assertIs<PropagationResult.Implied>(native.implyIntAtLeast(2, 1))
        assertIs<PropagationResult.Implied>(native.implyIntAtMost(2, 1))
        assertIs<PropagationResult.Implied>(native.pinIntAtLeast(1, 1))
        val state = native.explanationState
        val marker = assertNotNull(state.intMinAntecedents[0])

        val row = IntegerBoundImplicationSource(state, IntegerSourceWork(state)).source(0, true, marker)

        assertNull(row)
    }

    @Test
    fun `a guarded source should withhold the entire implication`() {
        val native = PropagationSession(Problem(1, 2, Array(2) { IntDomain(0, 1) }, arrayOf(
            ReifiedLinear(0, intArrayOf(1, -1), intArrayOf(1, 0), LinearOp.LE, 0),
        )), pbLearning = false)
        assertIs<PropagationResult.Implied>(native.pinBool(0, true))
        assertIs<PropagationResult.Implied>(native.pinIntAtLeast(1, 1))
        val state = native.explanationState
        val marker = assertNotNull(state.intMinAntecedents[0])

        val row = IntegerBoundImplicationSource(state, IntegerSourceWork(state)).source(0, true, marker)

        assertNull(row)
    }

    @Test
    fun `a changed projection should withhold the implication`() {
        val native = PropagationSession(Problem(0, 2, Array(2) { IntDomain(0, 1) }, arrayOf(
            Linear(intArrayOf(1, -1), intArrayOf(1, 0), LinearOp.LE, 0),
        )), pbLearning = false)
        assertIs<PropagationResult.Implied>(native.pinIntAtLeast(1, 1))
        val state = native.explanationState
        val marker = assertNotNull(state.intMinAntecedents[0])
        @Suppress("UNCHECKED_CAST")
        val projections = state.baseFactors as Array<Propagator>
        projections[0] = LinearPropagator(intArrayOf(), intArrayOf(1, 0),
            longArrayOf(2, -1), intArrayOf(1, 0), LinearOp.LE, 0)

        val row = IntegerBoundImplicationSource(state, IntegerSourceWork(state)).source(0, true, marker)

        assertNull(row)
    }

    @Test
    fun `copied extended and retired endpoint markers should withhold the implication`() {
        for (kind in 0..2) {
            val native = PropagationSession(Problem(0, 2, Array(2) { IntDomain(0, 1) }, arrayOf(
                Linear(intArrayOf(1, -1), intArrayOf(1, 0), LinearOp.LE, 0),
            )), pbLearning = false)
            assertIs<PropagationResult.Implied>(native.pinIntAtLeast(1, 1))
            val state = native.explanationState
            val original = assertNotNull(state.intMinAntecedents[0])
            val marker = when (kind) {
                0 -> original.copyOf()
                1 -> assertNotNull(extendReason(original, intArrayOf(Lit.make(state.atomVarGe(1, 1), false))))
                    .also { state.intMinAntecedents[0] = it }
                else -> original.also { native.popToLevel(0) }
            }

            val row = IntegerBoundImplicationSource(state, IntegerSourceWork(state)).source(0, true, marker)

            assertNull(row)
        }
    }

    @Test
    fun `a marker after its unit move should withhold the implication`() {
        val native = PropagationSession(Problem(0, 2, Array(2) { IntDomain(0, 1) }, arrayOf(
            Linear(intArrayOf(1, -1), intArrayOf(1, 0), LinearOp.LE, 0),
        )), pbLearning = false)
        assertIs<PropagationResult.Implied>(native.pinIntAtLeast(1, 1))
        val state = native.explanationState
        val marker = assertNotNull(state.intMinAntecedents[0])
        marker[2] = state.undo.size

        val row = IntegerBoundImplicationSource(state, IntegerSourceWork(state)).source(0, true, marker)

        assertNull(row)
    }

    @Test
    fun `an overwide complete condition set should withhold every subset`() {
        val native = PropagationSession(Problem(0, 17, Array(17) { IntDomain(0, 1) }, arrayOf(
            Linear(IntArray(17) { if (it == 0) -1 else 1 }, IntArray(17) { it }, LinearOp.LE, 15),
        )), pbLearning = false)
        for (v in 1..16) assertIs<PropagationResult.Implied>(native.pinIntAtLeast(v, 1))
        val state = native.explanationState
        val marker = assertNotNull(state.intMinAntecedents[0])

        val row = IntegerBoundImplicationSource(state, IntegerSourceWork(state)).source(0, true, marker)

        assertNull(row)
    }

    @Test
    fun `source exhaustion should stop implication reconstruction`() {
        val native = PropagationSession(Problem(0, 2, Array(2) { IntDomain(0, 1) }, arrayOf(
            Linear(intArrayOf(1, -1), intArrayOf(1, 0), LinearOp.LE, 0),
        )), pbLearning = false)
        assertIs<PropagationResult.Implied>(native.pinIntAtLeast(1, 1))
        val state = native.explanationState
        val marker = assertNotNull(state.intMinAntecedents[0])

        assertFailsWith<IntegerSourceWorkLimit> {
            IntegerBoundImplicationSource(state, IntegerSourceWork(state, remaining = 0)).source(0, true, marker)
        }
    }

    @Test
    fun `overflowing historical activity should not supply an implication`() {
        val native = PropagationState(Problem(0, 3, Array(3) { IntDomain(0, 1) }, arrayOf(
            ExactLinear.of(IntArray(3) { Term.ofIntVar(it) },
                listOf(BigFraction.MINUS_ONE, BigFraction.ofLong(Long.MAX_VALUE), BigFraction.ofLong(Long.MAX_VALUE)),
                LinearOp.LE, BigFraction.ofLong(-3)),
        )), Assumptions.None)
        native.undoLogging = true
        @Suppress("UNCHECKED_CAST")
        val projections = native.baseFactors as Array<Propagator>
        projections[0] = LinearPropagator(intArrayOf(), IntArray(3) { it },
            longArrayOf(-1, Long.MAX_VALUE, Long.MAX_VALUE), IntArray(3) { it }, LinearOp.LE, -3)
        assertTrue(native.tightenIntMin(1, 1))
        assertTrue(native.tightenIntMin(2, 1))
        native.currentFactor = 0
        val marker = native.lazyReasonSlots(6)
        marker[LAZY_PAYLOAD] = 0
        marker[LAZY_PAYLOAD + 1] = 1
        assertTrue(native.tightenIntMin(0, 1, marker))

        val row = try {
            IntegerBoundImplicationSource(native, IntegerSourceWork(native)).source(0, true, marker)
        } catch (_: CheckedLongOverflowException) {
            null
        }

        assertNull(row)
    }
}
