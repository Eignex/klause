package com.eignex.klause.portfolio

import com.eignex.klause.factor.arithmetic.Abs
import com.eignex.klause.factor.arithmetic.AffineAbs
import com.eignex.klause.factor.arithmetic.RealAbs
import com.eignex.klause.factor.arithmetic.RealProduct
import com.eignex.klause.factor.table.RealElement
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.ExactWitness
import com.eignex.klause.lp.exactComparison
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.Candidate
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.abs
import com.eignex.klause.util.fitsLong
import com.eignex.klause.util.toLongExact

internal fun verifyRealCoordinates(model: Problem, sample: Sample): Verification<Sample, Unit> {
    for ((id, factor) in model.factors.withIndex()) {
        if (factor !is Abs && factor !is AffineAbs) continue
        if (sample.numIntVars != model.numIntVars) {
            return Verification.Rejected("assignment does not cover the integer variables")
        }
        val valid = when (factor) {
            is Abs -> sample.exactIntValue(factor.operand).abs() == sample.exactIntValue(factor.result)
            is AffineAbs -> factor.isSatisfied(sample::exactIntValue)
            else -> false
        }
        if (!valid) {
            return Verification.Rejected("integer factor $id refutes the assignment")
        }
    }
    if (model.numRealVars == 0) return Verification.Accepted(Candidate(sample, Unit))
    val reals = sample.exactReals ?: return Verification.Indeterminate("continuous values are not certified")
    if (reals.size != model.numRealVars) return Verification.Rejected("assignment does not cover the real variables")
    for (id in reals.indices) {
        val lower = BigFraction.ofDouble(model.realLower[id])
        val upper = BigFraction.ofDouble(model.realUpper[id])
        if ((lower != null && reals[id] < lower) || (upper != null && reals[id] > upper)) {
            return Verification.Rejected("real $id is outside its declared range")
        }
    }
    val witness = object : ExactWitness {
        override fun truth(boolVar: Int): Boolean = sample.boolValue(boolVar)
        override fun at(column: Int): BigFraction = if (column < model.numRealVars) reals[column] else {
            BigFraction.of(sample.exactIntValue(column - model.numRealVars), BIG_ONE)
        }
    }
    for ((id, factor) in model.factors.withIndex()) {
        if (factor.variables.reals.isEmpty()) continue
        if (factor is RealAbs) {
            val operand = reals[factor.operand]
            val magnitude = if (operand < BigFraction.ZERO) operand.negated() else operand
            if (magnitude != reals[factor.result]) {
                return Verification.Rejected("real factor $id refutes the assignment")
            }
            continue
        }
        if (factor is RealProduct) {
            val integer = BigFraction.of(sample.exactIntValue(factor.intOperand), BIG_ONE)
            if (integer * reals[factor.realOperand] != reals[factor.result]) {
                return Verification.Rejected("real factor $id refutes the assignment")
            }
            continue
        }
        if (factor is RealElement) {
            val index = sample.exactIntValue(factor.idx)
            val position = if (index.fitsLong()) factor.position(index.toLongExact()) else null
            if (position == null) return Verification.Rejected("real factor $id has an invalid index")
            val selected = if (factor.arrIsVars) reals[factor.entries[position]]
                else checkNotNull(BigFraction.ofDouble(factor.constants[position]))
            if (selected != reals[factor.result]) return Verification.Rejected("real factor $id refutes the assignment")
            continue
        }
        val form = factor.linearForm
        if (form == null || form is LinearForm.Relaxation) {
            return Verification.Indeterminate("real factor $id has no complete witness checker")
        }
        fun holds(row: LinearRow): Boolean = row.exactComparison(
            model.numRealVars, row.activator == LinearRow.ALWAYS || witness.truth(row.activator), witness::truth,
        ).holdsAt(witness)
        val valid = when (form) {
            is LinearForm.Conjunction -> form.rows.all(::holds)
            is LinearForm.Disjunction -> form.rows.any(::holds)
            is LinearForm.Relaxation -> false
        }
        if (!valid) return Verification.Rejected("real factor $id refutes the assignment")
    }
    return Verification.Accepted(Candidate(sample, Unit))
}
