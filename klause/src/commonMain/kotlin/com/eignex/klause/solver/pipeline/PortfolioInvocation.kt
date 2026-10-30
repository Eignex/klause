package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.portfolio.AcceptedPortfolioIncumbent
import com.eignex.klause.portfolio.ArmFamily
import com.eignex.klause.portfolio.ArmFault
import com.eignex.klause.portfolio.AttributedImprovement
import com.eignex.klause.portfolio.Portfolio
import com.eignex.klause.portfolio.PortfolioDiagnostics
import com.eignex.klause.portfolio.PortfolioEvent
import com.eignex.klause.portfolio.PortfolioEvidence
import com.eignex.klause.portfolio.PortfolioExecutor
import com.eignex.klause.portfolio.PortfolioIncumbents
import com.eignex.klause.portfolio.PortfolioRewardPolicy
import com.eignex.klause.portfolio.PortfolioTerminalCheck
import com.eignex.klause.portfolio.PortfolioWorker
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.ProblemProfile
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.util.Cancellation
import kotlin.time.TimeSource

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
    val diagnostics: PortfolioDiagnostics? = null,
    val rewardPolicy: PortfolioRewardPolicy? = null,
    val onProgress: ((PortfolioEvent) -> Unit)? = null,
    val improvementProofShare: Double = 0.5,
    val includeRestartingProof: Boolean = false,
    val booleanProofTasks: Boolean = false,
)

internal class PortfolioInvocationPolicy(
    val profile: ProblemProfile? = null,
    shares: DoubleArray = doubleArrayOf(),
    val reserveBeforeIncumbentOnly: Boolean = false,
    probes: Set<Int> = emptySet(),
    probeWeights: IntArray = intArrayOf(),
    val onProgress: ((PortfolioEvent) -> Unit)? = null,
) {
    private val minimumShares = shares.copyOf()
    private val improvingProbes = probes.toSet()
    private val initialWeights = probeWeights.copyOf()
    val shares: DoubleArray get() = minimumShares.copyOf()
    val probes: Set<Int> get() = improvingProbes.toSet()
    val probeWeights: IntArray get() = initialWeights.copyOf()
}

@Suppress("TooGenericExceptionCaught") // construction failure closes every allocated worker and source service
internal class PortfolioInvocation(
    val preparedPlan: PreparedSourcePlan,
    factories: List<PortfolioArmFactory>,
    private val controls: PortfolioInvocationControls,
    evidence: PortfolioEvidence,
    objective: LinearObjective? = null,
    onFault: ((ArmFault) -> Unit)? = null,
    private val optimizationIncumbents: PortfolioIncumbents<BigFraction>? = null,
    preparationStages: Map<String, Long> = emptyMap(),
    policy: (List<PortfolioWorker>) -> PortfolioInvocationPolicy = { PortfolioInvocationPolicy() },
) : PortfolioExecutor {
    init { require(factories.isNotEmpty()) { "portfolio invocation requires admitted arm factories" } }
    private val factories = factories.toList()
    private val preparationStages = preparationStages.toMap()
    private val started = TimeSource.Monotonic.markNow()
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
            val construction = TimeSource.Monotonic.markNow()
            val worker = this.factories[slot.index % this.factories.size].materialize(preparedPlan, slot)
            worker.constructionNanos = construction.elapsedNow().inWholeNanoseconds
            worker.createdNanos = started.elapsedNow().inWholeNanoseconds
            workers += worker
            require(worker.sourceBinding?.preparedPlan === preparedPlan) {
                "source portfolio requires a complete worker binding"
            }
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
            diagnostics = controls.diagnostics,
            rewardPolicy = controls.rewardPolicy,
            onProgress = selected.onProgress?.let { observer ->
                { event: PortfolioEvent ->
                    observer(event)
                    controls.onProgress?.invoke(event)
                }
            } ?: controls.onProgress,
        ).also {
            it.invocationStarted = started
            it.exactLinearObjective = objective
            it.evidenceVerification = evidence
            it.reserveBeforeIncumbentOnly = selected.reserveBeforeIncumbentOnly
            it.incumbentProbeArms = selected.probes
            it.initialProbeWeights = selected.probeWeights
            it.improvementProofShare = controls.improvementProofShare
            it.includeRestartingProof = controls.includeRestartingProof
            it.disjointBooleanProof = controls.booleanProofTasks
            it.disjointBooleanProbe = controls.booleanProofTasks
        }
    } catch (failure: Throwable) {
        for (worker in workers.asReversed()) {
            runCatching { worker.close() }.exceptionOrNull()
                    ?.takeUnless { it === failure }?.let(failure::addSuppressed)
        }
        runCatching { resources.close() }.exceptionOrNull()
                    ?.takeUnless { it === failure }?.let(failure::addSuppressed)
        throw failure
    }
    private var closed = false

    val completeSearchAdmitted: Boolean
        get() = workers.any {
            it.family == ArmFamily.Backtrack && !it.improvementOnly && !it.auxiliaryProof && it.permitsSourceProofs
        }

    private fun withPreparationStats(stats: SolveStats): SolveStats {
        if (preparationStages.isEmpty()) return stats
        val stages = stats.smt.completeSearchStages.orEmpty().toMutableMap()
        for ((key, value) in preparationStages) stages[key] = (stages[key] ?: 0L) + value
        return stats.copy(smt = stats.smt.copy(completeSearchStages = stages))
    }

    override fun solve(cancellation: Cancellation): SolveResult {
        check(!closed) { "portfolio invocation is closed" }
        return withPreparationStats(portfolio.solve(controls.cancellation or cancellation))
    }

    override fun minimize(
        cancellation: Cancellation,
        onImprovement: ((AttributedImprovement) -> Unit)?,
    ): MinimizeResult {
        check(!closed) { "portfolio invocation is closed" }
        val incumbents = optimizationIncumbents
        val result = if (incumbents == null) portfolio.minimize(controls.cancellation or cancellation, onImprovement)
        else portfolio.minimize(controls.cancellation or cancellation, onImprovement, incumbents)
        return withPreparationStats(result)
    }

    fun <V> minimize(
        cancellation: Cancellation,
        onImprovement: ((AttributedImprovement) -> Unit)?,
        incumbents: PortfolioIncumbents<V>,
        terminalCheck: PortfolioTerminalCheck? = null,
        onAccepted: ((AcceptedPortfolioIncumbent<V>) -> Unit)? = null,
    ): MinimizeResult {
        check(!closed) { "portfolio invocation is closed" }
        val result = portfolio.minimize(controls.cancellation or cancellation, onImprovement, incumbents, terminalCheck,
            onAccepted)
        return withPreparationStats(result)
    }

    private fun withPreparationStats(result: SolveResult): SolveResult {
        if (preparationStages.isEmpty()) return result
        val stats = withPreparationStats(result.stats)
        return when (result) {
            is SolveResult.Sat -> result.copy(stats = stats)
            is SolveResult.Unsat -> result.copy(stats = stats)
            is SolveResult.Unknown -> result.copy(stats = stats)
        }
    }

    private fun withPreparationStats(result: MinimizeResult): MinimizeResult {
        if (preparationStages.isEmpty()) return result
        val stats = withPreparationStats(result.stats)
        return when (result) {
            is MinimizeResult.Optimal -> result.copy(stats = stats)
            is MinimizeResult.BestFound -> result.copy(stats = stats)
            is MinimizeResult.Unbounded -> result.copy(stats = stats)
            is MinimizeResult.Infeasible -> result.copy(stats = stats)
            is MinimizeResult.Unknown -> result.copy(stats = stats)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            portfolio.close()
        } catch (failure: Throwable) {
            runCatching { resources.close() }.exceptionOrNull()
                    ?.takeUnless { it === failure }?.let(failure::addSuppressed)
            throw failure
        }
        resources.close()
    }
}
