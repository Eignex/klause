package com.eignex.klause.portfolio

import com.eignex.klause.solver.ResumableSearch
import com.eignex.klause.solver.incumbent.Verification

internal fun interface PortfolioTerminalCheck {
    fun check(worker: PortfolioWorker, search: ResumableSearch?): PortfolioTerminalPublication?
}

internal class PortfolioTerminalPublication(
    val verification: Verification<Unit, Unit>,
    val accepted: () -> Unit,
)
