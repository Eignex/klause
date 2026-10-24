package com.eignex.klause.lp.optimization

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.SparseSourceLpPolicy
import com.eignex.klause.lp.engine.LpVerdict
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.IntegerPinStatus
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceDerivation
import com.eignex.klause.solver.evidence.SourceEvidence
import com.eignex.klause.solver.evidence.SourceEvidenceCheck
import com.eignex.klause.solver.evidence.SourceEvidenceVerifier
import com.eignex.klause.solver.evidence.SourceInequality
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.solver.evidence.integerPinBounds
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
    val fractional: List<Pair<SourceCoordinate, BigFraction>> = emptyList(),
    val relaxedPoint: Map<SourceCoordinate, Double> = emptyMap(),
    val paused: Boolean = false,
)

internal class LinearAuthority(
    private val budget: SourceLpBudget,
    onLpShape: (LinearAuthorityLpShape) -> Unit = {},
    private val retainLp: Boolean = false,
    guidancePolicy: SparseSourceLpPolicy = SparseSourceLpPolicy(),
) : AutoCloseable {
    private val lp = LinearAuthorityLp(budget, onLpShape, retainLp, guidancePolicy)
    val preparations: Long get() = lp.preparations
    val reuses: Long get() = lp.reuses
    private var template: LinearAuthorityTemplate? = null

    override fun close() {
        template = null
        lp.close()
    }

    fun check(source: SourceProofContext, cancellation: Cancellation): LinearAuthorityResult {
        relaxedPoint = emptyMap()
        if (cancellation()) {
            lp.close()
            return LinearAuthorityResult()
        }
        val result = checkSource(source, cancellation)
        if (cancellation()) {
            lp.close()
            return LinearAuthorityResult()
        }
        return result.copy(relaxedPoint = relaxedPoint)
    }

    fun advance(source: SourceProofContext, cancellation: Cancellation): LinearAuthorityResult {
        relaxedPoint = emptyMap()
        if (cancellation()) {
            lp.close()
            return LinearAuthorityResult()
        }
        val result = checkSource(source, cancellation, guidance = true)
        if (cancellation()) {
            lp.close()
            return LinearAuthorityResult()
        }
        return result.copy(relaxedPoint = relaxedPoint)
    }

    private var relaxedPoint: Map<SourceCoordinate, Double> = emptyMap()

    private fun checkSource(
        source: SourceProofContext, cancellation: Cancellation, guidance: Boolean = false,
    ): LinearAuthorityResult {
        if (!source.affineObjectiveAvailable || !source.linearCoordinatesSupported()) return LinearAuthorityResult()
        if ((source.scope.condition.lower.keys + source.scope.condition.upper.keys).any {
                it !in source.coordinates
            } || source.scope.condition.bools.keys.any {
                SourceCoordinate(SourceCoordinate.Kind.Boolean, it) !in source.coordinates
            }
        ) return LinearAuthorityResult()
        if (source.scope.objective == null || source.coordinates.any {
                it.kind == SourceCoordinate.Kind.Boolean && it.id !in source.scope.condition.bools
            }
        ) return LinearAuthorityResult()
        val context = source.withLinearAuthority()
        val prepared = if (retainLp) {
            template?.takeIf { it.matches(source) } ?: LinearAuthorityTemplate.prepare(source, cancellation)
                ?.also { template = it } ?: return LinearAuthorityResult()
        } else null
        val coordinates = prepared?.coordinates ?: source.numericCoordinates()
        val numeric = prepared?.numeric ?: source.numericContext()
        val premises = numeric.premises
        val integerBounds = if (premises.values.any { it.integerPins.isNotEmpty() }) {
            numeric.integerPinBounds(source.scope.condition, cancellation) ?: return LinearAuthorityResult()
        } else null
        val cited = ArrayList<Pair<SourceInequality, SourceDerivation>>()
        val inactive = HashSet<Int>()
        for ((id, premise) in premises) {
            if (cancellation()) return LinearAuthorityResult()
            val active = premise.guards.all { (bool, value) -> source.scope.condition.bools[bool] == value } &&
                (premise.integerPins.isEmpty() ||
                    integerBounds?.status(premise.integerPins) == IntegerPinStatus.Established)
            if (active || prepared != null) {
                if (!active) inactive += cited.size
                cited += premise.inequality to SourceDerivation.Premise(id)
            }
        }
        for ((upper, bounds) in listOf(false to source.scope.condition.lower, true to source.scope.condition.upper)) {
            val sign = if (upper) BigFraction.ONE else BigFraction.MINUS_ONE
            for ((id, value) in bounds) cited += SourceInequality(mapOf(id to sign), value * sign) to
                SourceDerivation.Assumption(id, upper)
        }
        val indices = prepared?.indices ?: coordinates.withIndex().associate { it.value to it.index }
        val rows = if (prepared == null) cited.map { it.first.row(indices) }
            else prepared.rows + cited.drop(prepared.rows.size).map { it.first.row(indices) }
        val sign = if (source.scope.objective.sense == ObjectiveSense.MINIMIZE) BigFraction.ONE
            else BigFraction.MINUS_ONE
        val costs = coordinates.map { sign * (numeric.objective[it] ?: BigFraction.ZERO) }
        val constant = numeric.objectiveConstant * sign
        fun combination(coefficients: List<BigFraction>, rhs: BigFraction, strict: Boolean): List<BigFraction>? {
            val selected = rows.indices.filter { it !in inactive }
            val weights = lp.combination(selected.map { rows[it] }, coefficients, rhs, strict, cancellation)
                ?: return null
            return MutableList(rows.size) { BigFraction.ZERO }.also { lifted ->
                for (id in selected.indices) lifted[selected[id]] = weights[id]
            }
        }
        fun derivation(weights: List<BigFraction>): SourceDerivation = SourceDerivation.Combination(
            weights.mapIndexedNotNull { id, weight -> if (weight.isZero) null
                else SourceDerivation.Combination.Term(weight, cited[id].second) })
        fun checked(claim: SourceClaim, proof: SourceDerivation): SourceEvidence? {
            val evidence = SourceEvidence(source.scope, claim, proof)
            return evidence.takeIf {
                SourceEvidenceVerifier(context).verify(it, cancellation) == SourceEvidenceCheck.Checked
            }
        }
        fun refutation(result: LinearAuthorityLpResult): SourceEvidence? {
            if (result.result.verdict != LpVerdict.INFEASIBLE) return null
            result.conflictWeights?.let { weights ->
                checked(SourceClaim.Refutation, LinearPinnedRefutation(derivation(weights)))?.let { return it }
            }
            val zeros = List(coordinates.size) { BigFraction.ZERO }
            val weights = combination(zeros, BigFraction.MINUS_ONE, false)
                ?: combination(zeros, BigFraction.ZERO, true) ?: return null
            return checked(SourceClaim.Refutation, LinearPinnedRefutation(derivation(weights)))
        }
        val closureRows = rows.map { ExactRationalInequality(it.columns, it.coefficients, it.rhs) }
        val guide = if (guidance) lp.advance(closureRows, costs, constant, source.scope, cancellation, inactive)
            ?: return LinearAuthorityResult() else null
        if (guide?.paused == true) return LinearAuthorityResult(paused = true)
        val solved = guide?.result ?: lp.solve(closureRows, costs, constant, cancellation, inactive)
            ?: return LinearAuthorityResult()
        val closure = solved.result
        (guide?.point ?: closure.float?.primal)?.takeIf {
            it.size == coordinates.size && it.all(Double::isFinite)
        }?.let { point ->
            relaxedPoint = coordinates.indices.associate { coordinates[it] to point[it] }
        }
        val bound = closure.lowerBound?.let { value ->
            fun checkBound(weights: List<BigFraction>): SourceEvidence? =
                checked(SourceClaim.Bound(value * sign), LinearPinnedObjective(derivation(weights)))
            val retained = solved.boundWeights?.let(::checkBound)
            if (retained != null || guidance) retained else
                combination(costs.map { it.negated() }, constant - value, false)?.let(::checkBound)
        }
        if (guide != null) {
            val point = guide.point?.map { BigFraction.ofDouble(it) ?: return LinearAuthorityResult(bound = bound) }
            val fractions = fractional(coordinates, point).filter { candidate ->
                val column = coordinates.indexOf(candidate.first)
                val threshold = candidate.floorSplit().second
                val bounds = guide.bounds[column]
                (bounds.lower?.let { threshold >= it.number.value } != false) &&
                    (bounds.upper?.let { threshold + BigFraction.ONE <= it.number.value } != false)
            }
            val candidate = point?.takeIf { fractional(coordinates, it).isEmpty() }
                ?.let { sample(source, coordinates, it) }?.takeIf {
                    checked(SourceClaim.Witness(it), SourceDerivation.SourceWitness) != null
                }
            if (candidate != null && bound != null && point != null) {
                val value = (point.indices.fold(constant) { value, id -> value + costs[id] * point[id] }) * sign
                val proof = checked(SourceClaim.Optimal(candidate, SourceValue.Rational(value)),
                    SourceDerivation.Optimum(SourceDerivation.SourceWitness, bound.derivation))
                if (proof != null) return LinearAuthorityResult(proof, bound, candidate)
            }
            if (fractions.isNotEmpty()) return LinearAuthorityResult(bound = bound, incumbent = candidate,
                split = fractions.first().floorSplit(), fractional = fractions)
            if (budget.admitsDimensions(costs.size, guide.workingRows.toLong())) {
                val exact = checkSource(source, cancellation)
                return exact.copy(bound = exact.bound ?: bound, incumbent = exact.incumbent ?: candidate)
            }
            return LinearAuthorityResult(bound = bound, incumbent = candidate)
        }
        if (closure.verdict == LpVerdict.INFEASIBLE) {
            return LinearAuthorityResult(refutation(solved), bound)
        }
        val endpoint = closure.exactPrimal?.take(coordinates.size)
        fractional(coordinates, endpoint).takeIf { it.isNotEmpty() }?.let {
            return LinearAuthorityResult(bound = bound, split = it.first().floorSplit(), fractional = it)
        }
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
            val original = lp.solve(rows, List(costs.size) { BigFraction.ZERO }, BigFraction.ZERO,
                cancellation, inactive)
            original?.let { refutation(it) }?.let { return LinearAuthorityResult(it, bound) }
            val primal = original?.result?.exactPrimal?.take(coordinates.size)
            fractional(coordinates, primal).takeIf { it.isNotEmpty() }?.let {
                return LinearAuthorityResult(bound = bound, split = it.first().floorSplit(), fractional = it)
            }
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
            val weights = combination(costs.map { it.negated() }, constant - value, true)
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
): List<Pair<SourceCoordinate, BigFraction>> {
    if (point == null) return emptyList()
    return coordinates.indices.mapNotNull { id ->
        if (coordinates[id].kind == SourceCoordinate.Kind.Integer && point[id].den != BIG_ONE) {
            coordinates[id] to point[id]
        } else null
    }
}

internal fun Pair<SourceCoordinate, BigFraction>.floorSplit(): Pair<SourceCoordinate, BigFraction> =
    first to BigFraction.of(second.negated().ceilInteger(), BIG_ONE).negated()

private fun sample(source: SourceProofContext, coordinates: List<SourceCoordinate>, point: List<BigFraction>): Sample {
    val integers = coordinates.indices.filter { coordinates[it].kind == SourceCoordinate.Kind.Integer }
        .map { point[it].num }
    val reals = coordinates.indices.filter { coordinates[it].kind == SourceCoordinate.Kind.Real }.map { point[it] }
    return Sample(BooleanArray(source.coordinates.count { it.kind == SourceCoordinate.Kind.Boolean }) {
        source.scope.condition.bools.getValue(it)
    }, if (integers.all { it.fitsLong() }) LongArray(integers.size) { integers[it].toLongExact() } else longArrayOf(),
        DoubleArray(reals.size) { reals[it].toDouble() }, exactReals = reals, exactInts = integers)
}
