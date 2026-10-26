package com.eignex.klause.solver.pipeline

import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.optimization.LinearPinnedRefutation
import com.eignex.klause.lp.optimization.atScope
import com.eignex.klause.lp.optimization.withLinearAuthority
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceCondition
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceDerivation
import com.eignex.klause.solver.evidence.SourceEvidence
import com.eignex.klause.solver.evidence.SourceEvidenceCheck
import com.eignex.klause.solver.evidence.SourceEvidenceVerifier
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.magnitudeBitLength

internal class LinearRefutationReuse(
    private val source: SourceProofContext,
    private val budget: SourceLpBudget,
    private val cancellation: Cancellation,
    private val maximumEntries: Int = 256,
    private val maximumBytes: Long = 1_048_576L,
) {
    private data class Side(val coordinate: SourceCoordinate, val upper: Boolean)
    private class Entry(val evidence: SourceEvidence, val anchor: Side?, val bytes: Long, val restrictions: Long)
    private class Support {
        val assumptions = linkedSetOf<Side>()
        var nodes = 0
        var bytes = 256L
    }
    private val entries = linkedMapOf<Long, Entry>()
    private val indexed = hashMapOf<Side, MutableSet<Long>>()
    private val unconditional = linkedSetOf<Long>()
    private var nextId = 0L
    private var retainedBytes = 0L
    private var peakBytes = 0L
    private var allocated = 0L
    private var work = 0L
    private var verificationWork = 0L
    private var verificationAllocation = 0L
    private var proofPolls = 0
    private val counts = linkedMapOf<String, Long>()
    private val checker by lazy { source.withLinearAuthority() }
    private var sourceShape: Pair<Long, Long>? = null
    private var sourceWidthDeclined = false

    init { require(maximumEntries >= 0 && maximumBytes >= 0L) }

    fun observations(): Map<String, Long> = counts.mapKeys { "numericalRefutationReuse${it.key}" } + mapOf(
        "numericalRefutationReuseRetained" to entries.size.toLong(),
        "numericalRefutationReuseBytes" to retainedBytes,
        "numericalRefutationReusePeakBytes" to peakBytes,
        "numericalRefutationReuseAllocation" to allocated,
        "numericalRefutationReuseWork" to work,
        "numericalRefutationReuseVerificationWork" to verificationWork,
        "numericalRefutationReuseVerificationAllocation" to verificationAllocation,
    )

    private fun observe(name: String) { counts[name] = (counts[name] ?: 0L) + 1L }

    private fun charge(units: Long, bytes: Long = 0L) {
        budget.observe(conflictCertification = units)
        budget.funding?.charge(0L, bytes)
        work += units
        allocated += bytes
    }

    private fun spend(units: Long, bytes: Long = 0L): Boolean {
        if (cancellation() || budget.funding?.paused == true) return false
        charge(units, bytes)
        return !cancellation() && budget.funding?.paused != true
    }

    private fun scalar(value: BigFraction, support: Support): Boolean {
        val bits = value.num.magnitudeBitLength().toLong() + value.den.magnitudeBitLength()
        if (!admitted(value) || !spend(1L)) return false
        support.bytes += 64L + (bits + 7L) / 8L
        return support.bytes <= maximumBytes
    }

    private fun admitted(value: BigFraction): Boolean =
        value.num.magnitudeBitLength() <= 4096 && value.den.magnitudeBitLength() <= 4096

    private fun support(proof: SourceDerivation, captured: Support, depth: Int = 0): Boolean {
        if (depth >= 64 || ++captured.nodes > 4096 || !spend(1L, 32L)) return false
        captured.bytes += 64L
        if (captured.bytes > maximumBytes) return false
        return when (proof) {
            is LinearPinnedRefutation -> support(proof.conflict, captured, depth + 1)
            is SourceDerivation.Premise -> {
                captured.bytes += proof.id.length * 2L
                captured.bytes <= maximumBytes
            }
            is SourceDerivation.Assumption -> {
                captured.assumptions += Side(proof.coordinate, proof.upper)
                true
            }
            is SourceDerivation.Combination -> {
                val terms = proof.terms
                spend(terms.size.toLong(), terms.size * 8L) && terms.all {
                    scalar(it.weight, captured) && support(it.derivation, captured, depth + 1)
                }
            }
            is SourceDerivation.IntegerRounding -> support(proof.inequality, captured, depth + 1)
            is SourceDerivation.MixedIntegerRounding -> support(proof.inequality, captured, depth + 1) &&
                proof.bounds.all { support(it, captured, depth + 1) }
            else -> false
        }
    }

    private fun shape(): Pair<Long, Long>? {
        sourceShape?.let { return it }
        if (sourceWidthDeclined) return null
        val metadataEntries = source.coordinateCount.toLong() + source.premiseCount
        if (!spend(1L + source.premiseCount, 256L + 48L * metadataEntries)) return null
        var units = 1L + source.coordinateCount
        var bytes = 256L + 48L * source.coordinateCount
        for (premise in source.premises.values) {
            val entries = 1L + premise.inequality.supportSize + premise.guardCount + premise.integerPinCount
            val rowBytes = 128L + 96L * entries
            if (!spend(entries - 1L, rowBytes)) return null
            if (!(premise.inequality.coefficients.values + premise.inequality.rhs +
                    premise.integerPins.values).all(::admitted)) {
                sourceWidthDeclined = true
                observe("WidthDeclines")
                return null
            }
            units += entries
            bytes += rowBytes
        }
        if (!(source.objective.values + source.objectiveConstant).all(::admitted)) {
            sourceWidthDeclined = true
            observe("WidthDeclines")
            return null
        }
        return (units to bytes).also { sourceShape = it }
    }

    private fun verify(evidence: SourceEvidence): SourceEvidenceCheck {
        val before = work
        val beforeAllocation = allocated
        try {
            val shape = shape() ?: return SourceEvidenceCheck.Unavailable("refutation reuse preparation stopped")
            // Pinned replay reconstructs the numeric source; those temporary rows spend the containing allocation.
            if (!spend(shape.first, shape.second)) {
                return SourceEvidenceCheck.Unavailable("refutation reuse verification stopped")
            }
            observe("Verifications")
            return SourceEvidenceVerifier(checker.atScope(evidence.scope)).verify(evidence, Cancellation {
                ++proofPolls > 100_000 || !spend(1L)
            })
        } finally {
            verificationWork += work - before
            verificationAllocation += allocated - beforeAllocation
        }
    }

    fun retain(evidence: SourceEvidence) {
        if (maximumEntries == 0 || maximumBytes == 0L || evidence.claim != SourceClaim.Refutation ||
            evidence.scope.revision != source.scope.revision || evidence.scope.objective != source.scope.objective ||
            cancellation() || budget.funding?.paused == true
        ) return
        val condition = evidence.scope.condition
        if (!spend(1L + 3L * source.coordinateCount, 192L * source.coordinateCount) ||
            !condition.implies(source.scope.condition)) return
        val bools = condition.bools
        if (source.coordinates.any { it.kind == SourceCoordinate.Kind.Boolean && it.id !in bools }) return
        proofPolls = 0
        val captured = Support()
        if (!support(evidence.derivation, captured)) {
            observe("Unsupported")
            return
        }
        val lower = condition.lower
        val upper = condition.upper
        if (!(lower.values + upper.values).all(::admitted)) {
            observe("WidthDeclines")
            return
        }
        val required = source.scope.condition
        val reduced = SourceCondition(bools,
            required.lower + lower.filterKeys { Side(it, false) in captured.assumptions },
            required.upper + upper.filterKeys { Side(it, true) in captured.assumptions })
        var retained = SourceEvidence(evidence.scope.copy(condition = reduced), SourceClaim.Refutation,
            evidence.derivation)
        observe("ReductionAttempts")
        when (verify(retained)) {
            SourceEvidenceCheck.Checked -> if (reduced != condition) observe("ReducedScopes")
            is SourceEvidenceCheck.Rejected -> {
                observe("ReductionRejected")
                if (reduced == condition || verify(evidence) != SourceEvidenceCheck.Checked) return
                retained = evidence
            }
            is SourceEvidenceCheck.Unavailable -> {
                observe("Unavailable")
                return
            }
        }
        val retainedCondition = retained.scope.condition
        val lowers = retainedCondition.lower
        val uppers = retainedCondition.upper
        val sides = lowers.keys.map { Side(it, false) } + uppers.keys.map { Side(it, true) }
        val bytes = captured.bytes + 64L * (sides.size + retainedCondition.bools.size) +
            (lowers.values + uppers.values).sumOf {
                64L + (it.num.magnitudeBitLength().toLong() + it.den.magnitudeBitLength() + 7L) / 8L
            }
        if (bytes > maximumBytes || !spend(1L + sides.size, bytes)) return
        while (entries.size >= maximumEntries || bytes > maximumBytes - retainedBytes) evict()
        val anchor = sides.minWithOrNull(compareBy({ it.coordinate.kind.ordinal }, { it.coordinate.id }, { it.upper }))
        val id = nextId++
        entries[id] = Entry(retained, anchor, bytes, (sides.size + retainedCondition.bools.size).toLong())
        if (anchor == null) unconditional += id else indexed.getOrPut(anchor) { linkedSetOf() }.add(id)
        retainedBytes += bytes
        peakBytes = maxOf(peakBytes, retainedBytes)
        observe("RetainedProofs")
    }

    fun find(condition: SourceCondition): SourceEvidence? {
        if (entries.isEmpty() || cancellation() || budget.funding?.paused == true) return null
        proofPolls = 0
        observe("Lookups")
        val lower = condition.lower
        val upper = condition.upper
        if (!spend(1L + lower.size + upper.size, 48L * (lower.size + upper.size))) return null
        if (!(lower.values + upper.values).all(::admitted)) {
            observe("WidthDeclines")
            return null
        }
        val buckets = sequenceOf(unconditional.asSequence()) +
            lower.keys.asSequence().map { indexed[Side(it, false)].orEmpty().asSequence() } +
            upper.keys.asSequence().map { indexed[Side(it, true)].orEmpty().asSequence() }
        for (id in buckets.flatten()) {
            val entry = entries.getValue(id)
            if (!spend(1L + entry.restrictions)) return null
            observe("Candidates")
            if (!condition.implies(entry.evidence.scope.condition)) continue
            val imported = SourceEvidence(source.scope.copy(condition = condition), SourceClaim.Refutation,
                SourceDerivation.Import(entry.evidence))
            when (verify(imported)) {
                SourceEvidenceCheck.Checked -> {
                    if (cancellation() || budget.funding?.paused == true) return null
                    observe("Hits")
                    return imported
                }
                is SourceEvidenceCheck.Rejected -> observe("Rejected")
                is SourceEvidenceCheck.Unavailable -> {
                    observe("Unavailable")
                    if (cancellation() || budget.funding?.paused == true || proofPolls > 100_000) return null
                }
            }
        }
        return null
    }

    private fun evict() {
        val first = entries.entries.first()
        val id = first.key
        val entry = first.value
        entries.remove(id)
        val bucket = entry.anchor?.let { indexed.getValue(it) } ?: unconditional
        bucket.remove(id)
        if (bucket.isEmpty() && entry.anchor != null) indexed.remove(entry.anchor)
        retainedBytes -= entry.bytes
        charge(1L)
        observe("Evictions")
    }

    fun clear() { while (entries.isNotEmpty()) evict() }
}
