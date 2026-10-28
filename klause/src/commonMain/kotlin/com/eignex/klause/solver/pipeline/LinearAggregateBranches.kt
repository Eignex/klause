package com.eignex.klause.solver.pipeline

import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceInequality
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.adjacentIntegerAbove
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.abs
import com.eignex.klause.util.div
import com.eignex.klause.util.gcd
import com.eignex.klause.util.magnitudeBitLength
import com.eignex.klause.util.negate

internal class LinearAggregateBranches(private val budget: SourceLpBudget) {
    var work = 0L
        private set
    var allocation = 0L
        private set
    var proposals = 0L
        private set
    private var attempts = 0

    private fun fits(value: BigFraction): Boolean =
        value.num.magnitudeBitLength() <= 512 && value.den.magnitudeBitLength() <= 512

    private fun minimum(left: BigFraction, right: BigFraction): BigFraction = if (left < right) left else right

    private fun maximum(left: BigFraction, right: BigFraction): BigFraction = if (left > right) left else right

    private fun spend(units: Long, bytes: Long = 0L): Boolean {
        if (units > 16_384L - work || budget.funding?.paused == true ||
            bytes > 1_048_576L - allocation) return false
        work += units
        allocation += bytes
        budget.funding?.charge(units, bytes)
        return budget.funding?.paused != true
    }

    fun propose(source: SourceProofContext, cancellation: Cancellation): SourceInequality? {
        if (cancellation() || attempts >= 32 || source.scope.condition.integerRows.size >= 8) return null
        if (source.premiseCount > 128) {
            spend(1L)
            return null
        }
        val premises = source.premises
        if (!spend(premises.size.toLong(), 64L * premises.size) || premises.size > 128) return null
        val condition = source.scope.condition
        val entries = premises.values.sumOf { it.inequality.supportSize.toLong() }
        if (!spend(entries, 64L * entries)) return null
        val rows = premises.values.filter { premise ->
            val row = premise.inequality
            premise.integerPins.isEmpty() && premise.guards.all { (id, value) -> condition.bools[id] == value } &&
                row.supportSize <= 32 && (row.coefficients.values + row.rhs).all {
                    it.num.magnitudeBitLength() <= 128 && it.den.magnitudeBitLength() <= 128
                }
        }.map { it.inequality }
        for (row in rows) {
            if (cancellation() || !spend(1)) return null
            val coefficients = row.coefficients
            if (row.strict || coefficients.size !in 2..32 ||
                coefficients.keys.any { it.kind != SourceCoordinate.Kind.Integer } ||
                (coefficients.values + row.rhs).any {
                    it.num.magnitudeBitLength() > 128 || it.den.magnitudeBitLength() > 128
                }) continue
            val opposite = coefficients.mapValues { it.value.negated() }
            if (!spend(rows.size.toLong(), 64L * (coefficients.size + rows.sumOf { it.supportSize }))) return null
            val lower = rows.filter { !it.strict && it.coefficients == opposite }.map { it.rhs }.reduceOrNull(::minimum)
                ?.negated() ?: continue
            val shifts = linkedMapOf<SourceCoordinate, BigFraction>()
            for ((id, coefficient) in coefficients) {
                if (cancellation() || !spend(rows.size.toLong())) return null
                val positive = coefficient > BigFraction.ZERO
                var bound = (if (positive) condition.lower else condition.upper)[id]
                if (bound != null && !fits(bound)) break
                for (side in rows) {
                    if (side.supportSize != 1 || side.strict) continue
                    val value = side.coefficients[id] ?: continue
                    if ((value < BigFraction.ZERO) != positive) continue
                    val candidate = side.rhs * value.reciprocal()
                    bound = bound?.let { if (positive) maximum(it, candidate) else minimum(it, candidate) } ?: candidate
                }
                if (bound == null) break
                shifts[id] = bound
            }
            if (shifts.size != coefficients.size) continue
            val magnitude = coefficients.values.map { if (it < BigFraction.ZERO) it.negated() else it }
                .reduce(::maximum)
            for (width in listOf(1L, 2L, 4L, 8L)) {
                if (cancellation() || attempts >= 32 || !spend(32L * coefficients.size, 256L * coefficients.size)) {
                    return null
                }
                attempts++
                val scale = magnitude * BigFraction.ofLong(width).reciprocal()
                val raw = coefficients.mapValues { (_, value) ->
                    val magnitude = if (value < BigFraction.ZERO) value.negated() else value
                    val rounded = (magnitude * scale.reciprocal() + BigFraction.ofLong(2).reciprocal())
                        .negated().ceilInteger().negate()
                    if (value < BigFraction.ZERO) rounded.negate() else rounded
                }
                if (raw.values.any { it == BIG_ZERO }) continue
                val divisor = raw.values.fold(BIG_ZERO) { gcd, value -> gcd.gcd(value.abs()) }
                val form = raw.mapValues { (_, value) -> BigFraction.of(value / divisor, BIG_ONE) }
                var base = BigFraction.ZERO
                var formBase = BigFraction.ZERO
                var minimumRatio: BigFraction? = null
                var maximumRatio = BigFraction.ZERO
                for ((id, value) in coefficients) {
                    if (cancellation() || !spend(8)) return null
                    base += value * shifts.getValue(id)
                    formBase += form.getValue(id) * shifts.getValue(id)
                    if (!fits(base) || !fits(formBase)) return null
                    val ratio = value * form.getValue(id).reciprocal()
                    minimumRatio = minimumRatio?.let { minimum(it, ratio) } ?: ratio
                    maximumRatio = maximum(maximumRatio, ratio)
                }
                val minimum = checkNotNull(minimumRatio)
                if (maximumRatio > minimum * BigFraction.ofLong(5) * BigFraction.ofLong(4).reciprocal()) continue
                val formLower = formBase + maximum(BigFraction.ZERO, lower - base) * maximumRatio.reciprocal()
                val formUpper = formBase + (row.rhs - base) * minimum.reciprocal()
                if (!fits(formLower) || !fits(formUpper)) return null
                if (formLower > formUpper) continue
                val threshold = BigFraction.of(formUpper.negated().ceilInteger().negate(), BIG_ONE)
                if (formLower <= threshold || threshold.num.magnitudeBitLength() > 128) continue
                val proposed = SourceInequality(form, threshold)
                if (proposed in condition.integerRows || proposed.adjacentIntegerAbove() in condition.integerRows) continue
                proposals++
                return proposed
            }
        }
        return null
    }
}
