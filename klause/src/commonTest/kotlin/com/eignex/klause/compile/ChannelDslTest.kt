package com.eignex.klause.compile

import com.eignex.klause.solver.FixedCadenceRestart
import com.eignex.klause.solver.LocalSearchParams
import com.eignex.klause.ast.channel
import com.eignex.klause.schema.VariableSchema
import com.eignex.klause.solver.LocalSearchSolver
import kotlin.test.Test
import kotlin.test.assertTrue

class ChannelDslTest {

    @Test
    fun `channel links int to one hot booleans`() {
        class S : VariableSchema() {
            val idx by intVar(min = 0, max = 3)
            val b0 by boolVar()
            val b1 by boolVar()
            val b2 by boolVar()
            val b3 by boolVar()
            val link by constraint { channel(idx, listOf(b0, b1, b2, b3)) }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = LocalSearchSolver(compiled.problem, restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 500))
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 20_000, randomSeed = 21)).take(10).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val i = compiled.decode(schema.idx, s)
            val flags = listOf(schema.b0, schema.b1, schema.b2, schema.b3).map { compiled.decode(it, s) }
            for (j in flags.indices) {
                assertTrue(flags[j] == (i == j), "i=$i flags=$flags mismatch at j=$j")
            }
        }
    }

    @Test
    fun `channel honours offset`() {
        class S : VariableSchema() {
            val idx by intVar(min = 5, max = 7)
            val a by boolVar()
            val b by boolVar()
            val c by boolVar()
            val link by constraint { channel(idx, listOf(a, b, c), offset = 5) }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = LocalSearchSolver(compiled.problem, restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 500))
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 20_000, randomSeed = 6)).take(10).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val i = compiled.decode(schema.idx, s)
            val flags = listOf(schema.a, schema.b, schema.c).map { compiled.decode(it, s) }
            for (j in flags.indices) {
                assertTrue(flags[j] == (i == 5 + j), "i=$i flags=$flags mismatch at j=$j")
            }
        }
    }
}
