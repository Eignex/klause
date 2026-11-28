package com.eignex.klause.lp

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntegralConstants
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.RealConstants
import com.eignex.klause.ir.linearRows
import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.eignex.klause.solver.result.SourceLpWorkStats
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.magnitudeBitLength

// The witness fixes every conditional row, disjunct and disequality side, leaving a polyhedron inside the
// model that contains it; a rational recession direction strictly decreasing the objective is then a ray of
// the model. The cone of a rational polyhedron holding a mixed-integer point is the cone of its
// mixed-integer hull (R. R. Meyer, "On the existence of optimal solutions to integer and mixed-integer
// programming problems", Math. Programming 7, 1974, https://doi.org/10.1007/BF01585518), so the direction
// scales to integral integer steps.
//
// `false`: this witness's branch carries no such direction (a bounded branch of an unbounded model answers
// that too). `null`: not decided.
internal fun Problem.objectiveUnboundedBelow(
    terms: IntArray,
    coefficients: LongArray,
    witness: ExactWitness,
    cancellation: ExecutionControl = ExecutionControl.Never,
    onWork: (SourceLpWorkStats) -> Unit,
): Boolean? {
    val budget = SourceLpBudget(onWork = onWork)
    return budget.run(emptyList(), numRealVars + numIntVars, cancellation) { token ->
        if (terms.size > 128 || !admitsDirectionPreparation(token)) return@run null
        if (terms.isEmpty() || descentSidesClosed(terms, coefficients)) return@run false
        val point = List(numRealVars + numIntVars, witness::at)
        if (!point.admittedSourcePoint() ||
            (numRealVars until point.size).any { point[it].den != BIG_ONE }
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

private fun Problem.admitsDirectionPreparation(token: ExecutionControl): Boolean {
    if (numBoolVars > 512 || factors.size > 128) return false
    var terms = 0L
    var rows = 0L
    var bits = 0L
    fun admit(value: BigFraction): Boolean {
        if (token() || value.num.magnitudeBitLength() > 4096 || value.den.magnitudeBitLength() > 4096) return false
        bits += value.num.magnitudeBitLength().toLong() + value.den.magnitudeBitLength()
        return bits <= 8192L
    }
    for (integer in 0 until numIntVars) {
        val lower = intBounds.lowerAsBigInteger(integer)
        val upper = intBounds.upperAsBigInteger(integer)
        if (lower != null && (lower.magnitudeBitLength() > 4096 || !admit(lower.asFraction()))) return false
        if (upper != null && (upper.magnitudeBitLength() > 4096 || !admit(upper.asFraction()))) return false
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
                    if (constants.exactBound.magnitudeBitLength() > 4096 || !admit(
                            constants.exactBound.asFraction(),
                        )
                    ) {
                        return false
                    }
                    for (index in 0 until row.size) {
                        val coefficient = constants.exactCoeff(index)
                        if (coefficient.magnitudeBitLength() > 4096 || !admit(coefficient.asFraction())) return false
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

// A model with no disjunct, conditional row or disequality states one cone, so a descent can ask
// [objectiveUnboundedBelow] once instead of rebuilding the exact cone system per improvement.
internal fun Problem.statesOneBranch(): Boolean = factors.all { factor ->
    if (movesNoRay(factor)) return@all true
    factor.linearForm is LinearForm.Conjunction && factor.linearRows.all { row ->
        row.activator == LinearRow.ALWAYS && row.relation != LinearOp.NE
    }
}

private fun Problem.descentSidesClosed(terms: IntArray, coefficients: LongArray): Boolean = terms.indices.all { index ->
    val column = terms[index]
    if (coefficients[index] > 0L) intBounds.hasLower(column) else intBounds.hasUpper(column)
}

// Declared bounds enter as rows, so a declared value set needs no reading: its column is closed on both
// sides and no cone direction moves it.
private fun Problem.branchRowsAt(witness: ExactWitness, token: ExecutionControl): List<ExactRationalInequality>? {
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

// Such a factor's homogeneous row reads `0 ≤ 0`, so leaving it out relaxes nothing; this is the only way a
// factor outside the linear shapes can appear here.
private fun Problem.movesNoRay(factor: Factor): Boolean =
    factor.intVars.all { intBounds.hasLower(it) && intBounds.hasUpper(it) } &&
        factor.variables.reals.all { realLower[it].isFinite() && realUpper[it].isFinite() }
