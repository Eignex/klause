package com.eignex.klause.backtrack

import com.eignex.klause.propagation.RootWorkMeter
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.saturatedAdd

internal class PrivateRebuildBakeWork(private val outer: ExecutionControl) : ExecutionControl() {
    var constructionEntries = 0L
        private set
    var propagationWork = 0L
        private set

    private val meter = object : RootWorkMeter {
        override fun charge(units: Long) = outer.charge(units)

        override fun construction(entries: Long) {
            constructionEntries = saturatedAdd(constructionEntries, entries)
            val root = outer.accounting as? RootWorkMeter
            if (root == null) outer.charge(entries) else root.construction(entries)
        }

        override fun propagation(units: Long) {
            propagationWork = saturatedAdd(propagationWork, units)
            val root = outer.accounting as? RootWorkMeter
            if (root == null) outer.charge(units) else root.propagation(units)
        }
    }

    override fun isCancelled(): Boolean = outer()
    override fun deadline() = outer.deadline()
    override val accounting: RootWorkMeter get() = meter
}
