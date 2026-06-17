package com.eignex.klause.compile

import com.eignex.klause.schema.VariableSchema
import com.eignex.klause.schema.div
import com.eignex.klause.schema.eq
import com.eignex.klause.schema.rem
import com.eignex.klause.solver.localsearch.FixedCadenceRestart
import com.eignex.klause.solver.localsearch.LocalSearchParams
import com.eignex.klause.solver.localsearch.LocalSearchSolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IntDivModDslTest {

    @Test
    fun `division constraint holds in samples`() {
        class S : VariableSchema() {
            val n by intVar(min = 0, max = 12)
            val d by intVar(min = 1, max = 4)

            val pin by constraint { (n / d) eq 3 }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = LocalSearchSolver(
            compiled.problem,
            restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 500),
        )
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
    fun `modulus constraint holds in samples`() {
        class S : VariableSchema() {
            val n by intVar(min = 0, max = 10)
            val d by intVar(min = 1, max = 4)

            val pin by constraint { (n % d) eq 1 }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = LocalSearchSolver(
            compiled.problem,
            restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 500),
        )
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 30_000, randomSeed = 41)).take(15).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val nv = compiled.decode(schema.n, s)
            val dv = compiled.decode(schema.d, s)
            assertTrue(nv % dv == 1, "n=$nv d=$dv n%d=${nv % dv}, expected 1")
        }
    }

    /** Truncated toward zero: -7 div 2 = -3 (remainder -1), matching Kotlin's `/`. */
    @Test
    fun `negative numerator truncated division`() {
        class S : VariableSchema() {
            val n by intVar(min = -7, max = -7)
            val d by intVar(min = 1, max = 3)
            val pinQ by constraint { (n / d) eq -3 }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = LocalSearchSolver(
            compiled.problem,
            restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 500),
        )
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 30_000, randomSeed = 61)).take(10).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val nv = compiled.decode(schema.n, s)
            val dv = compiled.decode(schema.d, s)
            assertEquals(-7, nv)
            assertEquals(-3, nv / dv, "n=$nv d=$dv")
        }
    }

    /** Negative denominator, positive numerator: truncated and Euclidean agree here (the
     *  remainder is non-negative either way). e.g. 4 div -2 = -2 (r=0), 5 div -2 = -2 (r=1). */
    @Test
    fun `negative denominator division`() {
        class S : VariableSchema() {
            val n by intVar(min = 0, max = 6)
            val d by intVar(min = -3, max = -1)
            val pinQ by constraint { (n / d) eq -2 }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = LocalSearchSolver(
            compiled.problem,
            restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 500),
        )
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 30_000, randomSeed = 71)).take(10).toList()
        assertTrue(samples.isNotEmpty())
        val expectedPairs = setOf(2 to -1, 4 to -2, 5 to -2, 6 to -3)
        for (s in samples) {
            val nv = compiled.decode(schema.n, s)
            val dv = compiled.decode(schema.d, s)
            assertTrue(
                (nv to dv) in expectedPairs,
                "n=$nv d=$dv not in expected q=-2 pairs $expectedPairs",
            )
        }
    }

    /** Truncated mod takes the sign of the dividend: -10 mod 3 = -1 (matching Kotlin's `%`),
     *  not the Euclidean +2. */
    @Test
    fun `negative numerator truncated mod takes dividend sign`() {
        class S : VariableSchema() {
            val n by intVar(min = -10, max = -10)
            val d by intVar(min = 3, max = 3)
            val pinR by constraint { (n % d) eq -1 }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = LocalSearchSolver(
            compiled.problem,
            restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 500),
        )
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 20_000, randomSeed = 47)).take(5).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val nv = compiled.decode(schema.n, s)
            val dv = compiled.decode(schema.d, s)
            assertEquals(-10, nv)
            assertEquals(3, dv)
            assertEquals(-1, nv % dv, "n=$nv d=$dv")
        }
    }
}
