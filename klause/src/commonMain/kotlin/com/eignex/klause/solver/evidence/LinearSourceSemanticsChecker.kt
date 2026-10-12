package com.eignex.klause.solver.evidence

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.AlgebraicReal
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.arithmetic.polynomial.PolynomialResourceLimit
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation

/** Complete exact checker for a source consisting solely of declared coordinates and affine premises. */
class LinearSourceSemanticsChecker(
    coordinates: Set<SourceCoordinate>,
    premises: List<SourcePremise>,
    objective: Map<SourceCoordinate, BigFraction> = emptyMap(),
    private val objectiveConstant: BigFraction = BigFraction.ZERO,
) : SourceSemanticsChecker {
    private val coordinates = coordinates.toSet()
    private val premises = premises.toList()
    private val objective = objective.toMap()

    init {
        require(this.premises.all { it.inequality.coefficients.keys.all(this.coordinates::contains) })
        require(this.objective.keys.all(this.coordinates::contains))
        require(this.premises.all { premise -> premise.guards.keys.all {
            SourceCoordinate(SourceCoordinate.Kind.Boolean, it) in this.coordinates
        } })
        for (kind in SourceCoordinate.Kind.entries) {
            val ids = this.coordinates.filter { it.kind == kind }.map { it.id }.sorted()
            require(ids == ids.indices.toList()) { "source coordinate declarations must be complete" }
        }
    }

    override fun witness(sample: Sample, scope: SourceScope, cancellation: Cancellation): SourceEvidenceCheck =
        checked(cancellation) { algebraic -> point(sample, scope, algebraic) }

    override fun objectiveEquals(
        sample: Sample, value: SourceValue, scope: SourceScope, cancellation: Cancellation,
    ): SourceEvidenceCheck = checked(cancellation) { algebraic ->
        val pointCheck = point(sample, scope, algebraic)
        if (pointCheck != SourceEvidenceCheck.Checked) return@checked pointCheck
        val expected = when (value) {
            is SourceValue.Rational -> algebraic.rational(value.value)
            is SourceValue.Algebraic -> value.value
            is SourceValue.Extension -> {
                return@checked SourceEvidenceCheck.Unavailable("symbolic objective checker unavailable")
            }
        }
        var evaluated = algebraic.rational(objectiveConstant)
        for ((id, coefficient) in objective) {
            evaluated = algebraic.add(evaluated, algebraic.multiply(at(sample, id, algebraic),
                algebraic.rational(coefficient)))
        }
        if (algebraic.compare(evaluated, expected) == 0) SourceEvidenceCheck.Checked
        else SourceEvidenceCheck.Rejected("witness objective disagrees with the claimed bound")
    }

    override fun ray(claim: SourceClaim.Ray, scope: SourceScope, cancellation: Cancellation): SourceEvidenceCheck =
        checked(cancellation) { algebraic ->
            val pointCheck = point(claim.sample, scope, algebraic)
            if (pointCheck != SourceEvidenceCheck.Checked) return@checked pointCheck
            val direction = claim.direction
            if (direction.keys.any { it !in coordinates } || direction.any { (id, value) ->
                    (id.kind == SourceCoordinate.Kind.Boolean && !value.isZero) ||
                        (id.kind == SourceCoordinate.Kind.Integer && value.den != BIG_ONE)
                }
            ) return@checked SourceEvidenceCheck.Rejected("ray has invalid source coordinates or integer steps")
            for (row in premises) {
                algebraic.arithmetic.step()
                if (!active(row, claim.sample)) continue
                if (dot(row.inequality.coefficients, direction) > BigFraction.ZERO) {
                    return@checked SourceEvidenceCheck.Rejected("ray violates an original-source recession condition")
                }
            }
            if (scope.condition.lower.keys.any { (direction[it] ?: BigFraction.ZERO) < BigFraction.ZERO } ||
                scope.condition.upper.keys.any { (direction[it] ?: BigFraction.ZERO) > BigFraction.ZERO }
            ) return@checked SourceEvidenceCheck.Rejected("ray leaves the invocation restrictions")
            val objectiveScope = scope.objective
                ?: return@checked SourceEvidenceCheck.Rejected("ray has no objective scope")
            val slope = dot(objective, direction)
            val improves = if (objectiveScope.sense == ObjectiveSense.MINIMIZE) slope < BigFraction.ZERO
                else slope > BigFraction.ZERO
            if (improves) SourceEvidenceCheck.Checked
            else SourceEvidenceCheck.Rejected("ray does not improve the objective")
        }

    private fun point(sample: Sample, scope: SourceScope, algebraic: AlgebraicArithmetic): SourceEvidenceCheck {
        if (sample.numBoolVars != count(SourceCoordinate.Kind.Boolean) ||
            sample.numIntVars != count(SourceCoordinate.Kind.Integer) ||
            sample.numRealVars != count(SourceCoordinate.Kind.Real)
        ) return SourceEvidenceCheck.Rejected("witness does not cover the original source coordinates")
        if (sample.numRealVars > 0 && sample.exactReals == null && sample.algebraicReals == null) {
            return SourceEvidenceCheck.Unavailable("real witness lacks exact coordinates")
        }
        if (scope.condition.bools.any { (id, value) ->
                id !in 0 until sample.numBoolVars || sample.boolValue(id) != value
            }
        ) return SourceEvidenceCheck.Rejected("witness disagrees with invocation Boolean pins")
        for ((id, value) in scope.condition.lower) {
            if (id !in coordinates || algebraic.compare(at(sample, id, algebraic), algebraic.rational(value)) < 0) {
                return SourceEvidenceCheck.Rejected("witness violates an invocation lower bound")
            }
        }
        for ((id, value) in scope.condition.upper) {
            if (id !in coordinates || algebraic.compare(at(sample, id, algebraic), algebraic.rational(value)) > 0) {
                return SourceEvidenceCheck.Rejected("witness violates an invocation upper bound")
            }
        }
        for (premise in premises) {
            algebraic.arithmetic.step()
            if (!active(premise, sample)) continue
            val row = premise.inequality
            var value = algebraic.rational(BigFraction.ZERO)
            for ((id, coefficient) in row.coefficients) {
                value = algebraic.add(value, algebraic.multiply(at(sample, id, algebraic),
                    algebraic.rational(coefficient)))
            }
            val comparison = algebraic.compare(value, algebraic.rational(row.rhs))
            if (comparison > 0 || (comparison == 0 && row.strict)) {
                return SourceEvidenceCheck.Rejected("witness violates an original-source premise")
            }
        }
        return SourceEvidenceCheck.Checked
    }

    private fun count(kind: SourceCoordinate.Kind): Int = coordinates.count { it.kind == kind }

    private fun active(premise: SourcePremise, sample: Sample): Boolean =
        premise.guards.all { (id, value) -> sample.boolValue(id) == value }

    private fun at(
        sample: Sample, id: SourceCoordinate, algebraic: AlgebraicArithmetic,
    ): AlgebraicReal = when (id.kind) {
        SourceCoordinate.Kind.Boolean -> algebraic.rational(
            if (sample.boolValue(id.id)) BigFraction.ONE else BigFraction.ZERO,
        )
        SourceCoordinate.Kind.Integer -> algebraic.rational(BigFraction.of(sample.exactIntValue(id.id), BIG_ONE))
        SourceCoordinate.Kind.Real -> sample.algebraicReals?.get(id.id)
            ?: algebraic.rational(checkNotNull(sample.exactReals)[id.id])
    }

    private fun dot(left: Map<SourceCoordinate, BigFraction>, right: Map<SourceCoordinate, BigFraction>): BigFraction =
        left.entries.fold(BigFraction.ZERO) { sum, (id, value) -> sum + value * (right[id] ?: BigFraction.ZERO) }

    private inline fun checked(
        cancellation: Cancellation, action: (AlgebraicArithmetic) -> SourceEvidenceCheck,
    ): SourceEvidenceCheck = try {
        val algebraic = AlgebraicArithmetic(PolynomialArithmetic(cancellation))
        algebraic.arithmetic.step()
        action(algebraic).also { algebraic.arithmetic.step() }
    } catch (_: PolynomialInterrupted) {
        SourceEvidenceCheck.Unavailable("original-source check cancelled")
    } catch (_: PolynomialResourceLimit) {
        SourceEvidenceCheck.Unavailable("original-source arithmetic allowance exhausted")
    }
}
