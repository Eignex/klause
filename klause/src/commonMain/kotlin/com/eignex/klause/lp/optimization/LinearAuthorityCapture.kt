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

// The fields a feedback consumer reads from a completed query; Full is the inspection archive's shape.
internal data class LinearAuthorityFeedbackRequest(
    val point: Boolean = false,
    val cuts: Cuts = Cuts.None,
    val refutation: Boolean = false,
) {
    // Installed keeps installed, checked cuts with active owners and evaluates their violations on demand.
    enum class Cuts { None, Installed, All }

    operator fun plus(other: LinearAuthorityFeedbackRequest) = LinearAuthorityFeedbackRequest(
        point || other.point, maxOf(cuts, other.cuts), refutation || other.refutation)

    fun covers(other: LinearAuthorityFeedbackRequest): Boolean =
        (point || !other.point) && cuts >= other.cuts && (refutation || !other.refutation)

    companion object {
        val Full = LinearAuthorityFeedbackRequest(point = true, cuts = Cuts.All, refutation = true)
    }
}

internal sealed interface LinearAuthorityCutViolation {
    data class Available(val value: BigFraction) : LinearAuthorityCutViolation
    data class Unavailable(val reason: Reason) : LinearAuthorityCutViolation
    enum class Reason { Unrequested, NoPoint, Uncovered, Released, Oversized, Limit, Cancelled }
}

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
    val projection: LinearAuthorityFeedbackRequest = LinearAuthorityFeedbackRequest.Full,
)

// Source values and derivations are immutable; numerical owners, queries and their buffers never escape.
// The archive keeps a bounded inspection prefix. Delivery holds only the latest completed query under a per-query
// allowance, so a full archive never withholds later queries from a runtime consumer. Archived and delivered
// records share one construction, which the cumulative constructed ledger counts once; eviction never lowers it.
// A delivery-only record holds just the requested projection; a field outside it is withheld, never computed.
// Installed-cut violations are evaluated on demand against the delivered query's remaining allowance and the same
// ledger, and only while that query is still delivered.
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
    var constructedEntries: Long = 0L
        private set
    var constructedBytes: Long = 0L
        private set
    private var limitReached = false
    private var archiveClosed = false
    val limited: Boolean get() = limitReached || attempted > retained.size
    private val archiving: Boolean get() = retained.size < maximumRecords && entries < maximumEntries &&
        bytes < maximumBytes
    val accepting: Boolean get() = (archiving || delivering) && !stop()
    var attempted: Long = 0L
        private set
    private var recordedAttempt = 0L
    private var delivering = false
    private var deliveryEntries = 0L
    private var deliveryBytes = 0L
    private var deliveryRequest = LinearAuthorityFeedbackRequest()
    var delivered: LinearAuthorityCapturedQuery? = null
        private set
    private var deliveredBase: LinearAuthorityCapturedQuery? = null
    var deliveryOverflowed: Boolean = false
        private set
    var evaluatedCuts: Long = 0L
        private set
    var evaluatedTerms: Long = 0L
        private set
    private var inProgress = false
    private var archive = true
    private var live = false
    private var liveEntries = 0L
    private var liveBytes = 0L
    private var pointWithheld = false

    fun restrict(records: Int, entries: Long, bytes: Long) {
        require(records >= 0 && entries >= 0 && bytes >= 0)
        maximumRecords = minOf(maximumRecords, records)
        maximumEntries = minOf(maximumEntries, entries)
        maximumBytes = minOf(maximumBytes, bytes)
    }

    fun deliver(
        entries: Long,
        bytes: Long,
        request: LinearAuthorityFeedbackRequest = LinearAuthorityFeedbackRequest.Full,
    ) {
        require(entries > 0 && bytes > 0)
        deliveryEntries = if (delivering) minOf(deliveryEntries, entries) else entries
        deliveryBytes = if (delivering) minOf(deliveryBytes, bytes) else bytes
        deliveryRequest = if (delivering) deliveryRequest + request else request
        delivering = true
    }

    fun stopDelivery() {
        delivering = false
        delivered = null
        deliveredBase = null
        deliveryOverflowed = false
        deliveryRequest = LinearAuthorityFeedbackRequest()
        live = false
    }

    // Drops an attempt whose query never completed, so its partial point spends no later query's allowance.
    fun discard() {
        inProgress = false
    }

    private fun begin() {
        if (inProgress) return
        inProgress = true
        // The archive is a prefix: once a record or allowance fills it, later queries are constructed for delivery
        // alone and never start as archive copies.
        archive = retained.size < maximumRecords && !archiveClosed
        live = delivering
        liveEntries = 0L
        liveBytes = 0L
        deliveryOverflowed = false
        pointWithheld = false
        // The next query's allowance restarts here, so the previous delivery can no longer be evaluated against it.
        deliveredBase = null
    }

    private fun spend(count: Long, allocation: Long): Boolean {
        if (stop()) {
            limitReached = true
            archive = false
            live = false
            return false
        }
        if (archive && (count > maximumEntries - entries || allocation > maximumBytes - bytes)) {
            limitReached = true
            archiveClosed = true
            archive = false
        }
        if (live && (count > deliveryEntries - liveEntries || allocation > deliveryBytes - liveBytes)) {
            deliveryOverflowed = true
            live = false
        }
        if (!archive && !live) return false
        if (archive) {
            entries += count
            bytes += allocation
        }
        if (live) {
            liveEntries += count
            liveBytes += allocation
        }
        constructedEntries += count
        constructedBytes += allocation
        return true
    }

    private fun scalar(value: BigFraction): Boolean = spend(1L,
        64L + (value.num.magnitudeBitLength().toLong() + value.den.magnitudeBitLength() + 7L) / 8L)

    private fun row(value: SourceInequality): Boolean = spend(1L + value.supportSize, 96L + value.supportSize * 48L) &&
        scalar(value.rhs) &&
        value.coefficients.all { (_, coefficient) -> spend(1L, 48L) && scalar(coefficient) }

    fun point(coordinates: List<SourceCoordinate>, values: List<BigFraction>): Map<SourceCoordinate, BigFraction>? {
        begin()
        if (!archive && !deliveryRequest.point) {
            pointWithheld = values.size == coordinates.size
            return null
        }
        val archiving = archive
        val closed = archiveClosed
        if (values.size != coordinates.size || !spend(values.size.toLong(), values.size * 48L) ||
            !values.all(::scalar)) {
            // An archived record omits a point it cannot afford; delivery reports the oversized query instead.
            archive = archiving
            archiveClosed = closed
            return null
        }
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
            is SourceDerivation.BooleanSplit -> proof(value.whenFalse, depth + 1) && proof(value.whenTrue, depth + 1)
            is SourceDerivation.Import -> {
                val claim = value.evidence.claim as? SourceClaim.Consequence ?: return false
                val condition = value.evidence.scope.condition
                spend(1L + condition.bools.size, 128L + condition.bools.size * 48L) &&
                    (condition.lower.values + condition.upper.values).all(::scalar) && row(claim.inequality) &&
                    proof(value.evidence.derivation, depth + 1)
            }
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
        if (result.paused) {
            inProgress = false
            return
        }
        begin()
        inProgress = false
        attempted++
        delivered = null
        deliveredBase = null
        if (archive && retained.size >= maximumRecords) {
            limitReached = true
            archive = false
        }
        // Once the archive stops, a delivered record holds references to the immutable source rows, cut
        // definitions and cited derivations its authority prepared; it spends only on references and owned values.
        val shared = !archive && live
        val projection = if (shared) deliveryRequest else LinearAuthorityFeedbackRequest.Full
        val full = projection == LinearAuthorityFeedbackRequest.Full
        if (!spend(1L, 256L)) return
        val open = source.coordinates.any {
            it.kind == SourceCoordinate.Kind.Boolean && it.id !in source.scope.condition.bools
        }
        val numeric = numericSource ?: template?.numeric ?: source
        if (shared) {
            // A requested record holds the source context, scope, query lists and point map whole; it copies none.
            val references = if (!full) 8L else 1L + source.premises.size + numeric.coordinates.size +
                numeric.objective.size + (if (numeric !== source) numeric.premises.size else 0)
            if (!spend(references, references * 16L)) return
        } else {
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
        }
        val condition = source.scope.condition
        if (full && (!spend(condition.bools.size.toLong(), condition.bools.size * 48L) ||
            !(condition.lower.values + condition.upper.values).all(::scalar))) return
        val values = point?.takeIf { projection.point }
        // The point map was charged when it was captured.
        if (full && values != null && !values.values.all(::scalar)) return
        val cited = if (full) query?.cited.orEmpty() else emptyList()
        if (shared) {
            if (!spend(cited.size.toLong(), cited.size * 16L)) return
        } else for ((inequality, derivation) in cited) {
            if (!row(inequality) || !proof(derivation)) return
        }
        val candidates = ArrayList<LinearAuthorityCapturedCut>()
        val definitions = template?.cuts?.rows.orEmpty()
        val ids = when (projection.cuts) {
            LinearAuthorityFeedbackRequest.Cuts.All -> definitions.indices.toList()
            LinearAuthorityFeedbackRequest.Cuts.Installed -> if (query == null) emptyList() else
                query.installedCuts.filter { it in definitions.indices && it in query.checkedCuts }.sorted()
            LinearAuthorityFeedbackRequest.Cuts.None -> emptyList()
        }
        for (id in ids) {
            val cut = definitions[id]
            val ownerActive = query != null && cut.owners.none { it in query.inactive }
            if (projection.cuts == LinearAuthorityFeedbackRequest.Cuts.Installed && !ownerActive) continue
            val extraOwners = (cut.owners.size - 1).toLong()
            if (!spend(1L + extraOwners, 128L + extraOwners * 32L)) return
            if (!shared && (!row(cut.inequality) || !proof(cut.derivation))) return
            var violation: BigFraction? = null
            if (projection.cuts == LinearAuthorityFeedbackRequest.Cuts.All && values != null &&
                cut.inequality.coefficients.keys.all { it in values }) {
                violation = violation(values, cut.inequality) ?: return
            }
            candidates += LinearAuthorityCapturedCut(id, cut.owner, cut.inequality, cut.derivation,
                query != null && id in query.installedCuts, ownerActive,
                query != null && id in query.checkedCuts, violation, cut.owners)
        }
        val refutation = result.evidence?.takeIf { it.claim == SourceClaim.Refutation }
        val retainedRefutation = refutation?.takeIf { projection.refutation }
        for (evidence in listOfNotNull(result.bound, retainedRefutation)) {
            if (if (full) !proof(evidence.derivation) else !spend(1L, 16L)) return
        }
        val captured = LinearAuthorityCapturedQuery((attempted - 1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            source, numeric.atScope(source.scope),
            query?.coordinates.orEmpty().let { if (full) it.toList() else it },
            cited.toList(), query?.inactive.orEmpty().let { if (full) it.toSet() else it },
            if (full) values?.toMap() else values, candidates.toList(),
            if (full) template?.cuts?.observations.orEmpty().toMap() else emptyMap(), result.bound,
            retainedRefutation, when {
                refutation != null -> "checked-refutation"
                open && query != null -> "active-no-refutation"
                open -> "open-booleans"
                query == null -> "query-unavailable"
                point == null && !pointWithheld -> "point-unavailable"
                result.bound == null -> "bound-unavailable"
                else -> "completed"
            }, projection = projection)
        if (archive) {
            retained += captured
            recordedAttempt = attempted
        }
        if (live) {
            constructedEntries++
            constructedBytes += 64L
            delivered = captured
            deliveredBase = captured
        }
    }

    fun link(scope: SourceScope, parent: SourceScope?, sibling: SourceEvidence?, incumbent: BigFraction?) {
        val archived = retained.lastOrNull()?.takeIf { recordedAttempt == attempted && it.context.scope == scope }
        val current = delivered?.takeIf { it.context.scope == scope }
        val record = archived ?: current ?: return
        // Linking extends the completed query's allowances; a refused delivery keeps the unlinked record.
        archive = archived != null
        live = current != null
        val overflowed = deliveryOverflowed
        try {
            if (record.projection != LinearAuthorityFeedbackRequest.Full) {
                // A requested record references the sibling bound and incumbent its traversal already holds.
                if (!spend(2L, 32L)) return
            } else {
                if (sibling != null && !proof(sibling.derivation)) return
                if (incumbent != null && !scalar(incumbent)) return
            }
        } finally {
            deliveryOverflowed = overflowed
        }
        val value = ((record.bound?.claim as? SourceClaim.Bound)?.value as? SourceValue.Rational)?.value
        val disposition = when {
            value == null -> "bound-unavailable"
            incumbent == null -> "incumbent-absent"
            if (scope.objective?.sense == ObjectiveSense.MINIMIZE) value >= incumbent else value <= incumbent ->
                "incumbent-dominating"
            else -> "weak-bound"
        }
        val linked = record.copy(parent = parent, siblingBound = sibling, incumbent = incumbent,
            parentQuery = retained.lastOrNull { it.context.scope == parent }?.id, boundDisposition = disposition)
        if (archive && archived != null) retained[retained.lastIndex] = linked
        if (live && current != null) delivered = linked
    }

    fun violation(query: LinearAuthorityCapturedQuery, cut: LinearAuthorityCapturedCut): LinearAuthorityCutViolation {
        val values = query.point
        fun missing(reason: LinearAuthorityCutViolation.Reason) = LinearAuthorityCutViolation.Unavailable(reason)
        when (query.projection.cuts) {
            LinearAuthorityFeedbackRequest.Cuts.None -> return missing(LinearAuthorityCutViolation.Reason.Unrequested)
            LinearAuthorityFeedbackRequest.Cuts.All -> return cut.violation?.let {
                LinearAuthorityCutViolation.Available(it)
            } ?: missing(if (values == null) LinearAuthorityCutViolation.Reason.NoPoint
                else LinearAuthorityCutViolation.Reason.Uncovered)
            LinearAuthorityFeedbackRequest.Cuts.Installed -> Unit
        }
        val base = deliveredBase
        if (inProgress || base == null || (query !== base && query !== delivered)) {
            return missing(LinearAuthorityCutViolation.Reason.Released)
        }
        if (stop()) return missing(LinearAuthorityCutViolation.Reason.Cancelled)
        if (values == null) return missing(LinearAuthorityCutViolation.Reason.NoPoint)
        if (!cut.inequality.coefficients.keys.all { it in values }) {
            return missing(LinearAuthorityCutViolation.Reason.Uncovered)
        }
        // Evaluation extends only the delivered query's own allowance; the archive never holds its result.
        val archiving = archive
        val delivering = live
        val overflowed = deliveryOverflowed
        val limited = limitReached
        archive = false
        live = true
        try {
            violation(values, cut.inequality)?.let { return LinearAuthorityCutViolation.Available(it) }
            return missing(when {
                stop() -> LinearAuthorityCutViolation.Reason.Cancelled
                !live -> LinearAuthorityCutViolation.Reason.Oversized
                else -> LinearAuthorityCutViolation.Reason.Limit
            })
        } finally {
            archive = archiving
            live = delivering
            deliveryOverflowed = overflowed
            limitReached = limited
        }
    }

    // Null when an operand exceeds the exact arithmetic limits or the remaining allowance.
    private fun violation(values: Map<SourceCoordinate, BigFraction>, inequality: SourceInequality): BigFraction? {
        evaluatedCuts++
        var activity = BigFraction.ZERO
        for ((coordinate, coefficient) in inequality.coefficients) {
            val value = values.getValue(coordinate)
            if (coefficient.num.magnitudeBitLength() + value.num.magnitudeBitLength() > 4096 ||
                coefficient.den.magnitudeBitLength() + value.den.magnitudeBitLength() > 4096) return null
            evaluatedTerms++
            val product = coefficient * value
            if (!scalar(product) || !canAdd(activity, product)) return null
            activity += product
            if (!scalar(activity)) return null
        }
        if (!canAdd(activity, inequality.rhs)) return null
        return (activity - inequality.rhs).takeIf(::scalar)
    }

    private fun canAdd(left: BigFraction, right: BigFraction): Boolean =
        left.num.magnitudeBitLength() + right.den.magnitudeBitLength() < 4096 &&
            left.den.magnitudeBitLength() + right.num.magnitudeBitLength() < 4096 &&
            left.den.magnitudeBitLength() + right.den.magnitudeBitLength() < 4096
}
