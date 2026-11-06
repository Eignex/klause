package com.eignex.klause.lp.optimization

import com.eignex.klause.lp.engine.CutFamily
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceDerivation
import com.eignex.klause.solver.evidence.SourceInequality
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceRoundingLimit
import com.eignex.klause.solver.evidence.mixedIntegerRounding
import com.eignex.klause.util.Cancellation
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.BIG_ZERO
import com.eignex.util.numbers.abs
import com.eignex.util.numbers.div
import com.eignex.util.numbers.gcd
import com.eignex.util.numbers.isZero
import com.eignex.util.numbers.magnitudeBitLength
import com.eignex.util.numbers.times
import kotlin.math.abs

internal class LinearAuthorityCut(
    val owner: Int,
    val inequality: SourceInequality,
    val derivation: SourceDerivation,
    val mixed: Boolean = false,
    owners: Set<Int> = setOf(owner),
    val family: CutFamily = if (mixed) CutFamily.SOURCE_MIXED_ROUNDING else CutFamily.SOURCE_ROUNDING,
) {
    val owners: Set<Int> = owners.toSet()

    init { require(owner in this.owners && this.owners.all { it >= 0 }) }
}

internal class LinearAuthorityCuts private constructor(
    val rows: List<LinearAuthorityCut>,
    val work: Long,
    val observations: Map<String, Long>,
    val complementary: Boolean,
    val pointDirected: Boolean,
) {
    fun withAdditional(additional: List<LinearAuthorityCut>): LinearAuthorityCuts =
        if (additional.isEmpty()) this else LinearAuthorityCuts(rows + additional, work, observations,
            complementary, pointDirected)

    fun withoutZeroBoundRows(
        source: SourceProofContext, cancellation: Cancellation, maximumWork: Long = 65_536L,
        onCost: (Long, Long) -> Unit = { _, _ -> },
    ): LinearAuthorityCuts? {
        require(maximumWork in work..65_536L)
        if (cancellation()) return null
        if (rows.isEmpty() || rows.size > 64 || work == maximumWork) return this
        var spent = 0L
        var allocated = 0L
        var indexLimited = false
        var workLimited = false
        var allocationLimited = false
        var omittedRounding = 0L
        var omittedMixed = 0L
        fun charge(amount: Long, bytes: Long = 0L): Boolean {
            if (cancellation()) return false
            if (amount > maximumWork - work - spent) {
                workLimited = true
                return false
            }
            if (bytes > 16_384L - allocated) {
                allocationLimited = true
                return false
            }
            spent += amount
            allocated += bytes
            onCost(amount, bytes)
            return !cancellation()
        }
        fun finish(retained: List<LinearAuthorityCut>, complete: Boolean): LinearAuthorityCuts {
            val rounding = if (complete) omittedRounding else 0L
            val mixed = if (complete) omittedMixed else 0L
            val extra = mapOf(
                "ZeroBoundAdmissionWork" to spent,
                "ZeroBoundAdmissionAllocation" to allocated,
                "ZeroBoundAdmissionLimited" to if (complete) 0L else 1L,
                "ZeroBoundWorkLimited" to if (workLimited) 1L else 0L,
                "ZeroBoundAllocationLimited" to if (allocationLimited) 1L else 0L,
                "ZeroBoundIndexLimited" to if (indexLimited) 1L else 0L,
                "ZeroBoundOmitted" to rounding + mixed,
                "ZeroBoundOmittedSOURCE_ROUNDING" to rounding,
                "ZeroBoundOmittedSOURCE_MIXED_ROUNDING" to mixed,
            )
            return LinearAuthorityCuts(retained, work + spent, observations + extra.mapValues { (key, value) ->
                (observations[key] ?: 0L) + value
            }, complementary, pointDirected)
        }
        if (!charge(32L + observations.size * 2L, 2048L + observations.size * 64L + rows.size * 8L)) {
            return if (cancellation()) null else this
        }
        val zeroSides = HashMap<SourceCoordinate, Int>()
        val sourceComplete = source.allPremises { _, premise ->
            if (!charge(1L)) return@allPremises false
            val row = premise.inequality
            if (row.supportSize != 1 || !row.rhs.isZero || premise.guardCount != 0 ||
                premise.integerPinCount != 0) return@allPremises true
            val id = checkNotNull(row.singleCoordinate())
            if (id.kind == SourceCoordinate.Kind.Boolean) return@allPremises true
            val coefficient = checkNotNull(row.coefficient(id))
            val words = (coefficient.num.magnitudeBitLength().toLong() +
                coefficient.den.magnitudeBitLength() + 63L) / 64L
            if (!charge(1L + words * words)) return@allPremises false
            val previous = zeroSides[id]
            if (previous == null && zeroSides.size == 64) {
                indexLimited = true
                return@allPremises true
            }
            if (!charge(1L, if (previous == null) 96L else 0L)) return@allPremises false
            val side = if (coefficient > BigFraction.ZERO) ZERO_UPPER else ZERO_LOWER
            zeroSides[id] = (previous ?: 0) or side
            true
        }
        if (cancellation()) return null
        if (!sourceComplete) return finish(rows, complete = false)
        val retained = ArrayList<LinearAuthorityCut>(rows.size)
        for (cut in rows) {
            if (!charge(1L, 64L)) return if (cancellation()) null else finish(rows, complete = false)
            val row = cut.inequality
            if (row.strict || (cut.family != CutFamily.SOURCE_ROUNDING &&
                    cut.family != CutFamily.SOURCE_MIXED_ROUNDING)) {
                retained += cut
                continue
            }
            val rhsWords = (row.rhs.num.magnitudeBitLength().toLong() +
                row.rhs.den.magnitudeBitLength() + 63L) / 64L
            if (!charge(1L + rhsWords * rhsWords)) {
                return if (cancellation()) null else finish(rows, complete = false)
            }
            if (row.rhs < BigFraction.ZERO) {
                retained += cut
                continue
            }
            var covered = true
            val implied = row.forEachCoefficient { id, coefficient ->
                val words = (coefficient.num.magnitudeBitLength().toLong() +
                    coefficient.den.magnitudeBitLength() + 63L) / 64L
                if (!charge(1L + words * words)) {
                    covered = false
                    false
                } else {
                    val side = if (coefficient > BigFraction.ZERO) ZERO_UPPER else ZERO_LOWER
                    ((zeroSides[id] ?: 0) and side) != 0
                }
            }
            if (!covered) return if (cancellation()) null else finish(rows, complete = false)
            when {
                !implied -> retained += cut
                cut.family == CutFamily.SOURCE_ROUNDING -> omittedRounding++
                else -> omittedMixed++
            }
        }
        if (cancellation()) return null
        return finish(retained, complete = true)
    }

    companion object {
        private const val ZERO_LOWER = 1
        private const val ZERO_UPPER = 2

        fun prepare(
            source: SourceProofContext, cancellation: Cancellation, complementary: Boolean = false,
            onWork: (Long) -> Unit = {},
        ): LinearAuthorityCuts? = prepareInternal(source, cancellation, complementary, null, 65_536L, onWork)

        fun prepareAtPoint(
            source: SourceProofContext, cancellation: Cancellation, point: Map<SourceCoordinate, Double>,
            maximumWork: Long, onWork: (Long) -> Unit = {},
        ): LinearAuthorityCuts? {
            require(maximumWork in 0L..65_536L)
            return prepareInternal(source, cancellation, true, point, maximumWork, onWork)
        }

        private fun prepareInternal(
            source: SourceProofContext, cancellation: Cancellation, complementary: Boolean,
            point: Map<SourceCoordinate, Double>?, maximumWork: Long, onWork: (Long) -> Unit,
        ): LinearAuthorityCuts? {
            var work = 0L
            try {
                if (cancellation()) return null
                val cuts = ArrayList<LinearAuthorityCut>()
                val observations = linkedMapOf(
                    "MixedRowsVisited" to 0L, "MixedEligibleRows" to 0L, "MixedMissingBounds" to 0L,
                    "MixedIntegralShiftedRhs" to 0L, "MixedRetentionLimited" to 0L, "MixedWidthLimited" to 0L,
                    "BoundScanLimited" to 0L, "RowScanLimited" to 0L,
                )
                fun observe(name: String) {
                    observations[name] = (observations[name] ?: 0L) + 1L
                }
                val bounds = HashMap<Pair<SourceCoordinate, Boolean>, Pair<SourceInequality, SourceDerivation>>()
                for ((id, premise) in source.premises) {
                    if (cancellation()) return null
                    val row = premise.inequality
                    val cost = if (row.supportSize == 1) 2L else 1L
                    if (cost > minOf(32_768L, maximumWork) - work) {
                        observe("BoundScanLimited")
                        break
                    }
                    work += cost
                    if (row.supportSize != 1) continue
                    val (coordinate, coefficient) = row.coefficients.entries.singleOrNull() ?: continue
                    if (coordinate.kind != SourceCoordinate.Kind.Real ||
                        premise.guards.isNotEmpty() || premise.integerPins.isNotEmpty() ||
                        (listOf(coefficient, row.rhs)).any { it.num.magnitudeBitLength() > 4096 ||
                            it.den.magnitudeBitLength() > 4096 }) continue
                    bounds.getOrPut(coordinate to (coefficient > BigFraction.ZERO)) {
                        row to SourceDerivation.Premise(id)
                    }
                }
                var mixedCount = 0
                for ((owner, entry) in source.premises.entries.withIndex()) {
                    if (cancellation()) return null
                    val row = entry.value.inequality
                    val coefficients = row.coefficients
                    val cost = 1L + coefficients.size * 12L
                    if (cost > maximumWork - work) {
                        observe("RowScanLimited")
                        break
                    }
                    work += cost
                    val mixed = coefficients.keys.any { it.kind == SourceCoordinate.Kind.Real }
                    if (coefficients.isEmpty() || coefficients.keys.any { it.kind == SourceCoordinate.Kind.Boolean }) {
                        continue
                    }
                    val values = coefficients.values
                    if ((values + row.rhs).any { it.num.magnitudeBitLength() > 4096 ||
                            it.den.magnitudeBitLength() > 4096 }) continue
                    val integerValues = coefficients.filterKeys { it.kind == SourceCoordinate.Kind.Integer }.values
                    if (integerValues.isEmpty()) continue
                    if (mixed) observe("MixedRowsVisited")
                    var scale = BIG_ONE
                    for (value in integerValues) {
                        if (cancellation()) return null
                        scale = scale / scale.gcd(value.den) * value.den
                        if (scale.magnitudeBitLength() > 4096) break
                    }
                    if (scale.magnitudeBitLength() > 4096) continue
                    var divisor = BIG_ZERO
                    for (value in integerValues) {
                        if (cancellation()) return null
                        divisor = divisor.gcd((value.num * (scale / value.den)).abs())
                    }
                    if (divisor.isZero()) continue
                    val multiplier = BigFraction.of(scale, divisor)
                    val normalized = SourceInequality(coefficients.mapValues { (_, value) -> value * multiplier },
                        row.rhs * multiplier, row.strict)
                    val combination = SourceDerivation.Combination(listOf(
                        SourceDerivation.Combination.Term(multiplier, SourceDerivation.Premise(entry.key))))
                    if (mixed) {
                        val sides = coefficients.filterKeys { it.kind == SourceCoordinate.Kind.Real }
                            .map { (id, value) ->
                                bounds[id to (value > BigFraction.ZERO)] ?: bounds[id to (value < BigFraction.ZERO)]
                            }
                        if (sides.any { it == null }) {
                            observe("MixedMissingBounds")
                            continue
                        }
                        val preferred = sides.filterNotNull()
                        val alternatives = if (complementary) coefficients
                            .filterKeys { it.kind == SourceCoordinate.Kind.Real }.map { (id, value) ->
                                val opposite = bounds[id to (value < BigFraction.ZERO)]
                                val original = bounds[id to (value > BigFraction.ZERO)]
                                val at = point?.get(id)
                                fun distance(bound: Pair<SourceInequality, SourceDerivation>?): Double? {
                                    if (bound == null || at == null || !at.isFinite()) return null
                                    val row = bound.first
                                    val endpoint = row.rhs.toDouble() / row.coefficients.getValue(id).toDouble()
                                    return abs(at - endpoint).takeIf { it.isFinite() }
                                }
                                val originalDistance = distance(original)
                                val oppositeDistance = distance(opposite)
                                if (originalDistance != null && oppositeDistance != null &&
                                    originalDistance <= oppositeDistance) original else opposite ?: original
                            }.filterNotNull() else preferred
                        val choices = if (alternatives == preferred) listOf(preferred)
                            else listOf(preferred, alternatives)
                        var eligible = false
                        for ((choice, selected) in choices.withIndex()) {
                            if (choice > 0) {
                                if (cost > maximumWork - work) {
                                    observe("RowScanLimited")
                                    break
                                }
                                work += cost
                                observe("MixedComplementaryAttempts")
                            }
                            val conclusion = try {
                                mixedIntegerRounding(normalized, selected.map { it.first }, cancellation)
                            } catch (_: SourceRoundingLimit) {
                                observe("MixedWidthLimited")
                                continue
                            }
                            if (cancellation()) return null
                            if (conclusion == null) {
                                observe("MixedIntegralShiftedRhs")
                                continue
                            }
                            if (!eligible) observe("MixedEligibleRows")
                            eligible = true
                            observe("MixedCandidates")
                            if (mixedCount >= 64) {
                                observe("MixedRetentionLimited")
                                continue
                            }
                            if ((conclusion.coefficients.values + conclusion.rhs).any {
                                    it.num.magnitudeBitLength() > 4096 || it.den.magnitudeBitLength() > 4096 }) {
                                observe("MixedWidthLimited")
                                continue
                            }
                            val proof = SourceDerivation.MixedIntegerRounding(combination, selected.map { it.second })
                            cuts += LinearAuthorityCut(owner, conclusion, proof, mixed = true)
                            mixedCount++
                            if (choice > 0) observe("MixedComplementaryRetained")
                        }
                        continue
                    }
                    val scaled = row.rhs * multiplier
                    val rhs = if (row.strict) BigFraction.of(scaled.ceilInteger(), BIG_ONE) - BigFraction.ONE
                        else BigFraction.of(scaled.negated().ceilInteger(), BIG_ONE).negated()
                    if (rhs == scaled && !row.strict) continue
                    val conclusion = SourceInequality(coefficients.mapValues { (_, value) -> value * multiplier }, rhs)
                    val proof = SourceDerivation.IntegerRounding(combination)
                    cuts += LinearAuthorityCut(owner, conclusion, proof)
                }
                return LinearAuthorityCuts(cuts, work, observations, complementary, point != null)
            } finally {
                onWork(work)
            }
        }
    }
}
