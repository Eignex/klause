package com.eignex.klause.lp.engine

/**
 * Deterministic work meter for an LP solve: floating-point operations, counted rather than timed.
 *
 * A budget spent against a wall clock is not reproducible: the same solve on a loaded machine reaches
 * the budget after less work. Counting operations makes a budget a property of the model and the pivot
 * path alone, so a policy keyed on it behaves identically on a busy machine and a quiet one.
 *
 * The count is a proxy, not a measurement: each kernel charges the entries it touches (`nnz` for a solve
 * through the factorization, `m` per eta application or dense pass). That is proportional to real cost
 * and, unlike time, exactly reproducible.
 */
internal class LpWork {
    /** Operations charged since the last [reset]. */
    var ops: Long = 0L
        private set

    fun add(n: Int) {
        add(n.toLong())
    }

    fun add(n: Long) {
        require(n >= 0)
        ops = if (n > Long.MAX_VALUE - ops) Long.MAX_VALUE else ops + n
    }

    fun reset() {
        ops = 0L
    }

    companion object {
        /** Conversion factor that scales an operation count to the order of magnitude of a second. */
        const val SECONDS_PER_OP: Double = 2e-9
    }
}
