package com.eignex.klause.factor.scheduling.internals

import com.eignex.util.arrays.argsortBy

// The est-suffixes Ω of the edge-finding set Θ. Once `Θ ≺ i`, Ω's energy beyond what fits beside `i` over
// its window, `rest(Ω, c_i) = e_Ω − (C − c_i)(lct_Ω − est_Ω)`, must run before `i` starts, so a positive
// rest gives `est_i ≥ est_Ω + ⌈rest / c_i⌉`. Only a positive rest licenses the bound, so it is taken per Ω
// rather than read off Θ's energy envelope.
internal class EdgeFindingOmegas(capacity: Int) {
    private val est = LongArray(capacity)
    private val energy = LongArray(capacity)
    private val lct = LongArray(capacity)
    private var count = 0

    // -1 before the first build.
    var thetaSize = -1
        private set

    // Θ is the first k tasks of lctOrder.
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

    // Null when no Ω has positive rest.
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
