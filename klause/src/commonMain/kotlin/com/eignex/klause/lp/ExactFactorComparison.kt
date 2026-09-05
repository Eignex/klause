package com.eignex.klause.lp

import com.eignex.klause.factor.arithmetic.ComparisonClause
import com.eignex.klause.factor.arithmetic.FactorRow
import com.eignex.klause.factor.arithmetic.complemented
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.ionspin.kotlin.bignum.integer.BigInteger

/** Exact values of the mixed columns an [ExactComparison] states its terms over. */
internal interface ExactColumnValues {
    /** The value at mixed column [column]. */
    fun at(column: Int): BigFraction
}

/**
 * One witness read as the branch its rows are taken in: the column values, and the Boolean assignment
 * that says which conditional rows hold.
 */
internal interface ExactWitness : ExactColumnValues {
    /** Truth of Boolean variable [boolVar] at this witness. */
    fun truth(boolVar: Int): Boolean
}

/**
 * One comparison in exact rationals over the mixed column space: `[0, realColumns)` are the continuous
 * columns and `realColumns + v` is integer column `v`.
 *
 * What a factor states and what an exact lane asserts are different things: an `=` is two rows, a `≥` is
 * a negated `≤`, and a `≠` is one row only once a consumer has said which side of it holds. Reading a
 * factor's width and complementing its operator therefore happen here, once, and [rowsInto] states the
 * rows that follow.
 */
internal class ExactComparison(
    /** Coefficient per mixed column; a column absent from it carries none. */
    val terms: Map<Int, BigFraction>,
    /** The right-hand side [terms] is compared against. */
    val bound: BigFraction,
    /** The comparison itself. */
    val op: LinearOp,
    /** Whether the comparison is strict, which a row read against its own statement also is. */
    val strict: Boolean,
    /** Whether a continuous column carries a term, which decides how a disequality tightens. */
    val hasReals: Boolean,
) {

    /** The weighted sum at [values]. */
    fun activityAt(values: ExactColumnValues): BigFraction {
        var total = BigFraction.ZERO
        for ((column, coefficient) in terms) total += coefficient * values.at(column)
        return total
    }

    /**
     * The side of a disequality [values] holds it on.
     *
     * A `≠` is the union of two half-spaces and only a point picks one of them, so a consumer that reads
     * the side off a satisfying point keeps the row implied by the model rather than strengthening it.
     */
    fun directionAt(values: ExactColumnValues): LinearOp = if (activityAt(values) < bound) LinearOp.LE else LinearOp.GE

    /** Append the rows this comparison states to [rows], directing a disequality by [values]. */
    fun rowsInto(rows: MutableList<ExactRationalInequality>, values: ExactColumnValues) =
        rowsInto(rows, if (op == LinearOp.NE) directionAt(values) else null)

    /**
     * Append the `a·x ≤ b` rows this comparison states to [rows].
     *
     * [direction] is the side a [LinearOp.NE] is asserted on and is read for nothing else. An integer
     * disequality tightens by one; a continuous one turns strict, because no next value exists below a
     * real bound.
     */
    fun rowsInto(rows: MutableList<ExactRationalInequality>, direction: LinearOp? = null) {
        when (op) {
            LinearOp.LE -> rows += exactRow(terms, bound, strict)

            LinearOp.GE -> rows += exactRow(terms.negated(), bound.negated(), strict)

            LinearOp.EQ -> {
                rows += exactRow(terms, bound, strict = false)
                rows += exactRow(terms.negated(), bound.negated(), strict = false)
            }

            LinearOp.NE -> when (requireNotNull(direction) { "exact disequality direction is missing" }) {
                LinearOp.LE -> rows += exactRow(terms, if (hasReals) bound else bound - BigFraction.ONE, hasReals)

                LinearOp.GE -> rows += exactRow(
                    terms.negated(),
                    if (hasReals) bound.negated() else bound.negated() - BigFraction.ONE,
                    hasReals,
                )

                else -> error("exact disequality direction must be an inequality")
            }
        }
    }
}

/**
 * The comparison this row states when the Boolean gating it holds [truth].
 *
 * A row read under a false activator states its complement exactly, which is what separates an exact
 * lane from a relaxation that weakens the same row through a big-M.
 */
internal fun FactorRow.exactComparison(realColumns: Int, truth: Boolean): ExactComparison {
    val terms = HashMap<Int, BigFraction>()
    val actualOp = if (truth) op else op.complemented()
    return when (this) {
        is FactorRow.Wide -> {
            for (index in intVars.indices) terms.add(realColumns + intVars[index], coefficients[index].asFraction())
            // The complement of `a·x ≤ b` is `a·x > b`, and stating it as `a·x ≥ b` would readmit the very
            // point the false activator excludes — the boundary the row itself sits on.
            ExactComparison(terms, bound.asFraction(), actualOp, strict = !truth, hasReals = false)
        }

        is FactorRow.Doubles -> {
            val exactCoeffs = integerCoeffs
            for (index in intVars.indices) {
                terms.add(
                    realColumns + intVars[index],
                    if (exactCoeffs != null) {
                        BigFraction.ofLong(exactCoeffs[index])
                    } else {
                        requireNotNull(BigFraction.ofDouble(intCoeffs[index]))
                    },
                )
            }
            for (index in realVars.indices) {
                terms.add(realVars[index], requireNotNull(BigFraction.ofDouble(realCoeffs[index])))
            }
            ExactComparison(
                terms,
                integerBound?.let(BigFraction::ofLong) ?: requireNotNull(BigFraction.ofDouble(bound)),
                actualOp,
                strict = if (truth) strict else !strict,
                hasReals = realVars.isNotEmpty(),
            )
        }
    }
}

/** The comparison literal [literal] of this clause states. */
internal fun ComparisonClause.exactComparison(realColumns: Int, literal: Int): ExactComparison = ExactComparison(
    mapOf(realColumns + vars[literal] to BigFraction.ONE),
    BigFraction.ofLong(consts[literal]),
    ops[literal],
    strict = false,
    hasReals = false,
)

/** One exact `Σ terms·x ≤ bound` row over the columns carrying a nonzero coefficient. */
internal fun exactRow(
    terms: Map<Int, BigFraction>,
    bound: BigFraction,
    strict: Boolean = false,
): ExactRationalInequality {
    val ordered = terms.entries.filter { !it.value.isZero }.sortedBy { it.key }
    return ExactRationalInequality(ordered.map { it.key }.toIntArray(), ordered.map { it.value }, bound, strict)
}

/** `x(column) ≥ bound`, as the `≤` row it is. */
internal fun exactColumnLower(column: Int, bound: BigFraction): ExactRationalInequality =
    exactRow(mapOf(column to BigFraction.MINUS_ONE), bound.negated())

/** `x(column) ≤ bound`. */
internal fun exactColumnUpper(column: Int, bound: BigFraction): ExactRationalInequality =
    exactRow(mapOf(column to BigFraction.ONE), bound)

/** This integer as the exact fraction it is. */
internal fun BigInteger.asFraction(): BigFraction = BigFraction.of(this, BigInteger.ONE)

/** This finite double as the exact fraction it is: every finite double is one exactly. */
internal fun Double.asFraction(): BigFraction = requireNotNull(BigFraction.ofDouble(this))

/** Accumulate [value] onto [column], dropping a term the sum cancels. */
internal fun MutableMap<Int, BigFraction>.add(column: Int, value: BigFraction) {
    val sum = (this[column] ?: BigFraction.ZERO) + value
    if (sum.isZero) remove(column) else this[column] = sum
}

private fun Map<Int, BigFraction>.negated(): Map<Int, BigFraction> = mapValues { (_, value) -> value.negated() }
