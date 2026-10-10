package com.eignex.klause.portfolio

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

internal fun verifyRealCoordinates(model: Problem, sample: Sample): Verification<Sample, Unit> {
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
