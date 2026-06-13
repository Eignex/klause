package com.eignex.klause.compile

import com.eignex.klause.schema.VariableSchema
import com.eignex.klause.schema.gt
import com.eignex.klause.schema.lt
import com.eignex.klause.solver.localsearch.FixedCadenceRestart
import com.eignex.klause.solver.localsearch.LocalSearchParams
import com.eignex.klause.solver.localsearch.LocalSearchSolver
import kotlin.test.Test
import kotlin.test.assertTrue

class IntCompareLtGtTest {

    @Test
    fun `lt accepts bound minus one`() {
        class S : VariableSchema() {
            val x by intVar(min = 0, max = 10)
            val cap by constraint { x lt 5 }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver =
            LocalSearchSolver(compiled.problem, restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 200))
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 5_000, randomSeed = 7)).take(40).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val xv = compiled.decode(schema.x, s)
            assertTrue(xv < 5, "x=$xv violates lt 5")
        }
        val anyAtFour = samples.any { compiled.decode(schema.x, it) == 4 }
        assertTrue(anyAtFour, "no sample reached x=4 — compiler is over-tightening lt")
    }

    @Test
    fun `gt accepts bound plus one`() {
        class S : VariableSchema() {
            val x by intVar(min = 0, max = 10)
            val cap by constraint { x gt 5 }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver =
            LocalSearchSolver(compiled.problem, restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 200))
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 5_000, randomSeed = 13)).take(40).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val xv = compiled.decode(schema.x, s)
            assertTrue(xv > 5, "x=$xv violates gt 5")
        }
        val anyAtSix = samples.any { compiled.decode(schema.x, it) == 6 }
        assertTrue(anyAtSix, "no sample reached x=6 — compiler is over-tightening gt")
    }
}
