package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.basis.BasisSolveQuality
import com.eignex.util.numbers.saturatedAdd

internal enum class EngineRefactorTrigger {
    BACKEND_SINGULAR,
    BACKEND_REQUESTED,
    HARD_UPDATE_CAP,
    RESIDUAL,
    FILL_GROWTH,
    SOLVE_WORK_GROWTH,
    SYNTHETIC_WORK,
}

internal data class RefactorPolicyConfig(
    val hardUpdateCap: Int = 64,
    val residualSampleInterval: Int = 8,
    val relativeResidualTolerance: Double = 1e-8,
    val fillGrowthFactor: Double = 5.0,
    val solveWorkGrowthFactor: Double = 2.0,
    val minimumUpdatesForAdaptiveTrigger: Int = 8,
    val syntheticWorkEnabled: Boolean = false,
) {
    init {
        require(hardUpdateCap > 0 && residualSampleInterval > 0 && minimumUpdatesForAdaptiveTrigger >= 0)
        require(relativeResidualTolerance.isFinite() && relativeResidualTolerance >= 0.0)
        require(fillGrowthFactor.isFinite() && fillGrowthFactor >= 1.0)
        require(solveWorkGrowthFactor.isFinite() && solveWorkGrowthFactor >= 1.0)
    }
}

internal data class RefactorPolicyMetrics(
    val successfulBasisSolves: Long = 0,
    val acceptedUpdates: Long = 0,
    val qualitySamples: Long = 0,
    val residualTriggers: Long = 0,
    val boundUpdateFtranWork: Long = 0,
    val knownWork: Long = 0,
    val unknownWorkEvents: Long = 0,
    val saturatedWorkEvents: Long = 0,
    val cooldownDeclines: Long = 0,
    val accumulatedSolveWork: Long = 0,
    val freshBuildWork: Long? = null,
    val freshFactorNnz: Int? = null,
    val pivotSpread: Double? = null,
    val triggers: Map<EngineRefactorTrigger, Long> = emptyMap(),
)

internal class RefactorPolicy(private val config: RefactorPolicyConfig = RefactorPolicyConfig()) {
    private var successfulBasisSolves = 0L
    private var basisSolvesSinceFactorization = 0L
    private var acceptedUpdates = 0L
    private var updatesSinceFactorization = 0
    private var qualitySamples = 0L
    private var residualTriggers = 0L
    private var boundUpdateFtranWork = 0L
    private var knownWork = 0L
    private var unknownWorkEvents = 0L
    private var saturatedWorkEvents = 0L
    private var cooldownDeclines = 0L
    private var accumulatedSolveWork = 0L
    private var latestSolveWork = 0L
    private var freshBuildWork: Long? = null
    private var freshFactorNnz: Int? = null
    private var pivotSpread: Double? = null
    private var adaptiveWorkUsable = true
    private var generation = 0L
    private var consumedGeneration = -1L
    private val triggers = mutableMapOf<EngineRefactorTrigger, Long>()

    val metrics: RefactorPolicyMetrics
        get() = RefactorPolicyMetrics(
            successfulBasisSolves,
            acceptedUpdates,
            qualitySamples,
            residualTriggers,
            boundUpdateFtranWork,
            knownWork,
            unknownWorkEvents,
            saturatedWorkEvents,
            cooldownDeclines,
            accumulatedSolveWork,
            freshBuildWork,
            freshFactorNnz,
            pivotSpread,
            triggers.toMap(),
        )

    fun recordFactorization(factorNnz: Int, buildWork: Long?, pivotSpread: Double, basisChanged: Boolean = false) {
        require(factorNnz >= 0 && (buildWork == null || buildWork >= 0L))
        freshFactorNnz = factorNnz
        freshBuildWork = buildWork?.takeUnless { it == Long.MAX_VALUE }
        adaptiveWorkUsable = buildWork != Long.MAX_VALUE
        this.pivotSpread = pivotSpread.takeIf { it.isFinite() && it >= 0.0 }
        updatesSinceFactorization = 0
        basisSolvesSinceFactorization = 0L
        accumulatedSolveWork = 0L
        latestSolveWork = 0L
        if (basisChanged) {
            generation = saturatedAdd(generation, 1L)
            consumedGeneration = -1L
        }
    }

    fun recordBasisSolve(work: Long?, boundUpdateFtran: Boolean = false) {
        successfulBasisSolves = saturatedAdd(successfulBasisSolves, 1L)
        basisSolvesSinceFactorization = saturatedAdd(basisSolvesSinceFactorization, 1L)
        latestSolveWork = recordWork(work)
        accumulatedSolveWork = saturatedAdd(accumulatedSolveWork, latestSolveWork)
        if (boundUpdateFtran) boundUpdateFtranWork = saturatedAdd(boundUpdateFtranWork, latestSolveWork)
    }

    fun recordAcceptedUpdate(work: Long?) {
        acceptedUpdates = saturatedAdd(acceptedUpdates, 1L)
        if (updatesSinceFactorization < Int.MAX_VALUE) updatesSinceFactorization++
        accumulatedSolveWork = saturatedAdd(accumulatedSolveWork, recordWork(work))
        generation = saturatedAdd(generation, 1L)
        consumedGeneration = -1L
    }

    fun shouldSample(cancelled: Boolean): Boolean = !cancelled && basisSolvesSinceFactorization > 0L &&
        basisSolvesSinceFactorization % config.residualSampleInterval == 0L

    fun chooseAtSafePoint(
        updateCount: Int,
        factorNnz: Int,
        backendRequested: Boolean = false,
        backendSingular: Boolean = false,
        quality: BasisSolveQuality? = null,
    ): EngineRefactorTrigger? {
        require(updateCount >= 0 && factorNnz >= 0)
        if (quality != null) qualitySamples = saturatedAdd(qualitySamples, 1L)
        val residualBad = quality != null &&
            (!quality.relativeResidual.isFinite() || quality.relativeResidual > config.relativeResidualTolerance)
        val trigger = when {
            backendSingular -> EngineRefactorTrigger.BACKEND_SINGULAR

            backendRequested -> EngineRefactorTrigger.BACKEND_REQUESTED

            updateCount >= config.hardUpdateCap -> EngineRefactorTrigger.HARD_UPDATE_CAP

            residualBad -> EngineRefactorTrigger.RESIDUAL

            updatesSinceFactorization >= config.minimumUpdatesForAdaptiveTrigger && fillExceeded(factorNnz) ->
                EngineRefactorTrigger.FILL_GROWTH

            updatesSinceFactorization >= config.minimumUpdatesForAdaptiveTrigger && solveWorkExceeded() ->
                EngineRefactorTrigger.SOLVE_WORK_GROWTH

            updatesSinceFactorization >= config.minimumUpdatesForAdaptiveTrigger && syntheticWorkExceeded() ->
                EngineRefactorTrigger.SYNTHETIC_WORK

            else -> return null
        }
        val cooldownApplies = trigger !in setOf(
            EngineRefactorTrigger.BACKEND_SINGULAR,
            EngineRefactorTrigger.BACKEND_REQUESTED,
            EngineRefactorTrigger.HARD_UPDATE_CAP,
        )
        if (cooldownApplies && consumedGeneration == generation) {
            cooldownDeclines = saturatedAdd(cooldownDeclines, 1L)
            return null
        }
        if (cooldownApplies) consumedGeneration = generation
        triggers[trigger] = saturatedAdd(triggers[trigger] ?: 0L, 1L)
        if (trigger == EngineRefactorTrigger.RESIDUAL) residualTriggers = saturatedAdd(residualTriggers, 1L)
        return trigger
    }

    private fun fillExceeded(factorNnz: Int): Boolean {
        val fresh = freshFactorNnz ?: return false
        if (fresh == 0) return factorNnz > 0
        return factorNnz.toDouble() > fresh.toDouble() * config.fillGrowthFactor
    }

    private fun solveWorkExceeded(): Boolean {
        if (!adaptiveWorkUsable) return false
        val fresh = freshBuildWork ?: return false
        return latestSolveWork.toDouble() > fresh.toDouble() * config.solveWorkGrowthFactor
    }

    private fun syntheticWorkExceeded(): Boolean {
        if (!config.syntheticWorkEnabled || !adaptiveWorkUsable) return false
        val fresh = freshBuildWork ?: return false
        return accumulatedSolveWork >= fresh
    }

    private fun recordWork(work: Long?): Long {
        if (work == null) {
            unknownWorkEvents = saturatedAdd(unknownWorkEvents, 1L)
            adaptiveWorkUsable = false
            return 0L
        }
        if (work == Long.MAX_VALUE) {
            saturatedWorkEvents = saturatedAdd(saturatedWorkEvents, 1L)
            adaptiveWorkUsable = false
            knownWork = Long.MAX_VALUE
            return 0L
        }
        if (knownWork > Long.MAX_VALUE - work) {
            saturatedWorkEvents = saturatedAdd(saturatedWorkEvents, 1L)
            adaptiveWorkUsable = false
            knownWork = Long.MAX_VALUE
            return 0L
        }
        knownWork += work
        return work
    }
}
