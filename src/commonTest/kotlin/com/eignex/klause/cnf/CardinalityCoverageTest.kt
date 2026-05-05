package com.eignex.klause.cnf

import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.Cardinality
import com.eignex.klause.solver.factor.ReifiedCardinality
import kotlin.test.Test
import kotlin.test.assertEquals

class CardinalityCoverageTest {

    private fun isSat(cnf: CnfProblem, fixed: IntArray): Boolean =
        SatCheck.isSat(cnf.numVars, cnf.clauses, fixed)

    private fun pinBool(cnf: CnfProblem, originalVar: Int, value: Boolean): IntArray =
        intArrayOf(cnf.boolVarToCnfVar[originalVar], if (value) 1 else 0)

    @Test
    fun atMostKExactlyMatchesEnumeration() {
        // At most 2 of 4 booleans true.
        val lits = IntArray(4) { Lit.make(it, true) }
        val factor = Cardinality(lits, min = 0, max = 2)
        val problem = Problem(4, 0, emptyArray(), listOf(factor))
        val cnf = BitBlaster.compile(problem)
        for (mask in 0..15) {
            val count = mask.countOneBits()
            val expectedSat = count <= 2
            val pins = IntArray(8)
            for (i in 0..3) {
                pins[i * 2] = cnf.boolVarToCnfVar[i]
                pins[i * 2 + 1] = (mask shr i) and 1
            }
            assertEquals(expectedSat, isSat(cnf, pins), "mask=$mask")
        }
    }

    @Test
    fun atLeastKExactlyMatchesEnumeration() {
        // At least 2 of 4 booleans true.
        val lits = IntArray(4) { Lit.make(it, true) }
        val factor = Cardinality(lits, min = 2, max = 4)
        val problem = Problem(4, 0, emptyArray(), listOf(factor))
        val cnf = BitBlaster.compile(problem)
        for (mask in 0..15) {
            val count = mask.countOneBits()
            val expectedSat = count >= 2
            val pins = IntArray(8)
            for (i in 0..3) {
                pins[i * 2] = cnf.boolVarToCnfVar[i]
                pins[i * 2 + 1] = (mask shr i) and 1
            }
            assertEquals(expectedSat, isSat(cnf, pins), "mask=$mask")
        }
    }

    @Test
    fun rangeCardinality2to3OverFour() {
        // 2 ≤ count ≤ 3 over 4 booleans.
        val lits = IntArray(4) { Lit.make(it, true) }
        val factor = Cardinality(lits, min = 2, max = 3)
        val problem = Problem(4, 0, emptyArray(), listOf(factor))
        val cnf = BitBlaster.compile(problem)
        for (mask in 0..15) {
            val count = mask.countOneBits()
            val expectedSat = count in 2..3
            val pins = IntArray(8)
            for (i in 0..3) {
                pins[i * 2] = cnf.boolVarToCnfVar[i]
                pins[i * 2 + 1] = (mask shr i) and 1
            }
            assertEquals(expectedSat, isSat(cnf, pins), "mask=$mask count=$count")
        }
    }

    @Test
    fun reifiedCardinalityTracksAux() {
        // aux ↔ (count(x0..x3) ∈ [1, 2])
        val lits = IntArray(4) { i -> Lit.make(i + 1, true) }  // bool ids 1..4
        val factor = ReifiedCardinality(auxBoolVar = 0, literals = lits, min = 1, max = 2)
        val problem = Problem(5, 0, emptyArray(), listOf(factor))
        val cnf = BitBlaster.compile(problem)
        for (mask in 0..15) for (auxV in 0..1) {
            val count = mask.countOneBits()
            val want = count in 1..2
            val expectedSat = (auxV == 1) == want
            val pins = IntArray(10)
            pins[0] = cnf.boolVarToCnfVar[0]; pins[1] = auxV
            for (i in 0..3) {
                pins[(i + 1) * 2] = cnf.boolVarToCnfVar[i + 1]
                pins[(i + 1) * 2 + 1] = (mask shr i) and 1
            }
            assertEquals(expectedSat, isSat(cnf, pins), "aux=$auxV mask=$mask count=$count")
        }
    }
}
