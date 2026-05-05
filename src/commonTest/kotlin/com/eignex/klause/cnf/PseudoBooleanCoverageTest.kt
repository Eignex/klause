package com.eignex.klause.cnf

import com.eignex.klause.ast.PbOp
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.PseudoBoolean
import kotlin.test.Test
import kotlin.test.assertEquals

class PseudoBooleanCoverageTest {

    @Test
    fun pbAtMostMatchesEnumeration() {
        // 3a + 2b + 5c + 1d ≤ 4 over 4 booleans.
        val weights = intArrayOf(3, 2, 5, 1)
        val lits = IntArray(4) { Lit.make(it, true) }
        val factor = PseudoBoolean(weights, lits, PbOp.LE, 4)
        val problem = Problem(4, 0, emptyArray(), listOf(factor))
        val cnf = BitBlaster.compile(problem)
        for (mask in 0..15) {
            var sum = 0
            for (i in 0..3) if ((mask shr i) and 1 == 1) sum += weights[i]
            val expected = sum <= 4
            val pins = IntArray(8)
            for (i in 0..3) {
                pins[i * 2] = cnf.boolVarToCnfVar[i]
                pins[i * 2 + 1] = (mask shr i) and 1
            }
            assertEquals(expected, SatCheck.isSat(cnf.numVars, cnf.clauses, pins),
                "mask=$mask sum=$sum expected=$expected")
        }
    }

    @Test
    fun pbExactlyMatchesEnumeration() {
        val weights = intArrayOf(2, 3, 5)
        val lits = IntArray(3) { Lit.make(it, true) }
        val factor = PseudoBoolean(weights, lits, PbOp.EQ, 5)
        val problem = Problem(3, 0, emptyArray(), listOf(factor))
        val cnf = BitBlaster.compile(problem)
        for (mask in 0..7) {
            var sum = 0
            for (i in 0..2) if ((mask shr i) and 1 == 1) sum += weights[i]
            val expected = sum == 5
            val pins = IntArray(6)
            for (i in 0..2) {
                pins[i * 2] = cnf.boolVarToCnfVar[i]
                pins[i * 2 + 1] = (mask shr i) and 1
            }
            assertEquals(expected, SatCheck.isSat(cnf.numVars, cnf.clauses, pins),
                "mask=$mask sum=$sum expected=$expected")
        }
    }
}
