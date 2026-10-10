package com.eignex.klause.localsearch

import com.eignex.klause.factor.arithmetic.Linear
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
