package com.eignex.klause.cnf

import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.Product
import kotlin.test.Test
import kotlin.test.assertEquals

class SignedProductCoverageTest {

    @Test
    fun signedProductMatchesEnumeration() {
        // a, b ∈ [-2..2], result ∈ [-4..4].
        val factor = Product(a = 0, b = 1, result = 2)
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(-2, 2), IntDomain(-2, 2), IntDomain(-4, 4)),
            factors = listOf(factor),
        )
        val cnf = BitBlaster.compile(problem)
        for (av in -2..2) for (bv in -2..2) for (rv in -4..4) {
            val expected = (av * bv == rv)
            val pins = mutableListOf<Int>()
            val values = listOf(av, bv, rv)
            val mins = listOf(-2, -2, -4)
            for (idx in 0..2) {
                val bits = cnf.intVarBits[idx]
                val offset = values[idx] - mins[idx]
                for (i in bits.indices) {
                    pins += bits[i]; pins += (offset shr i) and 1
                }
            }
            val sat = SatCheck.isSat(cnf.numVars, cnf.clauses, pins.toIntArray())
            assertEquals(expected, sat, "a=$av b=$bv r=$rv expected=$expected got=$sat")
        }
    }
}
