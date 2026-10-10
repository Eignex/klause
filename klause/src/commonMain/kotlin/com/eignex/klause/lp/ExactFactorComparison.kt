package com.eignex.klause.lp

import com.eignex.klause.ir.IntegralConstants
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.RealConstants
import com.eignex.klause.ir.Term
import com.eignex.klause.ir.complemented
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BigInt

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
    val ordered: ExactOrderedTerms = ExactOrderedTerms(terms),
) {

    /** The weighted sum at [values]. */
    fun activityAt(values: ExactColumnValues): BigFraction {
        var total = BigFraction.ZERO
        for ((column, coefficient) in terms) total += coefficient * values.at(column)
        return total
    }

    fun holdsAt(values: ExactColumnValues): Boolean {
        val value = activityAt(values)
        return when (op) {
            LinearOp.LE -> if (strict) value < bound else value <= bound
            LinearOp.GE -> if (strict) value > bound else value >= bound
            LinearOp.EQ -> value == bound
            LinearOp.NE -> value != bound
        }
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
        if (terms.isEmpty()) {
            val holds = when (op) {
                LinearOp.LE -> if (strict) BigFraction.ZERO < bound else BigFraction.ZERO <= bound
                LinearOp.GE -> if (strict) BigFraction.ZERO > bound else BigFraction.ZERO >= bound
                LinearOp.EQ -> bound.isZero
                LinearOp.NE -> !bound.isZero
            }
            if (!holds) rows += exactRow(emptyMap(), BigFraction.MINUS_ONE)
            return
        }
        when (op) {
            LinearOp.LE -> rows += ordered.row(bound, strict)

            LinearOp.GE -> rows += ordered.negatedRow(bound.negated(), strict)

            LinearOp.EQ -> {
                rows += ordered.row(bound, strict = false)
                rows += ordered.negatedRow(bound.negated(), strict = false)
            }

            LinearOp.NE -> when (requireNotNull(direction) { "exact disequality direction is missing" }) {
                LinearOp.LE -> rows += ordered.row(if (hasReals) bound else bound - BigFraction.ONE, hasReals)

                LinearOp.GE -> rows += ordered.negatedRow(
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
internal fun LinearRow.exactComparison(
    realColumns: Int,
    truth: Boolean,
    booleanValue: (Int) -> Boolean,
): ExactComparison = exactForm(realColumns).comparison(truth, booleanValue)

/**
 * A row's terms in exact rationals, read once, with the Booleans it carries left open: a Boolean term only
 * moves the right-hand side, and the activator only picks the operator, so a consumer that states the same
 * row under many assignments keeps one form per row.
 */
internal class ExactRowForm(
    private val terms: Map<Int, BigFraction>,
    private val rhs: BigFraction,
    private val booleanLiterals: IntArray,
    private val booleanCoefficients: List<BigFraction>,
    private val relation: LinearOp,
    private val strict: Boolean,
    private val hasReals: Boolean,
) {
    private val ordered = ExactOrderedTerms(terms)

    /** The comparison the row states when its activator holds [truth] under [booleanValue]. */
    fun comparison(truth: Boolean, booleanValue: (Int) -> Boolean): ExactComparison {
        var bound = rhs
        for (k in booleanLiterals.indices) {
            val literal = booleanLiterals[k]
            if (booleanValue(Lit.variable(literal)) == Lit.isPositive(literal)) bound -= booleanCoefficients[k]
        }
        return ExactComparison(
            terms,
            bound,
            if (truth) relation else relation.complemented(),
            strict = if (truth) strict else !strict,
            hasReals = hasReals,
            ordered = ordered,
        )
    }
}

/** This row's [ExactRowForm] over the mixed columns, the first [realColumns] of them continuous. */
internal fun LinearRow.exactForm(realColumns: Int): ExactRowForm {
    val terms = HashMap<Int, BigFraction>()
    val booleanLiterals = ArrayList<Int>()
    val booleanCoefficients = ArrayList<BigFraction>()
    val c = constants
    val rhs = when (c) {
        is IntegralConstants -> c.exactBound.asFraction()
        is RealConstants -> c.bound.asFraction()
    }
    for (k in 0 until size) {
        val coefficient = when (c) {
            is IntegralConstants -> c.exactCoeff(k).asFraction()

            is RealConstants -> if (k < c.intCoefficients.size) {
                c.intCoefficients.at(k).asFraction()
            } else {
                c.realCoefficients.at(k - c.intCoefficients.size).asFraction()
            }
        }
        val reference = ref(k)
        when {
            Term.isBool(reference) -> {
                booleanLiterals += Term.lit(reference)
                booleanCoefficients += coefficient
            }

            Term.isInt(reference) -> terms.add(realColumns + Term.intVar(reference), coefficient)

            else -> terms.add(Term.realVar(reference), coefficient)
        }
    }
    return ExactRowForm(
        terms,
        rhs,
        booleanLiterals.toIntArray(),
        booleanCoefficients,
        relation,
        strict,
        hasReals = c is RealConstants || (0 until size).any { Term.isReal(ref(it)) },
    )
}

/** Nonzero terms in ascending column order, with their negation, shared by every row stated over them. */
internal class ExactOrderedTerms(terms: Map<Int, BigFraction>) {
    private val columns: IntArray
    private val coefficients: List<BigFraction>
    private val negated: List<BigFraction>

    init {
        val ordered = terms.entries.filter { !it.value.isZero }.sortedBy { it.key }
        columns = IntArray(ordered.size) { ordered[it].key }
        coefficients = ordered.map { it.value }
        negated = coefficients.map { it.negated() }
    }

    /** `Σ terms·x ≤ bound`. */
    fun row(bound: BigFraction, strict: Boolean): ExactRationalInequality =
        ExactRationalInequality(columns, coefficients, bound, strict)

    /** `−Σ terms·x ≤ bound`. */
    fun negatedRow(bound: BigFraction, strict: Boolean): ExactRationalInequality =
        ExactRationalInequality(columns, negated, bound, strict)
}

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
internal fun BigInt.asFraction(): BigFraction = BigFraction.of(this, BIG_ONE)

/** This finite double as the exact fraction it is: every finite double is one exactly. */
internal fun Double.asFraction(): BigFraction = requireNotNull(BigFraction.ofDouble(this))

/** Accumulate [value] onto [column], dropping a term the sum cancels. */
internal fun MutableMap<Int, BigFraction>.add(column: Int, value: BigFraction) {
    val sum = (this[column] ?: BigFraction.ZERO) + value
    if (sum.isZero) remove(column) else this[column] = sum
}
