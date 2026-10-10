package com.eignex.klause.presolve.structural

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.BakeConfig
import com.eignex.klause.presolve.Presolve
import com.eignex.klause.presolve.PresolveShared.withPassDelta
import com.eignex.klause.presolve.SharedIntOccurrence
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.propagation.bake
import com.eignex.klause.propagation.propagate
import com.eignex.klause.propagation.propagatorProjection
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.bigIntOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DuplicateColumnsTest {

    @Test
    fun `duplicate declared columns reconstruct a feasible assignment`() {
        val source = Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 3)
        val factor = object : Factor by source, Propagator by source.propagatorProjection() {}
        val model = Problem(0, 2, Array(2) { IntDomain(0, 2) }, listOf(factor))

        checkRoundTrip("declared columns", model, expectMerged = true, expectSat = true)
    }

    private fun isFeasible(problem: Problem, sample: Sample): Boolean {
        var a = Assumptions.None
        for (v in 0 until problem.numBoolVars) a = a.withBool(v, sample.bools[v])
        for (v in 0 until problem.numIntVars) a = a.withInt(v, sample.ints[v])
        return problem.propagate(a) !is PropagationResult.Unsat
    }

    private fun verdictSat(problem: Problem): Boolean =
        BacktrackSolver(problem.bake()).solve(BacktrackParams()) is SolveResult.Sat

    private fun checkRoundTrip(name: String, original: Problem, expectMerged: Boolean, expectSat: Boolean) {
        val baked = original.bake()
        val delta = Presolve.mergeDuplicateColumns(baked)
        assertEquals(expectMerged, !delta.isEmpty, "$name: merge expectation wrong")
        val reduced = baked.withPassDelta(delta, BakeConfig.NONE)
        val reconstruct = delta.reconstruct ?: { it }
        assertEquals(expectSat, verdictSat(original), "$name: original verdict unexpected")
        assertEquals(verdictSat(original), verdictSat(reduced), "$name: verdict changed by merge")
        if (verdictSat(reduced)) {
            val solved = BacktrackSolver(reduced.bake()).solve(BacktrackParams())
            check(solved is SolveResult.Sat)
            val full = reconstruct(solved.assignment)
            assertTrue(isFeasible(original, full), "$name: reconstructed sample infeasible in original")
        }
    }

    /** `x + y >= 7` and `x + y + w <= 40` over `x` (0), `y` (1), `w` (2), with `x` and `y` duplicate columns. */
    private fun sourceModel(x: IntDomain, y: IntDomain, openLo: BooleanArray? = null, openHi: BooleanArray? = null) =
        Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(x, y, IntDomain(0, 3)),
            factors = listOf(
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 7),
                Linear(intArrayOf(1, 1, 1), intArrayOf(0, 1, 2), LinearOp.LE, 40),
            ),
            openIntLo = openLo,
            openIntHi = openHi,
        )

    @Test
    fun `the source form folds a column closed at zero into a representative open above`() {
        val model = sourceModel(IntDomain(0, 100), IntDomain(0, 5), openHi = booleanArrayOf(true, false, false))
        val delta = Presolve.mergeSourceDuplicateColumns(model, emptySet(), Cancellation.Never)
        val ints = longArrayOf(12, 0, 3)

        delta.rebuild.rebuildInto(BooleanArray(0), ints)

        assertTrue(delta.addedFactors.none { 1 in it.intVars }, "y's terms are absorbed by x")
        assertEquals(listOf(7L, 5L), ints.take(2), "y takes all it can of 12, x the rest")
    }

    @Test
    fun `the source form splits an aggregate of two free columns`() {
        val free = booleanArrayOf(true, true, false)
        val model = sourceModel(IntDomain(0, 9), IntDomain(0, 9), openLo = free, openHi = free)
        val delta = Presolve.mergeSourceDuplicateColumns(model, emptySet(), Cancellation.Never)
        val ints = arrayOf(bigIntOf(-3), BIG_ZERO, BIG_ZERO)

        delta.rebuild.rebuildInto(BooleanArray(0), ints)

        assertEquals(listOf(bigIntOf(-3), BIG_ZERO), ints.take(2))
    }

    @Test
    fun `the source form leaves closed columns whose aggregate would need a wider range`() {
        val model = sourceModel(IntDomain(0, 9), IntDomain(1, 5))

        val delta = Presolve.mergeSourceDuplicateColumns(model, emptySet(), Cancellation.Never)

        assertTrue(delta.isEmpty)
    }

    @Test
    fun `the source form does not fold a column declaring holes`() {
        val model = sourceModel(
            IntDomain(0, 100),
            IntDomain(0, 4).excludeValue(2),
            openHi = booleanArrayOf(true, false, false),
        )

        val delta = Presolve.mergeSourceDuplicateColumns(model, emptySet(), Cancellation.Never)

        assertTrue(delta.isEmpty)
    }

    @Test
    fun `does not merge columns that differ in one factor`() {
        // x (0) and y (1) share a row x + y + z <= 5 but x carries coefficient 2 there and y carries 1
        // — the columns differ, so they are not duplicates and nothing is merged.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3)),
            factors = listOf(
                Linear(intArrayOf(2, 1, 1), intArrayOf(0, 1, 2), LinearOp.LE, 5),
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
            ),
        )
        checkRoundTrip("differ-one-factor", problem, expectMerged = false, expectSat = true)
    }

    @Test
    fun `does not merge a variable in a non-linear factor`() {
        // x (0) and y (1) are duplicate columns in the linear rows, but x also sits in an AllDifferent,
        // which reads it value-wise rather than as a column coefficient — so it is ineligible.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3)),
            factors = listOf(
                Linear(intArrayOf(1, 1, 1), intArrayOf(0, 1, 2), LinearOp.LE, 4),
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
                AllDifferent(intArrayOf(0, 2), domainMin = 0, domainSize = 4),
            ),
        )
        checkRoundTrip("var-in-global", problem, expectMerged = false, expectSat = true)
    }

    /** The int-variable occurrence CSR the incremental session hands a pass: for each variable, the
     *  ascending factor indices mentioning it — matching what a fresh scan over `problem.factors` builds. */
    private fun sharedOcc(problem: Problem): SharedIntOccurrence {
        val n = problem.numIntVars
        val offsets = IntArray(n + 1)
        for (f in problem.factors) for (v in f.intVars) offsets[v + 1]++
        for (v in 0 until n) offsets[v + 1] += offsets[v]
        val cursor = offsets.copyOf()
        val flat = IntArray(offsets[n])
        problem.factors.forEachIndexed { fid, f -> for (v in f.intVars) flat[cursor[v]++] = fid }
        return SharedIntOccurrence(offsets, flat)
    }

    @Test
    fun `bails on a re-run when no touched column is eligible`() {
        // x (0) and y (1) are duplicate columns a full scan would merge. On a re-run whose only touched
        // variable is z (2) — read value-wise by an AllDifferent, so column-ineligible — the fast-bail
        // returns empty: an already-collapsed class only re-forms on a touched eligible column.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3)),
            factors = listOf(
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 4),
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
                AllDifferent(intArrayOf(2, 3), domainMin = 0, domainSize = 4),
            ),
        )
        assertTrue(!Presolve.mergeDuplicateColumns(problem.bake()).isEmpty, "a full scan merges the duplicate columns")
        val delta = DuplicateColumns.mergeDuplicateColumns(
            problem.bake(),
            sharedIntOcc = sharedOcc(problem),
            incrementalTouchedVars = intArrayOf(2),
        )
        assertTrue(delta.isEmpty, "no touched eligible column: the re-run bails")
    }

    @Test
    fun `re-runs the full merge when a touched column is eligible`() {
        // Same duplicate columns; the re-run's touched set includes the eligible column x (0), so the
        // fast-bail falls through to the full scan and produces the identical merge (same dropped rows).
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3)),
            factors = listOf(
                Linear(intArrayOf(1, 1, 1), intArrayOf(0, 1, 2), LinearOp.LE, 4),
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
            ),
        )
        val full = Presolve.mergeDuplicateColumns(problem.bake())
        val incremental = DuplicateColumns.mergeDuplicateColumns(
            problem.bake(),
            sharedIntOcc = sharedOcc(problem),
            incrementalTouchedVars = intArrayOf(0),
        )
        assertTrue(!incremental.isEmpty, "a touched eligible column falls through to the merge")
        assertEquals(
            full.droppedIndices.toList(),
            incremental.droppedIndices.toList(),
            "the incremental re-run drops the same rows as the full scan",
        )
    }

}
