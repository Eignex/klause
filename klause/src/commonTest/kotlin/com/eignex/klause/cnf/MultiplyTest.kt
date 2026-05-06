package com.eignex.klause.cnf

import com.eignex.klause.solver.Lit
import kotlin.test.Test
import kotlin.test.assertEquals

class MultiplyTest {

    @Test
    fun threeBitTimesThreeBitMatchesIntegerMultiplication() {
        // a, b ∈ [0..7]. Expect a*b ∈ [0..49] in 6-bit canonical binary.
        val builder = CnfBuilder()
        val a = IntArray(3) { Lit.make(builder.newVar(), positive = true) }
        val b = IntArray(3) { Lit.make(builder.newVar(), positive = true) }
        val product = builder.multiply(a, b)
        val cnf = CnfProblem(
            numVars = builder.numVars,
            clauses = builder.clauses.toList(),
            boolVarToCnfVar = IntArray(0),
            intVarBits = emptyArray(),
            intVarMin = IntArray(0),
        )
        for (av in 0..7) for (bv in 0..7) {
            val expected = av * bv
            val pins = IntArray(2 * (a.size + b.size))
            var p = 0
            for (i in a.indices) {
                pins[p++] = Lit.variable(a[i]); pins[p++] = (av shr i) and 1
            }
            for (i in b.indices) {
                pins[p++] = Lit.variable(b[i]); pins[p++] = (bv shr i) and 1
            }
            // Now check each product bit.
            for (i in product.indices) {
                val want = (expected shr i) and 1
                val variant = pins.copyOf(pins.size + 2)
                variant[pins.size] = Lit.variable(product[i])
                variant[pins.size + 1] = want
                val sat = SatCheck.isSat(cnf.numVars, cnf.clauses, variant)
                assertEquals(true, sat, "av=$av bv=$bv bit=$i should permit product[$i]=$want")
                // And the opposite pin should be unsat.
                val opposite = pins.copyOf(pins.size + 2)
                opposite[pins.size] = Lit.variable(product[i])
                opposite[pins.size + 1] = 1 - want
                val unsat = SatCheck.isSat(cnf.numVars, cnf.clauses, opposite)
                assertEquals(false, unsat, "av=$av bv=$bv bit=$i wrong value should be infeasible")
            }
        }
    }
}
