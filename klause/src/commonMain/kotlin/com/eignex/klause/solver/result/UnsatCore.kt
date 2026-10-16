package com.eignex.klause.solver.result

import com.eignex.klause.util.EmptyIntArray

/**
 * A jointly-infeasible subset of `Problem.factors`, identified by factor id, in the sense of SMT-LIB's
 * `get-unsat-core`: the constraints whose conjunction a complete backend proved unsat.
 *
 * [factorIds] is sorted ascending and contains no duplicates. The core is **not** guaranteed minimal;
 * a minimal unsatisfiable subset needs a separate minimisation pass on top, such as deletion-based
 * filtering or QuickXplain (Junker, "QUICKXPLAIN: Preferred Explanations and Relaxations for
 * Over-Constrained Problems", AAAI 2004, https://mlanthology.org/aaai/2004/junker2004aaai-quickxplain).
 *
 * Backends that don't compute cores leave the result's `core` field `null`.
 */
data class UnsatCore(
    /** Ids of the factors forming the core. */
    val factorIds: IntArray,
) {
    /** Number of factors in the core. */
    val size: Int get() = factorIds.size

    /** True iff the core is empty. */
    val isEmpty: Boolean get() = factorIds.isEmpty()

    override fun equals(other: Any?): Boolean = other is UnsatCore && factorIds.contentEquals(other.factorIds)

    override fun hashCode(): Int = factorIds.contentHashCode()

    override fun toString(): String = "UnsatCore(${factorIds.joinToString(",")})"

    /** Factory for [UnsatCore]. */
    companion object {
        /** The empty core. */
        val Empty: UnsatCore = UnsatCore(EmptyIntArray)

        /** Build a core from factor [ids], sorting and de-duplicating in one pass. */
        fun of(ids: IntArray): UnsatCore {
            if (ids.isEmpty()) return Empty
            val sorted = ids.copyOf().also { it.sort() }
            var w = 1
            for (r in 1 until sorted.size) {
                if (sorted[r] != sorted[r - 1]) sorted[w++] = sorted[r]
            }
            return UnsatCore(if (w == sorted.size) sorted else sorted.copyOf(w))
        }

        /** Build a core from a collection of factor [ids]. */
        fun of(ids: Collection<Int>): UnsatCore = of(ids.toIntArray())
    }
}
