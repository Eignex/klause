package com.eignex.klause.compile

import com.eignex.klause.schema.VariableSchema
import com.eignex.klause.schema.ne
import com.eignex.klause.solver.factor.ReifiedLinear
import com.eignex.klause.solver.localsearch.FixedCadenceRestart
import com.eignex.klause.solver.localsearch.LocalSearchParams
import com.eignex.klause.solver.localsearch.LocalSearchSolver
import kotlin.test.Test
import kotlin.test.assertTrue

class IntDisequalityDslTest {

    @Test
    fun `two int vars disequality compiles via reified linear`() {
        class S : VariableSchema() {
            val x by intVar(min = 0, max = 5)
            val y by intVar(min = 0, max = 5)
            val differ by constraint { x ne y }
        }
        val compiled = S().compile()

        assertTrue(compiled.problem.factors.any { it is ReifiedLinear })
    }

    @Test
    fun `two int vars disequality holds in samples`() {
        class S : VariableSchema() {
            val x by intVar(min = 0, max = 4)
            val y by intVar(min = 0, max = 4)
            val differ by constraint { x ne y }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver =
            LocalSearchSolver(compiled.problem, restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 500))
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 20_000, randomSeed = 31)).take(10).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val xv = compiled.decode(schema.x, s)
            val yv = compiled.decode(schema.y, s)
            assertTrue(xv != yv, "x=$xv y=$yv equal")
        }
    }
}
