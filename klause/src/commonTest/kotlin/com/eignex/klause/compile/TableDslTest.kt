package com.eignex.klause.compile

import com.eignex.klause.localsearch.FixedCadenceRestart
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.schema.VariableSchema
import com.eignex.klause.schema.implies
import com.eignex.klause.schema.notTable
import com.eignex.klause.schema.table
import kotlin.test.Test
import kotlin.test.assertTrue

class TableDslTest {

    @Test
    fun `positive table forces one of allowed tuples`() {
        class S : VariableSchema() {
            val x by intVar(min = 0, max = 3)
            val y by intVar(min = 0, max = 3)
            val rel by constraint {
                table(listOf(x, y), listOf(listOf(0, 1), listOf(2, 2), listOf(3, 0)))
            }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = LocalSearchSolver(
            compiled.problem,
            restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 500),
        )
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 20_000, randomSeed = 11)).take(10).toList()
        assertTrue(samples.isNotEmpty())
        val allowed = setOf(0L to 1L, 2L to 2L, 3L to 0L)
        for (s in samples) {
            val xv = compiled.decode(schema.x, s)
            val yv = compiled.decode(schema.y, s)
            assertTrue((xv to yv) in allowed, "($xv, $yv) not in allowed table")
        }
    }

    @Test
    fun `negative table forbids listed tuples`() {
        class S : VariableSchema() {
            val x by intVar(min = 0, max = 2)
            val y by intVar(min = 0, max = 2)

            val rel by constraint {
                notTable(listOf(x, y), listOf(listOf(1, 1), listOf(2, 2)))
            }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = LocalSearchSolver(
            compiled.problem,
            restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 500),
        )
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 20_000, randomSeed = 5)).take(15).toList()
        assertTrue(samples.isNotEmpty())
        val forbidden = setOf(1L to 1L, 2L to 2L)
        for (s in samples) {
            val xv = compiled.decode(schema.x, s)
            val yv = compiled.decode(schema.y, s)
            assertTrue((xv to yv) !in forbidden, "($xv, $yv) should be forbidden")
        }
    }

    @Test
    fun `reified table under implies`() {
        class S : VariableSchema() {
            val flag by boolVar()
            val x by intVar(min = 0, max = 2)
            val y by intVar(min = 0, max = 2)
            val rule by constraint {
                flag implies table(listOf(x, y), listOf(listOf(0, 0), listOf(2, 1)))
            }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = LocalSearchSolver(
            compiled.problem,
            restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 500),
        )
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 20_000, randomSeed = 25)).take(20).toList()
        assertTrue(samples.isNotEmpty())
        val allowed = setOf(0L to 0L, 2L to 1L)
        for (s in samples) {
            if (!compiled.decode(schema.flag, s)) continue
            val xv = compiled.decode(schema.x, s)
            val yv = compiled.decode(schema.y, s)
            assertTrue((xv to yv) in allowed, "flag set, ($xv,$yv) not in $allowed")
        }
    }
}
