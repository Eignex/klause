package com.eignex.klause.lp.optimization

import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.SourceLpFunding
import com.eignex.klause.lp.SparseSourceLpPolicy
import com.eignex.klause.lp.engine.LpVerdict
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceDerivation
import com.eignex.klause.solver.evidence.SourceEvidence
import com.eignex.klause.solver.evidence.SourceEvidenceCheck
import com.eignex.klause.solver.evidence.SourceEvidenceVerifier
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
    val fractional: List<Pair<SourceCoordinate, BigFraction>> = emptyList(),
    val relaxedPoint: Map<SourceCoordinate, Double> = emptyMap(),
    val paused: Boolean = false,
    val coordinateConflict: Boolean = false,
    val lpConflict: Boolean = false,
    val primalStages: Map<String, Long> = emptyMap(),
)

internal class LinearAuthority(
    private val budget: SourceLpBudget,
    onLpShape: (LinearAuthorityLpShape) -> Unit = {},
    private val retainLp: Boolean = false,
    guidancePolicy: SparseSourceLpPolicy = SparseSourceLpPolicy(),
) : AutoCloseable {
    private val lp = LinearAuthorityLp(budget, onLpShape, retainLp, guidancePolicy)
    val funding: SourceLpFunding? get() = budget.funding
    val preparations: Long get() = lp.preparations
    val reuses: Long get() = lp.reuses
    private var template: LinearAuthorityTemplate? = null
    private var query: LinearAuthorityQuery? = null
    private var cutPreparationWork = 0L
    private var cutVerificationWork = 0L
    private var retainedCutPeak = 0L
    private var activeCutPeak = 0L
    fun cutObservations(): Map<String, Long> = mapOf(
        "numericalCutPreparationWork" to cutPreparationWork,
        "numericalCutVerificationWork" to cutVerificationWork,
        "numericalMaxRetainedCuts" to retainedCutPeak,
        "numericalMaxActiveCuts" to activeCutPeak,
    )
    private class PrimalAttempt(
        val query: LinearAuthorityQuery, val point: List<BigFraction>,
    ) {
        val progress = LinearPrimalRecoveryProgress()
        var recovered: List<BigFraction>? = null
        var finished = false
    }
    private var primalAttempt: PrimalAttempt? = null

    fun invalidateGuidance() = lp.invalidateGuidance()

    override fun close() {
        template = null
        query = null
        primalAttempt = null
        lp.close()
    }

    fun check(source: SourceProofContext, cancellation: Cancellation): LinearAuthorityResult {
        relaxedPoint = emptyMap()
        if (cancellation()) {
            lp.close()
            return LinearAuthorityResult()
        }
        if (budget.funding?.paused == true) return LinearAuthorityResult(paused = true)
        val result = checkSource(source, cancellation)
        if (cancellation()) {
            lp.close()
            return LinearAuthorityResult()
        }
        return result.copy(relaxedPoint = relaxedPoint,
            paused = result.paused || (result.evidence == null && result.split == null &&
                result.incumbent == null && budget.funding?.paused == true))
    }

    fun advance(source: SourceProofContext, cancellation: Cancellation, cuts: Boolean = false): LinearAuthorityResult {
        relaxedPoint = emptyMap()
        primalStages.clear()
        if (cancellation()) {
            lp.close()
            return LinearAuthorityResult()
        }
        if (budget.funding?.paused == true) return LinearAuthorityResult(paused = true)
        val result = checkSource(source, cancellation, guidance = true, cuts = cuts)
        if (cancellation()) {
            lp.close()
            return LinearAuthorityResult()
        }
        return result.copy(relaxedPoint = relaxedPoint, primalStages = primalStages.toMap(),
            paused = result.paused || (result.evidence == null && result.split == null &&
                result.incumbent == null && budget.funding?.paused == true))
    }

    private var relaxedPoint: Map<SourceCoordinate, Double> = emptyMap()
    private val primalStages = linkedMapOf<String, Long>()

    private fun observePrimal(stage: String, count: Long = 1L) {
        val previous = primalStages[stage] ?: 0L
        primalStages[stage] = if ("Max" in stage) maxOf(previous, count) else previous + count
    }

    private fun recover(
        prepared: LinearAuthorityQuery, point: List<BigFraction>, cancellation: Cancellation,
    ): List<BigFraction>? {
        val attempt = primalAttempt?.takeIf { it.query === prepared && it.point == point }
            ?: PrimalAttempt(prepared, point).also { primalAttempt = it }
        if (attempt.finished) {
            observePrimal("PrimalRecoveryReused")
            return attempt.recovered
        }
        val recovered = lp.recover(prepared.coordinates, point, prepared.rows, prepared.inactive,
            cancellation, ::observePrimal, attempt.progress)
        if (!cancellation()) {
            attempt.recovered = recovered
            attempt.finished = recovered != null || budget.funding?.paused != true
        }
        return recovered
    }

    private fun prepareQuery(
        source: SourceProofContext, cancellation: Cancellation, cuts: Boolean,
    ): LinearAuthorityQuery? {
        if (!source.affineObjectiveAvailable || !source.linearCoordinatesSupported()) return null
        if ((source.scope.condition.lower.keys + source.scope.condition.upper.keys).any {
                it !in source.coordinates
            } || source.scope.condition.bools.keys.any {
                SourceCoordinate(SourceCoordinate.Kind.Boolean, it) !in source.coordinates
            }
        ) return null
        if (source.scope.objective == null || source.coordinates.any {
                it.kind == SourceCoordinate.Kind.Boolean && it.id !in source.scope.condition.bools
            }
        ) return null
        if (budget.funding != null) {
            val condition = source.scope.condition
            var work = source.coordinates.size.toLong() + condition.bools.size + condition.lower.size +
                condition.upper.size
            try {
                for (premise in source.premises.values) {
                    if (cancellation()) return null
                    work += 1L + premise.inequality.coefficients.size + premise.guards.size + premise.integerPins.size
                }
            } finally {
                budget.observe(preparation = work)
            }
        }
        val prepared = if (retainLp || cuts) {
            template?.takeIf { it.matches(source) && (it.cuts != null) == cuts }
                ?: LinearAuthorityTemplate.prepare(source, cancellation, cuts) { work ->
                    cutPreparationWork += work
                    budget.observe(preparation = work)
                }?.also {
                    template = it
                    retainedCutPeak = maxOf(retainedCutPeak, it.cuts?.rows?.size?.toLong() ?: 0L)
                } ?: return null
        } else null
        return LinearAuthorityQuery.prepare(source, prepared, cancellation) { work ->
            cutVerificationWork += work
            budget.observe(boundCertification = work)
        }?.also { activeCutPeak = maxOf(activeCutPeak, it.activeCuts.toLong()) }
    }

    private fun checkSource(
        source: SourceProofContext, cancellation: Cancellation, guidance: Boolean = false, cuts: Boolean = false,
    ): LinearAuthorityResult {
        val cached = if (retainLp || guidance) query?.takeIf { it.matches(source, cuts) } else null
        val prepared = cached ?: prepareQuery(source, cancellation, cuts)?.also {
            if (retainLp || guidance) query = it
        } ?: return LinearAuthorityResult()
        val coordinates = prepared.coordinates
        val context = prepared.context
        val cited = prepared.cited
        val rows = prepared.rows
        val inactive = prepared.inactive
        val costs = prepared.costs
        val constant = prepared.constant
        val sign = prepared.sign
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
            if (guidance) return null
            val zeros = List(coordinates.size) { BigFraction.ZERO }
            val weights = combination(zeros, BigFraction.MINUS_ONE, false)
                ?: combination(zeros, BigFraction.ZERO, true) ?: return null
            return checked(SourceClaim.Refutation, LinearPinnedRefutation(derivation(weights)))
        }
        val closureRows = prepared.closureRows
        val guide = if (guidance) lp.advance(closureRows, costs, constant, source.scope, cancellation, inactive,
            prepared.unconditionalRows)
            ?: return LinearAuthorityResult(paused = budget.funding?.paused == true) else null
        if (guide?.paused == true) return LinearAuthorityResult(paused = true)
        guide?.coordinateConflictWeights?.let { weights ->
            val evidence = checked(SourceClaim.Refutation, LinearPinnedRefutation(derivation(weights)))
            return LinearAuthorityResult(evidence = evidence, coordinateConflict = evidence != null)
        }
        val solved = guide?.result ?: lp.solve(closureRows, costs, constant, cancellation, inactive)
            ?: return LinearAuthorityResult(paused = budget.funding?.paused == true)
        val closure = solved.result
        if (closure.verdict == LpVerdict.INFEASIBLE) {
            val evidence = refutation(solved)
            if (evidence != null) return LinearAuthorityResult(evidence = evidence, lpConflict = true)
            return if (guide != null && budget.admitsDimensions(costs.size, guide.workingRows.toLong())) {
                checkSource(source, cancellation)
            } else LinearAuthorityResult()
        }
        guide?.point?.let { point ->
            val projected = point.map { it.toDouble() }
            if (projected.all(Double::isFinite)) {
                relaxedPoint = coordinates.indices.associate { coordinates[it] to projected[it] }
            }
        }
        if (guide == null) closure.float?.primal?.takeIf {
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
            var point = guide.point
            val fractionalCoordinates = fractional(coordinates, point)
            observePrimal(when {
                point == null -> "PrimalUnavailable"
                fractionalCoordinates.isNotEmpty() -> "PrimalFractional"
                else -> "PrimalIntegral"
            })
            val fractions = fractionalCoordinates.filter { candidate ->
                val threshold = candidate.floorSplit().second
                val lower = source.scope.condition.lower[candidate.first]
                val upper = source.scope.condition.upper[candidate.first]
                (lower == null || threshold + BigFraction.ONE > lower) &&
                    (upper == null || threshold < upper)
            }
            fun witness(values: List<BigFraction>, recovered: Boolean): Sample? {
                val proposed = sample(source, coordinates, values)
                val evidence = SourceEvidence(source.scope, SourceClaim.Witness(proposed),
                    SourceDerivation.SourceWitness)
                val stage = if (recovered) "PrimalRecoverySource" else "PrimalSource"
                return when (SourceEvidenceVerifier(context).verify(evidence, cancellation)) {
                    SourceEvidenceCheck.Checked -> proposed
                    is SourceEvidenceCheck.Rejected -> {
                        observePrimal("${stage}Rejected")
                        null
                    }
                    is SourceEvidenceCheck.Unavailable -> {
                        observePrimal("${stage}Unavailable")
                        null
                    }
                }
            }
            var candidate = point?.takeIf { fractionalCoordinates.isEmpty() }?.let { witness(it, false) }
            if (candidate != null) observePrimal("PrimalSourceChecked")
            if (candidate == null && point != null && fractionalCoordinates.isEmpty()) {
                val recovered = recover(prepared, point, cancellation)
                candidate = recovered?.let { witness(it, true) }
                if (candidate != null) {
                    point = recovered
                    observePrimal("PrimalRecoverySourceChecked")
                }
            }
            if (candidate != null && bound != null) {
                val accepted = checkNotNull(point)
                val value = (accepted.indices.fold(constant) { value, id -> value + costs[id] * accepted[id] }) * sign
                val proof = checked(SourceClaim.Optimal(candidate, SourceValue.Rational(value)),
                    SourceDerivation.Optimum(SourceDerivation.SourceWitness, bound.derivation))
                if (proof != null) return LinearAuthorityResult(proof, bound, candidate)
            }
            if (fractions.isNotEmpty()) return LinearAuthorityResult(bound = bound, incumbent = candidate,
                split = fractions.first().floorSplit(), fractional = fractions)
            if (budget.admitsDimensions(costs.size, guide.workingRows.toLong())) {
                observePrimal("PrimalExactFallback")
                val exact = checkSource(source, cancellation, cuts = cuts)
                return exact.copy(bound = exact.bound ?: bound, incumbent = exact.incumbent ?: candidate)
            }
            if (candidate == null) observePrimal("PrimalExactDimension")
            return LinearAuthorityResult(bound = bound, incumbent = candidate)
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
