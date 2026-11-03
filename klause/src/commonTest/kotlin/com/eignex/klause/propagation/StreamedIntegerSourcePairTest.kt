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
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamedIntegerSourcePairTest {
    @Test
    fun `wide originals should cancel before compact row materialization`() {
        for ((exact, ge) in listOf(false to false, false to true, true to false, true to true)) {
            val problem = Problem(0, 35, Array(35) { IntDomain(0, 1) }, Array(2) { index ->
                val vars = if (index == 0) intArrayOf(0, 1, 2) + IntArray(31) { it + 4 }
                    else intArrayOf(0, 3) + IntArray(31) { it + 4 }
                val coefficients = IntArray(vars.size) { if (index == 0 || it == 1) 1 else -1 }
                val sign = if (index == 1 && ge) -1 else 1
                val bound = if (index == 0) 1 else 0
                val op = if (index == 1 && ge) LinearOp.GE else LinearOp.LE
                if (exact) ExactLinear.of(IntArray(vars.size) { Term.ofIntVar(vars[it]) },
                    coefficients.map { BigFraction.ofLong((it * sign).toLong()) },
                    op, BigFraction.ofLong(bound.toLong()))
                else Linear(IntArray(vars.size) { coefficients[it] * sign }, vars, op, bound)
            })
            val state = PropagationSession(problem, pbLearning = false).explanationState
            assertNull(GuardedIntegerSource(state).source(0, sourceWork = IntegerSourceWork(state)))
            assertNull(GuardedIntegerSource(state).source(1, sourceWork = IntegerSourceWork(state)))
            var trials = 0
            val source = StreamedIntegerSourcePair(state, IntegerSourceWork(state)) { trials++; true }

            val row = assertNotNull(source.combine(0, true, 1, !exact || !ge, 0))

            assertContentEquals(intArrayOf(1, 2, 3), row.variables)
            assertContentEquals(longArrayOf(1, 1, 1), row.coefficients)
            assertEquals(1L, row.bound)
            assertEquals(1, trials)
            for (x in 0L..1L) for (y in 0L..1L) for (z in 0L..1L) for (e in 0L..1L) {
                for (sum in 0L..31L) {
                    if (x + y + z + sum <= 1L && e - x - sum <= 0L) assertTrue(e + y + z <= row.bound)
                }
            }
        }
    }

    @Test
    fun `paired seeds should assert an additional bound and reuse it after backtracking`() {
        for (learning in listOf(false, true)) {
            val problem = Problem(0, 35, Array(35) { IntDomain(0, 1) }, arrayOf(
                Linear(IntArray(34) { 1 }, intArrayOf(0, 1, 2) + IntArray(31) { it + 4 }, LinearOp.LE, 32),
                Linear(IntArray(33) { if (it == 1) 1 else -1 },
                    intArrayOf(0, 3) + IntArray(31) { it + 4 }, LinearOp.LE, -31),
            ))
            val native = PropagationSession(PropagationProblem(problem), pbLearning = false, attribution = true)
            if (learning) native.enableIntegerRowLearning()
            val cp = CpSearchComponent(native, IntArray(35) { it + 7 })
            val shared = SearchSession(listOf(cp), atoms = SearchAtomRegistry(0))
            assertIs<ComponentResult.Consistent>(shared.initialize())
            assertIs<ComponentResult.Consistent>(shared.push(SearchDecision.IntAtLeast(8, 1)))
            assertEquals(1L, shared.intUpperBound(9))

            assertIs<ComponentResult.Conflict>(shared.push(SearchDecision.IntAtLeast(10, 1)))
            if (learning) {
                val learned =
                    assertIs<ConflictAnalyzer.AnalysisResult.LearnedIntegerRow>(cp.lastConflict?.learnedClause)
                assertContentEquals(intArrayOf(1, 2, 3), learned.row.variables)
                assertContentEquals(longArrayOf(1, 1, 1), learned.row.coefficients)
                assertEquals(1L, learned.row.bound)
                assertTrue(learned.row.hasStreamedPair)
            }
            val backjump = assertIs<SearchConflictResolution.Backjump>(cp.resolveConflict(shared)).conflict
            shared.popTo(backjump.decisionLevel)
            assertIs<SearchLearnedConflictResult.Resume>(backjump.apply(shared))

            assertEquals(0L, shared.intUpperBound(10))
            assertEquals(if (learning) 0L else 1L, shared.intUpperBound(9))
            shared.popTo(0)
            assertIs<ComponentResult.Consistent>(shared.push(SearchDecision.IntAtLeast(8, 1)))
            assertEquals(if (learning) 0L else 1L, shared.intUpperBound(9))
            val snapshot = native.learnedLifecycle()
            assertEquals(if (learning) 1L else 0L, snapshot["learningIntegerOriginalPairSources"] ?: 0L)
            assertEquals(if (learning) 1L else 0L, snapshot["learningIntegerOriginalPairProduced"] ?: 0L)
            assertEquals(if (learning) 1L else 0L, snapshot["learningIntegerOriginalPairAdmitted"] ?: 0L)
            assertEquals(if (learning) 1L else 0L, snapshot["learningIntegerOriginalPairConsumed"] ?: 0L)
            native.releaseRetainedState()
            assertEquals(snapshot, native.learnedLifecycle())
        }
    }

    @Test
    fun `an unavailable required root premise should withhold a paired seed`() {
        val problem = Problem(0, 36, Array(36) { IntDomain(0, 1) }, arrayOf(
            Linear(IntArray(34) { 1 }, intArrayOf(0, 1, 2) + IntArray(31) { it + 5 }, LinearOp.LE, 32),
            Linear(IntArray(34) { if (it == 1 || it == 2) 1 else -1 },
                intArrayOf(0, 3, 4) + IntArray(31) { it + 5 }, LinearOp.LE, -30),
        ))
        val native = PropagationSession(PropagationProblem(problem), pbLearning = false, attribution = true)
            .apply { enableIntegerRowLearning() }
        val peer = object : SearchComponent {
            override fun initialize(context: SearchContext): ComponentResult =
                context.publish(SearchDecision.IntAtLeast(4, 1))
        }
        val cp = CpSearchComponent(native)
        val shared = SearchSession(listOf(peer, cp), atoms = SearchAtomRegistry(0))
        assertIs<ComponentResult.Consistent>(shared.initialize())
        assertIs<ComponentResult.Consistent>(shared.push(SearchDecision.IntAtLeast(1, 1)))
        assertIs<ComponentResult.Conflict>(shared.push(SearchDecision.IntAtLeast(3, 1)))
        val learned = assertIs<ConflictAnalyzer.AnalysisResult.LearnedIntegerRow>(cp.lastConflict?.learnedClause)
        assertTrue(learned.row.hasStreamedPair)

        assertFalse(cp.prefersNativeConflictAnalysis)

        assertEquals(1L, native.learnedLifecycle()["learningIntegerOriginalPairProduced"])
        assertEquals(0L, native.learnedLifecycle()["learningIntegerOriginalPairAdmitted"])
        assertEquals(0L, native.learnedLifecycle()["learningIntegerOriginalPairConsumed"])
    }

    @Test
    fun `a descendant should not inherit current pair introduction from its retained parent`() {
        val problem = Problem(0, 35, Array(35) { IntDomain(0, 1) }, Array(2) { pivot ->
            Linear(IntArray(33) { if (it == 1) -1 else 1 },
                intArrayOf(3, pivot) + IntArray(31) { it + 4 }, LinearOp.LE, 0)
        })
        val native = PropagationSession(PropagationProblem(problem), pbLearning = false, attribution = true)
            .apply { enableIntegerRowLearning() }
        native.addLearnedIntegerRow(assertNotNull(IntegerConflictRow.normalized(
            mapOf(0 to 1L, 1 to 1L, 2 to 1L), 1L, Lit.NONE)).withStreamedPairOrigin(true), 1)
        val cp = CpSearchComponent(native)
        val shared = SearchSession(listOf(cp), atoms = SearchAtomRegistry(0))
        assertIs<ComponentResult.Consistent>(shared.initialize())
        val admitted = native.learnedLifecycle()["learningIntegerOriginalPairAdmitted"]
        assertIs<ComponentResult.Conflict>(shared.push(SearchDecision.IntAtLeast(3, 1)))

        val learned = assertIs<ConflictAnalyzer.AnalysisResult.LearnedIntegerRow>(cp.lastConflict?.learnedClause)

        assertTrue(learned.row.retainedSources > 0)
        assertFalse(learned.row.hasStreamedPair)
        val backjump = assertIs<SearchConflictResolution.Backjump>(cp.resolveConflict(shared)).conflict
        shared.popTo(backjump.decisionLevel)
        assertIs<SearchLearnedConflictResult.Resume>(backjump.apply(shared))
        assertEquals(0L, native.learnedLifecycle()["learningIntegerOriginalPairProduced"])
        assertEquals(admitted, native.learnedLifecycle()["learningIntegerOriginalPairAdmitted"])
    }

    @Test
    fun `guarded originals should not supply an unconditional pair`() {
        val problem = Problem(1, 35, Array(35) { IntDomain(0, 1) }, arrayOf(
            Linear(IntArray(34) { 1 }, intArrayOf(0, 1, 2) + IntArray(31) { it + 4 }, LinearOp.LE, 1),
            ReifiedLinear(0, IntArray(33) { if (it == 1) 1 else -1 },
                intArrayOf(0, 3) + IntArray(31) { it + 4 }, LinearOp.LE, 0),
        ))
        val native = PropagationSession(problem, pbLearning = false)
        assertIs<PropagationResult.Implied>(native.pinBool(0, true))
        val state = native.explanationState
        val source = StreamedIntegerSourcePair(state, IntegerSourceWork(state)) { true }

        val row = source.combine(0, true, 1, true, 0)

        assertNull(row)
    }

    @Test
    fun `altered projections should not supply a streamed pair`() {
        val problem = Problem(0, 35, Array(35) { IntDomain(0, 1) }, arrayOf(
            Linear(IntArray(34) { 1 }, intArrayOf(0, 1, 2) + IntArray(31) { it + 4 }, LinearOp.LE, 1),
            Linear(IntArray(33) { if (it == 1) 1 else -1 },
                intArrayOf(0, 3) + IntArray(31) { it + 4 }, LinearOp.LE, 0),
        ))
        val state = PropagationSession(problem, pbLearning = false).explanationState
        val original = state.factorAt(0) as LinearPropagator
        @Suppress("UNCHECKED_CAST")
        val projections = state.baseFactors as Array<Propagator>
        projections[0] = LinearPropagator(intArrayOf(), original.intVars, original.coeffs,
            original.vars, original.op, original.bound + 1)
        val source = StreamedIntegerSourcePair(state, IntegerSourceWork(state)) { true }

        val row = source.combine(0, true, 1, true, 0)

        assertNull(row)
    }

    @Test
    fun `unordered source coordinates should not be merged as ordered rows`() {
        val variables = intArrayOf(1, 0, 2) + IntArray(31) { it + 4 }
        val problem = Problem(0, 35, Array(35) { IntDomain(0, 1) }, arrayOf(
            Linear(IntArray(34) { 1 }, variables, LinearOp.LE, 1),
            Linear(IntArray(33) { if (it == 1) 1 else -1 },
                intArrayOf(0, 3) + IntArray(31) { it + 4 }, LinearOp.LE, 0),
        ))
        val state = PropagationSession(problem, pbLearning = false).explanationState
        val source = StreamedIntegerSourcePair(state, IntegerSourceWork(state)) { true }

        val row = source.combine(0, true, 1, true, 0)

        assertNull(row)
    }

    @Test
    fun `an overwide combined support should supply no partial row`() {
        val problem = Problem(0, 35, Array(35) { IntDomain(0, 1) }, arrayOf(
            Linear(IntArray(35) { 1 }, IntArray(35) { it }, LinearOp.LE, 35),
            Linear(IntArray(35) { if (it == 0) -1 else 1 }, IntArray(35) { it }, LinearOp.LE, 35),
        ))
        val state = PropagationSession(problem, pbLearning = false).explanationState
        var trials = 0
        val source = StreamedIntegerSourcePair(state, IntegerSourceWork(state)) { trials++; true }

        val row = source.combine(0, true, 1, true, 0)

        assertNull(row)
        assertEquals(1, trials)
    }

    @Test
    fun `overflowing combined bounds should not yield a row`() {
        val problem = Problem(0, 35, Array(35) { IntDomain(0, 0) }, arrayOf(
            Linear(LongArray(35) { 1L }, IntArray(35) { it }, LinearOp.LE, Long.MAX_VALUE),
            Linear(LongArray(35) { if (it == 1) 1L else if (it == 2) 2L else -1L },
                IntArray(35) { it }, LinearOp.LE, Long.MAX_VALUE),
        ))
        val state = PropagationSession(problem, pbLearning = false).explanationState
        var trials = 0
        val source = StreamedIntegerSourcePair(state, IntegerSourceWork(state)) { trials++; true }

        val row = source.combine(0, true, 1, true, 0)

        assertNull(row)
        assertEquals(1, trials)
    }

    @Test
    fun `source exhaustion should stop a streamed merge`() {
        val problem = Problem(0, 35, Array(35) { IntDomain(0, 1) }, arrayOf(
            Linear(IntArray(35) { 1 }, IntArray(35) { it }, LinearOp.LE, 35),
            Linear(IntArray(35) { if (it == 0) -1 else 1 }, IntArray(35) { it }, LinearOp.LE, 35),
        ))
        val state = PropagationSession(problem, pbLearning = false).explanationState
        val source = StreamedIntegerSourcePair(state, IntegerSourceWork(state, remaining = 0)) { true }

        assertFailsWith<IntegerSourceWorkLimit> { source.combine(0, true, 1, true, 0) }
    }

    @Test
    fun `a refused combination allowance should stop every later pair trial`() {
        val problem = Problem(0, 35, Array(35) { IntDomain(0, 1) }, arrayOf(
            Linear(IntArray(35) { 1 }, IntArray(35) { it }, LinearOp.LE, 35),
            Linear(IntArray(35) { if (it == 0) -1 else 1 }, IntArray(35) { it }, LinearOp.LE, 35),
        ))
        val state = PropagationSession(problem, pbLearning = false).explanationState
        var reservations = 0
        val source = StreamedIntegerSourcePair(state, IntegerSourceWork(state)) { reservations++; false }

        assertNull(source.combine(0, true, 1, true, 0))
        assertNull(source.combine(0, true, 1, true, 0))

        assertEquals(1, reservations)
    }

    @Test
    fun `all distinct pair trials should share the sixteen attempt ceiling`() {
        val problem = Problem(0, 35, Array(35) { IntDomain(0, 1) }, arrayOf(
            Linear(IntArray(35) { 1 }, IntArray(35) { it }, LinearOp.LE, 100000),
            Linear(IntArray(35) { if (it < 17) -(it + 1) else 1 }, IntArray(35) { it }, LinearOp.LE, 100000),
        ))
        val state = PropagationSession(problem, pbLearning = false).explanationState
        var reservations = 0
        val source = StreamedIntegerSourcePair(state, IntegerSourceWork(state)) { reservations++; true }

        for (variable in 0..16) assertNull(source.combine(0, true, 1, true, variable))

        assertEquals(16, reservations)
    }
}
