package com.eignex.klause.lp.optimization

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.engine.CertifiedLpResult
import com.eignex.klause.lp.engine.LpVerdict
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceDerivation
import com.eignex.klause.solver.evidence.SourceEvidence
import com.eignex.klause.solver.evidence.SourceEvidenceCheck
import com.eignex.klause.solver.evidence.SourceEvidenceVerifier
import com.eignex.klause.solver.evidence.SourceInequality
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.compareTo
import com.eignex.klause.util.fitsLong
import com.eignex.klause.util.times
import com.eignex.klause.util.toLongExact

internal data class LinearAuthorityResult(
    val evidence: SourceEvidence? = null,
    val bound: SourceEvidence? = null,
    val incumbent: Sample? = null,
    val split: Pair<SourceCoordinate, BigFraction>? = null,
)

internal class LinearAuthority(private val budget: SourceLpBudget) {
    private val lp = LinearAuthorityLp(budget)

    fun check(source: SourceProofContext, cancellation: Cancellation): LinearAuthorityResult {
        val coordinates = source.numericCoordinates()
        if (source.scope.objective == null || source.coordinates.any {
                it.kind == SourceCoordinate.Kind.Boolean && it.id !in source.scope.condition.bools
            }
        ) return LinearAuthorityResult()
        val context = source.withLinearAuthority()
        val numeric = source.numericContext()
        val cited = ArrayList<Pair<SourceInequality, SourceDerivation>>()
        for ((id, premise) in numeric.premises) {
            if (cancellation()) return LinearAuthorityResult()
            if (premise.guards.all { (bool, value) -> source.scope.condition.bools[bool] == value }) {
                cited += premise.inequality to SourceDerivation.Premise(id)
            }
        }
        for ((upper, bounds) in listOf(false to source.scope.condition.lower, true to source.scope.condition.upper)) {
            val sign = if (upper) BigFraction.ONE else BigFraction.MINUS_ONE
            for ((id, value) in bounds) cited += SourceInequality(mapOf(id to sign), value * sign) to
                SourceDerivation.Assumption(id, upper)
        }
        val rows = cited.map { (row, _) ->
            val ids = row.coefficients.keys.map(coordinates::indexOf).sorted().toIntArray()
            ExactRationalInequality(ids, ids.map { row.coefficients.getValue(coordinates[it]) }, row.rhs, row.strict)
        }
        val sign = if (source.scope.objective.sense == ObjectiveSense.MINIMIZE) BigFraction.ONE
            else BigFraction.MINUS_ONE
        val costs = coordinates.map { sign * (numeric.objective[it] ?: BigFraction.ZERO) }
        val constant = numeric.objectiveConstant * sign
        fun derivation(weights: List<BigFraction>): SourceDerivation = SourceDerivation.Combination(
            weights.mapIndexedNotNull { id, weight -> if (weight.isZero) null
                else SourceDerivation.Combination.Term(weight, cited[id].second) })
        fun checked(claim: SourceClaim, proof: SourceDerivation): SourceEvidence? {
            val evidence = SourceEvidence(source.scope, claim, proof)
            return evidence.takeIf {
                SourceEvidenceVerifier(context).verify(it, cancellation) == SourceEvidenceCheck.Checked
            }
        }
        fun refutation(result: CertifiedLpResult): SourceEvidence? {
            if (result.verdict != LpVerdict.INFEASIBLE) return null
            val conflict = result.rationalConflict
            if (conflict != null) {
                val weights = MutableList(rows.size) { BigFraction.ZERO }
                for (id in conflict.rows.indices) weights[conflict.rows[id]] += conflict.multipliers[id]
                checked(SourceClaim.Refutation, LinearPinnedRefutation(derivation(weights)))?.let { return it }
            }
            val zeros = List(coordinates.size) { BigFraction.ZERO }
            val weights = lp.combination(rows, zeros, BigFraction.MINUS_ONE, false, cancellation)
                ?: lp.combination(rows, zeros, BigFraction.ZERO, true, cancellation) ?: return null
            return checked(SourceClaim.Refutation, LinearPinnedRefutation(derivation(weights)))
        }
        val closureRows = rows.map { ExactRationalInequality(it.columns, it.coefficients, it.rhs) }
        val closure = lp.solve(closureRows, costs, constant, cancellation) ?: return LinearAuthorityResult()
        val bound = closure.lowerBound?.let { value ->
            lp.combination(rows, costs.map { it.negated() }, constant - value, false, cancellation)?.let { weights ->
                checked(SourceClaim.Bound(value * sign), LinearPinnedObjective(derivation(weights)))
            }
        }
        if (closure.verdict == LpVerdict.INFEASIBLE) {
            return LinearAuthorityResult(refutation(closure), bound)
        }
        val endpoint = closure.exactPrimal?.take(coordinates.size)
        fractional(coordinates, endpoint)?.let { return LinearAuthorityResult(bound = bound, split = it) }
        val endpointSample = endpoint?.let { sample(source, coordinates, it) }
        val endpointAccepted = endpointSample?.takeIf {
            checked(SourceClaim.Witness(it), SourceDerivation.SourceWitness) != null
        }
        if (endpointAccepted != null && bound != null && closure.lowerBound == closure.witness?.objective) {
            val value = (bound.claim as SourceClaim.Bound).value
            val proof = checked(SourceClaim.Optimal(endpointAccepted, value),
                SourceDerivation.Optimum(SourceDerivation.SourceWitness, bound.derivation))
            if (proof != null) return LinearAuthorityResult(proof, bound, endpointAccepted)
        }
        val feasible = if (endpointAccepted != null) endpointAccepted else {
            val original = lp.solve(rows, List(costs.size) { BigFraction.ZERO }, BigFraction.ZERO, cancellation)
            original?.let { refutation(it) }?.let { return LinearAuthorityResult(it, bound) }
            val primal = original?.exactPrimal?.take(coordinates.size)
            fractional(coordinates, primal)?.let { return LinearAuthorityResult(bound = bound, split = it) }
            primal?.let { sample(source, coordinates, it) }?.takeIf {
                checked(SourceClaim.Witness(it), SourceDerivation.SourceWitness) != null
            }
        }
        if (feasible == null) return LinearAuthorityResult(bound = bound)
        if (bound != null) {
            val proof = checked(SourceClaim.Optimal(feasible, (bound.claim as SourceClaim.Bound).value),
                SourceDerivation.Optimum(SourceDerivation.SourceWitness, bound.derivation))
            if (proof != null) return LinearAuthorityResult(proof, bound, feasible)
        }
        closure.unboundedness?.let { unbounded ->
            var scale = BIG_ONE
            for (id in coordinates.indices) {
                if (cancellation()) return LinearAuthorityResult(bound = bound, incumbent = feasible)
                if (coordinates[id].kind == SourceCoordinate.Kind.Integer) scale *= unbounded.direction[id].den
            }
            val multiplier = BigFraction.of(scale, BIG_ONE)
            val direction = coordinates.zip(unbounded.direction.take(coordinates.size).map { it * multiplier }).toMap()
            val proof = checked(SourceClaim.Ray(feasible, direction), SourceDerivation.SourceRay)
            if (proof != null) return LinearAuthorityResult(proof, bound, feasible)
        }
        if (bound != null && endpointSample != null && closure.lowerBound == closure.witness?.objective) {
            for (id in 0 until feasible.numIntVars) {
                if (feasible.exactIntValue(id) != endpointSample.exactIntValue(id)) {
                    val left = feasible.exactIntValue(id)
                    val right = endpointSample.exactIntValue(id)
                    val low = if (left < right) left else right
                    return LinearAuthorityResult(bound = bound, incumbent = feasible,
                        split = SourceCoordinate(SourceCoordinate.Kind.Integer, id) to BigFraction.of(low, BIG_ONE))
                }
            }
            val value = requireNotNull(closure.lowerBound)
            val weights = lp.combination(rows, costs.map { it.negated() }, constant - value, true, cancellation)
            if (weights != null) {
                val proof = checked(SourceClaim.Limit(feasible, SourceValue.Rational(value * sign)),
                    LinearApproach(endpointSample, LinearPinnedObjective(derivation(weights))))
                if (proof != null) return LinearAuthorityResult(proof, bound, feasible)
            }
        }
        return LinearAuthorityResult(bound = bound, incumbent = feasible)
    }
}

private fun fractional(
    coordinates: List<SourceCoordinate>, point: List<BigFraction>?,
): Pair<SourceCoordinate, BigFraction>? {
    if (point == null) return null
    for (id in coordinates.indices) {
        if (coordinates[id].kind == SourceCoordinate.Kind.Integer && point[id].den != BIG_ONE) {
            return coordinates[id] to BigFraction.of(point[id].negated().ceilInteger(), BIG_ONE).negated()
        }
    }
    return null
}

private fun sample(source: SourceProofContext, coordinates: List<SourceCoordinate>, point: List<BigFraction>): Sample {
    val integers = coordinates.indices.filter { coordinates[it].kind == SourceCoordinate.Kind.Integer }
        .map { point[it].num }
    val reals = coordinates.indices.filter { coordinates[it].kind == SourceCoordinate.Kind.Real }.map { point[it] }
    return Sample(BooleanArray(source.coordinates.count { it.kind == SourceCoordinate.Kind.Boolean }) {
        source.scope.condition.bools.getValue(it)
    }, if (integers.all { it.fitsLong() }) LongArray(integers.size) { integers[it].toLongExact() } else longArrayOf(),
        DoubleArray(reals.size) { reals[it].toDouble() }, exactReals = reals, exactInts = integers)
}
