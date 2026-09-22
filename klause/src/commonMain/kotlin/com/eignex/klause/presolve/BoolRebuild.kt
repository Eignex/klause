package com.eignex.klause.presolve

import com.eignex.klause.ir.Lit
import com.eignex.klause.solver.Sample

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
 * already recovered.
 */
internal sealed interface BoolRebuild {

    /**
     * Give [variable] the value of [source] — its variable's value, negated when [source] is negative.
     *
     * The equivalent-literal form: a merged variable takes its representative's value.
     */
    class CopyLiteral(val variable: Int, val source: Int) : BoolRebuild

    /**
     * Set [variable] to the polarity that satisfies the first of [clauses] no other literal satisfies,
     * and to `false` when every clause is already satisfied.
     *
     * The resolution form: an eliminated variable is recovered from the clauses that mentioned it.
     */
    class SatisfyClauses(val variable: Int, val clauses: List<IntArray>) : BoolRebuild

    /**
     * Force [literal] true when [clause] is unsatisfied, and leave every value alone otherwise.
     *
     * The blocked-clause form: a clause dropped as satisfiability-redundant is repaired on the way back,
     * which its blocking literal can always do without falsifying a clause holding the opposite literal.
     */
    class RepairClause(val clause: IntArray, val literal: Int) : BoolRebuild
}

/**
 * The Boolean columns a pass eliminated, in the order they are recovered.
 *
 * Immutable and lane-neutral: [rebuildInto] is the whole evaluator, and a lane adapts its witness to a
 * `BooleanArray` rather than this knowing about the witness.
 */
internal class BoolRebuilds(private val steps: List<BoolRebuild>) {

    /** Whether this recovers nothing, so a lane can skip adapting its witness at all. */
    val isEmpty: Boolean get() = steps.isEmpty()

    /** This rebuild followed by [next], for composing the passes of one round. */
    fun andThen(next: BoolRebuilds): BoolRebuilds = when {
        isEmpty -> next

        next.isEmpty -> this

        // `next` ran on the model this one produced, so its columns are recovered first and this one's
        // steps then read them.
        else -> BoolRebuilds(next.steps + steps)
    }

    /** Recover the eliminated columns into [bools], which holds the reduced model's Boolean values. */
    fun rebuildInto(bools: BooleanArray) {
        for (step in steps) {
            when (step) {
                is BoolRebuild.CopyLiteral ->
                    bools[step.variable] = Lit.evaluate(step.source, bools[Lit.variable(step.source)])

                is BoolRebuild.SatisfyClauses -> {
                    var value = false
                    for (clause in step.clauses) {
                        if (satisfiedIgnoring(clause, step.variable, bools)) continue
                        value = valueSatisfying(clause, step.variable)
                        break
                    }
                    bools[step.variable] = value
                }

                is BoolRebuild.RepairClause ->
                    if (!satisfied(step.clause, bools)) {
                        bools[Lit.variable(step.literal)] = Lit.isPositive(step.literal)
                    }
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

    /** The rebuild that recovers nothing. */
    companion object {
        /** No column was eliminated. */
        val NONE = BoolRebuilds(emptyList())
    }
}

/**
 * This rebuild as the finite lane's sample lift, or `null` when it recovers nothing.
 *
 * The adapter, not the evaluator: a lane owns how its witness becomes the `BooleanArray` the steps read
 * and write, so that [BoolRebuilds] itself names no witness type.
 */
internal fun BoolRebuilds.asSampleLift(): ((Sample) -> Sample)? = if (isEmpty) {
    null
} else {
    { sample -> sample.copy(bools = sample.bools.copyOf().also { rebuildInto(it) }) }
}
