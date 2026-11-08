package com.eignex.klause.lp.engine

import com.eignex.klause.util.Cancellation

internal enum class LpQueryRoute { SESSION, SCOPED_FLOAT, SCOPED_CONTINUATION }

// The model belongs to the synchronous callback's query. Retained diagnostics must encode an owned
// capture before returning; keeping the borrowed working view can observe a later bound adoption.
internal class LpQueryObservation(
    val owner: Any,
    val sequence: Long,
    model: LpModel,
    val retained: Boolean,
    val workLimit: Long,
    val claim: LpClaim?,
    val floatOffset: Double,
    val hasFloatAcceptance: Boolean,
    val refinementLimits: LpRefinementLimits?,
    val pricing: LpPricingOptions,
    val componentSplit: Boolean,
    val route: LpQueryRoute = LpQueryRoute.SESSION,
    val explicitAllowance: Boolean = true,
    val iterationLimit: Int = 0,
    val initialQuantum: Long? = null,
    val retainFundingStop: Boolean = false,
    val refactorUpdateLimit: Int = DEFAULT_REFACTOR_UPDATE_LIMIT,
    val trackDegeneracy: Boolean = false,
    readBasis: () -> Basis?,
) {
    private var view: LpModel? = model
    private var basisReader: (() -> Basis?)? = readBasis
    val model: LpModel get() = checkNotNull(view) { "LP query observation is outside its callback" }

    fun captureExactWithin(
        label: String,
        valueLimit: Int,
        byteLimit: Int,
        maxRetainedRows: Int,
        cancellation: Cancellation,
    ): ByteArray? {
        require(valueLimit >= 0 && byteLimit >= 0 && maxRetainedRows >= 0)
        val current = model
        if (cancellation() || current.m > maxRetainedRows) return null
        val state = current.exactState ?: return null
        if (state.model.m > maxRetainedRows) return null
        val settings = LpReplaySettings(label, pricing.tieSeed, LpReplaySolverKind.PERSISTENT,
            componentSplit = componentSplit, pivotLimit = iterationLimit, workLimit = workLimit,
            refactorUpdateLimit = refactorUpdateLimit, trackDegeneracy = trackDegeneracy)
        return LpExactCapture.capture(state, settings, emptyList(), maxRetainedRows)
            .encodeWithin(valueLimit, byteLimit, cancellation)
    }

    fun captureBasis(): Basis? = checkNotNull(basisReader) {
        "LP query observation is outside its callback"
    }()?.let {
        Basis(it.basicVars.copyOf(), it.status.copyOf(), it.captureEligible)
    }

    fun release() {
        view = null
        basisReader = null
    }
}
