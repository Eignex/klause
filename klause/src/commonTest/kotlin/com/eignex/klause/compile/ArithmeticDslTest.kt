package com.eignex.klause.compile

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.localsearch.FixedCadenceRestart
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.schema.VariableSchema
import com.eignex.klause.schema.ge
import com.eignex.klause.schema.implies
import com.eignex.klause.schema.le
import com.eignex.klause.schema.minus
import com.eignex.klause.schema.plus
import com.eignex.klause.schema.times
import com.eignex.klause.schema.unaryMinus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArithmeticDslTest {

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
        assertEquals(7L, linear.bound)
        assertEquals(2, linear.coeffs.size)
        assertTrue(linear.coeffs.all { it == 1L })
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
        assertEquals(setOf(2L, 3L), linear.coeffs.toSet())
        assertEquals(10L, linear.bound)
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

        assertEquals(LinearOp.GE, linear.op)
        assertEquals(2L, linear.bound)
        assertEquals(setOf(1L, -1L), linear.coeffs.toSet())
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
        assertEquals(10, lin.bound)
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

    @Test
    fun `arithmetic end to end solve satisfies predicate`() {
        class S : VariableSchema() {
            val x by intVar(min = 0, max = 5)
            val y by intVar(min = 0, max = 5)
            val sumCap by constraint { x + y le 6 }
            val xLeY by constraint { x le y }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver =
            LocalSearchSolver(compiled.problem, restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 500))
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 20_000, randomSeed = 17)).take(10).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val xv = compiled.decode(schema.x, s)
            val yv = compiled.decode(schema.y, s)
            assertTrue(xv + yv <= 6, "x+y=${xv + yv}")
            assertTrue(xv <= yv, "x=$xv y=$yv")
        }
    }

    @Test
    fun `negative unary and inequality match`() {
        class S : VariableSchema() {
            val x by intVar(min = 0, max = 5)
            val nonZero by constraint { -x le -1 }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver =
            LocalSearchSolver(compiled.problem, restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 200))
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 5_000, randomSeed = 3)).take(5).toList()
        for (s in samples) {
            val xv = compiled.decode(schema.x, s)
            assertTrue(xv >= 1, "Expected x≥1, got $xv")
        }
    }
}
