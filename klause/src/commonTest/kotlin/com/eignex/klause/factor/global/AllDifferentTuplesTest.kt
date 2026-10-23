package com.eignex.klause.factor.global

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.Candidate
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.validation.finiteWitnessVerifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class AllDifferentTuplesTest {
    @Test
    fun `finite witnesses should require distinct complete tuples`() {
        val problem = Problem(0, 3, Array(3) { IntDomain(0, 1) },
            arrayOf(AllDifferentTuples(listOf(intArrayOf(0, 0), intArrayOf(1, 2))))).bake()
        val verifier = finiteWitnessVerifier(problem, null)
        val distinct = Candidate<Sample, Double?>(Sample(booleanArrayOf(), longArrayOf(0, 0, 1)), null)
        val repeated = Candidate<Sample, Double?>(Sample(booleanArrayOf(), longArrayOf(0, 0, 0)), null)

        assertIs<Verification.Accepted<*, *>>(verifier.verify(distinct))
        assertIs<Verification.Rejected>(verifier.verify(repeated))
    }

    @Test
    fun `structural identity should preserve row permutations and coordinate order`() {
        val factor = AllDifferentTuples(listOf(intArrayOf(0, 1), intArrayOf(2, 3)))
        val reordered = AllDifferentTuples(listOf(intArrayOf(2, 3), intArrayOf(0, 1)))
        val reversed = AllDifferentTuples(listOf(intArrayOf(0, 1), intArrayOf(3, 2)))

        assertEquals(factor.structuralKey(), reordered.structuralKey())
        assertNotEquals(factor.structuralKey(), reversed.structuralKey())
    }

    @Test
    fun `remapping should preserve every repeated role including structural colors`() {
        val factor = AllDifferentTuples(listOf(intArrayOf(0, 0), intArrayOf(1, 2)))
        val mapping = VarRemap(intArrayOf(), intArrayOf(2, -1, 0))
        val expected = AllDifferentTuples(listOf(intArrayOf(2, 2), intArrayOf(-1, 0)))

        assertEquals(expected.structuralKey(), factor.remap(mapping).structuralKey())
        assertEquals(expected.structuralKey().hashCode(), factor.remapStructuralHash(mapping))
    }
}
