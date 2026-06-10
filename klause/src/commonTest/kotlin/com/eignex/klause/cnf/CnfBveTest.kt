package com.eignex.klause.cnf

import com.eignex.klause.solver.Lit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Bounded variable elimination (#316). The correctness property is preservation of the model
 * set *projected onto the protected variables*: for every assignment of the protected vars,
 * the original clauses are satisfiable (extending over the rest) iff the eliminated clauses
 * are. Eliminated aux vars become free, which is exactly what BVE is allowed to do.
 */
class CnfBveTest {

    private fun pos(v: Int) = Lit.make(v, true)
    private fun neg(v: Int) = Lit.make(v, false)

    /** SAT of [clauses] with the protected vars pinned per [mask]; aux vars left free. */
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
        val eliminated = CnfSimplify.eliminateAuxVars(clauses, protectedVars.toHashSet())
        for (mask in 0 until (1 shl protectedVars.size)) {
            assertEquals(
                satWithProtectedPinned(numVars, clauses, protectedVars, mask),
                satWithProtectedPinned(numVars, eliminated, protectedVars, mask),
                "projection disagrees at mask $mask; orig=$clauses bve=$eliminated",
            )
        }
    }

    @Test
    fun `eliminates an aux variable that defines an equivalence`() {
        // x (var 2) is aux: (¬a ∨ x) ∧ (¬b ∨ x) ∧ (¬x ∨ c). a,b,c protected.
        val clauses = listOf(
            intArrayOf(neg(0), pos(2)),
            intArrayOf(neg(1), pos(2)),
            intArrayOf(neg(2), pos(3)),
        )
        val protectedVars = listOf(0, 1, 3)
        val out = CnfSimplify.eliminateAuxVars(clauses, protectedVars.toHashSet())
        assertTrue(out.none { c -> c.any { Lit.variable(it) == 2 } }, "aux var 2 survived: $out")
        assertProjectionPreserved(4, protectedVars, clauses)
    }

    @Test
    fun `pure-literal aux variable is dropped with its clauses`() {
        // x (var 1) occurs only positively -> pure literal, set true, clauses with it vanish.
        val clauses = listOf(intArrayOf(pos(0), pos(1)), intArrayOf(pos(1)))
        val out = CnfSimplify.eliminateAuxVars(clauses, hashSetOf(0))
        assertTrue(out.none { c -> c.any { Lit.variable(it) == 1 } }, "pure literal survived: $out")
        assertProjectionPreserved(2, listOf(0), clauses)
    }

    @Test
    fun `protected variables are never eliminated`() {
        val clauses = listOf(intArrayOf(neg(0), pos(1)), intArrayOf(neg(1), pos(0)))
        val out = CnfSimplify.eliminateAuxVars(clauses, hashSetOf(0, 1))
        // Both vars protected: nothing eliminable; clause set is returned (normalized) intact.
        assertTrue(out.any { c -> c.any { Lit.variable(it) == 0 } })
        assertTrue(out.any { c -> c.any { Lit.variable(it) == 1 } })
    }

    @Test
    fun `random small CNFs preserve the protected projection`() {
        val rng = Random(0xBEEF)
        repeat(300) {
            val numVars = 3 + rng.nextInt(4) // 3..6
            // First two vars protected, the rest are aux candidates.
            val protectedVars = listOf(0, 1)
            val numClauses = rng.nextInt(10)
            val clauses = ArrayList<IntArray>(numClauses)
            repeat(numClauses) {
                val width = 1 + rng.nextInt(3)
                clauses.add(IntArray(width) { Lit.make(rng.nextInt(numVars), rng.nextBoolean()) })
            }
            assertProjectionPreserved(numVars, protectedVars, clauses)
        }
    }
}
