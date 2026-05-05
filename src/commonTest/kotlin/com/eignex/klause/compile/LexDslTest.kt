package com.eignex.klause.compile

import com.eignex.klause.ast.lexLeq
import com.eignex.klause.ast.lexLt
import com.eignex.klause.schema.VariableSchema
import com.eignex.klause.solver.Solver
import kotlin.test.Test
import kotlin.test.assertTrue

class LexDslTest {

    @Test
    fun lexLeqHoldsInSamples() {
        class S : VariableSchema() {
            val a0 by intVar(min = 0, max = 3)
            val a1 by intVar(min = 0, max = 3)
            val b0 by intVar(min = 0, max = 3)
            val b1 by intVar(min = 0, max = 3)
            val ord by constraint { lexLeq(listOf(a0, a1), listOf(b0, b1)) }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = Solver(compiled.problem, maxFlipsBeforeRestart = 500)
        val samples = solver.sample(maxFlips = 20_000, randomSeed = 8).take(15).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val a = listOf(compiled.decodeInt("a0", s), compiled.decodeInt("a1", s))
            val b = listOf(compiled.decodeInt("b0", s), compiled.decodeInt("b1", s))
            val ok = a[0] < b[0] || (a[0] == b[0] && a[1] <= b[1])
            assertTrue(ok, "a=$a b=$b violates lexLeq")
        }
    }

    @Test
    fun lexLtForcesStrictOrder() {
        class S : VariableSchema() {
            val a0 by intVar(min = 0, max = 2)
            val a1 by intVar(min = 0, max = 2)
            val b0 by intVar(min = 0, max = 2)
            val b1 by intVar(min = 0, max = 2)
            val ord by constraint { lexLt(listOf(a0, a1), listOf(b0, b1)) }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = Solver(compiled.problem, maxFlipsBeforeRestart = 500)
        val samples = solver.sample(maxFlips = 20_000, randomSeed = 12).take(15).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val a = listOf(compiled.decodeInt("a0", s), compiled.decodeInt("a1", s))
            val b = listOf(compiled.decodeInt("b0", s), compiled.decodeInt("b1", s))
            val ok = a[0] < b[0] || (a[0] == b[0] && a[1] < b[1])
            assertTrue(ok, "a=$a b=$b not strictly lex-less")
        }
    }
}
