package com.eignex.klause.solver.factor.bool

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.factor.litVars
import com.eignex.klause.solver.factor.remapLits

/**
 * `min ≤ (#true literals) ≤ max`. Payload at `longPayload(factorId)` is the count of true
 * literals. AtMostOne, AtLeastOne, ExactlyOne are special cases.
 */
class Cardinality(literals: IntArray, min: Int, max: Int) :
    CardinalitySumFactor(literals, min, max, excludedVar = -1),
    CardinalityPropagator,
    CardinalityInvariant {

    override fun structuralKey(): String = "card:$min:$max:" + literals.sorted().joinToString(",")

    override fun remap(boolMap: IntArray, intMap: IntArray): Factor = Cardinality(literals.remapLits(boolMap), min, max)

    override val boolVars: IntArray = literals.litVars()

    override fun signedForVar(v: Int): Int = signedByVar[v]

    /** Number of literals to watch on the at-least-min side (0 if min == 0, else min+1).
     * Capped to [literals].size; oversize means "all literals must be true" which the
     * watched scheme can't represent, in which case [initialBoolWatchers] returns null
     * and the general scanner handles it. */
    override val atLeastWatchSize: Int =
        if (min == 0) 0 else (min + 1).let { if (it > literals.size) -1 else it }

    /** Mirror of [atLeastWatchSize] for the at-most-max side. */
    override val atMostWatchSize: Int =
        if (max == literals.size) {
            0
        } else {
            (literals.size - max + 1).let {
                if (it > literals.size) -1 else it
            }
        }

    /**
     * Watched-literal opt-in. Generalises clause's two-watch scheme to cardinality:
     *  - At-least-min side: watch `min + 1` literals; the constraint stays alive while
     *    all are non-false. When one becomes false and no non-false replacement exists,
     *    the remaining `min` must all be true → unit-propagate them.
     *  - At-most-max side: symmetric, on literal negations — watch `n - max + 1`
     *    literals as non-true candidates; when one becomes true and no non-true
     *    replacement exists, the remaining `n - max` must all stay false → force them.
     *
     * Falls back to occurrence-list wakeup (returns `null`) when either side wants
     * more watches than the clause has literals (degenerate: `min == n` or `max == 0`),
     * or when both sides are trivial (`min == 0 && max == n`). The general scanner in
     * [propagate] handles those cases correctly without watched-literal bookkeeping.
     */
    override val initialBoolWatchers: IntArray? = run {
        if (atLeastWatchSize < 0 || atMostWatchSize < 0) return@run null
        if (atLeastWatchSize == 0 && atMostWatchSize == 0) return@run null
        val out = IntArray(atLeastWatchSize + atMostWatchSize)
        var w = 0
        // At-least watch set: positive literals at the first `min + 1` positions.
        for (i in 0 until atLeastWatchSize) out[w++] = literals[i]
        // At-most watch set: negations of the first `n - max + 1` positions.
        for (i in 0 until atMostWatchSize) out[w++] = Lit.negate(literals[i])
        out
    }

    /** Cached max |`signedByVar[v]`| across `boolVars`. Bounds the change `n` can
     *  see from a single flip, used by [updateBoolBreakMakeForFlip]'s early-out: when both
     *  the pre- and post-flip `n` are far enough from the [min] / [max] boundaries that no
     *  single subsequent flip could cross either side, no break/make state needs to change. */
    override val maxAbsSigned: Int = run {
        var m = 0
        for (v in boolVars) {
            val s = signedByVar[v]
            val a = if (s < 0) -s else s
            if (a > m) m = a
        }
        m
    }

    /** Factory methods for this factor. */
    companion object {
        /** At-most-one: at most one of [literals] is true. */
        fun atMostOne(literals: IntArray): Cardinality = Cardinality(literals, min = 0, max = 1)

        /** At-least-one: at least one of [literals] is true. */
        fun atLeastOne(literals: IntArray): Cardinality = Cardinality(literals, min = 1, max = literals.size)

        /** Exactly-one: exactly one of [literals] is true. */
        fun exactlyOne(literals: IntArray): Cardinality = Cardinality(literals, min = 1, max = 1)
    }
}
