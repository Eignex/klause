package com.eignex.klause.schema

import com.eignex.klause.compile.compile
import com.eignex.klause.factor.arithmetic.ReifiedPseudoBoolean
import com.eignex.klause.model.IntExpr
import com.eignex.klause.model.IntLit
import com.eignex.klause.model.IntMax
import com.eignex.klause.model.IntMin
import com.eignex.klause.model.IntRef
import com.eignex.skema.SchemaJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

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
    fun `pbAtMost under implies should compile to a reified pseudo-boolean`() {
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

    @Test
    fun `min and max should accept a literal operand`() {
        val a = IntHandle("a", -9, 9)

        assertEquals(listOf(a.min(IntLit(3)), a.max(IntLit(3))), listOf(a.min(3), a.max(3)))
    }

    @Test
    fun `conditional branches should accept literals`() {
        val a = IntHandle("a", -9, 9)
        val p = BoolHandle("p")

        assertEquals(
            listOf(ifThenElse(p, IntLit(1), a), ifThenElse(p, a, IntLit(0)), ifThenElse(p, IntLit(1), IntLit(0))),
            listOf(ifThenElse(p, 1, a), ifThenElse(p, a, 0), ifThenElse(p, 1, 0)),
        )
    }

    @Test
    fun `minOf and maxOf should keep every operand in one node`() {
        val a = IntHandle("a", -9, 9)
        val b = IntHandle("b", -9, 9)

        assertEquals(
            listOf(IntMin(listOf(IntRef("a"), IntRef("b"), IntRef("a"))), IntMax(listOf(IntRef("a"), IntRef("b")))),
            listOf(minOf(a, b, a), maxOf(a, b)),
        )
    }
}
