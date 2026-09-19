package com.eignex.klause.lp

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntegralConstants
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.RealConstants
import com.eignex.klause.ir.linearRows
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.eignex.klause.solver.result.SourceLpWorkStats
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
    onWork: (SourceLpWorkStats) -> Unit,
): Boolean? {
    val budget = SourceLpBudget(onWork = onWork)
    return budget.run(emptyList(), numRealVars + numIntVars, cancellation) { token ->
        if (terms.size > 128 || !admitsDirectionPreparation(token)) return@run null
        if (terms.isEmpty() || descentSidesClosed(terms, coefficients)) return@run false
        val point = List(numRealVars + numIntVars, witness::at)
        if (!point.admittedSourcePoint() ||
            (numRealVars until point.size).any { point[it].den != com.ionspin.kotlin.bignum.integer.BigInteger.ONE }
        ) {
            return@run null
        }
        val rows = branchRowsAt(witness, token) ?: return@run null
        budget.run(rows, point.size, token) checked@{ checkedToken ->
            if (!point.satisfiesSourceRows(rows, checkedToken)) return@checked null
            val activity = HashMap<Int, BigFraction>()
            for (index in terms.indices) {
                if (checkedToken()) return@checked null
                activity.add(numRealVars + terms[index], BigFraction.ofLong(coefficients[index]))
            }
            sourceDescendingDirection(
                rows,
                exactRow(activity, BigFraction.ZERO),
                point.size,
                checkedToken,
                budget,
            )
        }
    }
}

private fun Problem.admitsDirectionPreparation(token: Cancellation): Boolean {
    if (numBoolVars > 512 || factors.size > 128) return false
    var terms = 0L
    var rows = 0L
    var bits = 0L
    fun admit(value: BigFraction): Boolean {
        if (token() || value.num.bitLength() > 4096 || value.den.bitLength() > 4096) return false
        bits += value.num.bitLength().toLong() + value.den.bitLength()
        return bits <= 8192L
    }
    for (integer in 0 until numIntVars) {
        val lower = intBounds.lowerAsBigInteger(integer)
        val upper = intBounds.upperAsBigInteger(integer)
        if (lower != null && (lower.bitLength() > 4096 || !admit(lower.asFraction()))) return false
        if (upper != null && (upper.bitLength() > 4096 || !admit(upper.asFraction()))) return false
    }
    for (real in 0 until numRealVars) {
        if (realLower[real].isFinite() && !admit(realLower[real].asFraction())) return false
        if (realUpper[real].isFinite() && !admit(realUpper[real].asFraction())) return false
    }
    for (factor in factors) {
        if (token() || factor.intVars.size > 512 || factor.variables.reals.size > 512) return false
        val linear = factor.linearRows
        if (linear.size > 128) return false
        for (row in linear) {
            rows++
            terms += row.size
            if (rows > 128L || terms > 512L) return false
            when (val constants = row.constants) {
                is IntegralConstants -> {
                    if (constants.exactBound.bitLength() > 4096 || !admit(
                            constants.exactBound.asFraction(),
                        )
                    ) {
                            return false
                        }
                    for (index in 0 until row.size) {
                        val coefficient = constants.exactCoeff(index)
                        if (coefficient.bitLength() > 4096 || !admit(coefficient.asFraction())) return false
                    }
                }

                is RealConstants -> {
                    if (!constants.bound.isFinite() || !admit(constants.bound.asFraction())) return false
                    for (index in 0 until row.size) {
                        val coefficient = if (index < constants.intCoefficients.size) {
                            constants.intCoefficients.at(index)
                        } else {
                            constants.realCoefficients.at(index - constants.intCoefficients.size)
                        }
                        if (!coefficient.isFinite() || !admit(coefficient.asFraction())) return false
                    }
                }
            }
        }
    }
    return !token()
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
    factor.linearForm is LinearForm.Conjunction && factor.linearRows.all { row ->
        row.activator == LinearRow.ALWAYS && row.relation != LinearOp.NE
    }
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
private fun Problem.branchRowsAt(witness: ExactWitness, token: Cancellation): List<ExactRationalInequality>? {
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
        if (token()) return null
        if (movesNoRay(factor)) continue
        if (factor.linearForm is LinearForm.Relaxation || factor.linearForm == null) return null
        val comparisons = factor.linearRows.map { row ->
            if (token()) return null
            row.exactComparison(
                numRealVars,
                row.activator == LinearRow.ALWAYS || witness.truth(row.activator),
                witness::truth,
            )
        }
        if (factor.linearForm is LinearForm.Disjunction) {
            val selected = comparisons.firstOrNull { it.holdsAt(witness) } ?: return null
            selected.rowsInto(rows, witness)
        } else {
            for (comparison in comparisons) comparison.rowsInto(rows, witness)
        }
    }
    return rows
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

private fun ExactComparison.holdsAt(witness: ExactWitness): Boolean {
    val value = activityAt(witness)
    return when (op) {
        LinearOp.LE -> if (strict) value < bound else value <= bound
        LinearOp.GE -> if (strict) value > bound else value >= bound
        LinearOp.EQ -> value == bound
        LinearOp.NE -> value != bound
    }
}
