package com.eignex.klause.factor.global.internals

internal class InverseCache(val fRegin: ReginCache = ReginCache(), val gRegin: ReginCache = ReginCache()) {
    var initialized: Boolean = false
    var conflictVars: IntArray? = null
}
