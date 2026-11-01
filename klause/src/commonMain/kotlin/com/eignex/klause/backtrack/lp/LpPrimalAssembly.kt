package com.eignex.klause.backtrack.lp

import com.eignex.klause.lp.bounding.LpEngine
import com.eignex.klause.lp.bounding.LpNodeOverhead
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter

internal class LpPrimalAssembly(private val engine: LpEngine, cancellation: Cancellation) {
    private val allowance = engine.rootPrimalWorkBudget()
    var work = 0L
        private set

    private val meter = WorkMeter {
        cancellation.charge(it)
        debit(it)
    }

    val token: Cancellation = object : Cancellation {
        override fun isCancelled(): Boolean = cancellation() || (allowance > 0L && work >= allowance)
        override fun deadline() = cancellation.deadline()
        override fun workMeter(): WorkMeter = meter
    }

    fun chargeConstruction(units: Long) = debit(units * LpNodeOverhead.BUILD)

    private fun debit(units: Long) {
        work += units
        engine.noteNodeOverhead(units)
    }
}
