package com.eignex.klause.solver.integration

import com.eignex.klause.compile.compile
import com.eignex.klause.localsearch.FixedCadenceRestart
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.localsearch.strategy.Cbls
import com.eignex.klause.schema.VariableSchema
import com.eignex.klause.schema.atLeast
import com.eignex.klause.schema.atMost
import kotlin.test.Test
import kotlin.test.assertTrue

class CblsSmoothingTest {

    private class CardinalityS : VariableSchema() {
        val a by boolVar()
        val b by boolVar()
        val c by boolVar()
        val d by boolVar()
        val e by boolVar()
        val cap by constraint { atMost(3, a, b, c, d, e) }
        val req by constraint { atLeast(2, a, b, c, d, e) }
    }

    @Test
    fun `cbls with smoothing finds feasible samples`() {
        val schema = CardinalityS()
        val compiled = schema.compile()
        val solver = LocalSearchSolver(
            compiled.problem,
            strategy = Cbls(smoothProb = 0.4, smoothFactor = 0.8),
            restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 200),
        )
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 5_000, randomSeed = 19)).take(5).toList()
        assertTrue(samples.isNotEmpty(), "Cbls with smoothing produced no samples")
        for (s in samples) {
            val count = listOf(schema.a, schema.b, schema.c, schema.d, schema.e).count { compiled.decode(it, s) }
            assertTrue(count in 2..3, "count=$count violates 2..3")
        }
    }
}
