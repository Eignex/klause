package com.eignex.klause.schema

import com.eignex.klause.compile.compile
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.model.IntLit
import com.eignex.klause.model.IntRef
import com.eignex.klause.model.IntScale
import com.eignex.klause.model.IntSum
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IntOperatorsTest {
    @Test
    fun `a sum of two ints at top level should emit a linear`() {
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
    fun `scaled terms should carry their coefficients`() {
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
    fun `subtraction should compile to a canonical linear`() {
        class S : VariableSchema() {
            val x by intVar(min = 0, max = 10)
            val y by intVar(min = 0, max = 10)
            val cap by constraint { x - y ge 2 }
        }
        val compiled = S().compile()
        val linear = compiled.problem.factors.single { it is Linear } as Linear

        // x - y >= 2 is canonicalised to -x + y <= -2.
        assertEquals(LinearOp.LE, linear.op)
        assertEquals(-2L, checkNotNull(linear.integerConstants).bound)
        assertEquals(setOf(1L, -1L), checkNotNull(linear.integerConstants).coeffs.toSet())
    }

    @Test
    fun `a single var constraint should collapse to a single-term Linear`() {
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
    fun `a single var compare under implies should compile to a reified linear`() {
        class S : VariableSchema() {
            val flag by boolVar()
            val budget by intVar(min = 0, max = 100)
            val capWhenFlag by constraint { flag implies (budget le 50) }
        }
        val compiled = S().compile()
        assertTrue(compiled.problem.factors.any { it is ReifiedLinear && it.vars.size == 1 })
    }

    @Test
    fun `a multi var compare under implies should compile to a reified linear`() {
        class S : VariableSchema() {
            val flag by boolVar()
            val x by intVar(min = 0, max = 10)
            val y by intVar(min = 0, max = 10)
            val capSum by constraint { flag implies (x + y le 5) }
        }
        val compiled = S().compile()
        assertTrue(compiled.problem.factors.any { it is ReifiedLinear })
    }

    @Test
    fun `negating an Int MIN_VALUE literal should keep its exact magnitude`() {
        assertEquals(IntScale(-1, IntLit(Int.MIN_VALUE)), -IntLit(Int.MIN_VALUE))
    }

    @Test
    fun `subtracting an Int MIN_VALUE constant should keep its exact magnitude`() {
        val x = IntRef("x")
        assertEquals(IntSum(listOf(x, IntScale(-1, IntLit(Int.MIN_VALUE)))), x - Int.MIN_VALUE)
    }

    @Test
    fun `negating an Int MIN_VALUE coefficient should keep its exact magnitude`() {
        val scaled = IntScale(Int.MIN_VALUE, IntRef("x"))
        assertEquals(IntScale(-1, scaled), -scaled)
    }

    @Test
    fun `subtracting the smallest negatable constant should fold to Int MAX_VALUE`() {
        val x = IntRef("x")
        assertEquals(IntSum(listOf(x, IntLit(Int.MAX_VALUE))), x - (Int.MIN_VALUE + 1))
    }

    @Test
    fun `subtracting Int MIN_VALUE should fail compilation instead of wrapping`() {
        class S : VariableSchema() {
            val x by intVar(min = 0, max = 10)
            val cap by constraint { x - Int.MIN_VALUE le 0 }
        }
        assertFailsWith<IllegalArgumentException> { S().compile() }
    }

    @Test
    fun `division and remainder should accept a literal on either side`() {
        val a = IntHandle("a", 1, 9)

        assertEquals(
            listOf(a / IntLit(2), IntLit(7) / a, a % IntLit(2), IntLit(7) % a),
            listOf(a / 2, 7 / a, a % 2, 7 % a),
        )
    }
}
