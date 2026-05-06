package com.eignex.klause.compile

import com.eignex.klause.solver.LocalSearchParams
import com.eignex.klause.ast.allDifferent
import com.eignex.klause.ast.gcc
import com.eignex.klause.ast.notTable
import com.eignex.klause.ast.table
import com.eignex.klause.schema.VariableSchema
import com.eignex.klause.solver.LocalSearchSolver
import kotlin.test.Test
import kotlin.test.assertFails

class DslValidationTest {

    @Test
    fun gccRejectsNegativeRange() {
        class S : VariableSchema() {
            val a by intVar(min = 0, max = 2)
            val b by intVar(min = 0, max = 2)
            val pin by constraint { gcc(listOf(a, b), mapOf(0 to -2..-1)) }
        }
        assertFails { S() }
    }

    @Test
    fun gccRejectsRangeExceedingVarCount() {
        class S : VariableSchema() {
            val a by intVar(min = 0, max = 2)
            val b by intVar(min = 0, max = 2)
            val pin by constraint { gcc(listOf(a, b), mapOf(0 to 0..5)) }
        }
        assertFails { S() }
    }

    @Test
    fun allDifferentPigeonholeRejected() {
        class S : VariableSchema() {
            val a by intVar(min = 0, max = 1)
            val b by intVar(min = 0, max = 1)
            val c by intVar(min = 0, max = 1)
            // 3 vars over a 2-value domain — pigeonhole UNSAT.
            val pin by constraint { allDifferent(a, b, c) }
        }
        assertFails { S() }
    }

    @Test
    fun tableTupleOutOfDomainRejected() {
        class S : VariableSchema() {
            val a by intVar(min = 0, max = 2)
            val b by intVar(min = 0, max = 2)
            val pin by constraint { table(listOf(a, b), listOf(listOf(5, 1))) }
        }
        assertFails { S() }
    }

    @Test
    fun notTableTupleOutOfDomainRejected() {
        class S : VariableSchema() {
            val a by intVar(min = 0, max = 2)
            val b by intVar(min = 0, max = 2)
            val pin by constraint { notTable(listOf(a, b), listOf(listOf(0, 99))) }
        }
        assertFails { S() }
    }

    @Test
    fun solverSampleMinHammingDistanceTooLargeRejected() {
        class S : VariableSchema() {
            val a by boolVar()
            val b by boolVar()
        }
        val compiled = S().compile()
        val solver = LocalSearchSolver(compiled.problem)
        // totalBits = 2; demanding distance 3 is impossible.
        assertFails { solver.enumerate(LocalSearchParams(minHammingDistance = 3)).take(1).toList() }
    }
}
