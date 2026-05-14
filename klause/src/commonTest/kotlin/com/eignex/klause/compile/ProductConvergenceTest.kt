package com.eignex.klause.compile

import com.eignex.klause.solver.localsearch.FixedCadenceRestart
import com.eignex.klause.solver.localsearch.LocalSearchParams
import com.eignex.klause.ast.eq
import com.eignex.klause.ast.times
import com.eignex.klause.schema.VariableSchema
import com.eignex.klause.solver.localsearch.LocalSearchSolver
import kotlin.test.Test
import kotlin.test.assertTrue

class ProductConvergenceTest {

    @Test
    fun `product converges on tighly factored target`() {

        class S : VariableSchema() {
            val x by intVar(min = 1, max = 10)
            val y by intVar(min = 1, max = 10)
            val pin by constraint { (x * y) eq 42 }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = LocalSearchSolver(compiled.problem, restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 200))
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 30_000, randomSeed = 4)).take(8).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val xv = compiled.decode(schema.x, s)
            val yv = compiled.decode(schema.y, s)
            assertTrue(xv * yv == 42, "x=$xv y=$yv x*y=${xv * yv}")
        }
    }
}
