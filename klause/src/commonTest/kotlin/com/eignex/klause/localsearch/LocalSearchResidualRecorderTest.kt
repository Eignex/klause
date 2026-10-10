package com.eignex.klause.localsearch

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class LocalSearchResidualRecorderTest {
    @Test
    fun `reified residual categories distinguish comparison and input shape`() {
        for (op in LinearOp.entries) {
            for (shape in listOf("binary", "unary", "multi")) {
                val vars = if (shape == "multi") intArrayOf(0, 1) else intArrayOf(0)
                val domains = Array(vars.size) { IntDomain(0L, if (shape == "binary") 1L else 3L) }
                val problem = Problem(
                    1, vars.size, domains,
                    arrayOf<Factor>(ReifiedLinear(0, IntArray(vars.size) { 1 }, vars, op, 1)),
                )
                val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
                for (v in vars) state.assignment.setInt(v, 0)
                state.assignment.setBool(0, op != LinearOp.LE && op != LinearOp.NE)
                state.recompute()
                val recorder = LocalSearchResidualRecorder()

                recorder.observe(state)

                assertEquals(1L, recorder.best?.cost)
                assertEquals(mapOf("ReifiedLinear.$op.$shape" to 1L), recorder.best?.byKind)
            }
        }
    }

    @Test
    fun `a residual snapshot keeps the degrees from its observed assignment`() {
        val problem = Problem(
            1, 1, arrayOf(IntDomain(0, 3)),
            arrayOf<Factor>(
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 10),
                Clause(intArrayOf(Lit.make(0, true))),
            ),
        )
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
        state.recompute()
        val recorder = LocalSearchResidualRecorder()
        recorder.observe(state)
        val initial = recorder.best

        state.apply(Move.IntSet(0, 3))
        recorder.observe(state)
        val improved = recorder.best
        state.apply(Move.IntSet(0, 0))
        recorder.observe(state)

        assertEquals(11L, initial?.cost)
        assertEquals(mapOf("Linear" to 10L, "Clause" to 1L), initial?.byKind)
        assertEquals(8L, improved?.cost)
        assertEquals(mapOf("Linear" to 7L, "Clause" to 1L), recorder.best?.byKind)
    }
}
