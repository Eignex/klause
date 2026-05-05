package com.eignex.klause.compile

import com.eignex.klause.ast.implies
import com.eignex.klause.ast.notTable
import com.eignex.klause.ast.table
import com.eignex.klause.schema.VariableSchema
import com.eignex.klause.solver.Solver
import kotlin.test.Test
import kotlin.test.assertTrue

class TableDslTest {

    @Test
    fun positiveTableForcesOneOfAllowedTuples() {
        class S : VariableSchema() {
            val x by intVar(min = 0, max = 3)
            val y by intVar(min = 0, max = 3)
            val rel by constraint {
                table(listOf(x, y), listOf(listOf(0, 1), listOf(2, 2), listOf(3, 0)))
            }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = Solver(compiled.problem, maxFlipsBeforeRestart = 500)
        val samples = solver.sample(maxFlips = 20_000, randomSeed = 11).take(10).toList()
        assertTrue(samples.isNotEmpty())
        val allowed = setOf(0 to 1, 2 to 2, 3 to 0)
        for (s in samples) {
            val xv = compiled.decodeInt("x", s)
            val yv = compiled.decodeInt("y", s)
            assertTrue((xv to yv) in allowed, "($xv, $yv) not in allowed table")
        }
    }

    @Test
    fun negativeTableForbidsListedTuples() {
        class S : VariableSchema() {
            val x by intVar(min = 0, max = 2)
            val y by intVar(min = 0, max = 2)
            // Forbid (1,1) and (2,2).
            val rel by constraint {
                notTable(listOf(x, y), listOf(listOf(1, 1), listOf(2, 2)))
            }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = Solver(compiled.problem, maxFlipsBeforeRestart = 500)
        val samples = solver.sample(maxFlips = 20_000, randomSeed = 5).take(15).toList()
        assertTrue(samples.isNotEmpty())
        val forbidden = setOf(1 to 1, 2 to 2)
        for (s in samples) {
            val xv = compiled.decodeInt("x", s)
            val yv = compiled.decodeInt("y", s)
            assertTrue((xv to yv) !in forbidden, "($xv, $yv) should be forbidden")
        }
    }

    @Test
    fun reifiedTableUnderImplies() {
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
        val solver = Solver(compiled.problem, maxFlipsBeforeRestart = 500)
        val samples = solver.sample(maxFlips = 20_000, randomSeed = 25).take(20).toList()
        assertTrue(samples.isNotEmpty())
        val allowed = setOf(0 to 0, 2 to 1)
        for (s in samples) {
            if (!compiled.decodeBool("flag", s)) continue
            val xv = compiled.decodeInt("x", s)
            val yv = compiled.decodeInt("y", s)
            assertTrue((xv to yv) in allowed, "flag set, ($xv,$yv) not in $allowed")
        }
    }
}
