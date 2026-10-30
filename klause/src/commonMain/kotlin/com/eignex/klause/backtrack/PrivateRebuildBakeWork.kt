package com.eignex.klause.backtrack

import com.eignex.klause.propagation.RootWorkMeter
import com.eignex.klause.util.Cancellation

internal class PrivateRebuildBakeWork(private val outer: Cancellation) : Cancellation {
    var constructionEntries = 0L
        private set
    var propagationWork = 0L
        private set

    private val meter = object : RootWorkMeter {
        override fun charge(units: Long) = outer.charge(units)

        override fun construction(entries: Long) {
            constructionEntries = saturatedWork(constructionEntries, entries)
            val root = outer.workMeter() as? RootWorkMeter
            if (root == null) outer.charge(entries) else root.construction(entries)
        }

        override fun propagation(units: Long) {
            propagationWork = saturatedWork(propagationWork, units)
            val root = outer.workMeter() as? RootWorkMeter
            if (root == null) outer.charge(units) else root.propagation(units)
        }
    }

    override fun isCancelled(): Boolean = outer()
    override fun deadline() = outer.deadline()
    override fun workMeter(): RootWorkMeter = meter
}
