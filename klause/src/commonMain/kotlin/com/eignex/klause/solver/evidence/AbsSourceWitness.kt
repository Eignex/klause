package com.eignex.klause.solver.evidence

import com.eignex.klause.factor.arithmetic.Abs
import com.eignex.klause.factor.arithmetic.AffineAbs
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.ExactWitness
import com.eignex.klause.lp.exactComparison
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.abs
import kotlin.uuid.Uuid

internal fun Problem.hasCompleteAbsSource(): Boolean = factors.any { it is Abs || it is AffineAbs } &&
    factors.all { factor ->
        factor is Abs || factor is AffineAbs || when (factor.linearForm) {
            is LinearForm.Conjunction, is LinearForm.Disjunction -> true
            is LinearForm.Relaxation, null -> false
        }
    }

internal fun Problem.absSourceWitnessContext(
    objective: LinearObjective,
    cancellation: Cancellation,
): SourceProofContext? {
    if (cancellation() || !hasCompleteAbsSource()) return null
    val identity = Uuid.random().toString()
    val scope = SourceScope(SourceRevision(identity, "klause-ir-exact-v1"),
        objective = SourceObjective("$identity/objective", ObjectiveSense.MINIMIZE))
    return sourceProofContext(scope, objective, cancellation)
}

internal fun Problem.checkAbsSourceWitness(sample: Sample, cancellation: Cancellation): SourceEvidenceCheck {
    if (numRealVars > 0 && sample.exactReals == null) {
        return SourceEvidenceCheck.Unavailable("absolute value source requires rational real coordinates")
    }
    val witness = object : ExactWitness {
        override fun truth(boolVar: Int): Boolean = sample.boolValue(boolVar)
        override fun at(column: Int): BigFraction = if (column < numRealVars) {
            checkNotNull(sample.exactReals)[column]
        } else BigFraction.of(sample.exactIntValue(column - numRealVars), BIG_ONE)
    }
    var interrupted = false
    fun holds(row: LinearRow): Boolean {
        if (cancellation()) {
            interrupted = true
            return false
        }
        return row.exactComparison(
            numRealVars, row.activator == LinearRow.ALWAYS || witness.truth(row.activator), witness::truth,
        ).holdsAt(witness)
    }
    for ((id, factor) in factors.withIndex()) {
        if (cancellation()) return SourceEvidenceCheck.Unavailable("original-source check cancelled")
        val valid = when (factor) {
            is Abs -> sample.exactIntValue(factor.operand).abs() == sample.exactIntValue(factor.result)
            is AffineAbs -> factor.isSatisfied(sample::exactIntValue)
            else -> when (val form = factor.linearForm) {
                is LinearForm.Conjunction -> form.rows.all(::holds)
                is LinearForm.Disjunction -> form.rows.any(::holds)
                is LinearForm.Relaxation, null -> return SourceEvidenceCheck.Unavailable(
                    "original factor has no complete absolute value source checker")
            }
        }
        if (interrupted || cancellation()) return SourceEvidenceCheck.Unavailable("original-source check cancelled")
        if (!valid) return SourceEvidenceCheck.Rejected("original factor $id refutes the assignment")
    }
    return SourceEvidenceCheck.Checked
}
