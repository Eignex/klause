package com.eignex.klause.compile

import com.eignex.klause.solver.FixedCadenceRestart
import com.eignex.klause.solver.LocalSearchParams
import com.eignex.klause.ast.ne
import com.eignex.klause.schema.VariableSchema
import com.eignex.klause.solver.LocalSearchSolver
import com.eignex.klause.solver.factor.ReifiedLinear
import kotlin.test.Test
import kotlin.test.assertTrue

class IntDisequalityDslTest {

    @Test
    fun twoIntVarsDisequalityCompilesViaReifiedLinear() {
        class S : VariableSchema() {
            val x by intVar(min = 0, max = 5)
            val y by intVar(min = 0, max = 5)
            val differ by constraint { x ne y }
        }
        val compiled = S().compile()
        // Multi-var NE compiles to a ReifiedLinear EQ + assert ¬aux.
        assertTrue(compiled.problem.factors.any { it is ReifiedLinear })
    }

    @Test
    fun twoIntVarsDisequalityHoldsInSamples() {
        class S : VariableSchema() {
            val x by intVar(min = 0, max = 4)
            val y by intVar(min = 0, max = 4)
            val differ by constraint { x ne y }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = LocalSearchSolver(compiled.problem, restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 500))
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 20_000, randomSeed = 31)).take(10).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val xv = compiled.decodeInt("x", s)
            val yv = compiled.decodeInt("y", s)
            assertTrue(xv != yv, "x=$xv y=$yv equal")
        }
    }
}
