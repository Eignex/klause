package com.eignex.klause.cnf

import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.Product
import kotlin.test.Test
import kotlin.test.assertEquals

class ProductCoverageTest {

    @Test
    fun `product over three ints matches enumeration`() {

        val factor = Product(a = 0, b = 1, result = 2)
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3)),
            factors = listOf(factor),
        )
        val cnf = BitBlaster.compile(problem)
        for (av in 0..3) for (bv in 0..3) for (rv in 0..3) {
            val expected = (av * bv == rv)
            val pins = mutableListOf<Int>()
            for ((idx, value) in listOf(av, bv, rv).withIndex()) {
                val bits = cnf.intVarBits[idx]
                for (i in bits.indices) {
                    pins += bits[i]; pins += (value shr i) and 1
                }
            }
            val sat = SatCheck.isSat(cnf.numVars, cnf.clauses, pins.toIntArray())
            assertEquals(expected, sat, "a=$av b=$bv r=$rv expected=$expected got=$sat")
        }
    }
}
