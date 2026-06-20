package com.eignex.klause.solver.factor.global.internals

import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.propagation.RevIntArray

/** Per-cover-index count under the current assignment (LS state). */
internal class GccState(val counts: IntArray)

/** Per-propagation-state scratch for GlobalCardinality. */
internal class GccPropCache(val cachedDoms: Array<IntDomain?>) {
    var conflictVars: IntArray? = null
    val flow = GccFlowBuilder()
    var flowAssign: RevIntArray? = null
}
