package com.eignex.klause.schema

import com.eignex.klause.compile.compile
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.ir.LinearOp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IntOperatorsTest {

    @Test
    fun `sum of two ints at top level emits linear`() {
        class S : VariableSchema() {
            val x by intVar(min = 0, max = 5)
            val y by intVar(min = 0, max = 5)
            val cap by constraint { x + y le 7 }
        }
        val compiled = S().compile()
        val linear = compiled.problem.factors.single { it is Linear } as Linear
        assertEquals(LinearOp.LE, linear.op)
        assertEquals(7L, checkNotNull(linear.integerConstants).bound)
        assertEquals(2, checkNotNull(linear.integerConstants).coeffs.size)
        assertTrue(checkNotNull(linear.integerConstants).coeffs.all { it == 1L })
    }

    @Test
    fun `scaled terms carry coefficients`() {
        class S : VariableSchema() {
            val x by intVar(min = 0, max = 4)
            val y by intVar(min = 0, max = 4)
            val cap by constraint { 2 * x + 3 * y le 10 }
        }
        val compiled = S().compile()
        val linear = compiled.problem.factors.single { it is Linear } as Linear
        assertEquals(setOf(2L, 3L), checkNotNull(linear.integerConstants).coeffs.toSet())
        assertEquals(10L, checkNotNull(linear.integerConstants).bound)
    }

    @Test
    fun `subtraction and unary minus`() {
        class S : VariableSchema() {
            val x by intVar(min = 0, max = 10)
            val y by intVar(min = 0, max = 10)
            val cap by constraint { x - y ge 2 }
        }
        val compiled = S().compile()
        val linear = compiled.problem.factors.single { it is Linear } as Linear

        // `x - y ≥ 2` is canonicalised to `≤` at construction: `−x + y ≤ −2`.
        assertEquals(LinearOp.LE, linear.op)
        assertEquals(-2L, checkNotNull(linear.integerConstants).bound)
        assertEquals(setOf(1L, -1L), checkNotNull(linear.integerConstants).coeffs.toSet())
    }

    @Test
    fun `single var constraint collapses to single-term Linear`() {
        class S : VariableSchema() {
            val x by intVar(min = 0, max = 100)
            val y by intVar(min = 0, max = 100)
            val cap by constraint { (x + y) - y le 10 }
        }
        val compiled = S().compile()

        val lin = compiled.problem.factors.single { it is Linear } as Linear
        assertEquals(LinearOp.LE, lin.op)
        assertEquals(10, checkNotNull(lin.integerConstants).bound)
        assertEquals(1, lin.vars.size)
    }

    @Test
    fun `reified single var compare`() {
        class S : VariableSchema() {
            val flag by boolVar()
            val budget by intVar(min = 0, max = 100)
            val capWhenFlag by constraint { flag implies (budget le 50) }
        }
        val compiled = S().compile()
        assertTrue(compiled.problem.factors.any { it is ReifiedLinear && it.vars.size == 1 })
    }

    @Test
    fun `reified linear for multi var inside implies`() {
        class S : VariableSchema() {
            val flag by boolVar()
            val x by intVar(min = 0, max = 10)
            val y by intVar(min = 0, max = 10)
            val capSum by constraint { flag implies (x + y le 5) }
        }
        val compiled = S().compile()
        assertTrue(compiled.problem.factors.any { it is ReifiedLinear })
    }

}
