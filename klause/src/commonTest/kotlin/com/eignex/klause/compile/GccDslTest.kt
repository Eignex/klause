package com.eignex.klause.compile

import com.eignex.klause.localsearch.FixedCadenceRestart
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.schema.VariableSchema
import com.eignex.klause.schema.gcc
import kotlin.test.Test
import kotlin.test.assertTrue

class GccDslTest {

    @Test
    fun `multi value gcc bounds hold in samples`() {
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
        val solver =
            LocalSearchSolver(compiled.problem, restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 500))
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 30_000, randomSeed = 14)).take(10).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val vs = listOf(schema.a, schema.b, schema.c, schema.d).map { compiled.decode(it, s) }
            val c0 = vs.count { it == 0L }
            val c1 = vs.count { it == 1L }
            val c2 = vs.count { it == 2L }
            assertTrue(c0 in 1..2, "value 0 count=$c0, vs=$vs")
            assertTrue(c1 in 1..2, "value 1 count=$c1, vs=$vs")
            assertTrue(c2 in 0..2, "value 2 count=$c2, vs=$vs")
        }
    }

    @Test
    fun `gcc can force exact distribution`() {
        class S : VariableSchema() {
            val a by intVar(min = 0, max = 2)
            val b by intVar(min = 0, max = 2)
            val c by intVar(min = 0, max = 2)
            val perm by constraint { gcc(listOf(a, b, c), mapOf(0 to 1..1, 1 to 1..1, 2 to 1..1)) }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver =
            LocalSearchSolver(compiled.problem, restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 500))
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 30_000, randomSeed = 33)).take(10).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val vs = listOf(schema.a, schema.b, schema.c).map { compiled.decode(it, s) }
            assertTrue(vs.toSet() == setOf(0L, 1L, 2L), "not a permutation: $vs")
        }
    }
}
