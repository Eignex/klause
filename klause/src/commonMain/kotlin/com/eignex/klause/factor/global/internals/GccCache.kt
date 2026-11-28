package com.eignex.klause.factor.global.internals

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.RevInt
import com.eignex.klause.propagation.RevIntArray
import com.eignex.klause.propagation.RevRef

// Per-cover-index counts under the local-search assignment.
internal class GccState(val counts: IntArray)

// Per-cover definite (variables pinned to the value) and possible (domains still holding it) counts on the
// undo trail, so a plain (no-presence) fire patches only variables whose domain changed instead of an O(n·m)
// recount. Both are forward-monotone, so the trail restores them exactly. The presence path keeps the full
// recount.
internal class GccIncrementalState(state: PropagationState, val xs: IntArray, val m: Int) {
    val n = xs.size

    // Drops to 0 after a backtrack above the seeding level, forcing a rebuild.
    val valid = RevInt(state, 0)

    val definite = RevIntArray(state, m, 0)
    val possible = RevIntArray(state, m, 0)

    // Counts a newly pinned variable into definite exactly once: -1 not yet pinned, -2 pinned outside the
    // cover, k ≥ 0 pinned to cover(k).
    val pinnedCover = RevIntArray(state, n, -1)

    // Each variable's domain at its last fire, the delta base; current ⊆ domRef holds forward.
    val domRef = Array(n) { RevRef<IntDomain?>(state, null) }
}

internal class GccPropCache(val cachedDoms: Array<IntDomain?>) {
    val flow = GccFlowBuilder()
    var flowAssign: RevIntArray? = null

    var inc: GccIncrementalState? = null

    // Persistent flow-network structure for the fixed-bound, no-other, plain path, built over the first-fire
    // root domains. Later domains are subsets, so no edge is ever missing; a fire blocks edges whose value
    // left. Spanning the root domains, it needs no reversibility; the flow itself lives on the trail.
    var structBuilt = false

    // The structure may only be frozen on the first build, which sees the root domains: a later build runs
    // on narrowed domains and freezing then would miss edges a backtrack restores.
    var builtOnce = false
    var sN = 0
    var sM = 0
    var sBaseNodes = 0
    var sSuperSource = 0
    var sSuperSink = 0
    var sRequiredSSFlow = 0
    var pXToCov = IntArray(0) // persistent i*m + k → edge id (-1 = no edge), spans root domains

    // Per-fire scratch for the rebuild path, grown on demand; no entry is read before it is written.
    private var excessBuf = IntArray(0)
    private var xToCovBuf = IntArray(0)
    private var xToOtherBuf = IntArray(0)
    private var sccIdBuf = IntArray(0)

    // Written in full by computeSccResidual.
    fun sccId(nodes: Int): IntArray {
        if (sccIdBuf.size < nodes) sccIdBuf = IntArray(nodes)
        return sccIdBuf
    }

    fun excess(nodes: Int): IntArray {
        if (excessBuf.size < nodes) excessBuf = IntArray(nodes)
        for (i in 0 until nodes) excessBuf[i] = 0
        return excessBuf
    }

    // Edge index at i·m + k, -1 for none.
    fun xToCov(n: Int, m: Int): IntArray {
        val need = n * m
        if (xToCovBuf.size < need) xToCovBuf = IntArray(need)
        for (i in 0 until need) xToCovBuf[i] = -1
        return xToCovBuf
    }

    fun xToOther(n: Int): IntArray {
        if (xToOtherBuf.size < n) xToOtherBuf = IntArray(n)
        for (i in 0 until n) xToOtherBuf[i] = -1
        return xToOtherBuf
    }
}
