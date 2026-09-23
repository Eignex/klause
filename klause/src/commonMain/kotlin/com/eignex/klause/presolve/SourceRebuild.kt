package com.eignex.klause.presolve

import com.eignex.klause.ir.Lit
import com.eignex.klause.solver.Sample
import com.ionspin.kotlin.bignum.integer.BigInteger

/**
 * One step of recovering the Boolean columns a pass eliminated, stated as data rather than as a closure.
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
}

/**
 * The Boolean columns a pass eliminated, in the order they are recovered.
 *
 * Immutable and lane-neutral: [rebuildInto] is the whole evaluator, and a lane adapts its witness to a
 * `BooleanArray` rather than this knowing about the witness.
 */
internal class SourceRebuilds(private val steps: List<RebuildStep>) {

    /** Whether this recovers nothing, so a lane can skip adapting its witness at all. */
    val isEmpty: Boolean get() = steps.isEmpty()

    /** Whether any step recovers an integer column, so a lane knows if it must carry values at all. */
    val touchesInts: Boolean get() = steps.any { it is RebuildStep.AffineValue }

    /**
     * Recover the eliminated columns into [bools] and [ints], the reduced model's values.
     *
     * The `Long` evaluator, for a lane whose witness holds its integer columns at that width. An open
     * route reads [rebuildInto] over arbitrary precision instead; the steps are the same records.
     */
    fun rebuildInto(bools: BooleanArray, ints: LongArray) {
        for (step in steps) {
            if (step is RebuildStep.AffineValue) {
                var v = step.constTerm
                for (k in step.termVars.indices) v += step.termCoeffs[k] * ints[step.termVars[k]]
                ints[step.variable] = if (step.divisor == 1L) v else v / step.divisor
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
    fun rebuildInto(bools: BooleanArray, ints: Array<BigInteger>) {
        for (step in steps) {
            if (step is RebuildStep.AffineValue) {
                var v = BigInteger.fromLong(step.constTerm)
                for (k in step.termVars.indices) {
                    v += ints[step.termVars[k]] * BigInteger.fromLong(step.termCoeffs[k])
                }
                ints[step.variable] = if (step.divisor == 1L) v else v / BigInteger.fromLong(step.divisor)
                continue
            }
            rebuildBool(step, bools)
        }
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
                is RebuildStep.AffineValue -> Unit
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
        val ints = sample.ints.copyOf()
        rebuildInto(bools, ints)
        sample.copy(bools = bools, ints = ints)
    }
}
