package com.eignex.klause.compile

import com.eignex.klause.solver.localsearch.FixedCadenceRestart
import com.eignex.klause.solver.localsearch.LocalSearchParams
import com.eignex.klause.ast.ge
import com.eignex.klause.ast.implies
import com.eignex.klause.ast.le
import com.eignex.klause.ast.minus
import com.eignex.klause.ast.plus
import com.eignex.klause.ast.times
import com.eignex.klause.ast.unaryMinus
import com.eignex.klause.cnf.BitBlaster
import com.eignex.klause.schema.VariableSchema
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.localsearch.LocalSearchSolver
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.factor.ReifiedLinear
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
        assertEquals(7, linear.bound)
        assertEquals(2, linear.coeffs.size)
        assertTrue(linear.coeffs.all { it == 1 })
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
        assertEquals(setOf(2, 3), linear.coeffs.toSet())
        assertEquals(10, linear.bound)
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
        assertEquals(2, linear.bound)
        assertEquals(setOf(1, -1), linear.coeffs.toSet())
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
        // After the collapse of ReifiedIntCompare into ReifiedLinear, the single-var
        // reified compare emits a ReifiedLinear with one term.
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
        val solver = LocalSearchSolver(compiled.problem, restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 500))
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
    fun `bit blaster accepts arithmetic problem`() {
        class S : VariableSchema() {
            val flag by boolVar()
            val x by intVar(min = 0, max = 5)
            val y by intVar(min = 0, max = 5)
            val capSum by constraint { flag implies (x + y le 4) }
        }
        val compiled = S().compile()
        val cnf = BitBlaster.compile(compiled.problem)

        assertTrue(cnf.clauses.isNotEmpty())

        cnf.decodeInt(compiled.intVarIdByName["x"]!!, BooleanArray(cnf.numVars))
    }

    @Test
    fun `negative unary and inequality match`() {
        class S : VariableSchema() {
            val x by intVar(min = 0, max = 5)
            val nonZero by constraint { -x le -1 }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = LocalSearchSolver(compiled.problem, restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 200))
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 5_000, randomSeed = 3)).take(5).toList()
        for (s in samples) {
            val xv = compiled.decode(schema.x, s)
            assertTrue(xv >= 1, "Expected x≥1, got $xv")
        }
    }

    @Test
    fun `symmetry helper lit forwards through cnf`() {

        class S : VariableSchema() {
            val flag by boolVar()
            val x by intVar(min = 0, max = 3)
            val cond by constraint { flag implies (x le 1) }
        }
        val compiled = S().compile()
        val cnf = BitBlaster.compile(compiled.problem)
        assertEquals(1, compiled.intVarIdByName.size)

        val flagCnfVar = cnf.boolVarToCnfVar[compiled.boolVarIdByName["flag"]!!]
        assertTrue(flagCnfVar in 0 until cnf.numVars)

        Lit.make(flagCnfVar, true)
    }
}
