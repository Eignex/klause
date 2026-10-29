package com.eignex.klause.backtrack

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Lit
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession

internal object PrivateRebuildCapture {
    fun observe(session: PropagationSession, budget: InprocessingBudget, limit: Int) {
        if (limit == 0 || !session.canSimplifyRoot) return
        budget.note("RebuildAttempts")
        val source = session.problem as? BakedProblem
        val initial = source?.rootDeductions as? PropagationResult.Implied
        if (source == null || initial == null) {
            budget.note("RebuildUnsupported")
            return
        }
        val columns = source.numBoolVars
        if (source.numIntVars != 0 || source.numRealVars != 0 || columns > limit / 10) {
            budget.note("RebuildUnsupported")
            return
        }
        var visits = 0
        fun visit(count: Int = 1): Boolean {
            if (count > limit - visits || count.toLong() > budget.limit - budget.spent || budget.stopped()) return false
            visits += count
            budget.charge(count)
            return true
        }
        // Initialization and disposal belong to the same containing allowance as the scan.
        if (!visit(columns * 10)) {
            budget.note("RebuildPartial")
            return
        }
        val before = IntArray(columns * 2)
        val after = IntArray(columns * 2)
        val initialValues = ByteArray(columns)
        val currentValues = ByteArray(columns)
        budget.note("RebuildScratchBytes", columns * 18L)
        var removed = 0L
        var shortened = 0L
        var pins = 0L
        var progressPins = 0L
        var complete = false
        try {
            for (index in initial.boolKeys.indices) {
                if (!visit()) return
                initialValues[initial.boolKeys[index]] = if (initial.boolValues[index]) 1 else 2
            }
            for (column in 0 until columns) {
                if (!visit()) return
                currentValues[column] = when (session.boolValue(column)) {
                    true -> 1
                    false -> 2
                    null -> 0
                }
                if (currentValues[column] != 0.toByte()) {
                    pins++
                    if (initialValues[column] == 0.toByte()) progressPins++
                }
            }
            for (factor in source.factors) {
                if (!visit()) return
                if (factor !is Clause) {
                    budget.note("RebuildUnsupported")
                    return
                }
                var satisfied = false
                var initiallySatisfied = false
                var initialResidual = 0
                var currentResidual = 0
                for (literal in factor.literals) {
                    if (!visit()) return
                    if (Lit.variable(literal) !in 0 until columns) {
                        budget.note("RebuildUnsupported")
                        return
                    }
                    val column = Lit.variable(literal)
                    val trueValue = if (Lit.isPositive(literal)) 1.toByte() else 2.toByte()
                    if (initialValues[column] == trueValue) initiallySatisfied = true
                    if (currentValues[column] == trueValue) satisfied = true
                    if (initialValues[column] == 0.toByte()) initialResidual++
                    if (currentValues[column] == 0.toByte()) currentResidual++
                }
                if (!initiallySatisfied && satisfied) removed++
                if (!satisfied && currentResidual < initialResidual) shortened++
                for (literal in factor.literals) {
                    if (!visit()) return
                    val column = Lit.variable(literal)
                    if (!initiallySatisfied && initialValues[column] == 0.toByte()) before[literal]++
                    if (!satisfied && currentValues[column] == 0.toByte()) after[literal]++
                }
            }
            var newlyEligible = 0L
            var existingEligible = 0L
            for (column in 0 until columns) {
                if (!visit()) return
                if (currentValues[column] != 0.toByte()) continue
                val positive = 2 * column
                fun eligible(counts: IntArray): Boolean {
                    val p = counts[positive].toLong()
                    val n = counts[positive + 1].toLong()
                    return p + n > 0L && p * n <= p + n
                }
                if (eligible(after)) {
                    if (eligible(before)) existingEligible++ else newlyEligible++
                }
            }
            if (budget.stopped()) return
            budget.note("RebuildComplete")
            budget.note("RebuildRootPins", pins)
            budget.note("RebuildProgressPins", progressPins)
            budget.note("RebuildStableRemoved", removed)
            budget.note("RebuildStableShortened", shortened)
            budget.note("RebuildExistingCandidates", existingEligible)
            budget.note("RebuildNewCandidates", newlyEligible)
            complete = true
        } finally {
            budget.note("RebuildCaptureWork", visits.toLong())
            if (!complete) budget.note("RebuildPartial")
        }
    }
}
