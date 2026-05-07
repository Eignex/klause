package com.eignex.klause.compile

import com.eignex.klause.solver.FixedCadenceRestart
import com.eignex.klause.solver.LocalSearchParams
import com.eignex.klause.ast.eq
import com.eignex.klause.ast.times
import com.eignex.klause.schema.VariableSchema
import com.eignex.klause.solver.LocalSearchSolver
import kotlin.test.Test
import kotlin.test.assertTrue

class ProductConvergenceTest {

    @Test
    fun productConvergesOnTighlyFactoredTarget() {
        // x ∈ [1..10], y ∈ [1..10]. Pin (x*y) = 42 → factorisations (6,7), (7,6) only.
        // Without a closest-divisor secondary snap the solver often gets stuck nudging ±1 from
        // a non-divisible result.
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
            val xv = compiled.decodeInt("x", s)
            val yv = compiled.decodeInt("y", s)
            assertTrue(xv * yv == 42, "x=$xv y=$yv x*y=${xv * yv}")
        }
    }
}
