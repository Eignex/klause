package com.eignex.klause.schema

import com.eignex.klause.compile.compile
import com.eignex.klause.factor.arithmetic.ReifiedCardinality
import com.eignex.klause.factor.bool.Cardinality
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OperatorsTest {

    @Test
    fun `at most at top level emits a cardinality bounded by the given max`() {
        class S : VariableSchema() {
            val a by boolVar()
            val b by boolVar()
            val c by boolVar()
            val d by boolVar()
            val cap by constraint { atMost(2, a, b, c, d) }
        }
        val compiled = S().compile()
        val card = compiled.problem.factors.single { it is Cardinality } as Cardinality
        assertEquals(0, card.min)
        assertEquals(2, card.max)
    }

    @Test
    fun `at least nested reifies`() {
        class S : VariableSchema() {
            val flag by boolVar()
            val a by boolVar()
            val b by boolVar()
            val c by boolVar()
            val d by boolVar()
            val rule by constraint { flag implies atLeast(2, a, b, c, d) }
        }
        val compiled = S().compile()
        assertTrue(compiled.problem.factors.any { it is ReifiedCardinality })
    }
}
