package com.eignex.klause.compile

import com.eignex.klause.ast.implies
import com.eignex.klause.schema.VariableSchema
import com.eignex.klause.schema.allDifferent
import com.eignex.klause.solver.factor.AllDifferent
import com.eignex.klause.solver.localsearch.FixedCadenceRestart
import com.eignex.klause.solver.localsearch.LocalSearchParams
import com.eignex.klause.solver.localsearch.LocalSearchSolver
import kotlin.test.Test
import kotlin.test.assertTrue

class AllDifferentDslTest {

    @Test
    fun `three ints all different in samples`() {
        class S : VariableSchema() {
            val a by intVar(min = 1, max = 3)
            val b by intVar(min = 1, max = 3)
            val c by intVar(min = 1, max = 3)
            val unique by constraint { allDifferent(a, b, c) }
        }
        val schema = S()
        val compiled = schema.compile()

        assertTrue(compiled.problem.factors.any { it is AllDifferent })
        val solver =
            LocalSearchSolver(compiled.problem, restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 500))
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 30_000, randomSeed = 4)).take(8).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val av = compiled.decode(schema.a, s)
            val bv = compiled.decode(schema.b, s)
            val cv = compiled.decode(schema.c, s)
            assertTrue(setOf(av, bv, cv).size == 3, "duplicates: a=$av b=$bv c=$cv")
        }
    }

    @Test
    fun `all different reified under implies`() {
        class S : VariableSchema() {
            val flag by boolVar()
            val a by intVar(min = 0, max = 2)
            val b by intVar(min = 0, max = 2)
            val c by intVar(min = 0, max = 2)
            val rule by constraint { flag implies allDifferent(a, b, c) }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver =
            LocalSearchSolver(compiled.problem, restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 500))
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 20_000, randomSeed = 9)).take(20).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val flag = compiled.decode(schema.flag, s)
            if (!flag) continue
            val av = compiled.decode(schema.a, s)
            val bv = compiled.decode(schema.b, s)
            val cv = compiled.decode(schema.c, s)
            assertTrue(setOf(av, bv, cv).size == 3, "flag set but a=$av b=$bv c=$cv")
        }
    }
}
