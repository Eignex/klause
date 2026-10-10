package com.eignex.klause.schema

import com.eignex.klause.compile.compile
import com.eignex.klause.factor.arithmetic.ReifiedPseudoBoolean
import kotlin.test.Test
import kotlin.test.assertFails
import kotlin.test.assertTrue

class BuildersTest {

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
