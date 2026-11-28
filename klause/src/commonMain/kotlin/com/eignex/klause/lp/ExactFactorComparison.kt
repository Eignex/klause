package com.eignex.klause.lp

import com.eignex.klause.ir.IntegralConstants
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.RealConstants
import com.eignex.klause.ir.Term
import com.eignex.klause.ir.complemented
import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.BigInt

internal interface ExactColumnValues {
    fun at(column: Int): BigFraction
}

// A witness plus the Boolean assignment that says which conditional rows hold.
internal interface ExactWitness : ExactColumnValues {
    fun truth(boolVar: Int): Boolean
}

// Mixed column space: `[0, realColumns)` are continuous and `realColumns + v` is integer column `v`.
// An `=` is two rows, a `≥` a negated `≤`, and a `≠` one row only once a consumer picks its side, so
// operator complementing happens here once and [rowsInto] states the rows.
internal class ExactComparison(
    val terms: Map<Int, BigFraction>,
    val bound: BigFraction,
    val op: LinearOp,
    val strict: Boolean,
    // Decides how a disequality tightens.
    val hasReals: Boolean,
    val ordered: ExactOrderedTerms = ExactOrderedTerms(terms),
) {

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

    // Reading the side of a `≠` off a satisfying point keeps the row implied by the model rather than
    // strengthening it.
    fun directionAt(values: ExactColumnValues): LinearOp = if (activityAt(values) < bound) LinearOp.LE else LinearOp.GE

    fun rowsInto(rows: MutableList<ExactRationalInequality>, values: ExactColumnValues) =
        rowsInto(rows, if (op == LinearOp.NE) directionAt(values) else null)

    // [direction] is read only for a `≠`. An integer disequality tightens by one; a continuous one turns
    // strict, because no next value exists below a real bound.
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

// Under a false activator the row states its exact complement, not a big-M weakening.
internal fun LinearRow.exactComparison(
    realColumns: Int,
    truth: Boolean,
    booleanValue: (Int) -> Boolean,
): ExactComparison = exactForm(realColumns).comparison(truth, booleanValue)

internal class ExactRowTerms(val coefficients: Map<Int, BigFraction>) {
    val ordered = ExactOrderedTerms(coefficients)
}

internal class ExactRowTermPool {
    private val terms = HashMap<Map<Int, BigFraction>, ExactRowTerms>()

    fun captureOwned(coefficients: Map<Int, BigFraction>): ExactRowTerms =
        terms.getOrPut(coefficients) { ExactRowTerms(coefficients) }
}

// Booleans are left open: a Boolean term only moves the right-hand side and the activator only picks the
// operator, so one form serves the row under every assignment.
internal class ExactRowForm(
    private val terms: Map<Int, BigFraction>,
    private val rhs: BigFraction,
    private val booleanLiterals: IntArray,
    private val booleanCoefficients: List<BigFraction>,
    private val relation: LinearOp,
    private val strict: Boolean,
    private val hasReals: Boolean,
    private val ordered: ExactOrderedTerms,
) {
    val hasBooleanTerms get() = booleanLiterals.isNotEmpty()
    private val fixedComparison = if (booleanLiterals.isEmpty()) {
        ExactComparison(terms, rhs, relation, strict, hasReals, ordered)
    } else null

    fun sameNumericInputs(previous: List<BigFraction>, current: List<BigFraction>, visit: () -> Boolean): Boolean =
        terms.keys.all { visit() && previous[it] == current[it] }

    fun hintComparison(
        booleanValue: (Int) -> Boolean?,
        admit: (BigFraction) -> Boolean,
        preflight: (BigFraction, BigFraction) -> Boolean,
    ): ExactComparison? {
        if (!admit(rhs) || terms.values.any { !admit(it) }) return null
        var bound = rhs
        for (k in booleanLiterals.indices) {
            val literal = booleanLiterals[k]
            val value = booleanValue(Lit.variable(literal)) ?: return null
            val coefficient = booleanCoefficients[k]
            if (!admit(coefficient)) return null
            if (value == Lit.isPositive(literal)) {
                if (!preflight(bound, coefficient)) return null
                bound -= coefficient
                if (!admit(bound)) return null
            }
        }
        return fixedComparison ?: ExactComparison(terms, bound, relation, strict, hasReals, ordered)
    }

    fun comparison(truth: Boolean, booleanValue: (Int) -> Boolean): ExactComparison {
        if (truth) fixedComparison?.let { return it }
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

internal fun LinearRow.exactForm(realColumns: Int, pool: ExactRowTermPool? = null): ExactRowForm {
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
    val captured = pool?.captureOwned(terms)
    return ExactRowForm(
        captured?.coefficients ?: terms,
        rhs,
        booleanLiterals.toIntArray(),
        booleanCoefficients,
        relation,
        strict,
        hasReals = c is RealConstants || (0 until size).any { Term.isReal(ref(it)) },
        ordered = captured?.ordered ?: ExactOrderedTerms(terms),
    )
}

// Shared by every row stated over the same terms.
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

    fun row(bound: BigFraction, strict: Boolean): ExactRationalInequality =
        ExactRationalInequality(columns, coefficients, bound, strict)

    fun negatedRow(bound: BigFraction, strict: Boolean): ExactRationalInequality =
        ExactRationalInequality(columns, negated, bound, strict)
}

internal fun exactRow(
    terms: Map<Int, BigFraction>,
    bound: BigFraction,
    strict: Boolean = false,
): ExactRationalInequality {
    val ordered = terms.entries.filter { !it.value.isZero }.sortedBy { it.key }
    return ExactRationalInequality(ordered.map { it.key }.toIntArray(), ordered.map { it.value }, bound, strict)
}

internal fun exactColumnLower(column: Int, bound: BigFraction): ExactRationalInequality =
    exactRow(mapOf(column to BigFraction.MINUS_ONE), bound.negated())

internal fun exactColumnUpper(column: Int, bound: BigFraction): ExactRationalInequality =
    exactRow(mapOf(column to BigFraction.ONE), bound)

internal fun BigInt.asFraction(): BigFraction = BigFraction.of(this, BIG_ONE)

// Every finite double is exactly a fraction.
internal fun Double.asFraction(): BigFraction = requireNotNull(BigFraction.ofDouble(this))

internal fun MutableMap<Int, BigFraction>.add(column: Int, value: BigFraction) {
    val sum = (this[column] ?: BigFraction.ZERO) + value
    if (sum.isZero) remove(column) else this[column] = sum
}
