package com.eignex.klause.formats.smtlib

import com.eignex.klause.lp.engine.ExactLpBounds
import com.eignex.klause.lp.engine.ExactLpColumn
import com.eignex.klause.lp.engine.ExactLpEntry
import com.eignex.klause.lp.engine.ExactLpModel
import com.eignex.klause.lp.engine.ExactLpNumber
import com.eignex.klause.lp.engine.ExactLpObjective
import com.eignex.klause.lp.engine.ExactLpPremises
import com.eignex.klause.lp.engine.ExactLpRow
import com.eignex.klause.lp.engine.ExactLpSide
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.theory.qflra.ExactLpSourceColumn
import com.eignex.klause.theory.qflra.ExactLpSourceNumber
import com.eignex.klause.theory.qflra.ExactLpSourceRow
import com.eignex.klause.theory.qflra.QfLraRelaxation
import com.ionspin.kotlin.bignum.integer.BigInteger

/**
 * An exact rational linear combination over integer and LP-only real variables — the folded form of
 * a real-sorted SMT term. Coefficients stay [BigFraction]s until a row is emitted, where the least
 * common denominator scales them to exact integers.
 */
internal class RealComb(
    intCoeffs: Map<Int, BigFraction>,
    realCoeffs: Map<Int, BigFraction>,
    val constant: BigFraction,
) {
    val intCoeffs: Map<Int, BigFraction> = intCoeffs.toMap()
    val realCoeffs: Map<Int, BigFraction> = realCoeffs.toMap()

    fun plus(other: RealComb): RealComb = RealComb(
        mergeCoeffs(intCoeffs, other.intCoeffs),
        mergeCoeffs(realCoeffs, other.realCoeffs),
        constant + other.constant,
    )

    fun scaled(k: BigFraction): RealComb = RealComb(
        intCoeffs.mapValues { it.value * k },
        realCoeffs.mapValues { it.value * k },
        constant * k,
    )

    val isConstant: Boolean get() = intCoeffs.isEmpty() && realCoeffs.isEmpty()

    private companion object {
        fun mergeCoeffs(a: Map<Int, BigFraction>, b: Map<Int, BigFraction>): Map<Int, BigFraction> {
            if (b.isEmpty()) return a
            val out = HashMap(a)
            for ((v, c) in b) {
                val merged = (out[v]?.plus(c)) ?: c
                if (merged.isZero) out.remove(v) else out[v] = merged
            }
            return out
        }
    }
}

// Project the exact theory source declaration without crossing through Double.
internal fun QfLraRelaxation.toExactLpModel(): ExactLpModel = exactLpModel(sourceColumns, sourceRows)

internal fun exactLpModel(sourceColumns: List<ExactLpSourceColumn>, sourceRows: List<ExactLpSourceRow>): ExactLpModel {
    val zero = ExactLpNumber.of(0L)
    val columns = sourceColumns.map { column ->
        ExactLpColumn(
            ExactLpBounds(
                column.lower?.toExactLpNumber()?.let(::ExactLpSide),
                column.upper?.toExactLpNumber()?.let(::ExactLpSide),
            ),
            column.origin.toExactLpNumber(),
            column.integral,
            column.tag,
        )
    }.toMutableList()
    val matrix = List(sourceColumns.size) { ArrayList<ExactLpEntry>() }
    val rhs = ArrayList<ExactLpNumber>(sourceRows.size)
    val rows = ArrayList<ExactLpRow>(sourceRows.size)
    for ((rowIndex, sourceRow) in sourceRows.withIndex()) {
        val inequality = sourceRow.inequality
        var bound = inequality.rhs
        for (entry in inequality.columns.indices) {
            val column = inequality.columns[entry]
            val coefficient = inequality.coefficients[entry]
            bound -= coefficient * sourceColumns[column].origin.value
            if (!coefficient.isZero) {
                matrix[column].add(ExactLpEntry(rowIndex, ExactLpNumber.of(coefficient)))
            }
        }
        val premises = sourceRow.premiseLiterals.takeIf { it.isNotEmpty() }
            ?.let { ExactLpPremises(emptyList(), it) }
        rows.add(
            ExactLpRow(
                global = premises == null,
                strict = inequality.strict,
                premises = premises,
            ),
        )
        rhs.add(ExactLpNumber.of(bound))
        val integralSlack = inequality.rhs.den == BigInteger.ONE &&
            inequality.columns.indices.all { entry ->
                sourceColumns[inequality.columns[entry]].integral &&
                    inequality.coefficients[entry].den == BigInteger.ONE
            }
        columns.add(ExactLpColumn(ExactLpBounds(lower = ExactLpSide(zero)), integral = integralSlack))
    }
    return ExactLpModel(
        matrix,
        rhs,
        columns,
        rows,
        ExactLpObjective(List(columns.size) { zero }),
    )
}

private fun ExactLpSourceNumber.toExactLpNumber(): ExactLpNumber = ieeeBits?.let {
    ExactLpNumber.ofIeee(Double.fromBits(it))
} ?: ExactLpNumber.of(value)

/**
 * Sum of [combs], with every element after the first negated when [negateTail] (the n-ary `-` fold).
 * One mutable accumulator instead of a per-operand map copy, so a wide sum costs linear work in its
 * operand count — the exact-rational twin of `sumIntCombs`.
 */
internal fun sumRealCombs(combs: List<RealComb>, negateTail: Boolean = false): RealComb {
    val ints = HashMap<Int, BigFraction>()
    val reals = HashMap<Int, BigFraction>()
    var constant = BigFraction.ZERO
    for (idx in combs.indices) {
        val c = combs[idx]
        val neg = negateTail && idx > 0
        accumulate(ints, c.intCoeffs, neg)
        accumulate(reals, c.realCoeffs, neg)
        constant += if (neg) c.constant * BigFraction.MINUS_ONE else c.constant
    }
    return RealComb(ints, reals, constant)
}

private fun accumulate(into: HashMap<Int, BigFraction>, from: Map<Int, BigFraction>, negate: Boolean) {
    for ((v, c) in from) {
        val add = if (negate) c * BigFraction.MINUS_ONE else c
        val merged = into[v]?.plus(add) ?: add
        if (merged.isZero) into.remove(v) else into[v] = merged
    }
}
