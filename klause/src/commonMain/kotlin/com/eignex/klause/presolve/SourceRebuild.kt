package com.eignex.klause.presolve

import com.eignex.klause.ir.Lit
import com.eignex.klause.solver.Sample
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.EmptyLongArray
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.compareTo
import com.eignex.klause.util.div
import com.eignex.klause.util.fitsLong
import com.eignex.klause.util.minus
import com.eignex.klause.util.plus
import com.eignex.klause.util.signum
import com.eignex.klause.util.times
import com.eignex.klause.util.toLongExact

/**
 * One step of recovering the columns a pass eliminated, stated as data rather than as a closure.
 *
 * A reconstruction has to run on whichever witness the lane that solved the reduced model produced, and
 * those witnesses share no type: the finite lane yields a [com.eignex.klause.solver.Sample] over
 * `BooleanArray`, while an open route yields an assignment it answers by accessor. A `(Sample) -> Sample`
 * lambda can only serve the first. Declaring the step instead lets each lane evaluate the same record
 * over its own representation, which is what lets a column-eliminating pass run before either lane exists.
 *
 * Every step reads the values recovered so far and writes at most one variable, so a list of them is
 * applied in order and the producer is responsible for emitting an order in which each step's reads are
 * already recovered. Boolean and integer steps share one list rather than sitting in two, so that order
 * is stated once for the whole reconstruction instead of being implied between two carriers.
 *
 * A step names columns of the model before the elimination, and the reduced model has to keep every one
 * of them: a pass leaves an eliminated column in place and unconstrained rather than renumbering the
 * Boolean space. Both lanes evaluate over an array indexed by those ids, so a pass that renumbered
 * instead would have its steps read and write the wrong columns.
 */
internal sealed interface RebuildStep {

    /**
     * Give [variable] the value of [source] — its variable's value, negated when [source] is negative.
     *
     * The equivalent-literal form: a merged variable takes its representative's value.
     */
    class CopyLiteral(val variable: Int, val source: Int) : RebuildStep

    /**
     * Set [variable] to the polarity that satisfies the first of [clauses] no other literal satisfies,
     * and to `false` when every clause is already satisfied.
     *
     * The resolution form: an eliminated variable is recovered from the clauses that mentioned it.
     */
    class SatisfyClauses(val variable: Int, val clauses: List<IntArray>) : RebuildStep

    /**
     * Force [literal] true when [clause] is unsatisfied, and leave every value alone otherwise.
     *
     * The blocked-clause form: a clause dropped as satisfiability-redundant is repaired on the way back,
     * which its blocking literal can always do without falsifying a clause holding the opposite literal.
     */
    class RepairClause(val clause: IntArray, val literal: Int) : RebuildStep

    /**
     * Give integer column [variable] the value `(constTerm + Σ termCoeffs·termVars) / divisor`.
     *
     * The affine form: a column an equality defines is rebuilt from the partners it was folded into.
     * [divisor] is `1` for a unit pivot and the pivot coefficient for a residue-class doubleton, where
     * the division is exact on the values the partner's restricted range admits.
     *
     * The coefficients stay `Long` whichever lane reads them — a model states them at that width — while
     * the *values* do not: the finite lane holds `Long`, an open route arbitrary precision. That split is
     * why this is a record rather than a closure.
     */
    class AffineValue(
        val variable: Int,
        val constTerm: Long,
        val termVars: IntArray,
        val termCoeffs: LongArray,
        val divisor: Long,
    ) : RebuildStep

    /**
     * Give integer column [variable] the extreme integer on the satisfying side of
     * `(constTerm + Σ termCoeffs·termVars) / divisor`: the floor when [roundDown], the ceiling otherwise,
     * then held inside [clamp] — an upper bound when rounding down, a lower bound when rounding up.
     *
     * The projection form: a column free in the direction that relaxes the one row it sat in is recovered
     * as a value that row admits, since the row itself is gone. [clamp] is the column's other, closed side,
     * or null when that side is open too. The division rounds toward the satisfying side rather than toward
     * zero, which is what keeps the recovered value inside the row for a negative quotient.
     */
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

/**
 * The columns a pass eliminated, in the order they are recovered.
 *
 * Immutable and lane-neutral: [rebuildInto] is the whole evaluator, and a lane adapts its witness to a
 * `BooleanArray` rather than this knowing about the witness.
 */
internal class SourceRebuilds(steps: List<RebuildStep>) {
    internal val steps: List<RebuildStep> = steps.map { step ->
        when (step) {
            is RebuildStep.CopyLiteral -> RebuildStep.CopyLiteral(step.variable, step.source)
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

    /** Whether this recovers nothing, so a lane can skip adapting its witness at all. */
    val isEmpty: Boolean get() = steps.isEmpty()

    /** Whether any step recovers an integer column, so a lane knows if it must carry values at all. */
    val touchesInts: Boolean get() = steps.any { it is RebuildStep.AffineValue || it is RebuildStep.QuotientValue }

    /**
     * Recover the eliminated columns into [bools] and [ints], the reduced model's values.
     *
     * The `Long` evaluator, for a lane whose witness holds its integer columns at that width. An open
     * route reads [rebuildInto] over arbitrary precision instead; the steps are the same records.
     */
    fun rebuildInto(bools: BooleanArray, ints: LongArray) {
        for (step in steps) {
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

    /**
     * Recover the eliminated columns into [bools] and [ints], where an integer value does not fit `Long`.
     *
     * A coefficient still does — the model states it that way — so each product is an arbitrary-precision
     * value times a `Long`, and only the accumulation widens.
     */
    fun rebuildInto(bools: BooleanArray, ints: Array<BigInt>) {
        for (step in steps) {
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

    private inline fun affineValue(step: RebuildStep.AffineValue, value: (Int) -> BigInt): BigInt {
        var numerator = bigIntOf(step.constTerm)
        for (k in step.termVars.indices) numerator += value(step.termVars[k]) * bigIntOf(step.termCoeffs[k])
        val divisor = bigIntOf(step.divisor)
        val result = numerator / divisor
        require(result * divisor == numerator) { "affine reconstruction requires an integral source value" }
        return result
    }

    /** Recover the Boolean columns alone, for a lane with no integer column to carry. */
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
            }
        }
    }

    /** Whether a literal other than the one over [v] satisfies [clause]. */
    private fun satisfiedIgnoring(clause: IntArray, v: Int, bools: BooleanArray): Boolean {
        for (l in clause) {
            if (Lit.variable(l) == v) continue
            if (Lit.evaluate(l, bools[Lit.variable(l)])) return true
        }
        return false
    }

    /** The value of [v] that satisfies its literal in [clause]. */
    private fun valueSatisfying(clause: IntArray, v: Int): Boolean {
        for (l in clause) if (Lit.variable(l) == v) return Lit.isPositive(l)
        return false
    }

    /** Whether any literal satisfies [clause]. */
    private fun satisfied(clause: IntArray, bools: BooleanArray): Boolean {
        for (l in clause) if (Lit.evaluate(l, bools[Lit.variable(l)])) return true
        return false
    }

    /** Ways to obtain a rebuild. */
    companion object {
        /** No column was eliminated. */
        val NONE = SourceRebuilds(emptyList())

        /**
         * The rebuilds of [passes] as one, taking them in the order the passes ran.
         *
         * Recovery runs the other way round from elimination: each pass eliminated columns from the model
         * the pass before it produced, so the last pass's columns are recovered first and every earlier
         * pass's steps then read them. Composing a whole sequence rather than a pair keeps that reversal
         * in one place — a pairwise operator reads as "this, then that" and states the opposite of what it
         * does. [PresolveRoundEngine.compose] folds the finite lane's lifts over the same order.
         */
        fun compose(passes: List<SourceRebuilds>): SourceRebuilds {
            val steps = passes.asReversed().flatMap { it.steps }
            return if (steps.isEmpty()) NONE else SourceRebuilds(steps)
        }
    }
}

/**
 * This rebuild as the finite lane's sample lift, or `null` when it recovers nothing.
 *
 * The adapter, not the evaluator: a lane owns how its witness becomes the `BooleanArray` the steps read
 * and write, so that [SourceRebuilds] itself names no witness type.
 */
internal fun SourceRebuilds.asSampleLift(): ((Sample) -> Sample)? = if (isEmpty) {
    null
} else {
    { sample ->
        val bools = sample.bools.copyOf()
        val exact = sample.exactInts
        if (exact == null) {
            val ints = sample.ints.copyOf()
            rebuildInto(bools, ints)
            sample.copy(bools = bools, ints = ints)
        } else {
            val ints = exact.toTypedArray()
            rebuildInto(bools, ints)
            val finite = if (ints.all { it.fitsLong() }) {
                LongArray(ints.size) { ints[it].toLongExact() }
            } else {
                EmptyLongArray
            }
            sample.copy(bools = bools, ints = finite, exactInts = ints.toList())
        }
    }
}
