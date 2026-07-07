package com.eignex.klause.compile

import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.localsearch.FixedCadenceRestart
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.schema.VariableSchema
import com.eignex.klause.schema.eq
import com.eignex.klause.schema.le
import com.eignex.klause.schema.times
import kotlin.test.Test
import kotlin.test.assertTrue

class IntMulDslTest {

    @Test
    fun `var times var emits product factor`() {
        class S : VariableSchema() {
            val x by intVar(min = 0, max = 4)
            val y by intVar(min = 0, max = 4)
            val cap by constraint { (x * y) le 6 }
        }
        val compiled = S().compile()
        assertTrue(compiled.problem.factors.any { it is Product })
    }

    @Test
    fun `multiplication constraint holds in samples`() {
        class S : VariableSchema() {
            val x by intVar(min = 0, max = 4)
            val y by intVar(min = 0, max = 4)
            val pin by constraint { (x * y) eq 6 }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = LocalSearchSolver(
            compiled.problem,
            restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 500),
        )
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 30_000, randomSeed = 19)).take(15).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val xv = compiled.decode(schema.x, s)
            val yv = compiled.decode(schema.y, s)
            assertTrue(xv * yv == 6L, "x=$xv y=$yv x*y=${xv * yv}")
        }
    }
}
