package com.eignex.klause.schema

import com.eignex.klause.model.IntExpr
import com.eignex.skema.SchemaJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class BuildersTest {
    @Test
    fun `gcc should reject a negative range`() {
        class S : VariableSchema() {
            val a by intVar(min = 0, max = 2)
            val b by intVar(min = 0, max = 2)
            val pin by constraint { gcc(listOf(a, b), mapOf(0 to -2..-1)) }
        }
        assertFails { S() }
    }

    @Test
    fun `gcc should reject a range exceeding the var count`() {
        class S : VariableSchema() {
            val a by intVar(min = 0, max = 2)
            val b by intVar(min = 0, max = 2)
            val pin by constraint { gcc(listOf(a, b), mapOf(0 to 0..5)) }
        }
        assertFails { S() }
    }

    @Test
    fun `all different should reject a pigeonhole violation`() {
        class S : VariableSchema() {
            val a by intVar(min = 0, max = 1)
            val b by intVar(min = 0, max = 1)
            val c by intVar(min = 0, max = 1)

            val pin by constraint { allDifferent(a, b, c) }
        }
        assertFails { S() }
    }

    @Test
    fun `table should reject a tuple out of domain`() {
        class S : VariableSchema() {
            val a by intVar(min = 0, max = 2)
            val b by intVar(min = 0, max = 2)
            val pin by constraint { table(listOf(a, b), listOf(listOf(5, 1))) }
        }
        assertFails { S() }
    }

    @Test
    fun `not table should reject a tuple out of domain`() {
        class S : VariableSchema() {
            val a by intVar(min = 0, max = 2)
            val b by intVar(min = 0, max = 2)
            val pin by constraint { notTable(listOf(a, b), listOf(listOf(0, 99))) }
        }
        assertFails { S() }
    }

    @Test
    fun `portable helpers should encode to the shared expression keys`() {
        val a = IntHandle("a", -9, 9)
        val b = IntHandle("b", -9, 9)

        val encoded = listOf(a.abs(), a.min(b), a.max(b), a.pow(3), ifThenElse(a gt 0, a, b))
            .map { SchemaJson.encodeToString(IntExpr.serializer(), it) }

        assertEquals(
            listOf(
                """{"abs":{"ref":"a"}}""",
                """{"min":[{"ref":"a"},{"ref":"b"}]}""",
                """{"max":[{"ref":"a"},{"ref":"b"}]}""",
                """{"pow":[{"ref":"a"},3]}""",
                """{"if":[{"gt":[{"ref":"a"},0]},{"ref":"a"},{"ref":"b"}]}""",
            ),
            encoded,
        )
    }
}
