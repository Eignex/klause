package com.eignex.klause.solver.evidence

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation

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

/** Checks transformation obligations against two independently supplied source contexts. */
class SourceTransformationVerifier(
    private val source: SourceProofContext,
    private val target: SourceProofContext,
    private val maximumSteps: Int = 100_000,
    private val maximumDepth: Int = 128,
) {
    init { require(maximumSteps > 0 && maximumDepth > 0) }

    /** Check that every original assignment maps to an assignment of the target invocation. */
    fun verify(
        transformation: SourceTransformation,
        cancellation: Cancellation = Cancellation.Never,
    ): SourceEvidenceCheck = verify(transformation, cancellation, SourceProofAllowance(maximumSteps, maximumDepth))

    private fun verify(
        transformation: SourceTransformation, cancellation: Cancellation, allowance: SourceProofAllowance,
    ): SourceEvidenceCheck {
        if (transformation.source != source.scope || transformation.target != target.scope) {
            return SourceEvidenceCheck.Rejected("transformation belongs to different source revisions or invocations")
        }
        if (!declaredCondition(source) || !declaredCondition(target)) {
            return SourceEvidenceCheck.Rejected("transformation invocation references undeclared coordinates")
        }
        if (source.scope.condition.integerRows.isNotEmpty() || target.scope.condition.integerRows.isNotEmpty()) {
            return SourceEvidenceCheck.Unavailable("aggregate invocation projection requires an integral image proof")
        }
        if (transformation.images.keys != target.coordinates) {
            return SourceEvidenceCheck.Unavailable("transformation lacks a total target coordinate image")
        }
        for ((coordinate, image) in transformation.images) {
            if (cancellation() || !allowance.consume(0)) {
                return SourceEvidenceCheck.Unavailable("transformation verification allowance exhausted")
            }
            if (image.coefficients.keys.any { it !in source.coordinates }) {
                return SourceEvidenceCheck.Rejected("transformation reads an undeclared original coordinate")
            }
            val nonIntegralImage = image.constant.den != BIG_ONE || image.coefficients.any { (id, value) ->
                id.kind == SourceCoordinate.Kind.Real || value.den != BIG_ONE
            }
            when (coordinate.kind) {
                SourceCoordinate.Kind.Integer -> if (nonIntegralImage) {
                    return SourceEvidenceCheck.Unavailable("target integer image has no integrality derivation")
                }
                SourceCoordinate.Kind.Boolean -> if (image.constant != BigFraction.ZERO ||
                    image.coefficients != mapOf(coordinate to BigFraction.ONE)
                ) return SourceEvidenceCheck.Unavailable("Boolean transformation image is not supported")
                SourceCoordinate.Kind.Real -> Unit
            }
        }
        val unjustifiedPin = target.scope.condition.bools.any { (id, value) ->
            source.scope.condition.bools[id] != value
        }
        if (unjustifiedPin) {
            return SourceEvidenceCheck.Rejected("target invocation adds an unjustified Boolean restriction")
        }
        val verifier = SourceEvidenceVerifier(source)
        for ((id, premise) in target.premises) {
            if (cancellation() || !allowance.consume(0)) {
                return SourceEvidenceCheck.Unavailable("transformation verification allowance exhausted")
            }
            if (premise.guards.any { (variable, value) ->
                    source.scope.condition.bools[variable]?.let { it != value } == true
                }
            ) continue
            var condition = source.scope.condition
            for ((variable, value) in premise.guards) condition = condition.pin(variable, value)
            val scope = source.scope.copy(condition = condition)
            val context = SourceProofContext(scope, source.coordinates, source.premises,
                source.objective.takeIf { source.affineObjectiveAvailable },
                source.objectiveConstant, source.semanticsChecker, source.semantics)
            val obligation = substitute(premise.inequality, transformation)
            val proof = transformation.premiseDerivations[id]
                ?: return SourceEvidenceCheck.Unavailable("missing original-source transformation premise $id")
            val checked = SourceEvidenceVerifier(context).verify(
                SourceEvidence(scope, SourceClaim.Consequence(obligation), proof), cancellation, allowance,
            )
            if (checked != SourceEvidenceCheck.Checked) return checked
        }
        for ((coordinate, value) in target.scope.condition.lower) {
            val checked = restriction(coordinate, value, false, transformation, verifier, cancellation, allowance)
            if (checked != SourceEvidenceCheck.Checked) return checked
        }
        for ((coordinate, value) in target.scope.condition.upper) {
            val checked = restriction(coordinate, value, true, transformation, verifier, cancellation, allowance)
            if (checked != SourceEvidenceCheck.Checked) return checked
        }
        return if (cancellation()) SourceEvidenceCheck.Unavailable("transformation check cancelled")
            else SourceEvidenceCheck.Checked
    }

    /** Transfer only a checked target refutation through a verified source projection. */
    fun refutation(
        transformation: SourceTransformation,
        evidence: SourceEvidence,
        cancellation: Cancellation = Cancellation.Never,
    ): SourceEvidenceCheck {
        if (evidence.claim != SourceClaim.Refutation) {
            return SourceEvidenceCheck.Rejected("transformation requires a target refutation claim")
        }
        val allowance = SourceProofAllowance(maximumSteps, maximumDepth)
        val projection = verify(transformation, cancellation, allowance)
        if (projection != SourceEvidenceCheck.Checked) return projection
        val coverage = affineCoverage(evidence.derivation, cancellation, allowance, 0)
        if (coverage != SourceEvidenceCheck.Checked) return coverage
        return SourceEvidenceVerifier(target).verify(evidence, cancellation, allowance)
    }

    /** Check the affine source objective adjustment against original-source equalities. */
    fun objective(
        transformation: SourceTransformation,
        cancellation: Cancellation = Cancellation.Never,
    ): SourceEvidenceCheck {
        if (!source.affineObjectiveAvailable || !target.affineObjectiveAvailable) {
            return SourceEvidenceCheck.Unavailable("transformation has no original affine objective authority")
        }
        if (source.scope.objective == null || target.scope.objective == null ||
            source.scope.objective.sense != target.scope.objective.sense ||
            transformation.objectiveScale <= BigFraction.ZERO
        ) return SourceEvidenceCheck.Rejected("transformation has incompatible objective direction or scale")
        val allowance = SourceProofAllowance(maximumSteps, maximumDepth)
        val projection = verify(transformation, cancellation, allowance)
        if (projection != SourceEvidenceCheck.Checked) return projection
        val mapped = substitute(SourceInequality(target.objective, target.objectiveConstant.negated()), transformation)
        val coefficients = source.objective.toMutableMap()
        for ((id, value) in mapped.coefficients) {
            coefficients[id] = (coefficients[id] ?: BigFraction.ZERO) - transformation.objectiveScale * value
        }
        val constant = source.objectiveConstant + transformation.objectiveScale * mapped.rhs -
            transformation.objectiveOffset
        val equality = SourceInequality(coefficients, constant.negated())
        if (equality.coefficients.isEmpty() && equality.rhs.isZero) return SourceEvidenceCheck.Checked
        val verifier = SourceEvidenceVerifier(source)
        for ((id, row) in listOf(
            "objective/upper" to equality,
            "objective/lower" to SourceInequality(equality.coefficients.mapValues { (_, value) -> value.negated() },
                equality.rhs.negated()),
        )) {
            val proof = transformation.premiseDerivations[id]
                ?: return SourceEvidenceCheck.Unavailable("missing source objective transformation equality $id")
            val checked = verifier.verify(SourceEvidence(source.scope, SourceClaim.Consequence(row),
                proof), cancellation, allowance)
            if (checked != SourceEvidenceCheck.Checked) return checked
        }
        return SourceEvidenceCheck.Checked
    }

    private fun affineCoverage(
        derivation: SourceDerivation,
        cancellation: Cancellation,
        allowance: SourceProofAllowance,
        depth: Int,
    ): SourceEvidenceCheck {
        if (cancellation() || !allowance.consume(depth)) {
            return SourceEvidenceCheck.Unavailable("transformation verification allowance exhausted")
        }
        val children = when (derivation) {
            is SourceDerivation.Extension -> return SourceEvidenceCheck.Unavailable(
                "transformation has no source projection justification for target semantic extensions",
            )
            is SourceDerivation.BooleanSplit -> listOf(derivation.whenFalse, derivation.whenTrue)
            is SourceDerivation.IntegerSplit -> listOf(derivation.below, derivation.above)
            is SourceDerivation.Combination -> derivation.terms.map { it.derivation }
            is SourceDerivation.IntegerRounding -> listOf(derivation.inequality)
            is SourceDerivation.MixedIntegerRounding -> listOf(derivation.inequality) + derivation.bounds
            is SourceDerivation.Import -> listOf(derivation.evidence.derivation)
            is SourceDerivation.Discharge -> listOf(derivation.refutation.derivation)
            is SourceDerivation.Optimum -> listOf(derivation.witness, derivation.bound)
            else -> emptyList()
        }
        for (child in children) {
            val checked = affineCoverage(child, cancellation, allowance, depth + 1)
            if (checked != SourceEvidenceCheck.Checked) return checked
        }
        return SourceEvidenceCheck.Checked
    }

    private fun restriction(
        coordinate: SourceCoordinate,
        value: BigFraction,
        upper: Boolean,
        transformation: SourceTransformation,
        verifier: SourceEvidenceVerifier,
        cancellation: Cancellation,
        allowance: SourceProofAllowance,
    ): SourceEvidenceCheck {
        val sign = if (upper) BigFraction.ONE else BigFraction.MINUS_ONE
        val row = substitute(SourceInequality(mapOf(coordinate to sign), value * sign), transformation)
        val id = "assumption/${coordinate.kind}/${coordinate.id}/${if (upper) "upper" else "lower"}"
        val proof = transformation.premiseDerivations[id]
            ?: return SourceEvidenceCheck.Unavailable("missing target invocation transformation justification $id")
        return verifier.verify(SourceEvidence(source.scope, SourceClaim.Consequence(row), proof),
            cancellation, allowance)
    }

    private fun substitute(row: SourceInequality, transformation: SourceTransformation): SourceInequality {
        val coefficients = mutableMapOf<SourceCoordinate, BigFraction>()
        var rhs = row.rhs
        for ((coordinate, multiplier) in row.coefficients) {
            val image = checkNotNull(transformation.images[coordinate])
            rhs -= multiplier * image.constant
            for ((id, value) in image.coefficients) {
                coefficients[id] = (coefficients[id] ?: BigFraction.ZERO) + multiplier * value
            }
        }
        return SourceInequality(coefficients, rhs, row.strict)
    }
}

private fun declaredCondition(context: SourceProofContext): Boolean =
    (context.scope.condition.lower.keys + context.scope.condition.upper.keys).all { it in context.coordinates } &&
        context.scope.condition.bools.keys.all {
            SourceCoordinate(SourceCoordinate.Kind.Boolean, it) in context.coordinates
        }
