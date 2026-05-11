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
    fun `gcc rejects negative range`() {
        class S : VariableSchema() {
            val a by intVar(min = 0, max = 2)
            val b by intVar(min = 0, max = 2)
            val pin by constraint { gcc(listOf(a, b), mapOf(0 to -2..-1)) }
        }
        assertFails { S() }
    }

    @Test
    fun `gcc rejects range exceeding var count`() {
        class S : VariableSchema() {
            val a by intVar(min = 0, max = 2)
            val b by intVar(min = 0, max = 2)
            val pin by constraint { gcc(listOf(a, b), mapOf(0 to 0..5)) }
        }
        assertFails { S() }
    }

    @Test
    fun `all different pigeonhole rejected`() {
        class S : VariableSchema() {
            val a by intVar(min = 0, max = 1)
            val b by intVar(min = 0, max = 1)
            val c by intVar(min = 0, max = 1)

            val pin by constraint { allDifferent(a, b, c) }
        }
        assertFails { S() }
    }

    @Test
    fun `table tuple out of domain rejected`() {
        class S : VariableSchema() {
            val a by intVar(min = 0, max = 2)
            val b by intVar(min = 0, max = 2)
            val pin by constraint { table(listOf(a, b), listOf(listOf(5, 1))) }
        }
        assertFails { S() }
    }

    @Test
    fun `not table tuple out of domain rejected`() {
        class S : VariableSchema() {
            val a by intVar(min = 0, max = 2)
            val b by intVar(min = 0, max = 2)
            val pin by constraint { notTable(listOf(a, b), listOf(listOf(0, 99))) }
        }
        assertFails { S() }
    }

    @Test
    fun `solver sample min hamming distance too large rejected`() {
        class S : VariableSchema() {
            val a by boolVar()
            val b by boolVar()
        }
        val compiled = S().compile()
        val solver = LocalSearchSolver(compiled.problem)

        assertFails { solver.enumerate(LocalSearchParams(minHammingDistance = 3)).take(1).toList() }
    }
}
