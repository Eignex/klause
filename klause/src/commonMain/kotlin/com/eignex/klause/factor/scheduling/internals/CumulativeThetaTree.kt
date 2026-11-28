package com.eignex.klause.factor.scheduling.internals

/**
 * Θ-tree for cumulative scheduling envelope reasoning — the data-structure half of the edge-finding
 * propagator on `Cumulative` (P. Vilím, "Edge Finding Filtering Algorithm for Discrete Cumulative
 * Resources in O(kn log n)", CP 2009, https://doi.org/10.1007/978-3-642-04244-7_62).
 *
 * Tasks keep a stable id `0..n-1`; [setLeafOrder] maps ids to EST-sorted leaf positions. The envelope of
 * Ω ⊆ Θ is `Env(Ω) = C · est(Ω) + e(Ω)`, and with leaves in EST order each node keeps
 * `e(v) = e(L) + e(R)` and `env(v) = max(env(L) + e(R), env(R))`. Mutations are O(log n); the root
 * queries are O(1). Inactive leaves hold `e = 0` and [NO_ENV].
 */
class CumulativeThetaTree(private val n: Int, capacity: Long) {

    init {
        require(n >= 0) { "task count must be non-negative, got $n" }
        require(capacity >= 0) { "capacity must be non-negative, got $capacity" }
    }

    private val capacityL: Long = capacity

    private val leafBase: Int = run {
        var p = 1
        while (p < n.coerceAtLeast(1)) p = p shl 1
        p
    }

    private val treeSize: Int = leafBase * 2
    private val energy = LongArray(treeSize)
    private val env = LongArray(treeSize) { NO_ENV }

    private val leafOf = IntArray(n) { leafBase + it }

    /**
     * Sets each task's EST-sorted leaf position from the permutation [leafPos] and deactivates every task.
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

    /** Deactivates every task. */
    fun clear() {
        for (k in 0 until treeSize) {
            energy[k] = 0L
            env[k] = NO_ENV
        }
    }

    /** Activates or updates task [id] with [est] and non-negative [taskEnergy]. */
    fun activate(id: Int, est: Long, taskEnergy: Long) {
        require(id in 0 until n) { "task id $id out of 0..${n - 1}" }
        require(taskEnergy >= 0L) { "task energy must be non-negative, got $taskEnergy" }
        val k = leafOf[id]
        energy[k] = taskEnergy
        env[k] = capacityL * est + taskEnergy
        siftUp(k)
    }

    /** Removes task [id] from Θ. */
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

    /** `max(C · est(Ω) + e(Ω))` over non-empty Ω ⊆ Θ, or [NO_ENV] when Θ is empty. */
    fun envOfTheta(): Long = env[1]

    /** Summed energy of active tasks in Θ. */
    fun energyOfTheta(): Long = energy[1]

    /** Root envelope if [id] were activated with (`est`, [taskEnergy]), without mutating the tree. */
    fun envIfActivated(id: Int, est: Long, taskEnergy: Long): Long {
        require(id in 0 until n) { "task id $id out of 0..${n - 1}" }
        require(taskEnergy >= 0L) { "task energy must be non-negative, got $taskEnergy" }
        var k = leafOf[id]
        var e = taskEnergy
        var ev = capacityL * est + taskEnergy
        while (k > 1) {
            val parent = k ushr 1
            val left = parent shl 1
            val right = left or 1
            if (k == left) {
                e += energy[right]
                ev = maxOf(ev + energy[right], env[right])
            } else {
                e += energy[left]
                ev = maxOf(env[left] + e - energy[left], ev)
            }
            k = parent
        }
        return ev
    }

    private fun siftUp(start: Int) {
        var k = start ushr 1
        while (k >= 1) {
            val l = k shl 1
            val r = l or 1
            energy[k] = energy[l] + energy[r]
            val envL = env[l]
            val envR = env[r]
            // NO_ENV stays far below zero under any non-negative e(R), so empty children need no special case.
            env[k] = maxOf(envL + energy[r], envR)
            k = k ushr 1
        }
    }

    /** Shared constants. */
    companion object {
        /** Envelope of a subtree with no active task, low enough to stay below every real envelope. */
        const val NO_ENV: Long = Long.MIN_VALUE / 2
    }
}
