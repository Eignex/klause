package com.eignex.klause.lp

import com.eignex.klause.factor.arithmetic.ComparisonClause
import com.eignex.klause.factor.arithmetic.FactorRow
import com.eignex.klause.factor.arithmetic.linearRow
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.eignex.klause.simplex.exact.exactDescendingDirection
import com.eignex.klause.util.Cancellation

/**
 * Whether `Σ coefficients(i)·x(terms(i))` descends without limit over this model, proved at [witness].
 *
 * A descent that re-bounds below its incumbent has no optimum to reach when the objective is unbounded
 * below, so it improves until a budget fires. This decides the question the descent's own rounds cannot:
 * [witness] fixes every conditional row, every disjunct and every disequality side, which leaves a
 * polyhedron that contains the witness and is contained in the model, and a rational direction of that
 * polyhedron's recession cone along which the objective strictly decreases is a ray of the model itself.
 * The cone of a rational polyhedron holding a mixed-integer point is the cone of its mixed-integer hull
 * (Meyer), so such a direction scales to one whose integer steps are integral and the objective takes
 * every value below the witness's.
 *
 * Three-valued, because a refusal and an undecided run justify different things: `false` says this
 * witness's own branch carries no such direction, which a bounded branch of an unbounded model answers
 * too, and `null` says the question was not reached at all — a factor outside the linear shapes, or an
 * exact run the stop cut short. The descent goes on under either.
 */
internal fun Problem.objectiveUnboundedBelow(
    terms: IntArray,
    coefficients: LongArray,
    witness: ExactWitness,
    cancellation: Cancellation = Cancellation.Never,
): Boolean? {
    // A constant objective has no direction to descend along, and one whose every column is bounded on
    // its own descent side is already bounded below by the declared box — the cheap half of the question,
    // answered before a cone system over every row of the model is built.
    if (terms.isEmpty() || descentSidesClosed(terms, coefficients)) return false
    val rows = branchRowsAt(witness) ?: return null
    val activity = HashMap<Int, BigFraction>()
    for (index in terms.indices) activity.add(numRealVars + terms[index], BigFraction.ofLong(coefficients[index]))
    return exactDescendingDirection(
        rows,
        exactRow(activity, BigFraction.ZERO),
        numRealVars + numIntVars,
        cancellation,
    )
}

/**
 * Whether every witness of this model lies in the same branch, so [objectiveUnboundedBelow] reads one
 * polyhedron whichever witness it is handed.
 *
 * A witness enters the certificate to pick a disjunct, to say which side of a conditional row holds, and
 * to direct a disequality. A model stating none of those states one cone, and a cone a completed run
 * found no descending direction in carries none on any later round either — which is what lets a descent
 * ask the question once instead of rebuilding an exact cone system per improvement.
 */
internal fun Problem.statesOneBranch(): Boolean = factors.all { factor ->
    if (movesNoRay(factor)) return@all true
    if (factor is ComparisonClause) return@all false
    val row = factor.linearRow() ?: return@all false
    row.activator == FactorRow.ALWAYS && row.op != LinearOp.NE
}

/** Whether every column the objective descends along is bounded on that side. */
private fun Problem.descentSidesClosed(terms: IntArray, coefficients: LongArray): Boolean = terms.indices.all { index ->
    val column = terms[index]
    if (coefficients[index] > 0L) intBounds.hasLower(column) else intBounds.hasUpper(column)
}

/**
 * Exact rows of one polyhedron holding [witness] and held by this model, or `null` when a factor states
 * no row this can read.
 *
 * The declared bounds enter as rows, so a column bounded on both sides is pinned by the cone system
 * itself — which is also why a declared value set needs no reading here: a column declaring one is closed
 * on both sides, and no direction of the cone moves it.
 */
private fun Problem.branchRowsAt(witness: ExactWitness): List<ExactRationalInequality>? {
    val rows = ArrayList<ExactRationalInequality>()
    for (integer in 0 until numIntVars) {
        val column = numRealVars + integer
        intBounds.lowerAsBigInteger(integer)?.let { rows += exactColumnLower(column, it.asFraction()) }
        intBounds.upperAsBigInteger(integer)?.let { rows += exactColumnUpper(column, it.asFraction()) }
    }
    for (real in 0 until numRealVars) {
        realLower[real].takeIf(Double::isFinite)?.let { rows += exactColumnLower(real, it.asFraction()) }
        realUpper[real].takeIf(Double::isFinite)?.let { rows += exactColumnUpper(real, it.asFraction()) }
    }
    for (factor in factors) {
        if (movesNoRay(factor)) continue
        val comparison = comparisonAt(factor, witness) ?: return null
        comparison.rowsInto(rows, witness)
    }
    return rows
}

/** The comparison [factor] states in the branch [witness] lies in, or `null` when it states none. */
private fun Problem.comparisonAt(factor: Factor, witness: ExactWitness): ExactComparison? {
    if (factor is ComparisonClause) {
        val literal = factor.satisfiedLiteralAt(numRealVars, witness) ?: return null
        return factor.exactComparison(numRealVars, literal)
    }
    val row = factor.linearRow() ?: return null
    val activator = row.activator
    return row.exactComparison(numRealVars, activator == FactorRow.ALWAYS || witness.truth(activator))
}

/**
 * Whether every column [factor] reads is bounded on both sides, so no direction of the cone moves one.
 *
 * Such a factor states nothing the cone system does not already: its own homogeneous row reads `0 ≤ 0`,
 * because a pinned column contributes no direction and a Boolean does not move at all. Leaving it out
 * therefore relaxes nothing — and it is the only way a factor outside the linear shapes can appear here,
 * since an open column reaches no lane but the theory's.
 */
private fun Problem.movesNoRay(factor: Factor): Boolean =
    factor.intVars.all { intBounds.hasLower(it) && intBounds.hasUpper(it) } &&
        factor.variables.reals.all { realLower[it].isFinite() && realUpper[it].isFinite() }

/** The clause's first literal [witness] satisfies. */
private fun ComparisonClause.satisfiedLiteralAt(realColumns: Int, witness: ExactWitness): Int? =
    vars.indices.firstOrNull { literal ->
        val value = witness.at(realColumns + vars[literal])
        val constant = BigFraction.ofLong(consts[literal])
        when (ops[literal]) {
            LinearOp.LE -> value <= constant
            LinearOp.GE -> value >= constant
            LinearOp.EQ -> value == constant
            LinearOp.NE -> value != constant
        }
    }
