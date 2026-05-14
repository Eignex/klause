package com.eignex.klause.compile

import com.eignex.klause.solver.localsearch.FixedCadenceRestart
import com.eignex.klause.solver.localsearch.LocalSearchParams
import com.eignex.klause.ast.element
import com.eignex.klause.ast.eq
import com.eignex.klause.ast.ge
import com.eignex.klause.schema.VariableSchema
import com.eignex.klause.solver.localsearch.LocalSearchSolver
import kotlin.test.Test
import kotlin.test.assertTrue

class ElementDslTest {

    @Test
    fun `element over int vars picks constrained item`() {
        class S : VariableSchema() {
            val idx by intVar(min = 0, max = 2)
            val a by intVar(min = 0, max = 9)
            val b by intVar(min = 0, max = 9)
            val c by intVar(min = 0, max = 9)
            val pin by constraint { element(idx, listOf(a, b, c)) eq 7 }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = LocalSearchSolver(compiled.problem, restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 500))
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 30_000, randomSeed = 5)).take(8).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val i = compiled.decode(schema.idx, s)
            val items = listOf(schema.a, schema.b, schema.c).map { compiled.decode(it, s) }
            assertTrue(i in items.indices)
            assertTrue(items[i] == 7, "items[$i]=${items[i]}, expected 7 (a=${items[0]} b=${items[1]} c=${items[2]})")
        }
    }

    @Test
    fun `element with larger index domain constrains to valid range`() {

        class S : VariableSchema() {
            val idx by intVar(min = 0, max = 5)
            val a by intVar(min = 0, max = 5)
            val b by intVar(min = 0, max = 5)
            val c by intVar(min = 0, max = 5)
            val pin by constraint { element(idx, listOf(a, b, c)) ge 3 }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = LocalSearchSolver(compiled.problem, restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 500))
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 30_000, randomSeed = 13)).take(8).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val i = compiled.decode(schema.idx, s)
            assertTrue(i in 0..2, "idx=$i out of items range")
            val items = listOf(schema.a, schema.b, schema.c).map { compiled.decode(it, s) }
            assertTrue(items[i] >= 3, "items[$i]=${items[i]} < 3")
        }
    }
}
