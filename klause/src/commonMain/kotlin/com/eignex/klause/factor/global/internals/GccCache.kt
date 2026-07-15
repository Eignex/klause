package com.eignex.klause.factor.global.internals

import com.eignex.klause.propagation.RevIntArray
import com.eignex.klause.solver.IntDomain

/** Per-cover-index count under the current assignment (LS state). */
internal class GccState(val counts: IntArray)

/** Per-propagation-state scratch for GlobalCardinality. */
internal class GccPropCache(val cachedDoms: Array<IntDomain?>) {
    var conflictVars: IntArray? = null
    val flow = GccFlowBuilder()
    var flowAssign: RevIntArray? = null

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
