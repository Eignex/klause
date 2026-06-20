package com.eignex.klause.solver.factor.global.internals

/** Per-session warm-start / scoping cache for `Inverse`. */
internal class InverseCache(val fRegin: ReginCache = ReginCache(), val gRegin: ReginCache = ReginCache()) {
    var initialized: Boolean = false
    var conflictVars: IntArray? = null
}
