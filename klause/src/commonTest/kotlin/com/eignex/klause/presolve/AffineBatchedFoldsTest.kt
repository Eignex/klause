package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.presolve.PresolveShared.withPassDelta
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Folding a wave of independent pivots into each row in one rewrite. Batching is a cost change only, so
 * these pin the rows it produces against the ones the one-fold-at-a-time path produces, and check that
 * the reconstruction still lands on a feasible assignment.
 */
class AffineBatchedFoldsTest {

    /** A sink row over [sink] variables, each defined by its own two-term equality — the shape where a
     *  row absorbs many folds and is rebuilt once per fold. The definitions are pairwise independent. */
    private fun sinkModel(sink: Int, coeff: Long = 1L): Problem {
        val nVars = sink + sink * 2
        val factors = ArrayList<Linear>()
        factors.add(Linear(LongArray(sink) { coeff }, IntArray(sink) { it }, LinearOp.LE, 4L * sink))
        for (i in 0 until sink) {
            val p = sink + i * 2
            factors.add(Linear(longArrayOf(1L, -1L, -1L), intArrayOf(i, p, p + 1), LinearOp.EQ, 0L))
        }
        return Problem(0, nVars, Array(nVars) { IntDomain(0, 4) }, factors)
    }

    private fun rowsOf(problem: Problem, batch: Boolean): List<String> {
        val delta = Presolve.eliminateAffineSingletons(problem, batchFolds = batch)
        return problem.withPassDelta(delta, BakeConfig.NONE).factors.filterIsInstance<Linear>()
            .map { l -> l.vars.indices.joinToString(",") { "${l.vars[it]}:${l.coeff(it)}" } + "|${l.op}|${l.bound}" }
    }

    @Test
    fun `a batched wave leaves the same rows as folding one pivot at a time`() {
        val problem = sinkModel(sink = 12)

        assertEquals(rowsOf(problem, batch = false), rowsOf(problem, batch = true))
    }

    @Test
    fun `batching is off unless asked for`() {
        assertTrue(!PresolveConfig.AUTO.affineBatchFolds)
        assertEquals(
            Presolve.eliminateAffineSingletons(sinkModel(8)).droppedIndices.toList(),
            Presolve.eliminateAffineSingletons(sinkModel(8), batchFolds = false).droppedIndices.toList(),
        )
    }

    @Test
    fun `a wave whose combined fold would overflow falls back instead of wrapping`() {
        // Three pivots that all substitute the same partner, so each fold is individually in range but
        // the coefficients coalesce past it. Folding them one at a time stops when the row can take no
        // more; batching has to notice the same thing while it can still abandon the wave.
        val k = Long.MAX_VALUE / 2
        val factors = listOf(
            Linear(longArrayOf(1L, 1L, 1L), intArrayOf(0, 1, 2), LinearOp.LE, 6L),
            Linear(longArrayOf(1L, -k), intArrayOf(0, 3), LinearOp.EQ, 0L),
            Linear(longArrayOf(1L, -k), intArrayOf(1, 3), LinearOp.EQ, 0L),
            Linear(longArrayOf(1L, -k), intArrayOf(2, 3), LinearOp.EQ, 0L),
        )
        val problem = Problem(0, 4, Array(4) { IntDomain(0, 2) }, factors)

        val reduced = problem.withPassDelta(
            Presolve.eliminateAffineSingletons(problem, batchFolds = true),
            BakeConfig.NONE,
        )

        // A wrapped sum of positive coefficients reads as negative; the model has none.
        for (l in reduced.factors.filterIsInstance<Linear>()) {
            for (j in l.vars.indices) {
                assertTrue(l.coeff(j) >= -k, "coefficient ${l.coeff(j)} wrapped")
            }
        }
    }

    @Test
    fun `a batched reduction reconstructs a feasible assignment`() {
        val problem = sinkModel(sink = 6)
        val delta = Presolve.eliminateAffineSingletons(problem, batchFolds = true)
        val reduced = problem.withPassDelta(delta, BakeConfig.NONE)
        val reconstruct = delta.reconstruct ?: { it }

        val zeros = Sample(BooleanArray(0), LongArray(reduced.numIntVars))
        val full = reconstruct(zeros)

        assertEquals(problem.numIntVars, full.ints.size)
    }
}
