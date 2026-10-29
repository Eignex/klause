package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.table.Table
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.PresolveShared.withPassDelta
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.bake
import com.eignex.klause.propagation.propagate
import com.eignex.klause.solver.Sample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AffinePivotOrderTest {
    @Test
    fun `rewrite cost should retain the larger table and reconstruct either affine direction through every host`() {
        for (lane in listOf("source", "incremental", "rebuilt")) {
            val table = Table(intArrayOf(0, 2), longArrayOf(1, 0, 1, 1, 1, 2, 2, 0, 2, 1, 2, 2, 3, 0, 3, 1, 3, 2))
            val source = Problem(0, 4,
                arrayOf(IntDomain(1, 3), IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2)),
                listOf(
                    Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 1),
                    table,
                    Table(intArrayOf(1, 3), longArrayOf(0, 0, 1, 1, 2, 2)),
                ))
            val config = PresolveConfig.parse(if (lane == "rebuilt") "off,+affine,+bce" else "off,+affine")
                .withAffinePivotOrder(AffinePivotOrder.REWRITE_COST)
            val reduced: Problem
            val reconstruct: (Sample) -> Sample
            if (lane == "source") {
                val result = Presolver.runSource(source, config)
                reduced = result.problem
                reconstruct = result.rebuild.asSampleLift() ?: { it }
            } else {
                val result = Presolver.run(source.bake(), config)
                reduced = result.problem
                reconstruct = result.reconstruct
            }

            assertSame(table, reduced.factors.first { it === table }, lane)
            for (x in 1L..3L) {
                val sample = Sample(BooleanArray(0), longArrayOf(x, 0, 0, x - 1))
                val rebuilt = reconstruct(sample)
                assertEquals(x - 1, rebuilt.ints[1], lane)
                var pins = Assumptions.None
                for (v in rebuilt.ints.indices) pins = pins.withInt(v, rebuilt.ints[v])
                assertFalse(source.propagate(pins) is PropagationResult.Unsat, lane)
            }
        }
    }

    private fun sinkModel(sink: Int, wideFirst: Int): Problem {
        val perDef = 2
        val nVars = sink + sink * perDef + wideFirst
        val factors = ArrayList<Linear>()
        factors.add(Linear(LongArray(sink) { 1L }, IntArray(sink) { it }, LinearOp.LE, sink.toLong()))
        for (i in 0 until sink) {
            val p = sink + i * perDef
            factors.add(
                Linear(longArrayOf(1L, -1L, -1L), intArrayOf(i, p, p + 1), LinearOp.EQ, 0L),
            )
        }
        // Extra rows raise the fill cost of the lowest ids without changing their order.
        for (k in 0 until wideFirst) {
            val extra = sink + sink * perDef + k
            factors.add(Linear(longArrayOf(1L, 1L), intArrayOf(k, extra), LinearOp.LE, 8L))
        }
        return Problem(0, nVars, Array(nVars) { IntDomain(0, 4) }, factors)
    }

    private fun reduce(problem: Problem, order: AffinePivotOrder): Problem {
        val baked = problem.bake()
        return baked.withPassDelta(Presolve.eliminateAffineSingletons(baked, pivotOrder = order), BakeConfig.NONE)
    }

    private fun totalTerms(problem: Problem): Int = problem.factors.filterIsInstance<Linear>().sumOf { it.vars.size }

    @Test
    fun `ordering by fill should fold the cheap pivots that id order leaves behind`() {
        val problem = sinkModel(sink = 20, wideFirst = 10)

        val byId = reduce(problem, AffinePivotOrder.STABLE_ID)
        val byFill = reduce(problem, AffinePivotOrder.MARKOWITZ)

        assertTrue(
            totalTerms(byFill) < totalTerms(byId),
            "${totalTerms(byFill)} vs ${totalTerms(byId)}",
        )
    }
}
