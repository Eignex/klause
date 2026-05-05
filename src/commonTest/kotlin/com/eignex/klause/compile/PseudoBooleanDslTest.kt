package com.eignex.klause.compile

import com.eignex.klause.ast.implies
import com.eignex.klause.ast.pbAtLeast
import com.eignex.klause.ast.pbAtMost
import com.eignex.klause.ast.pbExactly
import com.eignex.klause.schema.VariableSchema
import com.eignex.klause.solver.Solver
import com.eignex.klause.solver.factor.PseudoBoolean
import com.eignex.klause.solver.factor.ReifiedPseudoBoolean
import kotlin.test.Test
import kotlin.test.assertTrue

class PseudoBooleanDslTest {

    @Test
    fun pbAtMostEmitsFactorAtTopLevel() {
        class S : VariableSchema() {
            val a by boolVar()
            val b by boolVar()
            val c by boolVar()
            val cap by constraint { pbAtMost(listOf(3, 2, 5), listOf(a, b, c), 4) }
        }
        val compiled = S().compile()
        assertTrue(compiled.problem.factors.any { it is PseudoBoolean })
    }

    @Test
    fun pbAtMostHoldsInSamples() {
        class S : VariableSchema() {
            val a by boolVar()
            val b by boolVar()
            val c by boolVar()
            val d by boolVar()
            // 3a + 2b + 5c + 1d ≤ 6.
            val cap by constraint { pbAtMost(listOf(3, 2, 5, 1), listOf(a, b, c, d), 6) }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = Solver(compiled.problem, maxFlipsBeforeRestart = 500)
        val samples = solver.sample(maxFlips = 20_000, randomSeed = 31).take(15).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val av = if (compiled.decodeBool("a", s)) 3 else 0
            val bv = if (compiled.decodeBool("b", s)) 2 else 0
            val cv = if (compiled.decodeBool("c", s)) 5 else 0
            val dv = if (compiled.decodeBool("d", s)) 1 else 0
            assertTrue(av + bv + cv + dv <= 6, "sum=${av + bv + cv + dv}")
        }
    }

    @Test
    fun pbAtLeastHoldsInSamples() {
        class S : VariableSchema() {
            val a by boolVar()
            val b by boolVar()
            val c by boolVar()
            // 2a + 3b + 4c ≥ 5.
            val req by constraint { pbAtLeast(listOf(2, 3, 4), listOf(a, b, c), 5) }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = Solver(compiled.problem, maxFlipsBeforeRestart = 500)
        val samples = solver.sample(maxFlips = 20_000, randomSeed = 13).take(15).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val sum = (if (compiled.decodeBool("a", s)) 2 else 0) +
                (if (compiled.decodeBool("b", s)) 3 else 0) +
                (if (compiled.decodeBool("c", s)) 4 else 0)
            assertTrue(sum >= 5, "sum=$sum")
        }
    }

    @Test
    fun pbExactlyHoldsInSamples() {
        class S : VariableSchema() {
            val a by boolVar()
            val b by boolVar()
            val c by boolVar()
            // 2a + 3b + 5c = 5.
            val pin by constraint { pbExactly(listOf(2, 3, 5), listOf(a, b, c), 5) }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = Solver(compiled.problem, maxFlipsBeforeRestart = 500)
        val samples = solver.sample(maxFlips = 20_000, randomSeed = 41).take(10).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val sum = (if (compiled.decodeBool("a", s)) 2 else 0) +
                (if (compiled.decodeBool("b", s)) 3 else 0) +
                (if (compiled.decodeBool("c", s)) 5 else 0)
            assertTrue(sum == 5, "sum=$sum")
        }
    }

    @Test
    fun pbReifiedUnderImplies() {
        class S : VariableSchema() {
            val flag by boolVar()
            val a by boolVar()
            val b by boolVar()
            val c by boolVar()
            val rule by constraint { flag implies pbAtMost(listOf(2, 3, 4), listOf(a, b, c), 4) }
        }
        val compiled = S().compile()
        assertTrue(compiled.problem.factors.any { it is ReifiedPseudoBoolean })
    }
}
