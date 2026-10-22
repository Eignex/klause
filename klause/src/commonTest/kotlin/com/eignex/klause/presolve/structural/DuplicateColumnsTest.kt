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
import com.eignex.klause.propagation.BakedProblem
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
import com.eignex.klause.util.plus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DuplicateColumnsTest {
    @Test
    fun `merging extreme columns in successive batches should round trip every aggregate`() {
        val domains = arrayOf(
            IntDomain(-Long.MAX_VALUE, -Long.MAX_VALUE + 20),
            IntDomain(Long.MAX_VALUE - 20, Long.MAX_VALUE),
            IntDomain(-5, 5),
        )
        val problem = BakedProblem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = domains,
            factors = listOf(Linear(intArrayOf(1, 1, 1), intArrayOf(0, 1, 2), LinearOp.LE, 100)),
            alreadyFolded = true,
        )

        val delta = DuplicateColumns.mergeDuplicateColumns(problem)

        assertTrue(!delta.isEmpty)
        val reconstruct = checkNotNull(delta.reconstruct)
        for (z in -25L..25L) {
            val result = reconstruct(Sample(BooleanArray(0), longArrayOf(z, 0, 0))).ints
            for (v in domains.indices) assertTrue(result[v] in domains[v].min..domains[v].max)
            assertEquals(bigIntOf(z), bigIntOf(result[0]) + bigIntOf(result[1]) + bigIntOf(result[2]))
        }
    }

    @Test
    fun `duplicate declared columns should reconstruct a feasible assignment`() {
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
    fun `source merging should fold a column closed at zero into a representative open above`() {
        val model = sourceModel(IntDomain(0, 100), IntDomain(0, 5), openHi = booleanArrayOf(true, false, false))
        val delta = Presolve.mergeSourceDuplicateColumns(model, emptySet(), Cancellation.Never)
        val ints = longArrayOf(12, 0, 3)

        delta.rebuild.rebuildInto(BooleanArray(0), ints)

        assertTrue(delta.addedFactors.none { 1 in it.intVars })
        assertEquals(listOf(7L, 5L), ints.take(2), "y takes all it can of 12, x the rest")
    }

    @Test
    fun `source merging should split an aggregate of two free columns`() {
        val free = booleanArrayOf(true, true, false)
        val model = sourceModel(IntDomain(0, 9), IntDomain(0, 9), openLo = free, openHi = free)
        val delta = Presolve.mergeSourceDuplicateColumns(model, emptySet(), Cancellation.Never)
        val ints = arrayOf(bigIntOf(-3), BIG_ZERO, BIG_ZERO)

        delta.rebuild.rebuildInto(BooleanArray(0), ints)

        assertEquals(listOf(bigIntOf(-3), BIG_ZERO), ints.take(2))
    }

    @Test
    fun `source merging should leave closed columns whose aggregate would need a wider range`() {
        val model = sourceModel(IntDomain(0, 9), IntDomain(1, 5))

        val delta = Presolve.mergeSourceDuplicateColumns(model, emptySet(), Cancellation.Never)

        assertTrue(delta.isEmpty)
    }

    @Test
    fun `source merging should not fold a column declaring holes`() {
        val model = sourceModel(
            IntDomain(0, 100),
            IntDomain(0, 4).excludeValue(2),
            openHi = booleanArrayOf(true, false, false),
        )

        val delta = Presolve.mergeSourceDuplicateColumns(model, emptySet(), Cancellation.Never)

        assertTrue(delta.isEmpty)
    }

    @Test
    fun `columns that differ in one factor should not be merged`() {
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
    fun `a variable in a non-linear factor should not be merged`() {
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
    fun `an incremental run should match the full merge when a touched column is eligible`() {
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
        assertTrue(!incremental.isEmpty)
        assertEquals(
            full.droppedIndices.toList(),
            incremental.droppedIndices.toList(),
        )
    }
}
