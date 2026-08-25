package com.eignex.klause.solver

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.NoInvariant
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.solver.MixedVars
import com.eignex.klause.solver.VarList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Classifying integer columns into the ones the search must branch on and the ones a theory could take.
 * The distinction is what the invented search box rests on, so what matters here is which factor kinds
 * force a column into the search set.
 */
class VariablePartitionTest {

    private fun problemOf(numInts: Int, vararg factors: Factor) = Problem(
        numBoolVars = 0,
        numIntVars = numInts,
        intDomains = Array(numInts) { IntDomain(0, 10) },
        factors = arrayOf(*factors),
    )

    private fun row(vararg vars: Int) = Linear(LongArray(vars.size) { 1L }, vars.toList().toIntArray(), LinearOp.LE, 5L)

    @Test
    fun `a column only interval reasoning mentions is theory-eligible`() {
        val partition = problemOf(2, row(0, 1)).variablePartition()
        assertTrue(partition.isTheoryEligible(0))
        assertTrue(partition.isTheoryEligible(1))
    }

    @Test
    fun `a column a value-indexing global mentions must be searched`() {
        // AllDifferent is parameterised by a value window, so it cannot act on a range it cannot walk.
        val alldiff = AllDifferent(intArrayOf(0, 1), domainMin = 0, domainSize = 11)
        val partition = problemOf(2, alldiff).variablePartition()
        assertFalse(partition.isTheoryEligible(0))
        assertFalse(partition.isTheoryEligible(1))
    }

    @Test
    fun `one global drags only the columns it mentions into the search set`() {
        val alldiff = AllDifferent(intArrayOf(0, 1), domainMin = 0, domainSize = 11)
        val partition = problemOf(3, alldiff, row(2)).variablePartition()
        assertEquals(1, partition.theoryEligibleCount, "only the linear-only column is eligible")
        assertTrue(partition.isTheoryEligible(2))
    }

    @Test
    fun `a column both kinds mention must be searched`() {
        val alldiff = AllDifferent(intArrayOf(0, 1), domainMin = 0, domainSize = 11)
        val partition = problemOf(3, alldiff, row(1, 2)).variablePartition()
        assertFalse(partition.isTheoryEligible(1), "the global's need decides a shared column")
        assertTrue(partition.isTheoryEligible(2), "the column only the row mentions stays eligible")
    }

    @Test
    fun `an unknown factor kind keeps its columns in the search set`() {
        // A factor kind the partition knows nothing about still has its declaration read: the demand
        // comes from what the factor states, not from the kind being recognized.
        val unconsidered = object : Factor {
            override val variables: VarList = MixedVars(spanInts = intArrayOf(0), boolVars = IntArray(0))
            override fun remap(mapping: VarRemap): Factor = this
            override fun structuralKey(): StructuralKey = StructuralKey.of(FactorKind.LINEAR) { int(0) }
            override fun asPropagator(): Propagator = object : Propagator {}
            override fun asInvariant(): Invariant = NoInvariant
        }
        assertFalse(problemOf(1, unconsidered).variablePartition().isTheoryEligible(0))
    }
}
