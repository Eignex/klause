package com.eignex.klause.solver.factor

/**
 * Vilím-style Θ-tree for cumulative scheduling envelope reasoning. The data-structure
 * half of a Schutt-Feydy-Stuckey edge-finding propagator on `Cumulative`.
 *
 * Tasks are addressed by a stable id `0..n-1` set at construction. The id is *not* a leaf
 * position — callers pass the per-leaf order to [setLeafOrder] (typically `argsort` by
 * ascending EST after each propagation pass) so the in-tree leaf positions stay sorted by
 * EST while the calling convention stays id-based.
 *
 * For an *active* task with earliest start `est_i` and energy `e_i = duration_i · resource_i`:
 *  - leaf energy contribution: `e_i`
 *  - leaf envelope contribution: `C · est_i + e_i`
 *
 * The "envelope" of a subset Ω ⊆ Θ is `Env(Ω) = C · est(Ω) + e(Ω)` where `est(Ω)` is the
 * smallest EST in Ω. With leaves stored in EST-ascending order, for any internal node v
 * with children L (smaller-EST half) and R (larger-EST half):
 *  - `e(v) = e(L) + e(R)`
 *  - `env(v) = max(env(L) + e(R), env(R))` — anchor at the left min-EST and add R's full
 *    energy, *or* re-anchor in R entirely. Mixing a left-anchored subset with a strict
 *    subset of R never beats those two by the definition of `env`.
 *
 * Inactive leaves contribute `e = 0` and `env = NO_ENV` (a large-negative sentinel chosen
 * so that `env + e_R` for any in-range non-negative `e_R` stays negative — i.e. an empty
 * subtree's contribution loses every `max` against an active subtree).
 *
 * All mutations are O(log n) (sift to the root). [envOfTheta] / [energyOfTheta] read the
 * root in O(1). [capacity] and the EST/energy of each active task are owned by the
 * caller; this class just maintains the recurrence.
 *
 * Implementation notes:
 *  - Tree is an implicit binary heap on `1..2·leafBase-1`, `leafBase` rounded up to the
 *    next power of two. Slot 0 is unused; the root is slot 1; leaves are slots
 *    `leafBase..2·leafBase-1`. Padding leaves stay inactive forever.
 *  - `env` is `Long` because `C · est` can exceed `Int` range for plausible scheduling
 *    horizons (capacity ~10^4, est ~10^6 → 10^10).
 *  - `NO_ENV = Long.MIN_VALUE / 2` leaves ~4·10^18 of headroom under non-negative-energy
 *    addition, which is well past any feasible cumulative instance.
 */
class CumulativeThetaTree(private val n: Int, capacity: Int) {

    init {
        require(n >= 0) { "task count must be non-negative, got $n" }
        require(capacity >= 0) { "capacity must be non-negative, got $capacity" }
    }

    private val capacityL: Long = capacity.toLong()

    private val leafBase: Int = run {
        var p = 1
        while (p < n.coerceAtLeast(1)) p = p shl 1
        p
    }

    private val treeSize: Int = leafBase * 2
    private val energy = LongArray(treeSize)
    private val env = LongArray(treeSize) { NO_ENV }

    /** id → leaf slot (`leafBase + leafPos`), default position is id itself. */
    private val leafOf = IntArray(n) { leafBase + it }

    /**
     * Set the leaf position of every task in one shot. `leafPos[id]` is the position
     * `0..n-1` for task `id` in EST-sorted order; positions must be a permutation of
     * `0..n-1`. Resets the tree to all-inactive — call [activate] for each task whose
     * Θ membership you want afterwards.
     */
    fun setLeafOrder(leafPos: IntArray) {
        require(leafPos.size == n) { "leafPos.size=${leafPos.size}, expected n=$n" }
        clear()
        for (id in 0 until n) {
            val pos = leafPos[id]
            require(pos in 0 until n) { "leafPos[$id]=$pos out of 0..${n - 1}" }
            leafOf[id] = leafBase + pos
        }
    }

    /** Reset every leaf (real and padding) to the inactive state. O(2n). */
    fun clear() {
        for (k in 0 until treeSize) {
            energy[k] = 0L
            env[k] = NO_ENV
        }
    }

    /** Activate (or update) task [id] with the given EST and energy contribution.
     *  `taskEnergy` must be non-negative; pass `duration * resource` for cumulative. */
    fun activate(id: Int, est: Int, taskEnergy: Long) {
        require(id in 0 until n) { "task id $id out of 0..${n - 1}" }
        require(taskEnergy >= 0L) { "task energy must be non-negative, got $taskEnergy" }
        val k = leafOf[id]
        energy[k] = taskEnergy
        env[k] = capacityL * est.toLong() + taskEnergy
        siftUp(k)
    }

    /** Deactivate task [id]: zero its energy contribution and clear its envelope. */
    fun deactivate(id: Int) {
        require(id in 0 until n) { "task id $id out of 0..${n - 1}" }
        val k = leafOf[id]
        if (energy[k] == 0L && env[k] == NO_ENV) return
        energy[k] = 0L
        env[k] = NO_ENV
        siftUp(k)
    }

    /** Whether task [id] currently contributes to Θ. */
    fun isActive(id: Int): Boolean {
        if (id !in 0 until n) return false
        return env[leafOf[id]] != NO_ENV
    }

    /** Envelope of the active subset Θ, i.e. `max over non-empty Ω ⊆ Θ of (C · est(Ω) + e(Ω))`.
     *  Returns [NO_ENV] when Θ is empty. */
    fun envOfTheta(): Long = env[1]

    /** Summed energy of active tasks in Θ. */
    fun energyOfTheta(): Long = energy[1]

    private fun siftUp(start: Int) {
        var k = start ushr 1
        while (k >= 1) {
            val l = k shl 1
            val r = l or 1
            energy[k] = energy[l] + energy[r]
            val envL = env[l]
            val envR = env[r]
            // env(v) = max(env(L) + e(R), env(R)). NO_ENV stays large-negative under any
            // non-negative `e(R)`, so an empty L collapses naturally to env(R) without
            // a special case. An empty R also collapses naturally: env(R) = NO_ENV and
            // env(L) + 0 dominates.
            env[k] = maxOf(envL + energy[r], envR)
            k = k ushr 1
        }
    }

    companion object {
        /** Sentinel representing "no envelope" (subtree contains no active leaf). Chosen
         *  far enough below zero that adding any non-negative `e(R)` keeps the value
         *  smaller than any real envelope. */
        const val NO_ENV: Long = Long.MIN_VALUE / 2
    }
}
