package com.eignex.klause.lp.optimization

import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.SparseSourceLpPolicy
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

internal class LinearActiveRefutation(val conflict: SourceDerivation) : SourceDerivation.Extension

internal data class LinearActiveResult(
    val evidence: SourceEvidence? = null,
    val attempted: Boolean = false,
    val unchanged: Boolean = false,
    val paused: Boolean = false,
)

internal class LinearActiveRows(
    private val source: SourceProofContext,
    private val budget: SourceLpBudget,
    policy: SparseSourceLpPolicy = SparseSourceLpPolicy(),
) : AutoCloseable {
    private data class State(
        val rows: Map<String, SourceInequality>,
        val lower: Map<SourceCoordinate, BigFraction>,
        val upper: Map<SourceCoordinate, BigFraction>,
    )
    private val lp = LinearAuthorityLp(budget, retain = true, guidancePolicy = policy)
    private var state: State? = null
    private var pending: LinearAuthorityQuery? = null
    private var finished = false

    fun check(
        condition: SourceCondition, cancellation: Cancellation, begin: () -> Boolean = { true },
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
            val numeric = branch.activeNumericContext(cancellation) { work, allocation ->
                budget.observe(preparation = work)
                budget.funding?.charge(0L, allocation)
            } ?: return LinearActiveResult()
            if (budget.funding?.paused == true) return LinearActiveResult(paused = true)
            val next = State(numeric.premises.mapValues { it.value.inequality }, condition.lower, condition.upper)
            if (next == state && finished) return LinearActiveResult(unchanged = true)
            if (!begin()) return LinearActiveResult()
            state = next
            finished = false
            LinearAuthorityQuery.prepare(numeric, null, cancellation)?.also { pending = it }
                ?: return LinearActiveResult(attempted = true)
        }
        val zero = BigFraction.ZERO
        val costs = List(query.coordinates.size) { zero }
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
            val proof = SourceDerivation.Combination(weights.mapIndexedNotNull { id, weight ->
                if (weight.isZero) null else SourceDerivation.Combination.Term(weight, query.cited[id].second)
            })
            val evidence = SourceEvidence(branch.scope, SourceClaim.Refutation, LinearActiveRefutation(proof))
            var work = 0L
            val verdict = try {
                SourceEvidenceVerifier(branch.withLinearAuthority()).verify(evidence, Cancellation {
                    work++
                    cancellation() || budget.funding?.paused == true
                })
            } finally {
                budget.observe(conflictCertification = work)
            }
            return evidence.takeIf { verdict == SourceEvidenceCheck.Checked && !cancellation() }
        }
        var evidence = checked(guidance?.coordinateConflictWeights ?: guidance?.result?.conflictWeights)
        if (evidence == null && query.rows.any { it.strict } &&
            budget.admitsDimensions(costs.size, query.rows.size.toLong())) {
            evidence = checked(lp.solve(query.rows, costs, zero, cancellation, query.inactive)?.conflictWeights)
        }
        if (cancellation()) {
            invalidate()
            return LinearActiveResult(attempted = resumed == null)
        }
        if (budget.funding?.paused == true) return LinearActiveResult(attempted = resumed == null, paused = true)
        pending = null
        finished = true
        return LinearActiveResult(evidence, attempted = resumed == null)
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
        lp.close()
    }
}

internal fun SourceProofContext.activeNumericContext(
    cancellation: Cancellation,
    onPreparation: (Long, Long) -> Unit = { _, _ -> },
): SourceProofContext? {
    var work = 0L
    var allocation = 0L
    fun visit(size: Int = 1): Boolean {
        work += size
        allocation += 128L * size
        return work <= 100_000 && !cancellation()
    }
    try {
        val condition = scope.condition
        val rows = linkedMapOf<String, SourcePremise>()
        val coordinates = coordinates
        if (!visit(coordinates.size + condition.bools.size + condition.lower.size + condition.upper.size)) return null
        for ((id, premise) in premises) {
            if (!visit(1 + premise.guards.size + premise.integerPins.size)) return null
            if (premise.guards.any { (bool, value) -> condition.bools[bool] != value }) continue
            val coefficients = linkedMapOf<SourceCoordinate, BigFraction>()
            var offset = BigFraction.ZERO
            var established = true
            for ((coordinate, coefficient) in premise.inequality.coefficients) {
                if (!visit()) return null
                if (coordinate.kind == SourceCoordinate.Kind.Boolean) {
                    val value = condition.bools[coordinate.id]
                    if (value == null) established = false else if (value) offset += coefficient
                } else coefficients[coordinate] = coefficient
            }
            if (established) rows[id] = SourcePremise(SourceInequality(coefficients,
                premise.inequality.rhs - offset, premise.inequality.strict), premise.guards, premise.integerPins)
        }
        val numeric = SourceProofContext(scope, coordinates, rows, emptyMap(), semantics = semantics)
        val bounds = numeric.integerPinBounds(condition, cancellation) ?: return null
        val active = rows.filterValues { premise ->
            premise.integerPins.isEmpty() || bounds.status(premise.integerPins) == IntegerPinStatus.Established
        }
        return if (cancellation()) null else SourceProofContext(scope, coordinates, active, emptyMap(), semantics = semantics)
    } finally {
        onPreparation(work, allocation)
    }
}
