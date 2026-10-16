package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.FactorKind
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.MixedVars
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.StructuralKey
import com.eignex.klause.ir.VarList
import com.eignex.klause.ir.VarRemap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VariablePartitionTest {

    private fun problemOf(numInts: Int, vararg factors: Factor) = Problem(
        numBoolVars = 0,
        numIntVars = numInts,
        intDomains = Array(numInts) { IntDomain(0, 10) },
        factors = arrayOf(*factors),
    )

    private fun row(vararg vars: Int) = Linear(LongArray(vars.size) { 1L }, vars.toList().toIntArray(), LinearOp.LE, 5L)

    @Test
    fun `an encoded global should admit all its columns to arithmetic representation`() {
        val alldiff = AllDifferent(intArrayOf(0, 1), domainMin = 0, domainSize = 11)
        val partition = problemOf(3, alldiff, row(2)).variablePartition()
        assertEquals(3, partition.theoryEligibleCount)
        assertTrue(partition.isTheoryEligible(2))
    }

    @Test
    fun `unknown factor kind should keep its columns in the search set`() {
        assertFalse(
            problemOf(1, cpOnlyFactor(MixedVars(spanInts = intArrayOf(0)))).variablePartition().isTheoryEligible(0),
        )
    }

    @Test
    fun `column a CP-only factor only bounds should still be searched`() {
        // A theory owning the column could not see the CP factor that reads its bounds.
        val boundsOnly = cpOnlyFactor(MixedVars(boundInts = intArrayOf(0)))

        assertFalse(problemOf(1, boundsOnly).variablePartition().isTheoryEligible(0))
    }

    private fun cpOnlyFactor(variables: VarList) = object : Factor {
        override val variables: VarList = variables
        override fun remap(mapping: VarRemap): Factor = this
        override fun structuralKey(): StructuralKey = StructuralKey.of(FactorKind.LINEAR) { int(0) }
    }
}
