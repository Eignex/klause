package com.eignex.klause.compile

import com.eignex.klause.ast.div
import com.eignex.klause.ast.eq
import com.eignex.klause.ast.ge
import com.eignex.klause.ast.rem
import com.eignex.klause.cnf.BitBlaster
import com.eignex.klause.schema.VariableSchema
import com.eignex.klause.solver.Solver
import kotlin.test.Test
import kotlin.test.assertTrue

class IntDivModDslTest {

    @Test
    fun divisionConstraintHoldsInSamples() {
        class S : VariableSchema() {
            val n by intVar(min = 0, max = 12)
            val d by intVar(min = 1, max = 4)
            // n / d = 3.
            val pin by constraint { (n / d) eq 3 }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = Solver(compiled.problem, maxFlipsBeforeRestart = 500)
        val samples = solver.sample(maxFlips = 30_000, randomSeed = 27).take(15).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val nv = compiled.decodeInt("n", s)
            val dv = compiled.decodeInt("d", s)
            assertTrue(dv >= 1, "d=$dv must be positive")
            assertTrue(nv / dv == 3, "n=$nv d=$dv n/d=${nv / dv}, expected 3")
        }
    }

    @Test
    fun modulusConstraintHoldsInSamples() {
        class S : VariableSchema() {
            val n by intVar(min = 0, max = 10)
            val d by intVar(min = 1, max = 4)
            // n mod d = 1.
            val pin by constraint { (n % d) eq 1 }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = Solver(compiled.problem, maxFlipsBeforeRestart = 500)
        val samples = solver.sample(maxFlips = 30_000, randomSeed = 41).take(15).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val nv = compiled.decodeInt("n", s)
            val dv = compiled.decodeInt("d", s)
            assertTrue(nv % dv == 1, "n=$nv d=$dv n%d=${nv % dv}, expected 1")
        }
    }

    @Test
    fun divModBitBlastsCleanly() {
        class S : VariableSchema() {
            val n by intVar(min = 0, max = 7)
            val d by intVar(min = 1, max = 3)
            val cap by constraint { (n / d) ge 1 }
        }
        val compiled = S().compile()
        val cnf = BitBlaster.compile(compiled.problem)
        assertTrue(cnf.clauses.isNotEmpty())
    }
}
