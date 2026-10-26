package com.eignex.klause.meta.alns

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.bake
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.search.BranchingConflict
import com.eignex.klause.solver.search.VarRef
import com.eignex.klause.util.Cancellation
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConflictSupportedRemovalTest {
    private val problem = Problem(2, 2, Array(2) { IntDomain(0, 1) }, emptyList()).bake()
    private val center = Sample(BooleanArray(2), LongArray(2))
    private val objective = LinearObjective(intCoefficients = longArrayOf(1, 1))

    @Test
    fun `a cutoff at the pinned root should leave conflict guidance unavailable`() {
        val source = Problem(0, 2, Array(2) { IntDomain(0, 1) }, listOf(
            Linear(longArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 0L),
        )).bake()
        val incumbent = Sample(BooleanArray(0), longArrayOf(1, 1))
        val pins = Assumptions.None.withInt(0, 1)
        val guidance = ConflictSupportedRemoval(source, objective)
        guidance.begin(incumbent, pins, 2.0, Cancellation.Never)
        val params = guidance.observe(BacktrackParams(randomSeed = 3, maxDecisions = 16)).copy(
            assumptions = pins, objectiveBoundSupplier = { 2.0 },
        )

        val result = BacktrackSolver(source).minimize(objective, params)
        guidance.end()

        assertNull(result.assignment)
        assertEquals(ConflictGuidanceStatus.Unavailable, guidance.status)
    }

    @Test
    fun `observing finite conflicts should preserve the search verdict and node count`() {
        val clauses = buildList {
            for ((left, right) in listOf(0 to 1, 1 to 2, 2 to 0)) {
                add(Clause(intArrayOf(Lit.make(left, true), Lit.make(right, true))))
                add(Clause(intArrayOf(Lit.make(left, false), Lit.make(right, false))))
            }
        }
        val source = Problem(3, 0, emptyArray(), clauses).bake()
        val params = BacktrackParams(randomSeed = 3, maxDecisions = 32)
        val guidance = ConflictSupportedRemoval(source, objective)
        guidance.begin(Sample(BooleanArray(3), LongArray(0)), Assumptions.None, 0.0, Cancellation.Never)

        val control = BacktrackSolver(source).solve(params)
        val observed = BacktrackSolver(source).solve(guidance.observe(params))
        guidance.end()

        assertEquals(control::class, observed::class)
        assertEquals(control.stats.search.nodes, observed.stats.search.nodes)
        assertEquals(ConflictGuidanceStatus.Supported, guidance.status)
    }

    @Test
    fun `finite conflict attribution should rank both coordinate spaces`() {
        val guidance = ConflictSupportedRemoval(problem, objective)
        val observer = guidance.observe(BacktrackParams()).variableSelector.fresh()
        guidance.begin(center, Assumptions.None, 0.0, Cancellation.Never)
        observer.onConflict(VarRef.Bool(0), PropagationResult.Unsat(
            conflictBools = intArrayOf(1), conflictInts = intArrayOf(0),
        ))

        val freed = guidance.destroy(Random(3), problem, center, objective, 0.5)

        assertContentEquals(intArrayOf(1), freed.bools)
        assertContentEquals(intArrayOf(0), freed.ints)
        assertEquals(ConflictGuidanceStatus.Supported, guidance.status)
    }

    @Test
    fun `missing and empty attribution should retain distinct outcomes with random fallback`() {
        for (empty in listOf(false, true)) {
            val guidance = ConflictSupportedRemoval(problem, objective)
            val observer = guidance.observe(BacktrackParams()).variableSelector.fresh()
            guidance.begin(center, Assumptions.None, 0.0, Cancellation.Never)
            if (empty) observer.onConflict(null, BranchingConflict.Empty)

            val freed = guidance.destroy(Random(17), problem, center, objective, 0.5)

            assertEquals(DestroyOperator.Random.destroy(Random(17), problem, center, objective, 0.5), freed)
            assertEquals(
                if (empty) ConflictGuidanceStatus.EmptySupport else ConflictGuidanceStatus.Unavailable,
                guidance.status,
            )
        }
    }

    @Test
    fun `a different center should retire conditioned attribution`() {
        val guidance = ConflictSupportedRemoval(problem, objective)
        val observer = guidance.observe(BacktrackParams()).variableSelector.fresh()
        guidance.begin(center, Assumptions.None, -1.0, Cancellation.Never)
        observer.onConflict(null, BranchingConflict(intArrayOf(1)))

        guidance.destroy(Random(3), problem, Sample(BooleanArray(2), LongArray(2)), objective, 0.5)

        assertEquals(ConflictGuidanceStatus.Incompatible, guidance.status)
        assertNull(guidance.originPins)
        assertNull(guidance.originCutoff)
    }

    @Test
    fun `a reseed should discard every attribution from the previous repair`() {
        val guidance = ConflictSupportedRemoval(problem, objective)
        val observer = guidance.observe(BacktrackParams()).variableSelector.fresh()
        guidance.begin(center, Assumptions.None, 0.0, Cancellation.Never)
        observer.onConflict(null, BranchingConflict(intArrayOf(1)))

        guidance.begin(center, Assumptions.None, -1.0, Cancellation.Never)

        assertEquals(ConflictGuidanceStatus.Unavailable, guidance.status)
        assertEquals(-1.0, guidance.originCutoff)
    }

    @Test
    fun `capture limits should decline an oversized attribution as a whole`() {
        val guidance = ConflictSupportedRemoval(problem, objective, maxCoordinates = 4)
        val observer = guidance.observe(BacktrackParams()).variableSelector.fresh()
        guidance.begin(center, Assumptions.None, 0.0, Cancellation.Never)

        observer.onConflict(null, BranchingConflict(intArrayOf(0, 1, 0, 1, 0)))

        assertEquals(ConflictGuidanceStatus.Limited, guidance.status)
        assertNull(guidance.originPins)
    }

    @Test
    fun `caller pinned coordinates should contribute no ranking weight`() {
        val assumptions = Assumptions.None.withBool(1, false)
        val guidance = ConflictSupportedRemoval(problem, objective, assumptions)
        val observer = guidance.observe(BacktrackParams(assumptions = assumptions)).variableSelector.fresh()
        guidance.begin(center, assumptions, 0.0, Cancellation.Never)

        observer.onConflict(null, BranchingConflict(intArrayOf(1)))

        assertEquals(ConflictGuidanceStatus.EmptySupport, guidance.status)
    }

    @Test
    fun `close and cancellation should release conditioned capture`() {
        for (close in listOf(false, true)) {
            var stopped = false
            val guidance = ConflictSupportedRemoval(problem, objective)
            val observer = guidance.observe(BacktrackParams()).variableSelector.fresh()
            guidance.begin(center, Assumptions.None, 0.0, Cancellation { stopped })
            observer.onConflict(null, BranchingConflict(intArrayOf(1)))

            if (close) guidance.close() else {
                stopped = true
                observer.onConflict(null, BranchingConflict(intArrayOf(0)))
            }

            assertEquals(
                if (close) ConflictGuidanceStatus.Closed else ConflictGuidanceStatus.Cancelled,
                guidance.status,
            )
            assertNull(guidance.originPins)
        }
    }
}
