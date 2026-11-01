package com.eignex.klause.backtrack

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.BoundedVariableElimination
import com.eignex.klause.presolve.PresolveShared.withSourcePassDelta
import com.eignex.klause.presolve.SourceDelta
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.IntArrayList

internal class PrivateRebuildNeighborhood private constructor(
    private val source: Problem,
    private val problem: Problem,
    private val protectedColumns: Set<Int>,
    private val sourceIndices: IntArray?,
) {
    val indexedFactors: Int get() = problem.numFactors
    val sourceFactors: Int get() = source.numFactors

    fun eliminate(cancellation: Cancellation): Reduction = Reduction(cancellation)

    inner class Reduction internal constructor(cancellation: Cancellation) {
        private val delta = BoundedVariableElimination.eliminateSource(problem, objectiveBoolVars = protectedColumns,
            cancellation = cancellation)
        val rebuild get() = delta.rebuild
        val infeasible get() = delta.infeasible

        fun materialize(cancellation: Cancellation): Problem? {
            if (cancellation()) return null
            val indices = sourceIndices
            val restored = if (indices == null) delta else {
                cancellation.charge(delta.droppedIndices.size * 2L + 1L)
                if (cancellation()) return null
                SourceDelta(
                    droppedIndices = IntArray(delta.droppedIndices.size) { indices[delta.droppedIndices[it]] },
                    addedFactors = delta.addedFactors, bounds = delta.bounds, infeasible = delta.infeasible,
                    rebuild = delta.rebuild,
                ).also { it.objective = delta.objective }
            }
            return source.withSourcePassDelta(restored).takeUnless { cancellation() }
        }
    }

    companion object {
        fun prepare(
            source: Problem,
            admission: PrivateRebuildAdmission,
            cancellation: Cancellation,
        ): PrivateRebuildNeighborhood? {
            if (cancellation()) return null
            if (admission.candidates != 1) {
                return PrivateRebuildNeighborhood(source, source, admission.protectedColumns, null)
            }
            var pivot = -1
            for (column in 0 until source.numBoolVars) {
                cancellation.charge(1L)
                if (cancellation()) return null
                if (column !in admission.protectedColumns) {
                    pivot = column
                    break
                }
            }
            if (pivot < 0) return null
            val factors = ArrayList<Factor>()
            val indices = IntArrayList()
            for (index in source.factors.indices) {
                val clause = source.factors[index] as Clause
                cancellation.charge(clause.literals.size + 5L)
                if (cancellation()) return null
                if (clause.literals.none { Lit.variable(it) == pivot }) continue
                factors.add(clause)
                indices.add(index)
            }
            if (factors.size == source.numFactors) {
                return PrivateRebuildNeighborhood(source, source, admission.protectedColumns, null)
            }
            cancellation.charge(factors.size * 2L)
            if (cancellation()) return null
            // Outside rows contain no pivot literal, so existential projection commutes with them.
            return PrivateRebuildNeighborhood(source, source.withFactors(factors.toTypedArray()),
                admission.protectedColumns, indices.toIntArray())
        }
    }
}
