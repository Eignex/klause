package com.eignex.klause.cnf

import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.Xor
import kotlin.test.Test
import kotlin.test.assertEquals

class XorCoverageTest {

    @Test
    fun `xor odd parity matches enumeration`() {
        val lits = IntArray(4) { Lit.make(it, true) }
        val factor = Xor(lits, targetParity = 1)
        val problem = Problem(4, 0, emptyArray(), listOf(factor))
        val cnf = BitBlaster.compile(problem)
        for (mask in 0..15) {
            val parity = mask.countOneBits() and 1
            val expected = parity == 1
            val pins = IntArray(8)
            for (i in 0..3) {
                pins[i * 2] = cnf.boolVarToCnfVar[i]
                pins[i * 2 + 1] = (mask shr i) and 1
            }
            assertEquals(
                expected,
                SatCheck.isSat(cnf.numVars, cnf.clauses, pins),
                "mask=$mask parity=$parity expected=$expected"
            )
        }
    }

    @Test
    fun `xor even parity matches enumeration`() {
        val lits = IntArray(4) { Lit.make(it, true) }
        val factor = Xor(lits, targetParity = 0)
        val problem = Problem(4, 0, emptyArray(), listOf(factor))
        val cnf = BitBlaster.compile(problem)
        for (mask in 0..15) {
            val parity = mask.countOneBits() and 1
            val expected = parity == 0
            val pins = IntArray(8)
            for (i in 0..3) {
                pins[i * 2] = cnf.boolVarToCnfVar[i]
                pins[i * 2 + 1] = (mask shr i) and 1
            }
            assertEquals(
                expected,
                SatCheck.isSat(cnf.numVars, cnf.clauses, pins),
                "mask=$mask parity=$parity expected=$expected"
            )
        }
    }
}
