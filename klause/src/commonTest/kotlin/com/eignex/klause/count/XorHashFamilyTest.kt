package com.eignex.klause.count

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.bake
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class XorHashFamilyTest {

    @Test
    fun `hashing should keep invented integer sides open`() {
        val open = Problem(
            numBoolVars = 2,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 5)),
            factors = arrayOf<Factor>(),
            openIntHi = booleanArrayOf(true),
        ).bake()
        val hashes = XorHashFamily(intArrayOf(0, 1), seed = 3L).draw(1)

        val augmented = open.withHashes(hashes)

        assertTrue(augmented.intBounds.isOpenUpper(0))
    }

    private val samplingSet = intArrayOf(0, 1, 2, 3, 4)

    @Test
    fun `draw should be deterministic for a fixed seed`() {
        val a = XorHashFamily(samplingSet, seed = 42L).draw(4)
        val b = XorHashFamily(samplingSet, seed = 42L).draw(4)
        assertEquals(a.size, b.size)
        for (i in a.indices) {
            assertTrue(a[i].literals.contentEquals(b[i].literals), "hash $i literals differ")
            assertEquals(a[i].targetParity, b[i].targetParity, "hash $i parity differs")
        }
    }

    @Test
    fun `every hash should range only over the sampling set and be non-empty`() {
        val set = samplingSet.toHashSet()
        for (h in XorHashFamily(samplingSet, seed = 7L).draw(8)) {
            assertTrue(h.literals.isNotEmpty())
            for (lit in h.literals) {
                assertTrue(Lit.variable(lit) in set, "hash touched a var outside the sampling set")
            }
            assertTrue(h.targetParity == 0 || h.targetParity == 1)
        }
    }

    @Test
    fun `empty sampling set should yield no hashes`() {
        assertEquals(0, XorHashFamily(IntArray(0), seed = 0L).draw(5).size)
    }
}
