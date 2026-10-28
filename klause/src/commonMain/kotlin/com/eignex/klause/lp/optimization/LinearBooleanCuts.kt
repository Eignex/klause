package com.eignex.klause.lp.optimization

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceDerivation
import com.eignex.klause.solver.evidence.SourceEvidence
import com.eignex.klause.solver.evidence.SourceInequality
import com.eignex.klause.solver.evidence.SourcePremise
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.magnitudeBitLength

internal data class LinearBooleanCutLimits(
    val rows: Int = 32,
    val booleans: Int = 4,
    val support: Int = 8,
    val scalarBits: Int = 128,
    val candidates: Int = 16,
    val branchRows: Int = 128,
    val work: Long = 65_536L,
    val allocation: Long = 1_048_576L,
) {
    init {
        require(rows in 1..256 && booleans in 1..16 && support in 1..128 && scalarBits in 1..4096 &&
            candidates in 1..64 && branchRows in 1..1024 && work >= 0L && allocation >= 0L)
    }
}

internal class LinearBooleanCuts(
    val consequences: List<SourceEvidence>, val complete: Boolean, val reason: String,
)

internal object LinearBooleanCutPreparation {
    private data class Row(val id: String, val premise: SourcePremise)
    private data class Derived(val row: SourceInequality, val proof: SourceDerivation)
    private class Stop(val reason: String) : RuntimeException()
    private fun stop(reason: String): Nothing = throw Stop(reason)

    fun prepare(
        source: SourceProofContext, cancellation: Cancellation,
        onCost: (Long, Long) -> Unit = { _, _ -> },
        limits: LinearBooleanCutLimits = LinearBooleanCutLimits(),
    ): LinearBooleanCuts {
        var work = 0L
        var allocation = 0L
        val retained = ArrayList<SourceEvidence>()
        fun reserve(steps: Long, bytes: Long = 0L) {
            if (cancellation()) stop("stopped")
            if (steps > limits.work - work) stop("work")
            if (bytes > limits.allocation - allocation) stop("allocation")
            work += steps
            allocation += bytes
            onCost(steps, bytes)
            if (cancellation()) stop("stopped")
        }
        fun number(value: BigFraction): BigFraction {
            reserve(1L, 64L + (limits.scalarBits + 7L) / 4L)
            if (value.num.magnitudeBitLength() > limits.scalarBits ||
                value.den.magnitudeBitLength() > limits.scalarBits) stop("width")
            return value
        }
        fun derive(terms: List<Pair<Row, BigFraction>>): Derived {
            val coefficients = linkedMapOf<SourceCoordinate, BigFraction>()
            var rhs = BigFraction.ZERO
            var strict = false
            for ((entry, weight) in terms) {
                reserve(1L, 128L + entry.id.length * 2L)
                val row = entry.premise.inequality
                for ((id, value) in row.coefficients) {
                    val product = number(weight * number(value))
                    coefficients[id] = number((coefficients[id] ?: BigFraction.ZERO) + product)
                }
                rhs = number(rhs + number(weight * number(row.rhs)))
                strict = strict || row.strict
            }
            val nonzero = coefficients.filterValues { !it.isZero }
            if (nonzero.size > limits.support) stop("support")
            val first = nonzero.entries.minWithOrNull(compareBy({ it.key.kind.ordinal }, { it.key.id }))?.value
            val scale = if (first == null) BigFraction.ONE else
                number((if (first < BigFraction.ZERO) first.negated() else first).reciprocal())
            reserve(1L + nonzero.size, 128L + nonzero.size * 64L)
            val row = SourceInequality(nonzero.mapValues { (_, value) -> number(value * scale) },
                number(rhs * scale), strict)
            val proof = SourceDerivation.Combination(terms.map { (entry, weight) ->
                SourceDerivation.Combination.Term(number(weight * scale), SourceDerivation.Premise(entry.id))
            })
            return Derived(row, proof)
        }
        fun stronger(left: SourceInequality, right: SourceInequality): Boolean =
            left.rhs < right.rhs || (left.rhs == right.rhs && (left.strict || !right.strict))
        try {
            reserve(source.coordinateCount.toLong() + source.premiseCount,
                source.coordinateCount * 48L + source.premiseCount * 64L)
            val coordinates = source.coordinates
            val pinned = source.scope.condition.bools
            reserve(pinned.size.toLong(), pinned.size * 48L)
            val rows = ArrayList<Row>()
            val booleans = linkedSetOf<Int>()
            for ((id, premise) in source.premises) {
                reserve(1L, 64L)
                val row = premise.inequality
                if (row.supportSize > limits.support || premise.guards.size > 1 ||
                    premise.integerPins.isNotEmpty()) continue
                reserve(row.supportSize.toLong(), row.supportSize * 48L)
                if (row.coefficients.keys.any { it.kind != SourceCoordinate.Kind.Real || it !in coordinates }) {
                    continue
                }
                if (rows.size >= limits.rows) stop("rows")
                for (bool in premise.guards.keys) {
                    if (SourceCoordinate(SourceCoordinate.Kind.Boolean, bool) !in coordinates) stop("typing")
                    if (bool !in pinned) booleans += bool
                    if (booleans.size > limits.booleans) stop("booleans")
                }
                rows += Row(id, premise)
            }
            val unconditional = rows.filter { it.premise.guards.isEmpty() }
                .map { derive(listOf(it to BigFraction.ONE)) }
            fun branch(bool: Int, value: Boolean): Map<Map<SourceCoordinate, BigFraction>, Derived> {
                val available = rows.filter { it.premise.guards.isEmpty() || it.premise.guards == mapOf(bool to value) }
                val derived = linkedMapOf<Map<SourceCoordinate, BigFraction>, Derived>()
                fun add(terms: List<Pair<Row, BigFraction>>) {
                    if (terms.none { bool in it.first.premise.guards }) return
                    val candidate = derive(terms)
                    val key = candidate.row.coefficients
                    val previous = derived[key]
                    if (previous != null && stronger(previous.row, candidate.row)) return
                    if (previous == null && derived.size >= limits.branchRows) stop("branch rows")
                    reserve(1L, 128L + key.size * 48L)
                    derived[key] = candidate
                }
                for ((index, left) in available.withIndex()) {
                    add(listOf(left to BigFraction.ONE))
                    for (rightIndex in index + 1 until available.size) {
                        val right = available[rightIndex]
                        for ((a, b) in listOf(1L to 1L, 1L to 2L, 2L to 1L)) {
                            reserve(1L, 64L)
                            add(listOf(left to BigFraction.ofLong(a), right to BigFraction.ofLong(b)))
                        }
                    }
                }
                return derived
            }
            for (bool in booleans) {
                val whenFalse = branch(bool, false)
                val whenTrue = branch(bool, true)
                for ((key, left) in whenFalse) {
                    reserve(1L)
                    val right = whenTrue[key] ?: continue
                    val rhs = if (left.row.rhs > right.row.rhs) left.row.rhs else right.row.rhs
                    val strict = (left.row.rhs < rhs || left.row.strict) && (right.row.rhs < rhs || right.row.strict)
                    val common = SourceInequality(key, rhs, strict)
                    if (unconditional.any { it.row.coefficients == key && stronger(it.row, common) } ||
                        retained.any { (it.claim as SourceClaim.Consequence).inequality == common }) continue
                    if (retained.size >= limits.candidates) stop("candidates")
                    reserve(1L + key.size, 256L + key.size * 64L)
                    retained += SourceEvidence(source.scope, SourceClaim.Consequence(common),
                        SourceDerivation.BooleanSplit(bool, left.proof, right.proof))
                }
            }
            return LinearBooleanCuts(retained.toList(), true, "complete")
        } catch (stop: Stop) {
            return LinearBooleanCuts(retained.toList(), false, stop.reason)
        }
    }
}
