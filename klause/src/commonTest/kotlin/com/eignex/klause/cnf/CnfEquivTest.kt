package com.eignex.klause.cnf

import com.eignex.klause.solver.Lit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Equivalent-literal substitution via implication-graph SCCs (#320). Correctness is, as for
 * BVE, preservation of the model set projected onto the protected variables.
 */
class CnfEquivTest {

    private fun pos(v: Int) = Lit.make(v, true)
    private fun neg(v: Int) = Lit.make(v, false)

    private fun satWithProtectedPinned(
        numVars: Int,
        clauses: List<IntArray>,
        protectedVars: List<Int>,
        mask: Int,
    ): Boolean {
        val fixed = ArrayList<Int>(protectedVars.size * 2)
        for (j in protectedVars.indices) {
            fixed += protectedVars[j]
            fixed += (mask shr j) and 1
        }
        return SatCheck.isSat(numVars, clauses, fixed.toIntArray())
    }

    private fun assertProjectionPreserved(numVars: Int, protectedVars: List<Int>, clauses: List<IntArray>) {
        val out = CnfSimplify.substituteEquivalentLiterals(clauses, numVars, protectedVars.toHashSet())
        for (mask in 0 until (1 shl protectedVars.size)) {
            assertTrue(
                satWithProtectedPinned(numVars, clauses, protectedVars, mask) ==
                    satWithProtectedPinned(numVars, out, protectedVars, mask),
                "projection disagrees at mask $mask; orig=$clauses out=$out",
            )
        }
    }

    @Test
    fun `aux equivalent to a protected var folds onto it`() {
        // a ≡ x : (¬a ∨ x) ∧ (¬x ∨ a). a (0) protected, x (1) aux.
        val clauses = listOf(intArrayOf(neg(0), pos(1)), intArrayOf(neg(1), pos(0)))
        val out = CnfSimplify.substituteEquivalentLiterals(clauses, 2, hashSetOf(0))
        assertTrue(out.none { c -> c.any { Lit.variable(it) == 1 } }, "aux var 1 not folded away: $out")
        assertProjectionPreserved(2, listOf(0), clauses)
    }

    @Test
    fun `contradictory equivalence is detected as unsat`() {
        // a ≡ b and a ≡ ¬b  ⇒  b ≡ ¬b  ⇒  UNSAT.
        val clauses = listOf(
            intArrayOf(neg(0), pos(1)), // a → b
            intArrayOf(neg(1), pos(0)), // b → a
            intArrayOf(neg(0), neg(1)), // a → ¬b
            intArrayOf(pos(0), pos(1)), // ¬b → a  (and ¬a → b)
        )
        val out = CnfSimplify.substituteEquivalentLiterals(clauses, 2, emptySet())
        assertTrue(out.size == 1 && out[0].isEmpty(), "expected empty clause (UNSAT), got $out")
    }

    @Test
    fun `two equivalent protected vars are left intact`() {
        // a ≡ b, both protected: cannot fold either away (decode needs both).
        val clauses = listOf(intArrayOf(neg(0), pos(1)), intArrayOf(neg(1), pos(0)))
        val out = CnfSimplify.substituteEquivalentLiterals(clauses, 2, hashSetOf(0, 1))
        assertTrue(out.any { c -> c.any { Lit.variable(it) == 0 } })
        assertTrue(out.any { c -> c.any { Lit.variable(it) == 1 } })
        assertProjectionPreserved(2, listOf(0, 1), clauses)
    }

    @Test
    fun `random small CNFs preserve the protected projection`() {
        val rng = Random(0x5CC)
        repeat(300) {
            val numVars = 3 + rng.nextInt(4) // 3..6
            val protectedVars = listOf(0, 1)
            val clauses = ArrayList<IntArray>()
            repeat(rng.nextInt(10)) {
                val width = 1 + rng.nextInt(3)
                clauses.add(IntArray(width) { Lit.make(rng.nextInt(numVars), rng.nextBoolean()) })
            }
            assertProjectionPreserved(numVars, protectedVars, clauses)
        }
    }
}
