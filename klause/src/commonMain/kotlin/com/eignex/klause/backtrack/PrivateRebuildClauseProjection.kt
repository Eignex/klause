package com.eignex.klause.backtrack

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Lit
import com.eignex.klause.presolve.RebuildStep
import com.eignex.klause.presolve.SourceRebuilds

internal class PrivateRebuildClauseProjection private constructor(
    private val eliminated: BooleanArray,
    private val stages: List<Stage>,
) {
    private class Stage(val variable: Int, val positive: IntArray?, val negative: IntArray?) {
        val pureValue: Boolean? get() = when {
            positive == null -> false
            negative == null -> true
            else -> null
        }
    }

    sealed interface Result {
        class Complete(val literals: IntArray) : Result
        data object Skipped : Result
        data object Stopped : Result
        data object Refuted : Result
    }

    fun eliminates(variable: Int): Boolean = eliminated[variable]

    fun project(literals: IntArray, visit: (Long) -> Boolean): Result {
        if (literals.size > MAX_WIDTH) return Result.Skipped
        var current = literals
        for (stage in stages) {
            if (!visit(current.size + 1L)) return Result.Stopped
            var pivot = Lit.NONE
            for (literal in current) {
                if (Lit.variable(literal) != stage.variable) continue
                if (pivot != Lit.NONE) return Result.Skipped
                pivot = literal
            }
            if (pivot == Lit.NONE) continue
            val pure = stage.pureValue
            if (pure != null) {
                if (Lit.evaluate(pivot, pure)) return Result.Skipped
                if (!visit(current.size * 2L + 1L)) return Result.Stopped
                // A pure satisfying value lifts every projected model, so its substitution preserves consequences.
                current = current.filter { Lit.variable(it) != stage.variable }.toIntArray()
                if (current.isEmpty()) return Result.Refuted
                continue
            }
            val parent = if (Lit.isPositive(pivot)) checkNotNull(stage.negative) else checkNotNull(stage.positive)
            if (parent.size > MAX_WIDTH) return Result.Skipped
            if (!visit((current.size + parent.size) * 4L + 4L)) return Result.Stopped
            val merged = IntArray(current.size + parent.size - 2)
            var used = 0
            for (row in listOf(current, parent)) for (literal in row) {
                if (Lit.variable(literal) != stage.variable) merged[used++] = literal
            }
            if (merged.isEmpty()) return Result.Refuted
            // One source resolvent remains entailed after projection without expanding the learned database.
            val resolved = Clause(merged)
            if (resolved.tautological || resolved.literals.size > MAX_WIDTH) return Result.Skipped
            current = resolved.literals
        }
        if (!visit(current.size + 1L)) return Result.Stopped
        if (current.any { eliminated[Lit.variable(it)] }) return Result.Skipped
        return Result.Complete(current)
    }

    companion object {
        const val SCRATCH_BYTES: Long = 16_384L
        private const val MAX_WIDTH = 64

        fun prepare(
            rebuild: SourceRebuilds,
            columns: Int,
            visit: (Long) -> Boolean,
        ): PrivateRebuildClauseProjection? {
            if (!visit(columns * 2L + rebuild.steps.size)) return null
            val eliminated = BooleanArray(columns)
            val stages = ArrayList<Stage>(rebuild.steps.size)
            // Rebuild descriptors run backwards; clause projection follows the elimination order.
            for (step in rebuild.steps.asReversed()) {
                if (step !is RebuildStep.SatisfyClauses || step.variable !in 0 until columns) return null
                if (eliminated[step.variable] || !visit(3L)) return null
                eliminated[step.variable] = true
                var positive: IntArray? = null
                var negative: IntArray? = null
                for (parent in step.clauses) {
                    if (!visit(parent.size + 2L)) return null
                    var pivot = Lit.NONE
                    for (literal in parent) {
                        if (Lit.variable(literal) !in 0 until columns) return null
                        if (Lit.variable(literal) != step.variable) continue
                        if (pivot != Lit.NONE) return null
                        pivot = literal
                    }
                    if (pivot == Lit.NONE) return null
                    if (Lit.isPositive(pivot)) {
                        if (parent.size < (positive?.size ?: Int.MAX_VALUE)) positive = parent
                    } else if (parent.size < (negative?.size ?: Int.MAX_VALUE)) negative = parent
                }
                if (positive == null && negative == null) return null
                stages.add(Stage(step.variable, positive, negative))
            }
            return PrivateRebuildClauseProjection(eliminated, stages)
        }
    }
}
