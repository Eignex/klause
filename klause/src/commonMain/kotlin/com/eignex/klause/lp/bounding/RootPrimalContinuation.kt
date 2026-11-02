package com.eignex.klause.lp.bounding

import com.eignex.klause.lp.relaxation.LpRelaxation
import com.eignex.klause.lp.relaxation.RelaxationDomains
import com.eignex.klause.util.Cancellation

internal interface RootPrimalContinuation : AutoCloseable {
    fun paidWorkFor(engine: LpEngine): Long

    fun includeSetupWork(engine: LpEngine, work: Long)

    fun matches(
        engine: LpEngine,
        root: LpRelaxation,
        domains: RelaxationDomains,
        cancellation: Cancellation,
        onWork: (Long) -> Unit,
    ): Boolean
}
