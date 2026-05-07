package com.eignex.klause.compile

import com.eignex.klause.solver.FixedCadenceRestart
import com.eignex.klause.solver.LocalSearchParams
import com.eignex.klause.ast.gcc
import com.eignex.klause.schema.VariableSchema
import com.eignex.klause.solver.LocalSearchSolver
import kotlin.test.Test
import kotlin.test.assertTrue

class GccDslTest {

    @Test
    fun multiValueGccBoundsHoldInSamples() {
        // 4 vars over {0,1,2}. Require: value 0 appears 1..2 times, value 1 appears 1..2 times,
        // value 2 appears 0..2 times.
        class S : VariableSchema() {
            val a by intVar(min = 0, max = 2)
            val b by intVar(min = 0, max = 2)
            val c by intVar(min = 0, max = 2)
            val d by intVar(min = 0, max = 2)
            val counts by constraint {
                gcc(listOf(a, b, c, d), mapOf(0 to 1..2, 1 to 1..2, 2 to 0..2))
            }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = LocalSearchSolver(compiled.problem, restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 500))
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 30_000, randomSeed = 14)).take(10).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val vs = listOf(schema.a, schema.b, schema.c, schema.d).map { compiled.decode(it, s) }
            val c0 = vs.count { it == 0 }
            val c1 = vs.count { it == 1 }
            val c2 = vs.count { it == 2 }
            assertTrue(c0 in 1..2, "value 0 count=$c0, vs=$vs")
            assertTrue(c1 in 1..2, "value 1 count=$c1, vs=$vs")
            assertTrue(c2 in 0..2, "value 2 count=$c2, vs=$vs")
        }
    }

    @Test
    fun gccCanForceExactDistribution() {
        // 3 vars over {0,1,2}, each value appears exactly once → permutation.
        class S : VariableSchema() {
            val a by intVar(min = 0, max = 2)
            val b by intVar(min = 0, max = 2)
            val c by intVar(min = 0, max = 2)
            val perm by constraint { gcc(listOf(a, b, c), mapOf(0 to 1..1, 1 to 1..1, 2 to 1..1)) }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = LocalSearchSolver(compiled.problem, restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 500))
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 30_000, randomSeed = 33)).take(10).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val vs = listOf(schema.a, schema.b, schema.c).map { compiled.decode(it, s) }
            assertTrue(vs.toSet() == setOf(0, 1, 2), "not a permutation: $vs")
        }
    }
}
