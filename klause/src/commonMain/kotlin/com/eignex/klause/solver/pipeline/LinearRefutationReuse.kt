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
import com.eignex.klause.solver.search.LearnedKind
import com.eignex.klause.solver.search.LearnedLifecycle
import com.eignex.klause.solver.search.LearnedStage
import com.eignex.klause.solver.search.resolveSearchClauses
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.magnitudeBitLength

internal class LinearRefutationReuse(
    private val source: SourceProofContext,
    private val budget: SourceLpBudget,
    private val cancellation: Cancellation,
    private val maximumEntries: Int = 256,
    private val maximumBytes: Long = 1_048_576L,
    maximumAllocation: Long = 67_108_864L,
    private val resolve: Boolean = false,
) {
    class UnitExclusion(
        val id: Long,
        val coordinate: SourceCoordinate,
        val threshold: BigFraction,
        val upper: Boolean,
        val evidence: SourceEvidence,
        val support: SourceCondition,
        val resolved: Boolean,
        val unfixedPivot: Boolean,
    )
    private data class Side(val coordinate: SourceCoordinate, val upper: Boolean)
    private data class Predicate(val coordinate: SourceCoordinate, val value: BigFraction, val upper: Boolean)
    private class Entry(val evidence: SourceEvidence, val anchor: Side?, val bytes: Long, val restrictions: Long,
        val clause: IntArray?, val nodes: Int, val depth: Int) {
        var consumed = false
        var asserted = false
    }
    private class Support {
        val assumptions = linkedSetOf<Side>()
        var nodes = 0
        var bytes = 256L
    }
    private val lifecycle = LearnedLifecycle()
    private val entries = linkedMapOf<Long, Entry>()
    private val indexed = hashMapOf<Side, MutableSet<Long>>()
    private val unconditional = linkedSetOf<Long>()
    private val names = hashMapOf<Predicate, Int>()
    private val predicates = hashMapOf<Int, Predicate>()
    private val scopes = hashMapOf<SourceCondition, Long>()
    private var nameBytes = 0L
    private var nextId = 0L
    private var retainedBytes = 0L
    private var peakBytes = 0L
    private var allocated = 0L
    private val allocationLimit = minOf(maximumAllocation,
        budget.funding?.remainingAllocation?.div(8L) ?: maximumAllocation)
    private var retired = false
    private var work = 0L
    private var verificationWork = 0L
    private var verificationAllocation = 0L
    private var proofPolls = 0
    private val counts = linkedMapOf<String, Long>()
    private val checker by lazy { source.withLinearAuthority() }
    private var sourceShape: Pair<Long, Long>? = null
    private var sourceWidthDeclined = false

    init { require(maximumEntries >= 0 && maximumBytes >= 0L && maximumAllocation >= 0L) }

    fun observations(): Map<String, Long> = counts.mapKeys { "numericalRefutationReuse${it.key}" } +
        lifecycle.snapshot() + mapOf(
        "numericalRefutationReuseRetained" to entries.size.toLong(),
        "numericalRefutationReuseBytes" to retainedBytes,
        "numericalRefutationReuseNameBytes" to nameBytes,
        "numericalRefutationReusePeakBytes" to peakBytes,
        "numericalRefutationReuseAllocation" to allocated,
        "numericalRefutationReuseWork" to work,
        "numericalRefutationReuseVerificationWork" to verificationWork,
        "numericalRefutationReuseVerificationAllocation" to verificationAllocation,
    )

    private fun observe(name: String) { counts[name] = (counts[name] ?: 0L) + 1L }

    fun name(coordinate: SourceCoordinate, threshold: BigFraction, positive: Int) {
        if (!resolve) return
        val below = Predicate(coordinate, threshold, true)
        if (below in names) return
        val aboveValue = threshold + BigFraction.ONE
        val bits = threshold.num.magnitudeBitLength().toLong() + threshold.den.magnitudeBitLength() +
            aboveValue.num.magnitudeBitLength() + aboveValue.den.magnitudeBitLength()
        val bytes = 512L + (bits + 7L) / 8L
        if (bytes > maximumBytes / 4L - nameBytes) {
            observe("ResolutionNameStops")
            return
        }
        if (!spend(2L, bytes)) return
        val above = Predicate(coordinate, aboveValue, false)
        names[below] = positive
        names[above] = positive xor 1
        predicates[positive] = below
        predicates[positive xor 1] = above
        nameBytes += bytes
        while (retainedBytes > maximumBytes - nameBytes) evict()
        peakBytes = maxOf(peakBytes, retainedBytes + nameBytes)
    }

    private fun charge(units: Long, bytes: Long = 0L) {
        budget.observe(conflictCertification = units)
        budget.funding?.charge(0L, bytes)
        work += units
        allocated += bytes
    }

    private fun spend(units: Long, bytes: Long = 0L): Boolean {
        if (retired || cancellation() || budget.funding?.paused == true) return false
        if (bytes > allocationLimit - allocated) {
            retired = true
            observe("AllocationStops")
            clear()
            return false
        }
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
        lifecycle.note(LearnedKind.SourceRefutation, LearnedStage.Constructed)
        if (retired || maximumEntries == 0 || maximumBytes == 0L || evidence.claim != SourceClaim.Refutation ||
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
        val entry = store(retained, captured.bytes, captured.nodes, 0) ?: return
        if (resolve && entry.clause != null) resolve(entry)
    }

    private fun store(evidence: SourceEvidence, proofBytes: Long, nodes: Int, depth: Int): Entry? {
        val retainedCondition = evidence.scope.condition
        if (resolve && retainedCondition in scopes) return null
        val lowers = retainedCondition.lower
        val uppers = retainedCondition.upper
        val sides = lowers.keys.map { Side(it, false) } + uppers.keys.map { Side(it, true) }
        val bytes = proofBytes + 64L * (sides.size + retainedCondition.bools.size) +
            (lowers.values + uppers.values).sumOf {
                64L + (it.num.magnitudeBitLength().toLong() + it.den.magnitudeBitLength() + 7L) / 8L
            }
        if (bytes > maximumBytes - nameBytes || !spend(1L + sides.size, bytes)) return null
        val clause = if (resolve) clause(retainedCondition) else null
        if (retired || cancellation() || budget.funding?.paused == true) return null
        while (entries.size >= maximumEntries || bytes > maximumBytes - nameBytes - retainedBytes) evict()
        val anchor = sides.minWithOrNull(compareBy({ it.coordinate.kind.ordinal }, { it.coordinate.id }, { it.upper }))
        val id = nextId++
        val entry = Entry(evidence, anchor, bytes, (sides.size + retainedCondition.bools.size).toLong(),
            clause, nodes, depth)
        entries[id] = entry
        if (resolve) scopes[retainedCondition] = id
        if (anchor == null) unconditional += id else indexed.getOrPut(anchor) { linkedSetOf() }.add(id)
        retainedBytes += bytes
        peakBytes = maxOf(peakBytes, retainedBytes + nameBytes)
        lifecycle.note(LearnedKind.SourceRefutation, LearnedStage.Admitted)
        observe("RetainedProofs")
        return entry
    }

    private fun clause(condition: SourceCondition): IntArray? {
        val opening = source.scope.condition
        val size = condition.bools.size + condition.lower.size + condition.upper.size
        if (size > 4096 || !spend(1L + size, 128L * size)) return null
        val literals = ArrayList<Int>()
        val openingBools = opening.bools
        for ((id, value) in condition.bools) if (openingBools[id] != value) {
            literals += (id shl 1) or if (value) 1 else 0
        }
        for ((upper, bounds) in listOf(false to condition.lower, true to condition.upper)) {
            for ((coordinate, value) in bounds) {
                val required = SourceCondition().bound(coordinate, value, upper)
                if (opening.implies(required)) continue
                val name = names[Predicate(coordinate, value, upper)]
                if (name == null) {
                    observe("ResolutionNameDeclines")
                    return null
                }
                literals += name xor 1
            }
        }
        return literals.toIntArray()
    }

    private fun condition(clause: IntArray): SourceCondition {
        val opening = source.scope.condition
        val bools = opening.bools.toMutableMap()
        val lower = opening.lower.toMutableMap()
        val upper = opening.upper.toMutableMap()
        for (literal in clause) {
            val bound = predicates[literal xor 1]
            if (bound == null) bools[literal ushr 1] = literal and 1 != 0 else {
                val bounds = if (bound.upper) upper else lower
                val previous = bounds[bound.coordinate]
                if (previous == null || if (bound.upper) bound.value < previous else bound.value > previous) {
                    bounds[bound.coordinate] = bound.value
                }
            }
        }
        return SourceCondition(bools, lower, upper)
    }

    private fun resolve(added: Entry) {
        val clause = added.clause ?: return
        if (!spend(entries.size.toLong(), entries.size * 8L)) return
        val candidates = entries.values.toList()
        for (entry in candidates) {
            val other = entry.clause ?: continue
            if (entry === added || entry.depth >= 32 || added.nodes + entry.nodes + 3 > 4096) continue
            for (pivot in clause) {
                if (!spend(1L + other.size)) return
                val bound = predicates[pivot xor 1] ?: continue
                if (pivot xor 1 !in other) continue
                observe("ResolutionCandidates")
                if (!spend(clause.size.toLong() + other.size,
                        256L * (clause.size + other.size + added.restrictions + entry.restrictions))) return
                val resolved = resolveSearchClauses(clause, other, pivot) ?: continue
                val required = condition(resolved)
                if (required in scopes) continue
                val threshold = if (bound.upper) bound.value else bound.value - BigFraction.ONE
                val first = SourceDerivation.Import(added.evidence)
                val second = SourceDerivation.Import(entry.evidence)
                val proof = SourceDerivation.IntegerSplit(bound.coordinate, threshold,
                    if (bound.upper) first else second, if (bound.upper) second else first)
                lifecycle.note(LearnedKind.SourceRefutation, LearnedStage.Constructed)
                val evidence = SourceEvidence(source.scope.copy(condition = required), SourceClaim.Refutation, proof)
                when (verify(evidence)) {
                    SourceEvidenceCheck.Checked -> {
                        if (store(evidence, added.bytes + entry.bytes + 256L,
                                added.nodes + entry.nodes + 3, maxOf(added.depth, entry.depth) + 1) != null) {
                            observe("ResolutionProofs")
                        }
                    }
                    is SourceEvidenceCheck.Rejected -> observe("ResolutionRejected")
                    is SourceEvidenceCheck.Unavailable -> {
                        observe("Unavailable")
                        if (retired || cancellation() || budget.funding?.paused == true || proofPolls > 100_000) return
                    }
                }
            }
        }
    }

    private fun unfixedPivot(entry: Entry, condition: SourceCondition): Boolean? {
        if (entry.depth == 0) return false
        if (!spend(2L + 2L * entry.restrictions, 192L + 96L * entry.restrictions)) return null
        val split = entry.evidence.derivation as? SourceDerivation.IntegerSplit ?: return false
        val lower = condition.lower[split.coordinate]
        val upper = condition.upper[split.coordinate]
        return (upper == null || upper > split.threshold) &&
            (lower == null || lower < split.threshold + BigFraction.ONE)
    }

    fun find(condition: SourceCondition, consume: Boolean = false): SourceEvidence? {
        if (retired || entries.isEmpty() || cancellation() || budget.funding?.paused == true) return null
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
            val unfixed = unfixedPivot(entry, condition) ?: return null
            when (verify(imported)) {
                SourceEvidenceCheck.Checked -> {
                    if (retired || cancellation() || budget.funding?.paused == true) return null
                    asserted(entry)
                    if (consume) consumed(id)
                    observe("Hits")
                    if (entry.depth > 0) observe("ResolutionHits")
                    if (unfixed) observe("ResolutionUnfixedHits")
                    return imported
                }
                is SourceEvidenceCheck.Rejected -> observe("Rejected")
                is SourceEvidenceCheck.Unavailable -> {
                    observe("Unavailable")
                    if (retired || cancellation() || budget.funding?.paused == true || proofPolls > 100_000) return null
                }
            }
        }
        return null
    }

    fun unit(condition: SourceCondition): UnitExclusion? {
        if (retired || entries.isEmpty() || cancellation() || budget.funding?.paused == true) return null
        proofPolls = 0
        observe("UnitLookups")
        val lower = condition.lower
        val upper = condition.upper
        val bools = condition.bools
        if (!spend(1L + lower.size + upper.size + bools.size, 48L * (lower.size + upper.size + bools.size)) ||
            !condition.implies(source.scope.condition) || !(lower.values + upper.values).all(::admitted)) return null
        for ((id, entry) in entries) {
            if (!spend(1L + entry.restrictions, 96L * entry.restrictions)) return null
            val required = entry.evidence.scope.condition
            if (!required.bools.all { (id, value) -> bools[id] == value }) continue
            val missing = required.lower.filter { (id, value) -> lower[id]?.let { it >= value } != true }
                .map { Triple(it.key, it.value, false) } +
                required.upper.filter { (id, value) -> upper[id]?.let { it <= value } != true }
                    .map { Triple(it.key, it.value, true) }
            if (missing.size != 1) continue
            val (coordinate, value, isUpper) = missing.single()
            if (coordinate.kind != SourceCoordinate.Kind.Integer || value.den != BIG_ONE) continue
            val threshold = if (isUpper) value else value - BigFraction.ONE
            val complement = if (isUpper) value + BigFraction.ONE else threshold
            if (!admitted(threshold) || !admitted(complement)) continue
            val opposite = if (isUpper) lower[coordinate] else upper[coordinate]
            if (opposite != null && if (isUpper) opposite >= complement else opposite <= complement) continue
            observe("UnitCandidates")
            val scoped = condition.bound(coordinate, value, isUpper)
            val imported = SourceEvidence(source.scope.copy(condition = scoped), SourceClaim.Refutation,
                SourceDerivation.Import(entry.evidence))
            val unfixed = unfixedPivot(entry, scoped) ?: return null
            when (verify(imported)) {
                SourceEvidenceCheck.Checked -> {
                    if (retired || cancellation() || budget.funding?.paused == true) return null
                    observe("UnitChecks")
                    if (entry.depth > 0) observe("ResolutionUnits")
                    asserted(entry)
                    return UnitExclusion(id, coordinate, threshold, isUpper, imported, required,
                        entry.depth > 0, unfixed)
                }
                is SourceEvidenceCheck.Rejected -> observe("UnitRejected")
                is SourceEvidenceCheck.Unavailable -> {
                    observe("Unavailable")
                    if (retired || cancellation() || budget.funding?.paused == true || proofPolls > 100_000) return null
                }
            }
        }
        return null
    }

    private fun asserted(entry: Entry) {
        if (entry.asserted) return
        entry.asserted = true
        lifecycle.note(LearnedKind.SourceRefutation, LearnedStage.Asserted)
    }

    fun consumed(id: Long) {
        val entry = entries[id] ?: return
        if (entry.consumed) return
        entry.consumed = true
        lifecycle.note(LearnedKind.SourceRefutation, LearnedStage.Consumed)
    }

    private fun evict() {
        val first = entries.entries.first()
        val id = first.key
        val entry = first.value
        entries.remove(id)
        if (resolve) scopes.remove(entry.evidence.scope.condition)
        val bucket = entry.anchor?.let { indexed.getValue(it) } ?: unconditional
        bucket.remove(id)
        if (bucket.isEmpty() && entry.anchor != null) indexed.remove(entry.anchor)
        retainedBytes -= entry.bytes
        charge(1L)
        lifecycle.note(LearnedKind.SourceRefutation, LearnedStage.Retired)
        observe("Evictions")
    }

    fun clear() {
        while (entries.isNotEmpty()) evict()
        names.clear()
        predicates.clear()
        nameBytes = 0L
    }
}
