package com.eignex.klause.presolve

import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.solver.Sample
import com.eignex.util.arrays.emptyLongArray
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.BigInt
import com.eignex.util.numbers.bigIntOf
import com.eignex.util.numbers.compareTo
import com.eignex.util.numbers.div
import com.eignex.util.numbers.fitsLong
import com.eignex.util.numbers.minus
import com.eignex.util.numbers.plus
import com.eignex.util.numbers.signum
import com.eignex.util.numbers.times
import com.eignex.util.numbers.toLongExact

// One step of recovering an eliminated column, stated as data rather than a closure so each lane can
// evaluate it over its own witness representation (the finite lane's `Sample`, an open route's
// arbitrary-precision assignment), which lets a column-eliminating pass run before either lane exists.
// Each step reads values recovered so far and writes at most one variable; the producer emits an order in
// which every read is already recovered. Boolean and integer steps share one list so that order is stated
// once. Steps name pre-elimination columns; neither namespace is renumbered, and Booleans appended by
// binary substitution are read before the witness adapter truncates them.
internal sealed interface RebuildStep {

    // A merged variable takes its representative literal's value.
    class CopyLiteral(val variable: Int, val source: Int) : RebuildStep

    class IntegerFromLiteral(val variable: Int, val literal: Int) : RebuildStep

    class TupleValue(val variable: Int, val literals: IntArray, val values: LongArray) : RebuildStep

    // Truth of the defining integer comparison, before clause polarity.
    class ComparisonValue(val variable: Int, val intVar: Int, val op: LinearOp, val bound: Long) : RebuildStep

    // Resolution form: the polarity satisfying the first clause no other literal satisfies, else false.
    class SatisfyClauses(val variable: Int, val clauses: List<IntArray>) : RebuildStep

    // Blocked-clause form: forcing the blocking literal repairs the clause without falsifying any clause
    // holding the opposite literal.
    class RepairClause(val clause: IntArray, val literal: Int) : RebuildStep

    // `(constTerm + Σ termCoeffs·termVars) / divisor`, rebuilding a column an equality defined. [divisor] is 1
    // for a unit pivot and the pivot coefficient for a residue-class doubleton, where the division is exact.
    // Coefficients are `Long` in every lane but values are not, which is why this is a record.
    class AffineValue(
        val variable: Int,
        val constTerm: Long,
        val termVars: IntArray,
        val termCoeffs: LongArray,
        val divisor: Long,
    ) : RebuildStep

    // Projection form: a column free in the direction that relaxes its one (now removed) row is recovered as
    // `(constTerm + Σ termCoeffs·termVars) / divisor` rounded toward the satisfying side (floor when
    // [roundDown]), then held inside [clamp], the column's other closed side or null when that is open too.
    class QuotientValue(
        val variable: Int,
        val constTerm: Long,
        val termVars: IntArray,
        val termCoeffs: LongArray,
        val divisor: Long,
        val roundDown: Boolean,
        val clamp: Long?,
    ) : RebuildStep
}

// Immutable and lane-neutral, in recovery order. The witness adapter truncates appended Boolean
// coordinates to [sourceBoolVars] after all steps have read them.
internal class SourceRebuilds(steps: List<RebuildStep>, val sourceBoolVars: Int? = null) {
    init {
        require(sourceBoolVars == null || sourceBoolVars >= 0)
    }

    internal val steps: List<RebuildStep> = steps.map { step ->
        when (step) {
            is RebuildStep.CopyLiteral -> RebuildStep.CopyLiteral(step.variable, step.source)
            is RebuildStep.IntegerFromLiteral -> RebuildStep.IntegerFromLiteral(step.variable, step.literal)
            is RebuildStep.TupleValue -> {
                require(step.literals.size == step.values.size)
                RebuildStep.TupleValue(step.variable, step.literals.copyOf(), step.values.copyOf())
            }
            is RebuildStep.ComparisonValue ->
                RebuildStep.ComparisonValue(step.variable, step.intVar, step.op, step.bound)
            is RebuildStep.SatisfyClauses -> RebuildStep.SatisfyClauses(step.variable, step.clauses.map { it.copyOf() })
            is RebuildStep.RepairClause -> RebuildStep.RepairClause(step.clause.copyOf(), step.literal)
            is RebuildStep.AffineValue -> {
                require(step.divisor != 0L && step.termVars.size == step.termCoeffs.size)
                RebuildStep.AffineValue(
                    step.variable, step.constTerm, step.termVars.copyOf(), step.termCoeffs.copyOf(), step.divisor,
                )
            }
            is RebuildStep.QuotientValue -> {
                require(step.divisor != 0L && step.termVars.size == step.termCoeffs.size)
                RebuildStep.QuotientValue(
                    step.variable, step.constTerm, step.termVars.copyOf(), step.termCoeffs.copyOf(),
                    step.divisor, step.roundDown, step.clamp,
                )
            }
        }
    }

    val isEmpty: Boolean get() = steps.isEmpty() && sourceBoolVars == null

    val touchesInts: Boolean get() = steps.any {
        it is RebuildStep.AffineValue || it is RebuildStep.QuotientValue || it is RebuildStep.ComparisonValue ||
            it is RebuildStep.IntegerFromLiteral || it is RebuildStep.TupleValue
    }

    fun rebuildInto(bools: BooleanArray, ints: LongArray) {
        for (step in steps) {
            if (step is RebuildStep.TupleValue) {
                ints[step.variable] = tupleValue(step, bools)
                continue
            }
            if (step is RebuildStep.IntegerFromLiteral) {
                ints[step.variable] = if (Lit.evaluate(step.literal, bools[Lit.variable(step.literal)])) 1L else 0L
                continue
            }
            if (step is RebuildStep.ComparisonValue) {
                bools[step.variable] = comparisonHolds(step.op, ints[step.intVar].compareTo(step.bound))
                continue
            }
            if (step is RebuildStep.AffineValue) {
                ints[step.variable] = affineValue(step) { bigIntOf(ints[it]) }.toLongExact()
                continue
            }
            if (step is RebuildStep.QuotientValue) {
                var n = step.constTerm
                for (k in step.termVars.indices) n += step.termCoeffs[k] * ints[step.termVars[k]]
                val down = n.floorDiv(step.divisor)
                val value = if (step.roundDown || down * step.divisor == n) down else down + 1
                val clamp = step.clamp
                ints[step.variable] = when {
                    clamp == null -> value
                    step.roundDown -> minOf(value, clamp)
                    else -> maxOf(value, clamp)
                }
                continue
            }
            rebuildBool(step, bools)
        }
    }

    fun rebuildInto(bools: BooleanArray, ints: Array<BigInt>) {
        for (step in steps) {
            if (step is RebuildStep.TupleValue) {
                ints[step.variable] = bigIntOf(tupleValue(step, bools))
                continue
            }
            if (step is RebuildStep.IntegerFromLiteral) {
                ints[step.variable] = bigIntOf(
                    if (Lit.evaluate(step.literal, bools[Lit.variable(step.literal)])) 1L else 0L,
                )
                continue
            }
            if (step is RebuildStep.ComparisonValue) {
                bools[step.variable] = comparisonHolds(step.op, ints[step.intVar].compareTo(bigIntOf(step.bound)))
                continue
            }
            if (step is RebuildStep.AffineValue) {
                ints[step.variable] = affineValue(step) { ints[it] }
                continue
            }
            if (step is RebuildStep.QuotientValue) {
                var n = bigIntOf(step.constTerm)
                for (k in step.termVars.indices) {
                    n += ints[step.termVars[k]] * bigIntOf(step.termCoeffs[k])
                }
                val d = bigIntOf(step.divisor)
                val truncated = n / d
                val exact = truncated * d == n
                // Truncation rounds toward zero; step to the satisfying side when it rounded the wrong way.
                val negative = (n.signum() < 0) xor (d.signum() < 0)
                val value = when {
                    exact -> truncated
                    step.roundDown -> if (negative) truncated - BIG_ONE else truncated
                    else -> if (negative) truncated else truncated + BIG_ONE
                }
                val clamp = step.clamp?.let { bigIntOf(it) }
                ints[step.variable] = when {
                    clamp == null -> value
                    step.roundDown -> if (value > clamp) clamp else value
                    else -> if (value < clamp) clamp else value
                }
                continue
            }
            rebuildBool(step, bools)
        }
    }

    private fun tupleValue(step: RebuildStep.TupleValue, bools: BooleanArray): Long {
        var selected = -1
        for (index in step.literals.indices) {
            val literal = step.literals[index]
            if (!Lit.evaluate(literal, bools[Lit.variable(literal)])) continue
            require(selected < 0) { "tuple reconstruction requires one selected row" }
            selected = index
        }
        require(selected >= 0) { "tuple reconstruction requires one selected row" }
        return step.values[selected]
    }

    private inline fun affineValue(step: RebuildStep.AffineValue, value: (Int) -> BigInt): BigInt {
        var numerator = bigIntOf(step.constTerm)
        for (k in step.termVars.indices) numerator += value(step.termVars[k]) * bigIntOf(step.termCoeffs[k])
        val divisor = bigIntOf(step.divisor)
        val result = numerator / divisor
        require(result * divisor == numerator) { "affine reconstruction requires an integral source value" }
        return result
    }

    private fun comparisonHolds(op: LinearOp, order: Int): Boolean = when (op) {
        LinearOp.LE -> order <= 0
        LinearOp.EQ -> order == 0
        LinearOp.GE -> order >= 0
        LinearOp.NE -> order != 0
    }

    fun rebuildInto(bools: BooleanArray) {
        for (step in steps) rebuildBool(step, bools)
    }

    private fun rebuildBool(step: RebuildStep, bools: BooleanArray) {
        run {
            when (step) {
                is RebuildStep.CopyLiteral ->
                    bools[step.variable] = Lit.evaluate(step.source, bools[Lit.variable(step.source)])

                is RebuildStep.SatisfyClauses -> {
                    var value = false
                    for (clause in step.clauses) {
                        if (satisfiedIgnoring(clause, step.variable, bools)) continue
                        value = valueSatisfying(clause, step.variable)
                        break
                    }
                    bools[step.variable] = value
                }

                is RebuildStep.RepairClause ->
                    if (!satisfied(step.clause, bools)) {
                        bools[Lit.variable(step.literal)] = Lit.isPositive(step.literal)
                    }

                // Recovered by the value evaluators, which alone know the width to compute it at.
                is RebuildStep.AffineValue, is RebuildStep.QuotientValue -> Unit

                is RebuildStep.ComparisonValue, is RebuildStep.IntegerFromLiteral, is RebuildStep.TupleValue ->
                    error("literal and comparison reconstruction requires integer coordinates")
            }
        }
    }

    private fun satisfiedIgnoring(clause: IntArray, v: Int, bools: BooleanArray): Boolean {
        for (l in clause) {
            if (Lit.variable(l) == v) continue
            if (Lit.evaluate(l, bools[Lit.variable(l)])) return true
        }
        return false
    }

    private fun valueSatisfying(clause: IntArray, v: Int): Boolean {
        for (l in clause) if (Lit.variable(l) == v) return Lit.isPositive(l)
        return false
    }

    private fun satisfied(clause: IntArray, bools: BooleanArray): Boolean {
        for (l in clause) if (Lit.evaluate(l, bools[Lit.variable(l)])) return true
        return false
    }

    companion object {
        val NONE = SourceRebuilds(emptyList())

        // [passes] in run order. Recovery runs the other way round: the last pass's columns are recovered
        // first and every earlier pass's steps then read them. Taking the whole sequence keeps that reversal
        // in one place.
        fun compose(passes: List<SourceRebuilds>): SourceRebuilds {
            val steps = passes.asReversed().flatMap { it.steps }
            val sourceBoolVars = passes.firstNotNullOfOrNull { it.sourceBoolVars }
            return if (steps.isEmpty() && sourceBoolVars == null) NONE else SourceRebuilds(steps, sourceBoolVars)
        }
    }
}

// Null when it recovers nothing. The lane owns this adapter so [SourceRebuilds] names no witness type.
internal fun SourceRebuilds.asSampleLift(): ((Sample) -> Sample)? = if (isEmpty) {
    null
} else {
    { sample ->
        val bools = sample.bools
        val sourceBools = sourceBoolVars ?: bools.size
        require(sourceBools <= bools.size) { "reconstruction cannot extend the reduced Boolean namespace" }
        val exact = sample.exactInts
        if (exact == null) {
            val ints = sample.ints
            rebuildInto(bools, ints)
            sample.copy(bools = if (sourceBools == bools.size) bools else bools.copyOf(sourceBools), ints = ints)
        } else {
            val ints = exact.toTypedArray()
            rebuildInto(bools, ints)
            val finite = if (ints.all { it.fitsLong() }) {
                LongArray(ints.size) { ints[it].toLongExact() }
            } else {
                emptyLongArray()
            }
            sample.copy(bools = if (sourceBools == bools.size) bools else bools.copyOf(sourceBools),
                ints = finite, exactInts = ints.toList())
        }
    }
}
