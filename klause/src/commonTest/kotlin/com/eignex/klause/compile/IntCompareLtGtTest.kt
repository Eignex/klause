package com.eignex.klause.compile

import com.eignex.klause.solver.FixedCadenceRestart
import com.eignex.klause.solver.LocalSearchParams
import com.eignex.klause.ast.gt
import com.eignex.klause.ast.lt
import com.eignex.klause.schema.VariableSchema
import com.eignex.klause.solver.LocalSearchSolver
import kotlin.test.Test
import kotlin.test.assertTrue

class IntCompareLtGtTest {

    @Test
    fun ltAcceptsBoundMinusOne() {
        // x < 5 must permit x = 4. Pre-fix the compiler emits x ≤ 3 (double-shift bug),
        // so this test fails until the second shift is removed.
        class S : VariableSchema() {
            val x by intVar(min = 0, max = 10)
            val cap by constraint { x lt 5 }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = LocalSearchSolver(compiled.problem, restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 200))
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 5_000, randomSeed = 7)).take(40).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val xv = compiled.decodeInt("x", s)
            assertTrue(xv < 5, "x=$xv violates lt 5")
        }
        val anyAtFour = samples.any { compiled.decodeInt("x", it) == 4 }
        assertTrue(anyAtFour, "no sample reached x=4 — compiler is over-tightening lt")
    }

    @Test
    fun gtAcceptsBoundPlusOne() {
        // x > 5 must permit x = 6.
        class S : VariableSchema() {
            val x by intVar(min = 0, max = 10)
            val cap by constraint { x gt 5 }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = LocalSearchSolver(compiled.problem, restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 200))
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 5_000, randomSeed = 13)).take(40).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val xv = compiled.decodeInt("x", s)
            assertTrue(xv > 5, "x=$xv violates gt 5")
        }
        val anyAtSix = samples.any { compiled.decodeInt("x", it) == 6 }
        assertTrue(anyAtSix, "no sample reached x=6 — compiler is over-tightening gt")
    }
}
