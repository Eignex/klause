package com.eignex.klause.solver

/**
 * A jointly-infeasible subset of [Problem.factors], identified by factor id. Mirrors
 * SMT-LIB's `get-unsat-core`: when a complete backend (currently Z3) proves UNSAT, it can
 * point back at the constraints whose conjunction is already unsat — useful for
 * minimisation, explanation, and assumption-style debugging.
 *
 * `factorIds` is sorted ascending and contains no duplicates. The core is **not**
 * guaranteed minimal; callers wanting a minimal unsatisfiable subset (MUS) should run a
 * separate minimisation pass (deletion-based, QuickXplain, etc.) on top.
 *
 * Backends that don't compute cores leave the result-type's `core` field as `null`.
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
        val Empty: UnsatCore = UnsatCore(IntArray(0))

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
