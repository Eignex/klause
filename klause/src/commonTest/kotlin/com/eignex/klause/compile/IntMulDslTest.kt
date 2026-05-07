package com.eignex.klause.compile

import com.eignex.klause.solver.FixedCadenceRestart
import com.eignex.klause.solver.LocalSearchParams
import com.eignex.klause.ast.eq
import com.eignex.klause.ast.le
import com.eignex.klause.ast.times
import com.eignex.klause.cnf.BitBlaster
import com.eignex.klause.schema.VariableSchema
import com.eignex.klause.solver.LocalSearchSolver
import com.eignex.klause.solver.factor.Product
import kotlin.test.Test
import kotlin.test.assertTrue

class IntMulDslTest {

    @Test
    fun varTimesVarEmitsProductFactor() {
        class S : VariableSchema() {
            val x by intVar(min = 0, max = 4)
            val y by intVar(min = 0, max = 4)
            val cap by constraint { (x * y) le 6 }
        }
        val compiled = S().compile()
        assertTrue(compiled.problem.factors.any { it is Product })
    }

    @Test
    fun multiplicationConstraintHoldsInSamples() {
        class S : VariableSchema() {
            val x by intVar(min = 0, max = 4)
            val y by intVar(min = 0, max = 4)
            val pin by constraint { (x * y) eq 6 }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = LocalSearchSolver(compiled.problem, restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 500))
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 30_000, randomSeed = 19)).take(15).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val xv = compiled.decode(schema.x, s)
            val yv = compiled.decode(schema.y, s)
            assertTrue(xv * yv == 6, "x=$xv y=$yv x*y=${xv * yv}")
        }
    }

    @Test
    fun multiplicationBitBlastsCleanly() {
        class S : VariableSchema() {
            val x by intVar(min = 0, max = 3)
            val y by intVar(min = 0, max = 3)
            val cap by constraint { (x * y) le 4 }
        }
        val compiled = S().compile()
        val cnf = BitBlaster.compile(compiled.problem)
        assertTrue(cnf.clauses.isNotEmpty())
    }
}
