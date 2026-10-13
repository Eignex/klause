package com.eignex.klause.solver.pipeline

import com.eignex.klause.portfolio.ArmFamily
import com.eignex.klause.portfolio.PortfolioWorker

internal class PortfolioArmFactory(
    private val preparedPlan: PreparedSourcePlan,
    val armId: Int,
    val family: ArmFamily,
    private val bind: ((PortfolioWorker) -> Unit)? = null,
    private val create: (PortfolioWorkerSlot) -> PortfolioWorker,
) {
    init { require(armId >= 0) }

    @Suppress("TooGenericExceptionCaught")
    fun materialize(plan: PreparedSourcePlan, slot: PortfolioWorkerSlot): PortfolioWorker {
        require(plan === preparedPlan) { "portfolio arm belongs to a different prepared invocation plan" }
        val worker = create(slot)
        try {
            require(worker.armId == armId) { "portfolio factory changed its composed arm identity" }
            worker.family = family
            bind?.invoke(worker)
            return worker
        } catch (failure: Throwable) {
            runCatching { worker.close() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }
}
