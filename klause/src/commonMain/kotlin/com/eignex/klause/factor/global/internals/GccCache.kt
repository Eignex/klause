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
}
