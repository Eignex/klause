package com.eignex.klause.lp.optimization

import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.SparseSourceLpPolicy
import com.eignex.klause.lp.engine.LpClaim
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.IntegerPinStatus
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceCondition
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceDerivation
import com.eignex.klause.solver.evidence.SourceEvidence
import com.eignex.klause.solver.evidence.SourceEvidenceCheck
import com.eignex.klause.solver.evidence.SourceEvidenceVerifier
import com.eignex.klause.solver.evidence.SourceInequality
import com.eignex.klause.solver.evidence.SourcePremise
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.integerPinBounds
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.magnitudeBitLength

internal class LinearActiveRefutation(
    val conflict: SourceDerivation, val alternatives: Boolean = false,
) : SourceDerivation.Extension

internal data class LinearActiveResult(
    val evidence: SourceEvidence? = null,
    val attempted: Boolean = false,
    val unchanged: Boolean = false,
    val paused: Boolean = false,
    val captureDeclined: Boolean = false,
)

internal class LinearActiveRows(
    private val source: SourceProofContext,
    private val budget: SourceLpBudget,
    policy: SparseSourceLpPolicy = SparseSourceLpPolicy(),
    private val composeBranches: Boolean = false,
) : AutoCloseable {
    private data class State(
        val rows: Map<String, SourceInequality>,
        val lower: Map<SourceCoordinate, BigFraction>,
        val upper: Map<SourceCoordinate, BigFraction>,
        val compositionScope: SourceCondition?,
    )
    private val lp = LinearAuthorityLp(budget, retain = true, guidancePolicy = policy)
    private var state: State? = null
    private var pending: LinearAuthorityQuery? = null
    private var finished = false
    private var ownerOpen = false
    private val compositionObservations = linkedMapOf<String, Long>()
    val observations: Map<String, Long> get() = compositionObservations.toMap()

    fun check(
        condition: SourceCondition, cancellation: Cancellation, begin: () -> Boolean = { true },
        capture: LinearAuthorityCapture? = null,
    ): LinearActiveResult {
        if (cancellation()) {
            invalidate()
            return LinearActiveResult()
        }
        if (budget.funding?.paused == true) return LinearActiveResult(paused = true)
        val branch = source.atScope(source.scope.copy(condition = condition))
        val resumed = pending?.takeIf { it.context.scope == branch.scope }
        val query = resumed ?: run {
            if (pending != null) invalidate()
            val numeric = branch.activeNumericContext(cancellation,
                maximumWork = budget.funding?.availableWork ?: 100_000L) { work, allocation ->
                budget.observe(preparation = work)
                budget.funding?.charge(0L, allocation)
            } ?: return LinearActiveResult(paused = budget.funding?.paused == true,
                captureDeclined = budget.funding?.paused != true && !cancellation())
            if (budget.funding?.paused == true) return LinearActiveResult(paused = true)
            val next = State(numeric.premises.mapValues { it.value.inequality }, condition.lower, condition.upper,
                if (composeBranches) condition else null)
            if (next == state && finished) return LinearActiveResult(unchanged = true)
            if (!begin()) return LinearActiveResult()
            state = next
            finished = false
            val consequences = if (composeBranches) {
                val funded = Cancellation { cancellation() || budget.funding?.paused == true }
                val prepared = LinearBooleanCutPreparation.prepare(branch, funded, { work, allocation ->
                    budget.observe(preparation = work)
                    budget.funding?.charge(0L, allocation)
                }, LinearBooleanCutLimits(work = minOf(65_536L, budget.funding?.availableWork ?: 65_536L),
                    allocation = minOf(1_048_576L, budget.funding?.remainingAllocation ?: 1_048_576L)))
                val status = if (prepared.complete) "numericalBooleanCutComplete" else "numericalBooleanCutLimited"
                compositionObservations[status] = (compositionObservations[status] ?: 0L) + 1L
                val proofAllocation = branch.coordinateCount * 48L + branch.premiseCount * 64L
                if (proofAllocation > (budget.funding?.remainingAllocation ?: Long.MAX_VALUE)) {
                    return LinearActiveResult(attempted = true, captureDeclined = true)
                }
                budget.funding?.charge(0L, proofAllocation)
                if (funded()) return LinearActiveResult(attempted = true, paused = budget.funding?.paused == true)
                var work = 0L
                val allowance = minOf(100_000L, budget.funding?.availableWork ?: 100_000L)
                val checks = SourceEvidenceVerifier(branch, maximumSteps = 100_000).verifyAll(
                    prepared.consequences, Cancellation {
                        if (work >= allowance || funded() ||
                            (budget.funding?.remainingAllocation ?: Long.MAX_VALUE) < 128L) true else {
                            work++
                            budget.funding?.charge(0L, 128L)
                            funded()
                        }
                    })
                budget.observe(boundCertification = work)
                if (funded()) return LinearActiveResult(attempted = true, paused = budget.funding?.paused == true)
                prepared.consequences.filterIndexed { index, _ -> checks[index] == SourceEvidenceCheck.Checked }.also {
                    compositionObservations["numericalBooleanCutChecked"] =
                        (compositionObservations["numericalBooleanCutChecked"] ?: 0L) + it.size
                }
            } else emptyList()
            LinearAuthorityQuery.prepare(numeric, null, cancellation, consequences = consequences)
                ?.also { pending = it }
                ?: return LinearActiveResult(attempted = true)
        }
        val zero = BigFraction.ZERO
        val costs = List(query.coordinates.size) { zero }
        ownerOpen = true
        val guidance = lp.advance(query.closureRows, costs, zero, branch.scope, cancellation,
            query.inactive, query.unconditionalRows)
        if (cancellation()) {
            invalidate()
            return LinearActiveResult(attempted = resumed == null)
        }
        if (guidance?.paused == true || budget.funding?.paused == true) {
            return LinearActiveResult(attempted = resumed == null, paused = true)
        }
        fun checked(weights: List<BigFraction>?): SourceEvidence? {
            if (weights == null) return null
            if (composeBranches) {
                val allocation = branch.coordinateCount * 128L + branch.premiseCount * 128L
                if (allocation > (budget.funding?.remainingAllocation ?: Long.MAX_VALUE)) return null
                budget.funding?.charge(0L, allocation)
            }
            val proof = SourceDerivation.Combination(weights.mapIndexedNotNull { id, weight ->
                if (weight.isZero) null else SourceDerivation.Combination.Term(weight, query.cited[id].second)
            })
            val evidence = SourceEvidence(branch.scope, SourceClaim.Refutation,
                LinearActiveRefutation(proof, alternatives = composeBranches))
            var work = 0L
            val allowance = budget.funding?.availableWork ?: Long.MAX_VALUE
            val verdict = try {
                SourceEvidenceVerifier(branch.withLinearAuthority()).verify(evidence, Cancellation {
                    if (work >= allowance || (composeBranches && (budget.funding?.paused == true ||
                            (budget.funding?.remainingAllocation ?: Long.MAX_VALUE) < 128L))) true else {
                        work++
                        if (composeBranches) budget.funding?.charge(0L, 128L)
                        cancellation()
                    }
                })
            } finally {
                budget.observe(conflictCertification = work)
            }
            return evidence.takeIf { verdict == SourceEvidenceCheck.Checked && !cancellation() }
        }
        var evidence = checked(guidance?.coordinateConflictWeights ?: guidance?.result?.conflictWeights)
        if (evidence == null && query.rows.any { it.strict } &&
            budget.admitsDimensions(costs.size, query.rows.size.toLong())) {
            evidence = checked(lp.solve(query.rows, costs, zero, cancellation, query.inactive,
                claim = LpClaim.CONTRADICTION)?.conflictWeights)
        }
        if (cancellation()) {
            invalidate()
            return LinearActiveResult(attempted = resumed == null)
        }
        if (budget.funding?.paused == true) return LinearActiveResult(attempted = resumed == null, paused = true)
        pending = null
        finished = evidence == null
        if (evidence != null) state = null
        capture?.record(branch, query, null, null, LinearAuthorityResult(evidence = evidence), query.context)
        return LinearActiveResult(evidence, attempted = resumed == null)
    }

    fun retract() {
        if (pending != null) {
            state = null
            pending = null
            finished = false
        }
        lp.invalidateGuidance()
    }

    fun release() {
        if (pending != null) invalidate()
        if (ownerOpen) {
            lp.close()
            ownerOpen = false
        }
    }

    fun invalidate() {
        state = null
        pending = null
        finished = false
        lp.invalidateGuidance()
    }

    override fun close() {
        state = null
        pending = null
        finished = false
        release()
    }
}

internal fun SourceProofContext.activeNumericContext(
    cancellation: Cancellation,
    maximumWork: Long = 100_000L,
    retainGuardedRows: Boolean = false,
    onPreparation: (Long, Long) -> Unit = { _, _ -> },
): SourceProofContext? {
    require(maximumWork >= 0L)
    val allowance = minOf(100_000L, maximumWork)
    var work = 0L
    var allocation = 0L
    fun visit(size: Int = 1): Boolean {
        allocation += 128L * size
        if (size.toLong() > allowance - work) {
            work = allowance
            return false
        }
        work += size
        return !cancellation()
    }
    fun scalar(value: BigFraction): Boolean {
        val numerator = value.num.magnitudeBitLength()
        val denominator = value.den.magnitudeBitLength()
        allocation += 64L + (numerator.toLong() + denominator + 7L) / 8L
        return numerator <= 4096 && denominator <= 4096
    }
    try {
        val condition = scope.condition
        val booleans = condition.bools
        val lower = condition.lower
        val upper = condition.upper
        val rows = linkedMapOf<String, SourcePremise>()
        val coordinates = this.coordinates
        if (!visit(coordinates.size + booleans.size + lower.size + upper.size)) return null
        if (booleans.keys.any { SourceCoordinate(SourceCoordinate.Kind.Boolean, it) !in coordinates } ||
            (lower.keys + upper.keys).any { it !in coordinates }) return null
        for (bound in lower.values + upper.values) {
            if (!visit() || !scalar(bound)) return null
        }
        if (work >= allowance) return null
        val premises = this.premises
        if (!visit(premises.size)) return null
        for ((id, premise) in premises) {
            if (!visit(1 + premise.guards.size + premise.integerPins.size)) return null
            if (!retainGuardedRows && premise.guards.any { (bool, value) -> booleans[bool] != value }) continue
            if (!scalar(premise.inequality.rhs) || !premise.integerPins.values.all(::scalar)) return null
            val coefficients = linkedMapOf<SourceCoordinate, BigFraction>()
            val guards = premise.guards.toMutableMap()
            var offset = BigFraction.ZERO
            var established = true
            for ((coordinate, coefficient) in premise.inequality.coefficients) {
                if (!visit() || !scalar(coefficient)) return null
                if (coordinate.kind == SourceCoordinate.Kind.Boolean) {
                    val value = booleans[coordinate.id]
                    if (value == null || (coordinate.id in guards && guards[coordinate.id] != value)) {
                        established = false
                    } else {
                        guards[coordinate.id] = value
                        if (value) {
                            offset += coefficient
                            if (!scalar(offset)) return null
                        }
                    }
                } else coefficients[coordinate] = coefficient
            }
            if (established) {
                val rhs = premise.inequality.rhs - offset
                if (!scalar(rhs)) return null
                rows[id] = SourcePremise(SourceInequality(coefficients, rhs, premise.inequality.strict),
                    guards, premise.integerPins)
            }
        }
        if (work >= allowance) return null
        val bounds = if (!retainGuardedRows && rows.values.any { it.integerPins.isNotEmpty() }) {
            SourceProofContext(scope, coordinates, rows, emptyMap(), semantics = semantics)
                .integerPinBounds(condition, Cancellation { !visit() }) ?: return null
        } else null
        val active = linkedMapOf<String, SourcePremise>()
        for ((id, premise) in rows) {
            if (!visit()) return null
            if (retainGuardedRows || premise.integerPins.isEmpty() ||
                bounds?.status(premise.integerPins) == IntegerPinStatus.Established) {
                active[id] = premise
            }
        }
        return if (cancellation()) null else
            SourceProofContext(scope, coordinates, active, emptyMap(), semantics = semantics)
    } finally {
        onPreparation(work, allocation)
    }
}
