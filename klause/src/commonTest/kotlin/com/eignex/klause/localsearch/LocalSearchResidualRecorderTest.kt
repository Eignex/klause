package com.eignex.klause.localsearch

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.result.LocalSearchResidualFactor
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
        assertEquals(
            LocalSearchResidualFactor(0, "Linear", 10L, emptyMap(), mapOf(0 to 0L)),
            initial?.factors?.first(),
        )
        assertEquals(8L, improved?.cost)
        assertEquals(mapOf("Linear" to 7L, "Clause" to 1L), recorder.best?.byKind)
        assertEquals(
            listOf(
                LocalSearchResidualFactor(0, "Linear", 7L, emptyMap(), mapOf(0 to 3L)),
                LocalSearchResidualFactor(1, "Clause", 1L, mapOf(0 to false), emptyMap()),
            ),
            recorder.best?.factors,
        )
    }

    @Test
    fun `representative factors keep the largest degree of each kind`() {
        val problem = Problem(
            0, 1, arrayOf(IntDomain(0, 3)),
            arrayOf<Factor>(
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 2),
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 10),
            ),
        )
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
        state.assignment.setInt(0, 0)
        state.recompute()
        val recorder = LocalSearchResidualRecorder()

        recorder.observe(state)

        assertEquals(mapOf("Linear" to 12L), recorder.best?.byKind)
        assertEquals(
            listOf(LocalSearchResidualFactor(1, "Linear", 10L, emptyMap(), mapOf(0 to 0L))),
            recorder.best?.factors,
        )
    }

    @Test
    fun `representative coordinates are bounded and report omissions`() {
        for (integers in listOf(false, true)) {
            val problem = Problem(
                17, 17, Array(17) { IntDomain(0, 3) },
                arrayOf<Factor>(
                    ReifiedLinear(16, IntArray(17) { 1 }, IntArray(17) { it }, LinearOp.GE, 1),
                    Clause(IntArray(17) { Lit.make(it, true) }),
                ),
            )
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
            repeat(17) {
                state.assignment.setBool(it, integers && it == 16)
                state.assignment.setInt(it, 0)
            }
            state.recompute()
            val recorder = LocalSearchResidualRecorder()

            recorder.observe(state)

            val factor = recorder.best?.factors?.single()
            assertEquals(if (integers) mapOf(16 to true) else (0 until 16).associateWith { false }, factor?.bools)
            assertEquals(if (integers) (0 until 16).associateWith { 0L } else emptyMap(), factor?.ints)
            assertEquals(if (integers) 0 else 1, factor?.omittedBools)
            assertEquals(if (integers) 1 else 0, factor?.omittedInts)
        }
    }

    @Test
    fun `representative factor count is bounded without truncating kind totals`() {
        val factors = Array<Factor>(12) { fid ->
            val vars = when (fid % 3) {
                0 -> intArrayOf(0)
                1 -> intArrayOf(1)
                else -> intArrayOf(1, 2)
            }
            ReifiedLinear(fid, IntArray(vars.size) { 1 }, vars, LinearOp.entries[fid / 3], 1)
        }
        val problem = Problem(12, 3, arrayOf(IntDomain(0, 1), IntDomain(0, 3), IntDomain(0, 3)), factors)
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
        repeat(3) { state.assignment.setInt(it, 0) }
        repeat(12) {
            val op = LinearOp.entries[it / 3]
            state.assignment.setBool(it, op != LinearOp.LE && op != LinearOp.NE)
        }
        state.recompute()
        val recorder = LocalSearchResidualRecorder()

        recorder.observe(state)

        assertEquals(12L, recorder.best?.cost)
        assertEquals(12, recorder.best?.byKind?.size)
        assertEquals(8, recorder.best?.factors?.size)
        assertEquals(8, recorder.best?.factors?.map { it.kind }?.distinct()?.size)
    }
}
