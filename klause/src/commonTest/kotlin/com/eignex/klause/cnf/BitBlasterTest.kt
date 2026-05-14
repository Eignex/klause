package com.eignex.klause.cnf

import com.eignex.klause.ast.IntCmpOp
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.Cardinality
import com.eignex.klause.solver.factor.Clause
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.factor.reifiedIntCompare
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BitBlasterTest {

    private fun isSat(cnf: CnfProblem, fixed: IntArray): Boolean =
        SatCheck.isSat(cnf.numVars, cnf.clauses, fixed)

    private fun pinInt(cnf: CnfProblem, intVar: Int, value: Int): IntArray {
        val bits = cnf.intVarBits[intVar]
        val offset = value - cnf.intVarMin[intVar]
        val pins = IntArray(bits.size * 2)
        for (i in bits.indices) {
            pins[i * 2] = bits[i]
            pins[i * 2 + 1] = (offset ushr i) and 1
        }
        return pins
    }

    @Test
    fun `int leq matches original semantics`() {
        val factor = Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 5)
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 7)), listOf(factor))
        val cnf = BitBlaster.compile(problem)
        for (v in 0..7) {
            val expectedSat = v <= 5
            assertEquals(expectedSat, isSat(cnf, pinInt(cnf, 0, v)),
                "x=$v: expected SAT=$expectedSat")
        }
    }

    @Test
    fun `int eq and domain constraint`() {

        val factor = Linear(intArrayOf(1), intArrayOf(0), LinearOp.EQ, 4)
        val problem = Problem(0, 1, arrayOf(IntDomain(2, 5)), listOf(factor))
        val cnf = BitBlaster.compile(problem)
        for (v in 2..5) {
            val expectedSat = v == 4
            assertEquals(expectedSat, isSat(cnf, pinInt(cnf, 0, v)), "x=$v")
        }
    }

    @Test
    fun `int eq out of domain emits empty clause`() {
        val factor = Linear(intArrayOf(1), intArrayOf(0), LinearOp.EQ, 99)
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 3)), listOf(factor))
        val cnf = BitBlaster.compile(problem)

        assertTrue(cnf.clauses.any { it.isEmpty() })
    }

    @Test
    fun `int neq matches original semantics`() {
        val factor = Linear(intArrayOf(1), intArrayOf(0), LinearOp.NE, 2)
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 3)), listOf(factor))
        val cnf = BitBlaster.compile(problem)
        for (v in 0..3) {
            val expectedSat = v != 2
            assertEquals(expectedSat, isSat(cnf, pinInt(cnf, 0, v)), "x=$v")
        }
    }

    @Test
    fun `linear le over two tiny vars matches enumeration`() {

        val factor = Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 3)
        val problem = Problem(0, 2, arrayOf(IntDomain(0, 3), IntDomain(0, 3)), listOf(factor))
        val cnf = BitBlaster.compile(problem)
        for (x in 0..3) for (y in 0..3) {
            val expectedSat = x + y <= 3
            val pins = pinInt(cnf, 0, x) + pinInt(cnf, 1, y)
            assertEquals(expectedSat, isSat(cnf, pins), "x=$x y=$y")
        }
    }

    @Test
    fun `linear var vs var matches enumeration`() {

        val factor = Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.LE, 0)
        val problem = Problem(0, 2, arrayOf(IntDomain(0, 3), IntDomain(0, 3)), listOf(factor))
        val cnf = BitBlaster.compile(problem)
        for (x in 0..3) for (y in 0..3) {
            val expectedSat = x <= y
            val pins = pinInt(cnf, 0, x) + pinInt(cnf, 1, y)
            assertEquals(expectedSat, isSat(cnf, pins), "x=$x y=$y")
        }
    }

    @Test
    fun `reified int compare tracks aux value`() {

        val factor = reifiedIntCompare(auxBoolVar = 0, intVar = 0, op = IntCmpOp.LE, 1)
        val problem = Problem(1, 1, arrayOf(IntDomain(0, 3)), listOf(factor))
        val cnf = BitBlaster.compile(problem)
        for (auxVal in 0..1) for (x in 0..3) {
            val want = (x <= 1)
            val expectedSat = (auxVal == 1) == want
            val auxPin = intArrayOf(cnf.boolVarToCnfVar[0], auxVal)
            val pins = auxPin + pinInt(cnf, 0, x)
            assertEquals(expectedSat, isSat(cnf, pins), "aux=$auxVal x=$x")
        }
    }

    @Test
    fun `clauses and cardinality round trip`() {

        val problem = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = listOf(
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
                Cardinality.atMostOne(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true))),
            ),
        )
        val cnf = BitBlaster.compile(problem)
        for (mask in 0..7) {
            val b0 = (mask and 1) == 1
            val b1 = (mask and 2) == 2
            val b2 = (mask and 4) == 4
            val expectedSat = (b0 || b1) && (listOf(b0, b1, b2).count { it } <= 1)
            val pins = intArrayOf(
                cnf.boolVarToCnfVar[0], if (b0) 1 else 0,
                cnf.boolVarToCnfVar[1], if (b1) 1 else 0,
                cnf.boolVarToCnfVar[2], if (b2) 1 else 0,
            )
            assertEquals(expectedSat, isSat(cnf, pins), "x0=$b0 x1=$b1 x2=$b2")
        }
    }

    @Test
    fun `dimacs round trips header`() {
        val problem = Problem(
            numBoolVars = 0, numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 3)),
            factors = listOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 1)),
        )
        val cnf = BitBlaster.compile(problem)
        val dimacs = cnf.toDimacs()
        val first = dimacs.lineSequence().first()
        assertTrue(first.startsWith("p cnf "))
        assertEquals(cnf.numVars.toString(), first.split(' ')[2])
        assertEquals(cnf.clauses.size.toString(), first.split(' ')[3])
    }
}
