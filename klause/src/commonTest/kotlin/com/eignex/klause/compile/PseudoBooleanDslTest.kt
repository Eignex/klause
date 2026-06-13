package com.eignex.klause.compile

import com.eignex.klause.schema.VariableSchema
import com.eignex.klause.schema.implies
import com.eignex.klause.schema.pbAtLeast
import com.eignex.klause.schema.pbAtMost
import com.eignex.klause.schema.pbExactly
import com.eignex.klause.solver.factor.PseudoBoolean
import com.eignex.klause.solver.factor.ReifiedPseudoBoolean
import com.eignex.klause.solver.localsearch.FixedCadenceRestart
import com.eignex.klause.solver.localsearch.LocalSearchParams
import com.eignex.klause.solver.localsearch.LocalSearchSolver
import kotlin.test.Test
import kotlin.test.assertTrue

class PseudoBooleanDslTest {

    @Test
    fun `pb at most emits factor at top level`() {
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
    fun `pb at most holds in samples`() {
        class S : VariableSchema() {
            val a by boolVar()
            val b by boolVar()
            val c by boolVar()
            val d by boolVar()

            val cap by constraint { pbAtMost(listOf(3, 2, 5, 1), listOf(a, b, c, d), 6) }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = LocalSearchSolver(
            compiled.problem,
            restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 500),
        )
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 20_000, randomSeed = 31)).take(15).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val av = if (compiled.decode(schema.a, s)) 3 else 0
            val bv = if (compiled.decode(schema.b, s)) 2 else 0
            val cv = if (compiled.decode(schema.c, s)) 5 else 0
            val dv = if (compiled.decode(schema.d, s)) 1 else 0
            assertTrue(av + bv + cv + dv <= 6, "sum=${av + bv + cv + dv}")
        }
    }

    @Test
    fun `pb at least holds in samples`() {
        class S : VariableSchema() {
            val a by boolVar()
            val b by boolVar()
            val c by boolVar()

            val req by constraint { pbAtLeast(listOf(2, 3, 4), listOf(a, b, c), 5) }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = LocalSearchSolver(
            compiled.problem,
            restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 500),
        )
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 20_000, randomSeed = 13)).take(15).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val sum = (if (compiled.decode(schema.a, s)) 2 else 0) +
                (if (compiled.decode(schema.b, s)) 3 else 0) +
                (if (compiled.decode(schema.c, s)) 4 else 0)
            assertTrue(sum >= 5, "sum=$sum")
        }
    }

    @Test
    fun `pb exactly holds in samples`() {
        class S : VariableSchema() {
            val a by boolVar()
            val b by boolVar()
            val c by boolVar()

            val pin by constraint { pbExactly(listOf(2, 3, 5), listOf(a, b, c), 5) }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = LocalSearchSolver(
            compiled.problem,
            restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 500),
        )
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 20_000, randomSeed = 41)).take(10).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val sum = (if (compiled.decode(schema.a, s)) 2 else 0) +
                (if (compiled.decode(schema.b, s)) 3 else 0) +
                (if (compiled.decode(schema.c, s)) 5 else 0)
            assertTrue(sum == 5, "sum=$sum")
        }
    }

    @Test
    fun `pb reified under implies`() {
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
