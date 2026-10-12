package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.portfolio.ArmFamily
import com.eignex.klause.portfolio.ArmFault
import com.eignex.klause.portfolio.AttributedImprovement
import com.eignex.klause.portfolio.Portfolio
import com.eignex.klause.portfolio.PortfolioEvidence
import com.eignex.klause.portfolio.PortfolioExecutor
import com.eignex.klause.portfolio.PortfolioIncumbents
import com.eignex.klause.portfolio.PortfolioWorker
import com.eignex.klause.solver.ProblemProfile
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.util.Cancellation

internal data class PortfolioInvocationControls(
    val parallelism: Int = 1,
    val seed: Long = 0,
    val workerSeed: Long = seed,
    val cancellation: Cancellation = Cancellation.Never,
    val nodeBudget: NodeBudget? = null,
    val baseSliceWork: Long = 5_000,
    val maxSliceWork: Long = 150_000,
    val sliceGrowth: Double = 1.5,
    val baseSliceMillis: Long = 2_000,
    val maxSliceMillis: Long = 60_000,
    val probeSliceMillis: Long = 1_000,
    val reseedStaleThreshold: Int = 3,
    val phaseRetention: Double = Portfolio.DEFAULT_PHASE_RETENTION,
)

internal class PortfolioInvocationPolicy(
    val profile: ProblemProfile? = null,
    shares: DoubleArray = doubleArrayOf(),
    val reserveBeforeIncumbentOnly: Boolean = false,
    probes: Set<Int> = emptySet(),
) {
    private val minimumShares = shares.copyOf()
    private val improvingProbes = probes.toSet()
    val shares: DoubleArray get() = minimumShares.copyOf()
    val probes: Set<Int> get() = improvingProbes.toSet()
}

@Suppress("TooGenericExceptionCaught") // construction failure closes every allocated worker and source service
internal class PortfolioInvocation(
    val preparedPlan: PreparedSourcePlan,
    factories: List<PortfolioArmFactory>,
    private val controls: PortfolioInvocationControls,
    evidence: PortfolioEvidence,
    objective: LinearObjective? = null,
    onFault: ((ArmFault) -> Unit)? = null,
    policy: (List<PortfolioWorker>) -> PortfolioInvocationPolicy = { PortfolioInvocationPolicy() },
) : PortfolioExecutor {
    init { require(factories.isNotEmpty()) { "portfolio invocation requires admitted arm factories" } }
    private val factories = factories.toList()
    val resources = PortfolioInvocationResources(
        maxOf(factories.size, controls.parallelism), controls.parallelism,
        controls.workerSeed, controls.cancellation, controls.nodeBudget,
        (0 until maxOf(factories.size, controls.parallelism)).filter {
            factories[it % factories.size].family == ArmFamily.Backtrack
        }.toSet(),
    )
    private val workers = ArrayList<PortfolioWorker>()
    private val portfolio: Portfolio = try {
        for (slot in resources.slots) {
            workers += this.factories[slot.index % this.factories.size].materialize(preparedPlan, slot)
        }
        val selected = policy(workers.toList())
        Portfolio.thompson(
            workers, lanes = resources.lanes, seed = controls.seed,
            baseSliceWork = controls.baseSliceWork, maxSliceWork = controls.maxSliceWork,
            sliceGrowth = controls.sliceGrowth,
            baseSliceMillis = controls.baseSliceMillis, maxSliceMillis = controls.maxSliceMillis,
            probeSliceMillis = controls.probeSliceMillis,
            reseedStaleThreshold = controls.reseedStaleThreshold, phaseRetention = controls.phaseRetention,
            profile = selected.profile, minShares = selected.shares, onFault = onFault,
        ).also {
            it.exactLinearObjective = objective
            it.evidenceVerification = evidence
            it.reserveBeforeIncumbentOnly = selected.reserveBeforeIncumbentOnly
            it.incumbentProbeArms = selected.probes
        }
    } catch (failure: Throwable) {
        for (worker in workers.asReversed()) {
            runCatching { worker.close() }.exceptionOrNull()?.let(failure::addSuppressed)
        }
        runCatching { resources.close() }.exceptionOrNull()?.let(failure::addSuppressed)
        throw failure
    }
    private var closed = false

    override fun solve(cancellation: Cancellation): SolveResult {
        check(!closed) { "portfolio invocation is closed" }
        return portfolio.solve(controls.cancellation or cancellation)
    }

    override fun minimize(
        cancellation: Cancellation,
        onImprovement: ((AttributedImprovement) -> Unit)?,
    ): MinimizeResult {
        check(!closed) { "portfolio invocation is closed" }
        return portfolio.minimize(controls.cancellation or cancellation, onImprovement)
    }

    fun <V> minimize(
        cancellation: Cancellation,
        onImprovement: ((AttributedImprovement) -> Unit)?,
        incumbents: PortfolioIncumbents<V>,
    ): MinimizeResult {
        check(!closed) { "portfolio invocation is closed" }
        return portfolio.minimize(controls.cancellation or cancellation, onImprovement, incumbents)
    }

    override fun close() {
        if (closed) return
        closed = true
        portfolio.close()
        resources.close()
    }
}
