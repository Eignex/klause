package com.eignex.klause.solver.pipeline

import com.eignex.klause.portfolio.ArmFamily
import com.eignex.klause.portfolio.PortfolioWorker

internal class PortfolioArmFactory(
    private val preparedPlan: PreparedSourcePlan,
    val armId: Int,
    val family: ArmFamily,
    private val create: (PortfolioWorkerSlot) -> PortfolioWorker,
) {
    init { require(armId >= 0) }

    fun materialize(plan: PreparedSourcePlan, slot: PortfolioWorkerSlot): PortfolioWorker {
        require(plan === preparedPlan) { "portfolio arm belongs to a different prepared invocation plan" }
        val worker = create(slot)
        if (worker.armId != armId) {
            val failure = IllegalArgumentException("portfolio factory changed its composed arm identity")
            runCatching { worker.close() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
        worker.family = family
        return worker
    }
}
