package com.eignex.klause.cnf

import com.eignex.klause.solver.Lit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Structural hash-consing of Tseitin gates: identical gates share one aux var + definition,
 *  and the sharing propagates up through bit-vector circuits built on those gates. */
class GateConsingTest {

    @Test
    fun `identical and-gate is shared and adds no new clauses`() {
        val b = CnfBuilder()
        val x = Lit.make(b.newVar(), true)
        val y = Lit.make(b.newVar(), true)
        val first = b.tseitinAnd(intArrayOf(x, y))
        val clausesAfterFirst = b.clauses.size
        val varsAfterFirst = b.numVars
        val second = b.tseitinAnd(intArrayOf(y, x)) // reordered — same gate
        assertEquals(first, second, "reordered AND should return the same aux literal")
        assertEquals(clausesAfterFirst, b.clauses.size, "cache hit must add no clauses")
        assertEquals(varsAfterFirst, b.numVars, "cache hit must add no vars")
    }

    @Test
    fun `xor canonicalizes polarity and self-pairs`() {
        val b = CnfBuilder()
        val a = Lit.make(b.newVar(), true)
        val c = Lit.make(b.newVar(), true)
        val base = b.tseitinXor(a, c)
        assertEquals(base, b.tseitinXor(c, a), "XOR is commutative")
        assertEquals(Lit.negate(base), b.tseitinXor(Lit.negate(a), c), "¬a ⊕ c = ¬(a ⊕ c)")
        assertEquals(base, b.tseitinXor(Lit.negate(a), Lit.negate(c)), "¬a ⊕ ¬c = a ⊕ c")
        assertEquals(b.falseLit(), b.tseitinXor(a, a), "a ⊕ a = false")
        assertEquals(b.trueLit(), b.tseitinXor(a, Lit.negate(a)), "a ⊕ ¬a = true")
    }

    @Test
    fun `equality over identical bit-vectors is shared`() {
        val b = CnfBuilder()
        val u = IntArray(4) { Lit.make(b.newVar(), true) }
        val v = IntArray(4) { Lit.make(b.newVar(), true) }
        val eq1 = b.unsignedEq(u, v)
        val clausesAfter = b.clauses.size
        val eq2 = b.unsignedEq(u, v)
        assertEquals(eq1, eq2, "equality over the same bit-vectors should be the same literal")
        assertEquals(clausesAfter, b.clauses.size, "second equality must reuse the consed gates")
    }

    @Test
    fun `consing shrinks a comparator-heavy encoding`() {
        // Reuse the same comparison many times. Without consing each call allocates fresh
        // gates; with consing they collapse to one.
        val b = CnfBuilder()
        val u = IntArray(8) { Lit.make(b.newVar(), true) }
        val v = IntArray(8) { Lit.make(b.newVar(), true) }
        repeat(50) { b.unsignedLeq(u, v) }
        // 50 identical comparators must not cost 50× the vars of a single one.
        val single = CnfBuilder()
        val s = IntArray(8) { Lit.make(single.newVar(), true) }
        val t = IntArray(8) { Lit.make(single.newVar(), true) }
        single.unsignedLeq(s, t)
        assertTrue(
            b.numVars < single.numVars * 2,
            "50 identical comparators (${b.numVars} vars) should be near one (${single.numVars} vars)",
        )
    }
}
