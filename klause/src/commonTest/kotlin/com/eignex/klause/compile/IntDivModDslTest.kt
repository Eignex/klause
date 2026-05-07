package com.eignex.klause.compile

import com.eignex.klause.solver.FixedCadenceRestart
import com.eignex.klause.solver.LocalSearchParams
import com.eignex.klause.ast.div
import com.eignex.klause.ast.eq
import com.eignex.klause.ast.ge
import com.eignex.klause.ast.rem
import com.eignex.klause.cnf.BitBlaster
import com.eignex.klause.schema.VariableSchema
import com.eignex.klause.solver.LocalSearchSolver
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
        val solver = LocalSearchSolver(compiled.problem, restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 500))
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 30_000, randomSeed = 27)).take(15).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val nv = compiled.decode(schema.n, s)
            val dv = compiled.decode(schema.d, s)
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
        val solver = LocalSearchSolver(compiled.problem, restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 500))
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 30_000, randomSeed = 41)).take(15).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val nv = compiled.decode(schema.n, s)
            val dv = compiled.decode(schema.d, s)
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

    @Test
    fun signedNumeratorTruncatedDivision() {
        // n = -7, d ∈ {1,2,3} → q = trunc(-7/d), r = -7 % d.
        class S : VariableSchema() {
            val n by intVar(min = -7, max = -7)
            val d by intVar(min = 1, max = 3)
            val pinQ by constraint { (n / d) eq -2 }   // -7/3 = -2 (truncated), -7/2=-3 fails
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = LocalSearchSolver(compiled.problem, restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 500))
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 30_000, randomSeed = 61)).take(10).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val nv = compiled.decode(schema.n, s)
            val dv = compiled.decode(schema.d, s)
            assertTrue(nv / dv == -2, "n=$nv d=$dv n/d=${nv / dv}")
        }
    }

    @Test
    fun signedDenominatorTruncatedDivision() {
        // d ∈ {-3,-2,-1}, n ∈ [0..6]; pin q = -2 to find feasible (n,d) pairs.
        class S : VariableSchema() {
            val n by intVar(min = 0, max = 6)
            val d by intVar(min = -3, max = -1)
            val pinQ by constraint { (n / d) eq -2 }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = LocalSearchSolver(compiled.problem, restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 500))
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 30_000, randomSeed = 71)).take(10).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val nv = compiled.decode(schema.n, s)
            val dv = compiled.decode(schema.d, s)
            assertTrue(nv / dv == -2, "n=$nv d=$dv n/d=${nv / dv}")
        }
    }

    @Test
    fun signedModTruncatedSemantics() {
        // -10 % 3 = -1 (Java/Kotlin truncated). Pin n=-10, d=3, expect r=-1.
        class S : VariableSchema() {
            val n by intVar(min = -10, max = -10)
            val d by intVar(min = 3, max = 3)
            val pinR by constraint { (n % d) eq -1 }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = LocalSearchSolver(compiled.problem, restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 500))
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 20_000, randomSeed = 47)).take(5).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val nv = compiled.decode(schema.n, s)
            val dv = compiled.decode(schema.d, s)
            assertTrue(nv == -10 && dv == 3 && nv % dv == -1, "n=$nv d=$dv n%d=${nv % dv}")
        }
    }
}
