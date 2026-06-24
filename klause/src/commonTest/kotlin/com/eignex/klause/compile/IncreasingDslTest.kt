package com.eignex.klause.compile

import com.eignex.klause.schema.VariableSchema
import com.eignex.klause.schema.decreasing
import com.eignex.klause.schema.increasing
import com.eignex.klause.schema.strictlyIncreasing
import com.eignex.klause.solver.localsearch.FixedCadenceRestart
import com.eignex.klause.solver.localsearch.LocalSearchParams
import com.eignex.klause.solver.localsearch.LocalSearchSolver
import kotlin.test.Test
import kotlin.test.assertTrue

class IncreasingDslTest {

    @Test
    fun `increasing holds in samples`() {
        class S : VariableSchema() {
            val x0 by intVar(min = 0, max = 3)
            val x1 by intVar(min = 0, max = 3)
            val x2 by intVar(min = 0, max = 3)
            val ord by constraint { increasing(listOf(x0, x1, x2)) }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver =
            LocalSearchSolver(compiled.problem, restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 500))
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 20_000, randomSeed = 5)).take(15).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val v = listOf(compiled.decode(schema.x0, s), compiled.decode(schema.x1, s), compiled.decode(schema.x2, s))
            assertTrue(v[0] <= v[1] && v[1] <= v[2], "v=$v not non-decreasing")
        }
    }

    @Test
    fun `strictlyIncreasing forces strict order`() {
        class S : VariableSchema() {
            val x0 by intVar(min = 0, max = 4)
            val x1 by intVar(min = 0, max = 4)
            val x2 by intVar(min = 0, max = 4)
            val ord by constraint { strictlyIncreasing(listOf(x0, x1, x2)) }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver =
            LocalSearchSolver(compiled.problem, restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 500))
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 20_000, randomSeed = 9)).take(15).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val v = listOf(compiled.decode(schema.x0, s), compiled.decode(schema.x1, s), compiled.decode(schema.x2, s))
            assertTrue(v[0] < v[1] && v[1] < v[2], "v=$v not strictly increasing")
        }
    }

    @Test
    fun `decreasing holds in samples`() {
        class S : VariableSchema() {
            val x0 by intVar(min = 0, max = 3)
            val x1 by intVar(min = 0, max = 3)
            val x2 by intVar(min = 0, max = 3)
            val ord by constraint { decreasing(listOf(x0, x1, x2)) }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver =
            LocalSearchSolver(compiled.problem, restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 500))
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 20_000, randomSeed = 7)).take(15).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val v = listOf(compiled.decode(schema.x0, s), compiled.decode(schema.x1, s), compiled.decode(schema.x2, s))
            assertTrue(v[0] >= v[1] && v[1] >= v[2], "v=$v not non-increasing")
        }
    }
}
