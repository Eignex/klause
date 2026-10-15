package com.eignex.klause.solver.pipeline

import com.eignex.klause.portfolio.ArmFamily
import com.eignex.klause.portfolio.PortfolioWorker
import com.eignex.klause.portfolio.SourceWorkerBinding

internal class PortfolioArmFactory(
    private val preparedPlan: PreparedSourcePlan,
    val armId: Int,
    val family: ArmFamily,
    private val binding: ((PortfolioWorker) -> SourceWorkerBinding)? = null,
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
            binding?.invoke(worker)?.let { source ->
                require(source.preparedPlan === plan) { "worker binding belongs to another source plan" }
                worker.bindSource(source)
            }
            require(worker.sourceBinding?.preparedPlan?.let { it === plan } != false) {
                "worker binding belongs to another source plan"
            }
            return worker
        } catch (failure: Throwable) {
            runCatching { worker.close() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }
}
