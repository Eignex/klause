package com.eignex.klause.factor.scheduling.internals

import com.eignex.klause.util.argsortBy

/**
 * The subsets Ω of the edge-finding set Θ that bound a detected task's earliest start: every est-suffix
 * of Θ, with its summed energy and its own latest completion.
 *
 * Once `Θ ≺ i` is detected, every Ω ⊆ Θ must end before `i` does, and Ω's energy beyond what it can
 * place beside `i` over its own window, `rest(Ω, c_i) = e_Ω − (C − c_i)(lct_Ω − est_Ω)`, must run before
 * `i` starts. When that rest is positive, `est_i ≥ est_Ω + ⌈rest / c_i⌉`. Only a positive rest licenses
 * the bound, which is why it is taken per Ω here rather than read off Θ's energy envelope.
 */
internal class EdgeFindingOmegas(capacity: Int) {
    private val est = LongArray(capacity)
    private val energy = LongArray(capacity)
    private val lct = LongArray(capacity)
    private var count = 0

    /** Size of Θ the candidates were built for; `-1` before the first [build]. */
    var thetaSize = -1
        private set

    /** Build the candidates for Θ = the first [k] tasks of [lctOrder]. */
    fun build(k: Int, lctOrder: IntArray, ests: LongArray, lcts: LongArray, energies: LongArray) {
        val byEstDesc = argsortBy(k) { a, b -> ests[lctOrder[b]].compareTo(ests[lctOrder[a]]) }
        var e = 0L
        var l = Long.MIN_VALUE
        for (p in 0 until k) {
            val t = lctOrder[byEstDesc[p]]
            e += energies[t]
            if (lcts[t] > l) l = lcts[t]
            est[p] = ests[t]
            energy[p] = e
            lct[p] = l
        }
        count = k
        thetaSize = k
    }

    /** The strongest earliest start the candidates force on a task of height [c] once `Θ ≺` it, or null
     *  when no Ω has positive rest. */
    fun earliestStartAfter(c: Long, cap: Long): Long? {
        var best = Long.MIN_VALUE
        for (p in 0 until count) {
            val rest = energy[p] - (cap - c) * (lct[p] - est[p])
            if (rest <= 0L) continue
            val bound = est[p] + (rest + c - 1L) / c
            if (bound > best) best = bound
        }
        return if (best == Long.MIN_VALUE) null else best
    }
}
