package com.eignex.klause.lp.engine

/**
 * Deterministic work meter for an LP solve: floating-point operations, counted rather than timed.
 *
 * A budget spent against a wall clock is not reproducible — the same solve on a loaded machine reaches
 * the budget after less work, so a run's shape depends on what else the box is doing, and two arms of an
 * A/B stop at different points for reasons unrelated to the change under test. Counting the operations
 * instead makes a budget a property of the model and the pivot path alone, so a policy keyed on it
 * behaves identically on a busy machine and a quiet one.
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
        ops += n.toLong()
    }

    fun add(n: Long) {
        ops += n
    }

    /** Start a fresh solve's accounting. */
    fun reset() {
        ops = 0L
    }

    companion object {
        /**
         * Operations scaled to the order of magnitude of a second, matching the constant GLOP uses so
         * the two are comparable when reading traces side by side.
         */
        const val SECONDS_PER_OP: Double = 2e-9
    }
}
