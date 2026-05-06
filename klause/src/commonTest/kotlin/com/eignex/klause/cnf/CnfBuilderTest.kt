package com.eignex.klause.cnf

import com.eignex.klause.solver.Lit
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Each Tseitin gadget claims `aux ↔ f(inputs)`. The trustworthy check is:
 * for every (inputs, auxValue) tuple the CNF is SAT iff `auxValue == f(inputs)`.
 */
class CnfBuilderTest {

    private fun isSat(numVars: Int, clauses: List<IntArray>, fixed: IntArray): Boolean {
        require(numVars <= 20) { "brute-force capped at 20 vars" }
        val model = BooleanArray(numVars)
        for (mask in 0 until (1 shl numVars)) {
            for (i in 0 until numVars) model[i] = (mask shr i) and 1 == 1
            var skip = false
            var k = 0
            while (k < fixed.size) {
                val v = fixed[k]; val target = fixed[k + 1] == 1
                if (model[v] != target) { skip = true; break }
                k += 2
            }
            if (skip) continue
            if (clauses.all { clause ->
                if (clause.isEmpty()) false
                else clause.any { lit ->
                    val variable = Lit.variable(lit)
                    model[variable] xor !Lit.isPositive(lit)
                }
            }) return true
        }
        return false
    }

    @Test
    fun tseitinAndIsExactlyAnd() {
        val b = CnfBuilder()
        val a = b.newVar(); val c = b.newVar()
        val auxLit = b.tseitinAnd(intArrayOf(Lit.make(a, true), Lit.make(c, true)))
        val aux = Lit.variable(auxLit)
        for (av in 0..1) for (cv in 0..1) for (auxV in 0..1) {
            val expectedSat = ((av == 1) && (cv == 1)) == (auxV == 1)
            assertEquals(expectedSat,
                isSat(b.numVars, b.clauses, intArrayOf(a, av, c, cv, aux, auxV)),
                "av=$av cv=$cv auxV=$auxV")
        }
    }

    @Test
    fun tseitinXor3IsParity() {
        val b = CnfBuilder()
        val a = b.newVar(); val c = b.newVar(); val d = b.newVar()
        val auxLit = b.tseitinXor3(Lit.make(a, true), Lit.make(c, true), Lit.make(d, true))
        val aux = Lit.variable(auxLit)
        for (av in 0..1) for (cv in 0..1) for (dv in 0..1) for (auxV in 0..1) {
            val expectedSat = ((av xor cv xor dv) == 1) == (auxV == 1)
            assertEquals(expectedSat,
                isSat(b.numVars, b.clauses,
                    intArrayOf(a, av, c, cv, d, dv, aux, auxV)),
                "av=$av cv=$cv dv=$dv auxV=$auxV")
        }
    }

    @Test
    fun tseitinMaj3IsAtLeastTwo() {
        val b = CnfBuilder()
        val a = b.newVar(); val c = b.newVar(); val d = b.newVar()
        val auxLit = b.tseitinMaj3(Lit.make(a, true), Lit.make(c, true), Lit.make(d, true))
        val aux = Lit.variable(auxLit)
        for (av in 0..1) for (cv in 0..1) for (dv in 0..1) for (auxV in 0..1) {
            val expectedSat = ((av + cv + dv) >= 2) == (auxV == 1)
            assertEquals(expectedSat,
                isSat(b.numVars, b.clauses,
                    intArrayOf(a, av, c, cv, d, dv, aux, auxV)),
                "av=$av cv=$cv dv=$dv auxV=$auxV")
        }
    }

    @Test
    fun rippleAddSumsTwoBitNumbers() {
        val b = CnfBuilder()
        val aBits = IntArray(2) { b.newVar() }
        val bBits = IntArray(2) { b.newVar() }
        val sum = b.rippleAdd(
            IntArray(2) { Lit.make(aBits[it], true) },
            IntArray(2) { Lit.make(bBits[it], true) },
        )
        val sumVars = sum.map { Lit.variable(it) }
        for (av in 0..3) for (bv in 0..3) {
            val expected = av + bv
            for (sv in 0..7) {
                val expectedSat = sv == expected
                val pins = mutableListOf<Int>()
                for (i in 0..1) { pins += aBits[i]; pins += (av ushr i) and 1 }
                for (i in 0..1) { pins += bBits[i]; pins += (bv ushr i) and 1 }
                for (i in sumVars.indices) { pins += sumVars[i]; pins += (sv ushr i) and 1 }
                assertEquals(expectedSat, isSat(b.numVars, b.clauses, pins.toIntArray()),
                    "av=$av bv=$bv sv=$sv expected=$expected")
            }
        }
    }

    @Test
    fun constantLeqMatchesBound() {
        val b = CnfBuilder()
        val bits = IntArray(3) { b.newVar() }
        val resultLit = b.constantLeq(IntArray(3) { Lit.make(bits[it], true) }, 5)
        val resVar = Lit.variable(resultLit)
        for (v in 0..7) for (rv in 0..1) {
            val expectedSat = (v <= 5) == (rv == 1)
            val pins = IntArray(8)
            for (i in 0..2) { pins[i * 2] = bits[i]; pins[i * 2 + 1] = (v ushr i) and 1 }
            pins[6] = resVar; pins[7] = rv
            assertEquals(expectedSat, isSat(b.numVars, b.clauses, pins), "v=$v rv=$rv")
        }
    }

    @Test
    fun unsignedLeqMatchesComparison() {
        val b = CnfBuilder()
        val aBits = IntArray(2) { b.newVar() }
        val bBits = IntArray(2) { b.newVar() }
        val resultLit = b.unsignedLeq(
            IntArray(2) { Lit.make(aBits[it], true) },
            IntArray(2) { Lit.make(bBits[it], true) },
        )
        val resVar = Lit.variable(resultLit)
        for (av in 0..3) for (bv in 0..3) for (rv in 0..1) {
            val expectedSat = (av <= bv) == (rv == 1)
            val pins = IntArray(10)
            for (i in 0..1) { pins[i * 2] = aBits[i]; pins[i * 2 + 1] = (av ushr i) and 1 }
            for (i in 0..1) { pins[(i + 2) * 2] = bBits[i]; pins[(i + 2) * 2 + 1] = (bv ushr i) and 1 }
            pins[8] = resVar; pins[9] = rv
            assertEquals(expectedSat, isSat(b.numVars, b.clauses, pins),
                "av=$av bv=$bv rv=$rv")
        }
    }
}
