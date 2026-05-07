package com.eignex.klause.compile

import com.eignex.klause.solver.FixedCadenceRestart
import com.eignex.klause.solver.LocalSearchParams
import com.eignex.klause.ast.eq
import com.eignex.klause.ast.times
import com.eignex.klause.schema.VariableSchema
import com.eignex.klause.solver.LocalSearchSolver
import kotlin.test.Test
import kotlin.test.assertTrue

class SignedMulDslTest {

    @Test
    fun signedProductHoldsInSamples() {
        class S : VariableSchema() {
            val x by intVar(min = -3, max = 3)
            val y by intVar(min = -3, max = 3)
            val pin by constraint { (x * y) eq -6 }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = LocalSearchSolver(compiled.problem, restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 500))
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 30_000, randomSeed = 53)).take(15).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val xv = compiled.decodeInt("x", s)
            val yv = compiled.decodeInt("y", s)
            assertTrue(xv * yv == -6, "x=$xv y=$yv x*y=${xv * yv}")
        }
    }
}
