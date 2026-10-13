package com.eignex.klause.solver.evidence

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.lp.engine.CertifiedLpBound
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.exactBounds
import com.eignex.klause.lp.engine.exactCost
import com.eignex.klause.lp.engine.exactRhs
import com.eignex.klause.lp.engine.exactShift
import com.eignex.klause.lp.engine.forEachRationalColumn
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.BigRationalConflict
import com.eignex.klause.util.Cancellation

internal class LpSourceEvidenceCapture(
    private val context: SourceProofContext,
    structuralCoordinates: List<SourceCoordinate>,
    private val maximumSteps: Int = 100_000,
) {
    private val structuralCoordinates = structuralCoordinates.toList()

    init { require(maximumSteps > 0) }

    fun bound(
        model: LpModel,
        bound: CertifiedLpBound,
        cancellation: Cancellation = Cancellation.Never,
    ): SourceEvidence = capture(SourceClaim.Bound(
        if (context.scope.objective?.sense == ObjectiveSense.MAXIMIZE) bound.value.negated() else bound.value,
    ), cancellation) { step ->
        val certificate = bound.certificate
        if (certificate != null && !certificate.belongsTo(model)) {
            missing("exact LP certificate belongs to another active model")
        }
        val multipliers = bound.dualMultipliers ?: certificate?.exactMultipliers()
            ?: missing("exact LP multiplier capture unavailable")
        if (multipliers.size != model.m) missing("exact LP bound has invalid row coordinates")
        val images = images(model, step)
        val scale = model.exactState?.model?.objective?.scale?.value ?: BigFraction.ONE
        if (scale <= BigFraction.ZERO) missing("exact LP objective has an invalid scale")
        val terms = buildList {
            for (column in 0 until model.numVars) {
                step()
                var reduced = model.exactCost(column)
                model.forEachRationalColumn(column) { row, coefficient ->
                    step()
                    reduced -= multipliers[row] * coefficient
                }
                reduced *= scale.reciprocal()
                if (reduced.isZero) continue
                val upper = reduced < BigFraction.ZERO
                val row = endpoint(model, column, upper, images[column])
                add(SourceDerivation.Combination.Term(if (upper) reduced.negated() else reduced, cite(row, step)))
            }
        }
        SourceDerivation.Combination(terms)
    }

    fun conflict(
        model: LpModel,
        conflict: BigRationalConflict,
        cancellation: Cancellation = Cancellation.Never,
    ): SourceEvidence = capture(SourceClaim.Refutation, cancellation) { step ->
        if (conflict.rows.size != conflict.multipliers.size ||
            conflict.rows.any { it !in 0 until model.m } || conflict.rows.toSet().size != conflict.rows.size
        ) missing("exact LP conflict has invalid row coordinates")
        val images = images(model, step)
        val multipliers = conflict.rows.indices.associate { conflict.rows[it] to conflict.multipliers[it] }
        val terms = buildList {
            for (column in 0 until model.numVars) {
                step()
                var coefficient = BigFraction.ZERO
                model.forEachRationalColumn(column) { row, value ->
                    step()
                    coefficient += (multipliers[row] ?: BigFraction.ZERO) * value
                }
                if (coefficient.isZero) continue
                val upper = coefficient < BigFraction.ZERO
                if (conflict.bounds.none { it.column == column && it.upper == upper }) {
                    missing("exact LP conflict lacks an endpoint citation")
                }
                val row = endpoint(model, column, upper, images[column])
                val weight = if (upper) coefficient.negated() else coefficient
                add(SourceDerivation.Combination.Term(weight, cite(row, step)))
            }
        }
        SourceDerivation.Combination(terms)
    }

    private fun capture(
        claim: SourceClaim,
        cancellation: Cancellation,
        proof: (() -> Unit) -> SourceDerivation,
    ): SourceEvidence {
        var steps = 0
        val step = {
            if (cancellation() || steps++ >= maximumSteps) missing("source LP evidence capture allowance exhausted")
        }
        val derivation = try {
            step()
            proof(step)
        } catch (stop: LpSourceCaptureUnavailable) {
            SourceDerivation.Unavailable(stop.reason)
        }
        return SourceEvidence(context.scope, claim, derivation)
    }

    private fun images(model: LpModel, step: () -> Unit): List<SourceAffineImage> {
        if (structuralCoordinates.size != model.n || structuralCoordinates.toSet().size != model.n ||
            structuralCoordinates.any { it !in context.coordinates }
        ) missing("LP columns lack original-source coordinate bindings")
        val structural = structuralCoordinates.mapIndexed { column, coordinate ->
            step()
            SourceAffineImage(mapOf(coordinate to BigFraction.ONE), model.exactShift(column).negated())
        }
        val coefficients = List(model.m) { mutableMapOf<SourceCoordinate, BigFraction>() }
        val constants = MutableList(model.m) { model.exactRhs(it) }
        for (column in 0 until model.n) {
            model.forEachRationalColumn(column) { row, value ->
                step()
                for ((coordinate, coefficient) in structural[column].coefficients) {
                    coefficients[row][coordinate] = (coefficients[row][coordinate] ?: BigFraction.ZERO) -
                        value * coefficient
                }
                constants[row] -= value * structural[column].constant
            }
        }
        return structural + coefficients.indices.map { row ->
            step()
            SourceAffineImage(coefficients[row], constants[row])
        }
    }

    private fun endpoint(model: LpModel, column: Int, upper: Boolean, image: SourceAffineImage): SourceInequality {
        val bounds = model.exactBounds(column)
        val side = (if (upper) bounds.upper else bounds.lower) ?: missing("LP endpoint has no exact finite side")
        val sign = if (upper) BigFraction.ONE else BigFraction.MINUS_ONE
        return SourceInequality(image.coefficients.mapValues { (_, value) -> sign * value },
            sign * (side.number.value - image.constant), side.strict)
    }

    private fun cite(row: SourceInequality, step: () -> Unit): SourceDerivation {
        for ((id, premise) in context.premises) {
            step()
            if (premise.guards.any { (id, value) -> context.scope.condition.bools[id] != value }) continue
            scaledCitation(premise.inequality, row, SourceDerivation.Premise(id), step)?.let { return it }
        }
        for ((coordinate, value) in context.scope.condition.lower) {
            step()
            val premise = SourceInequality(mapOf(coordinate to BigFraction.MINUS_ONE), value.negated())
            scaledCitation(premise, row, SourceDerivation.Assumption(coordinate, false), step)?.let { return it }
        }
        for ((coordinate, value) in context.scope.condition.upper) {
            step()
            val premise = SourceInequality(mapOf(coordinate to BigFraction.ONE), value)
            scaledCitation(premise, row, SourceDerivation.Assumption(coordinate, true), step)?.let { return it }
        }
        if (row.coefficients.isEmpty() && BigFraction.ZERO <= row.rhs && !row.strict) {
            return SourceDerivation.Combination(emptyList())
        }
        missing("LP endpoint has no original-source derivation")
    }

    private fun scaledCitation(
        premise: SourceInequality, row: SourceInequality, derivation: SourceDerivation, step: () -> Unit,
    ): SourceDerivation? {
        if (premise.coefficients.keys != row.coefficients.keys) return null
        val coordinate = row.coefficients.keys.firstOrNull() ?: return if (premise.entails(row)) derivation else null
        val scale = row.coefficients.getValue(coordinate) * premise.coefficients.getValue(coordinate).reciprocal()
        if (scale <= BigFraction.ZERO) return null
        val scaled = SourceInequality(premise.coefficients.mapValues { (_, value) -> step(); value * scale },
            premise.rhs * scale, premise.strict)
        if (!scaled.entails(row)) return null
        return SourceDerivation.Combination(listOf(SourceDerivation.Combination.Term(scale, derivation)))
    }
}

private class LpSourceCaptureUnavailable(val reason: String) : RuntimeException()

private fun missing(reason: String): Nothing = throw LpSourceCaptureUnavailable(reason)
