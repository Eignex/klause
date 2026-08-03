package com.eignex.klause.count

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class XorHashFamilyTest {

    @Test
    fun `hashed problem enumerates a non-empty cell`() {
        // 9 free vars (512 models). 4 parity hashes should carve out roughly 2^5 = 32 solutions.
        val p = Problem(numBoolVars = 9, numIntVars = 0, intDomains = emptyArray(), factors = arrayOf<Factor>())
        val hashes = XorHashFamily(IntArray(9) { it }, seed = 11L).draw(4)
        val augmented = p.withHashes(hashes)
        val count = BacktrackSolver(augmented.bake()).enumerate(BacktrackParams()).count()
        assertTrue(count in 1..200, "hashed cell size $count is implausible (expected ~32)")
    }

    private val samplingSet = intArrayOf(0, 1, 2, 3, 4)

    @Test
    fun `draw is deterministic for a fixed seed`() {
        val a = XorHashFamily(samplingSet, seed = 42L).draw(4)
        val b = XorHashFamily(samplingSet, seed = 42L).draw(4)
        assertEquals(a.size, b.size)
        for (i in a.indices) {
            assertTrue(a[i].literals.contentEquals(b[i].literals), "hash $i literals differ")
            assertEquals(a[i].targetParity, b[i].targetParity, "hash $i parity differs")
        }
    }

    @Test
    fun `different seeds give different families`() {
        val a = XorHashFamily(samplingSet, seed = 1L).draw(5)
        val b = XorHashFamily(samplingSet, seed = 2L).draw(5)
        val same = a.indices.all {
            a[it].literals.contentEquals(b[it].literals) && a[it].targetParity == b[it].targetParity
        }
        assertTrue(!same, "distinct seeds should (overwhelmingly) produce distinct hashes")
    }

    @Test
    fun `every hash ranges only over the sampling set and is non-empty`() {
        val set = samplingSet.toHashSet()
        for (h in XorHashFamily(samplingSet, seed = 7L).draw(8)) {
            assertTrue(h.literals.isNotEmpty(), "hash must include at least one variable")
            for (lit in h.literals) {
                assertTrue(Lit.variable(lit) in set, "hash touched a var outside the sampling set")
            }
            assertTrue(h.targetParity == 0 || h.targetParity == 1)
        }
    }

    @Test
    fun `draw of zero hashes is empty`() {
        assertEquals(0, XorHashFamily(samplingSet, seed = 0L).draw(0).size)
    }

    @Test
    fun `empty sampling set yields no hashes`() {
        assertEquals(0, XorHashFamily(IntArray(0), seed = 0L).draw(5).size)
    }
}
