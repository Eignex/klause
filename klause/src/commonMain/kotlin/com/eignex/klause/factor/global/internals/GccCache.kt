package com.eignex.klause.factor.global.internals

import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.RevInt
import com.eignex.klause.propagation.RevIntArray
import com.eignex.klause.propagation.RevRef
import com.eignex.klause.solver.IntDomain

/** Per-cover-index count under the current assignment (LS state). */
internal class GccState(val counts: IntArray)

/**
 * Reversible delta state for the plain (no-presence) `global_cardinality` fire. Holds the per-cover
 * `definite` (variables pinned to that cover value) and `possible` (variables whose domain still holds
 * it) counts on the engine undo trail, so a fire patches only the variables whose domain changed since
 * the last fire instead of the O(n·m) full recount. Forward the counts are monotone (`possible` only
 * falls as values leave, `definite` only rises as variables pin), so [RevIntArray] restores them exactly
 * on backtrack. [valid] drops to 0 when a backtrack lands above the seeding level, forcing a rebuild.
 *
 * Only used when the constraint has no presence literals (the common case); the optional-variable path
 * keeps the full per-fire recount.
 */
internal class GccIncrementalState(state: PropagationState, val xs: IntArray, val m: Int) {
    val n = xs.size

    /** 1 once seeded at the current (or an ancestor) level; reversible → 0 after a backtrack above it. */
    val valid = RevInt(state, 0)

    /** Per-cover counts, reversible. `definite[k]` = #vars pinned to `cover[k]`; `possible[k]` = #vars
     *  whose domain still contains `cover[k]`. */
    val definite = RevIntArray(state, m, 0)
    val possible = RevIntArray(state, m, 0)

    /** Per-var pin bookkeeping so a newly-pinned variable is counted into `definite` exactly once:
     *  `-1` = not yet pinned, `-2` = pinned to a non-cover value (never counted), `k >= 0` = pinned to
     *  `cover[k]` (counted). Reversible; forward-monotone (a variable only ever pins once). */
    val pinnedCover = RevIntArray(state, n, -1)

    /** Each var's [IntDomain] at its last fire — the delta base. Reversible, rolls back with the domains
     *  so `current ⊆ domRef` (deletions-only) always holds forward. */
    val domRef = Array(n) { RevRef<IntDomain?>(state, null) }
}

/** Per-propagation-state scratch for GlobalCardinality. */
internal class GccPropCache(val cachedDoms: Array<IntDomain?>) {
    var conflictVars: IntArray? = null
    val flow = GccFlowBuilder()
    var flowAssign: RevIntArray? = null

    /** Reversible delta state for the incremental `definite`/`possible` counts (plain, no-presence
     *  path). Created on the first plain fire; a var-set change would recreate it (the var set is
     *  stable for a given factor, so in practice it is built once). */
    var inc: GccIncrementalState? = null

    // Persistent flow-network structure for the fixed-bound, no-other, plain path. The edge set is
    // built once for the first-fire (root, widest) domains and reused across every later fire — later
    // domains are subsets, so no edge is ever missing; a fire just zeroes the capacity of var→cover
    // edges whose value has left, restores the rest, and recomputes the flow. Backtrack-invariant (the
    // structure spans the root domains), so it needs no reversibility; only the flow (recomputed each
    // fire from the reversible `flowAssign` warm start) and the domains vary.
    var structBuilt = false
    var sN = 0
    var sM = 0
    var sBaseNodes = 0
    var sSuperSource = 0
    var sSuperSink = 0
    var sRequiredSSFlow = 0
    var pXToCov = IntArray(0) // persistent i*m + k → edge id (-1 = no edge), spans root domains

    // Reused per-fire flow-network scratch, grown on demand and refilled each fire — the network is
    // rebuilt every fire but reusing the backing arrays avoids re-allocating them (`excess` per node,
    // the flattened `x→cover` edge-index map per var·cover, the per-var `x→other` edge index). Holds
    // no cross-fire state; every entry is written before it is read, so reuse is behaviour-identical.
    private var excessBuf = IntArray(0)
    private var xToCovBuf = IntArray(0)
    private var xToOtherBuf = IntArray(0)

    /** `excess` scratch sized `>= nodes`, zeroed over `[0, nodes)`. */
    fun excess(nodes: Int): IntArray {
        if (excessBuf.size < nodes) excessBuf = IntArray(nodes)
        for (i in 0 until nodes) excessBuf[i] = 0
        return excessBuf
    }

    /** Flattened `x·cover → edge-index` map (`i*m + k`), sized `>= n*m`, filled with `-1`. */
    fun xToCov(n: Int, m: Int): IntArray {
        val need = n * m
        if (xToCovBuf.size < need) xToCovBuf = IntArray(need)
        for (i in 0 until need) xToCovBuf[i] = -1
        return xToCovBuf
    }

    /** Per-var `x → other` edge index, sized `>= n`, filled with `-1`. */
    fun xToOther(n: Int): IntArray {
        if (xToOtherBuf.size < n) xToOtherBuf = IntArray(n)
        for (i in 0 until n) xToOtherBuf[i] = -1
        return xToOtherBuf
    }
}
