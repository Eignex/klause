package com.eignex.klause.solver.evidence

import com.eignex.klause.simplex.exact.BigFraction

/** An exact affine image in original source coordinates. */
class SourceAffineImage(
    coefficients: Map<SourceCoordinate, BigFraction>,
    /** Exact affine constant. */
    val constant: BigFraction = BigFraction.ZERO,
) {
    private val captured = coefficients.filterValues { !it.isZero }.toMap()
    /** Original-source terms defining the target coordinate. */
    val coefficients: Map<SourceCoordinate, BigFraction> get() = captured.toMap()
}

/** Checkable source-to-target projection obligations retained through lowering or presolve. */
class SourceTransformation(
    /** Original source invocation. */
    val source: SourceScope,
    /** Reduced source invocation. */
    val target: SourceScope,
    images: Map<SourceCoordinate, SourceAffineImage>,
    premiseDerivations: Map<String, SourceDerivation>,
    /** Positive adjustment taking target objective values to source units. */
    val objectiveScale: BigFraction = BigFraction.ONE,
    /** Exact source objective adjustment constant. */
    val objectiveOffset: BigFraction = BigFraction.ZERO,
) {
    private val capturedImages = images.toMap()
    private val capturedDerivations = premiseDerivations.toMap()
    /** A total image for every target coordinate. */
    val images: Map<SourceCoordinate, SourceAffineImage> get() = capturedImages.toMap()
    /** Original-source justifications for each substituted target premise and invocation restriction. */
    val premiseDerivations: Map<String, SourceDerivation> get() = capturedDerivations.toMap()
}
