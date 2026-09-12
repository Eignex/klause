package com.eignex.klause.lp.engine

import com.eignex.klause.util.Cancellation
import kotlin.math.abs

internal enum class CrashBasisDecline {
    CANCELLED,
    RESOURCE_LIMIT,
    NO_STRUCTURAL_BASIS,
    NONZERO_BASIC_COST,
    INVALID_NONBASIC_SEAT,
}

internal data class CrashBasisMetrics(
    val workOps: Long,
    val candidates: Int,
    val selected: Int,
    val passes: Int,
    val decline: CrashBasisDecline? = null,
)

internal class CrashBasisAttempt(val basis: Basis?, val metrics: CrashBasisMetrics)

/**
 * Proposes a deterministic GLPK-style triangular root basis in source-column coordinates.
 *
 * This is only a proposal: [RevisedSimplex] factorizes it, admits repaired headings in full, and
 * checks the actual reduced-cost signs before the dual pass. Selecting only exact zero-cost basics
 * makes the proposed dual vector zero. Every nonbasic seat is therefore chosen from the exact cost
 * sign; a missing required side declines the whole attempt instead of manufacturing a bound.
 */
@Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
internal fun triangularCrashBasis(
    model: LpModel,
    cancellation: Cancellation = Cancellation.Never,
    workLimit: Long = 0L,
): CrashBasisAttempt {
    require(workLimit >= 0L)
    var work = 0L
    var candidates = 0
    var selectedCount = 0
    var passes = 0

    fun result(basis: Basis? = null, decline: CrashBasisDecline? = null) = CrashBasisAttempt(
        basis,
        CrashBasisMetrics(work, candidates, selectedCount, passes, decline),
    )

    fun charge(amount: Long): Boolean {
        work = if (Long.MAX_VALUE - work < amount) Long.MAX_VALUE else work + amount
        return workLimit == 0L || work <= workLimit
    }

    if (cancellation()) return result(decline = CrashBasisDecline.CANCELLED)
    if (model.m == 0 || model.n == 0) return result(decline = CrashBasisDecline.NO_STRUCTURAL_BASIS)

    val supportRows = Array(model.n) { IntArray(0) }
    val supportValues = Array(model.n) { DoubleArray(0) }
    val eligible = BooleanArray(model.n)
    for (column in 0 until model.n) {
        if (cancellation()) return result(decline = CrashBasisDecline.CANCELLED)
        val rows = ArrayList<Int>()
        val values = ArrayList<Double>()
        model.forEachInColumnD(column) { row, value ->
            if (value != 0.0) {
                rows += row
                values += value
            }
        }
        if (!charge(rows.size.toLong() + 1L)) return result(decline = CrashBasisDecline.RESOURCE_LIMIT)
        supportRows[column] = rows.toIntArray()
        supportValues[column] = values.toDoubleArray()
        eligible[column] = model.exactCost(column).isZero && rows.isNotEmpty()
        if (eligible[column]) candidates++
    }

    val uncovered = BooleanArray(model.m) { true }
    val selected = BooleanArray(model.n)
    val headings = IntArray(model.m) { model.slackCol(it) }
    while (true) {
        passes++
        var bestColumn = -1
        var bestRow = -1
        var bestSupport = Int.MAX_VALUE
        var bestRatio = -1.0
        for (column in 0 until model.n) {
            if (!eligible[column] || selected[column]) continue
            if (cancellation()) return result(decline = CrashBasisDecline.CANCELLED)
            val rows = supportRows[column]
            val values = supportValues[column]
            if (!charge(rows.size.toLong() + 1L)) return result(decline = CrashBasisDecline.RESOURCE_LIMIT)
            var remaining = 0
            var pivotRow = -1
            var pivotMagnitude = 0.0
            var largestMagnitude = 0.0
            for (index in rows.indices) {
                val magnitude = abs(values[index])
                if (magnitude > largestMagnitude) largestMagnitude = magnitude
                if (uncovered[rows[index]]) {
                    remaining++
                    pivotRow = rows[index]
                    pivotMagnitude = magnitude
                }
            }
            if (remaining != 1 || largestMagnitude == 0.0 || !largestMagnitude.isFinite()) continue
            val ratio = pivotMagnitude / largestMagnitude
            if (!ratio.isFinite() || ratio < MIN_CRASH_PIVOT_RATIO) continue
            val required = !model.exactCost(model.slackCol(pivotRow)).isZero
            val bestRequired = bestRow >= 0 && !model.exactCost(model.slackCol(bestRow)).isZero
            val better = when {
                bestColumn == -1 -> true
                required != bestRequired -> required
                rows.size != bestSupport -> rows.size < bestSupport
                ratio != bestRatio -> ratio > bestRatio
                else -> column < bestColumn
            }
            if (better) {
                bestColumn = column
                bestRow = pivotRow
                bestSupport = rows.size
                bestRatio = ratio
            }
        }
        if (bestColumn == -1) break
        selected[bestColumn] = true
        uncovered[bestRow] = false
        headings[bestRow] = bestColumn
        selectedCount++
    }
    if (selectedCount == 0) return result(decline = CrashBasisDecline.NO_STRUCTURAL_BASIS)
    if (headings.indices.any { !model.exactCost(headings[it]).isZero }) {
        return result(decline = CrashBasisDecline.NONZERO_BASIC_COST)
    }

    val status = Array(model.numVars) { VarStatus.BASIC }
    val basic = BooleanArray(model.numVars)
    for (heading in headings) basic[heading] = true
    for (column in 0 until model.numVars) {
        if (basic[column]) continue
        if (!charge(1L)) return result(decline = CrashBasisDecline.RESOURCE_LIMIT)
        val sign = model.exactCost(column).signum()
        status[column] = when {
            model.fixed(column) -> VarStatus.FIXED
            sign > 0 && model.hasFiniteLower(column) -> VarStatus.AT_LOWER
            sign < 0 && model.hasFiniteUpper(column) -> VarStatus.AT_UPPER
            sign == 0 && model.hasFiniteLower(column) -> VarStatus.AT_LOWER
            sign == 0 && model.hasFiniteUpper(column) -> VarStatus.AT_UPPER
            sign == 0 -> VarStatus.FREE
            else -> return result(decline = CrashBasisDecline.INVALID_NONBASIC_SEAT)
        }
    }
    for (heading in headings) status[heading] = VarStatus.BASIC
    val captureEligible = status.none { it == VarStatus.FIXED }
    return result(Basis(headings, status, captureEligible))
}

private const val MIN_CRASH_PIVOT_RATIO = 1e-7
