package com.eignex.klause.lp.optimization

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceDerivation
import com.eignex.klause.solver.evidence.SourceEvidence
import com.eignex.klause.solver.evidence.SourceInequality
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.magnitudeBitLength

internal data class LinearAuthorityCapturedCut(
    val id: Int,
    val owner: Int,
    val inequality: SourceInequality,
    val derivation: SourceDerivation,
    val installed: Boolean,
    val ownerActive: Boolean,
    val checked: Boolean,
    val violation: BigFraction?,
    val owners: Set<Int> = setOf(owner),
)

internal data class LinearAuthorityCapturedQuery(
    val id: Int,
    val context: SourceProofContext,
    val numeric: SourceProofContext,
    val coordinates: List<SourceCoordinate>,
    val cited: List<Pair<SourceInequality, SourceDerivation>>,
    val inactive: Set<Int>,
    val point: Map<SourceCoordinate, BigFraction>?,
    val cuts: List<LinearAuthorityCapturedCut>,
    val eligibility: Map<String, Long>,
    val bound: SourceEvidence?,
    val refutation: SourceEvidence?,
    val state: String,
    val parent: SourceScope? = null,
    val siblingBound: SourceEvidence? = null,
    val incumbent: BigFraction? = null,
    val parentQuery: Int? = null,
    val boundDisposition: String = "unknown",
)

// Source values and derivations are immutable; numerical owners, queries and their buffers never escape.
internal class LinearAuthorityCapture(
    private var maximumRecords: Int,
    private var maximumEntries: Long,
    private var maximumBytes: Long,
    private val stop: Cancellation,
) {
    init {
        require(maximumRecords >= 0 && maximumEntries >= 0 && maximumBytes >= 0)
    }
    private val retained = ArrayList<LinearAuthorityCapturedQuery>()
    val records: List<LinearAuthorityCapturedQuery> get() = retained.toList()
    val lastRecord: LinearAuthorityCapturedQuery? get() = retained.lastOrNull()
    var entries: Long = 0L
        private set
    var bytes: Long = 0L
        private set
    private var limitReached = false
    val limited: Boolean get() = limitReached || attempted > retained.size
    val accepting: Boolean get() = retained.size < maximumRecords && entries < maximumEntries &&
        bytes < maximumBytes && !stop()
    var attempted: Long = 0L
        private set
    private var recordedAttempt = 0L

    fun restrict(records: Int, entries: Long, bytes: Long) {
        require(records >= 0 && entries >= 0 && bytes >= 0)
        maximumRecords = minOf(maximumRecords, records)
        maximumEntries = minOf(maximumEntries, entries)
        maximumBytes = minOf(maximumBytes, bytes)
    }

    private fun spend(count: Long, allocation: Long): Boolean {
        if (stop() || count > maximumEntries - entries || allocation > maximumBytes - bytes) {
            limitReached = true
            return false
        }
        entries += count
        bytes += allocation
        return true
    }

    private fun scalar(value: BigFraction): Boolean = spend(1L,
        64L + (value.num.magnitudeBitLength().toLong() + value.den.magnitudeBitLength() + 7L) / 8L)

    private fun row(value: SourceInequality): Boolean = spend(1L + value.supportSize, 96L + value.supportSize * 48L) &&
        scalar(value.rhs) &&
        value.coefficients.all { (_, coefficient) -> spend(1L, 48L) && scalar(coefficient) }

    fun point(coordinates: List<SourceCoordinate>, values: List<BigFraction>): Map<SourceCoordinate, BigFraction>? {
        if (values.size != coordinates.size || !spend(values.size.toLong(), values.size * 48L) ||
            !values.all(::scalar)) return null
        return coordinates.indices.associate { coordinates[it] to values[it] }
    }

    private fun proof(value: SourceDerivation, depth: Int = 0): Boolean {
        if (depth >= 64 || !spend(1L, 64L)) return false
        return when (value) {
            is SourceDerivation.Premise -> spend(0L, value.id.length * 2L)
            is SourceDerivation.Assumption -> true
            is SourceDerivation.Combination -> value.terms.all {
                scalar(it.weight) && proof(it.derivation, depth + 1)
            }
            is SourceDerivation.IntegerRounding -> proof(value.inequality, depth + 1)
            is SourceDerivation.MixedIntegerRounding -> proof(value.inequality, depth + 1) &&
                value.bounds.all { proof(it, depth + 1) }
            is LinearPinnedObjective -> proof(value.bound, depth + 1)
            is LinearPinnedRefutation -> proof(value.conflict, depth + 1)
            is LinearActiveRefutation -> proof(value.conflict, depth + 1)
            else -> false
        }
    }

    fun record(
        source: SourceProofContext,
        query: LinearAuthorityQuery?,
        template: LinearAuthorityTemplate?,
        point: Map<SourceCoordinate, BigFraction>?,
        result: LinearAuthorityResult,
        numericSource: SourceProofContext? = null,
    ) {
        if (result.paused) return
        attempted++
        if (retained.size >= maximumRecords || !spend(1L, 256L)) {
            limitReached = true
            return
        }
        val open = source.coordinates.any {
            it.kind == SourceCoordinate.Kind.Boolean && it.id !in source.scope.condition.bools
        }
        val numeric = numericSource ?: template?.numeric ?: source
        for ((id, premise) in source.premises) {
            if (!spend(1L, 96L + id.length * 2L) || !row(premise.inequality) ||
                !spend(premise.guards.size.toLong(), premise.guards.size * 48L) ||
                !premise.integerPins.values.all(::scalar)) return
        }
        if (numeric !== source) for (premise in numeric.premises.values) {
            if (!row(premise.inequality)) return
        }
        if (!spend(numeric.coordinates.size.toLong(), numeric.coordinates.size * 48L) ||
            !numeric.objective.values.all(::scalar) || !scalar(numeric.objectiveConstant)) return
        val condition = source.scope.condition
        if (!spend(condition.bools.size.toLong(), condition.bools.size * 48L) ||
            !(condition.lower.values + condition.upper.values).all(::scalar)) return
        if (point != null && !point.values.all(::scalar)) return
        val cited = query?.cited.orEmpty()
        for ((inequality, derivation) in cited) {
            if (!row(inequality) || !proof(derivation)) return
        }
        val candidates = ArrayList<LinearAuthorityCapturedCut>()
        val definitions = template?.cuts?.rows.orEmpty()
        for ((id, cut) in definitions.withIndex()) {
            val extraOwners = (cut.owners.size - 1).toLong()
            if (!spend(1L + extraOwners, 128L + extraOwners * 32L) ||
                !row(cut.inequality) || !proof(cut.derivation)) return
            var violation: BigFraction? = null
            if (point != null && cut.inequality.coefficients.keys.all { it in point }) {
                var activity = BigFraction.ZERO
                for ((coordinate, coefficient) in cut.inequality.coefficients) {
                    val value = point.getValue(coordinate)
                    if (coefficient.num.magnitudeBitLength() + value.num.magnitudeBitLength() > 4096 ||
                        coefficient.den.magnitudeBitLength() + value.den.magnitudeBitLength() > 4096) return
                    val product = coefficient * value
                    if (!scalar(product) || !canAdd(activity, product)) return
                    activity += product
                    if (!scalar(activity)) return
                }
                if (!canAdd(activity, cut.inequality.rhs)) return
                violation = activity - cut.inequality.rhs
                if (!scalar(violation)) return
            }
            candidates += LinearAuthorityCapturedCut(id, cut.owner, cut.inequality, cut.derivation,
                query != null && id in query.installedCuts,
                query != null && cut.owners.none { it in query.inactive },
                query != null && id in query.checkedCuts, violation, cut.owners)
        }
        val refutation = result.evidence?.takeIf { it.claim == SourceClaim.Refutation }
        for (evidence in listOfNotNull(result.bound, refutation)) {
            if (!proof(evidence.derivation)) return
        }
        retained += LinearAuthorityCapturedQuery(retained.size, source, numeric.atScope(source.scope),
            query?.coordinates.orEmpty().toList(),
            cited.toList(), query?.inactive.orEmpty().toSet(), point?.toMap(), candidates.toList(),
            template?.cuts?.observations.orEmpty().toMap(), result.bound, refutation, when {
                refutation != null -> "checked-refutation"
                open && query != null -> "active-no-refutation"
                open -> "open-booleans"
                query == null -> "query-unavailable"
                point == null -> "point-unavailable"
                result.bound == null -> "bound-unavailable"
                else -> "completed"
            })
        recordedAttempt = attempted
    }

    fun link(scope: SourceScope, parent: SourceScope?, sibling: SourceEvidence?, incumbent: BigFraction?) {
        if (recordedAttempt != attempted) return
        val record = retained.lastOrNull()?.takeIf { it.context.scope == scope } ?: return
        if (sibling != null && !proof(sibling.derivation)) return
        if (incumbent != null && !scalar(incumbent)) return
        val value = ((record.bound?.claim as? SourceClaim.Bound)?.value as? SourceValue.Rational)?.value
        val disposition = when {
            value == null -> "bound-unavailable"
            incumbent == null -> "incumbent-absent"
            if (scope.objective?.sense == ObjectiveSense.MINIMIZE) value >= incumbent else value <= incumbent ->
                "incumbent-dominating"
            else -> "weak-bound"
        }
        retained[retained.lastIndex] = record.copy(parent = parent, siblingBound = sibling, incumbent = incumbent,
            parentQuery = retained.lastOrNull { it.context.scope == parent }?.id, boundDisposition = disposition)
    }

    private fun canAdd(left: BigFraction, right: BigFraction): Boolean =
        left.num.magnitudeBitLength() + right.den.magnitudeBitLength() < 4096 &&
            left.den.magnitudeBitLength() + right.num.magnitudeBitLength() < 4096 &&
            left.den.magnitudeBitLength() + right.den.magnitudeBitLength() < 4096
}
