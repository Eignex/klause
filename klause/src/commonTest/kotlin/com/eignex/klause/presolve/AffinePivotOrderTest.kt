package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.presolve.PresolveShared.withPassDelta
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pivot ordering in affine elimination. Order is a cost knob — every order yields the same solutions —
 * so what these assert is that the default order is unchanged, that the alternative is admissible, and
 * that it actually orders by fill rather than by id.
 */
class AffinePivotOrderTest {

    /**
     * One wide sink row over `sink` variables, each defined by its own two-term equality so folding it
     * into the sink replaces one term with two. [wideFirst] variables are given extra occurrences, which
     * raises their Markowitz cost without changing their id order — so a fill-ordered pass folds them
     * last where an id-ordered pass folds them first.
     */
    private fun sinkModel(sink: Int, wideFirst: Int): Problem {
        val perDef = 2
        val nVars = sink + sink * perDef + wideFirst
        val factors = ArrayList<Linear>()
        // Stable id 0: the sink row the folds accumulate into.
        factors.add(Linear(LongArray(sink) { 1L }, IntArray(sink) { it }, LinearOp.LE, sink.toLong()))
        for (i in 0 until sink) {
            val p = sink + i * perDef
            factors.add(
                Linear(longArrayOf(1L, -1L, -1L), intArrayOf(i, p, p + 1), LinearOp.EQ, 0L),
            )
        }
        // Extra rows on the lowest-id definitions only: same ids, higher degree, higher fill cost.
        for (k in 0 until wideFirst) {
            val extra = sink + sink * perDef + k
            factors.add(Linear(longArrayOf(1L, 1L), intArrayOf(k, extra), LinearOp.LE, 8L))
        }
        return Problem(0, nVars, Array(nVars) { IntDomain(0, 4) }, factors)
    }

    private fun reduce(problem: Problem, order: AffinePivotOrder): Problem =
        problem.withPassDelta(Presolve.eliminateAffineSingletons(problem, pivotOrder = order), BakeConfig.NONE)

    private fun totalTerms(problem: Problem): Int = problem.factors.filterIsInstance<Linear>().sumOf { it.vars.size }

    @Test
    fun `the configured default order is by fill`() {
        assertEquals(AffinePivotOrder.MARKOWITZ, PresolveConfig.AUTO.affinePivotOrder)
    }

    @Test
    fun `the unparameterized pass uses the configured default order`() {
        val problem = sinkModel(sink = 20, wideFirst = 10)

        val implicit = Presolve.eliminateAffineSingletons(problem)
        val explicit = Presolve.eliminateAffineSingletons(problem, pivotOrder = AffinePivotOrder.MARKOWITZ)

        assertEquals(implicit.droppedIndices.toList(), explicit.droppedIndices.toList())
        assertEquals(implicit.addedFactors.size, explicit.addedFactors.size)
    }

    @Test
    fun `ordering by fill folds the cheap pivots that id order leaves behind`() {
        // The absorb cap bounds how many folds one row takes, so which pivots get in is decided by the
        // order. Ordering by fill spends that budget on the low-degree definitions.
        val problem = sinkModel(sink = 20, wideFirst = 10)

        val byId = reduce(problem, AffinePivotOrder.STABLE_ID)
        val byFill = reduce(problem, AffinePivotOrder.MARKOWITZ)

        assertTrue(
            totalTerms(byFill) < totalTerms(byId),
            "fill-ordered elimination must leave a narrower problem than id-ordered: " +
                "${totalTerms(byFill)} vs ${totalTerms(byId)}",
        )
    }

    @Test
    fun `both orders eliminate the same problem down to the same verdict`() {
        val problem = sinkModel(sink = 6, wideFirst = 3)

        val byId = reduce(problem, AffinePivotOrder.STABLE_ID)
        val byFill = reduce(problem, AffinePivotOrder.MARKOWITZ)

        assertEquals(byId.numIntVars, byFill.numIntVars)
        assertTrue(byId.factors.isNotEmpty() && byFill.factors.isNotEmpty())
    }
}
