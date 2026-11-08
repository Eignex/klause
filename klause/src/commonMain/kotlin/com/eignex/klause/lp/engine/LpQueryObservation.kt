package com.eignex.klause.lp.engine

// The model belongs to the synchronous callback's query. Retained diagnostics must encode an owned
// capture before returning; keeping the borrowed working view can observe a later bound adoption.
internal class LpQueryObservation(
    val owner: Any,
    val sequence: Long,
    model: LpModel,
    val retained: Boolean,
    val workLimit: Long,
    val claim: LpClaim,
    val floatOffset: Double,
    val hasFloatAcceptance: Boolean,
    val refinementLimits: LpRefinementLimits,
    val pricing: LpPricingOptions,
    val componentSplit: Boolean,
    readBasis: () -> Basis?,
) {
    private var view: LpModel? = model
    private var basisReader: (() -> Basis?)? = readBasis
    val model: LpModel get() = checkNotNull(view) { "LP query observation is outside its callback" }

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
