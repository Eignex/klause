package com.eignex.klause.propagation

import com.eignex.klause.ir.Lit
import com.eignex.klause.solver.search.resolveSearchClauses
import com.eignex.klause.util.Cancellation

internal fun PropagationSession.projectSeedCore(
    assumptions: Assumptions,
    conflict: PropagationResult.Unsat,
    cancellation: Cancellation,
): PropagationResult.Unsat {
    val applied = conflict.conflictLevels.lastOrNull() ?: return conflict
    if (applied !in 1..assumptions.numBools || cancellation()) return conflict
    val failed = assumptions.boolKeys[applied - 1]
    val prior = boolValue(failed) ?: return conflict
    if (prior == assumptions.boolValues[applied - 1]) return conflict
    val state = explanationState
    val roots = (problem as? BakedProblem)?.rootDeductions as? PropagationResult.Implied
    var remaining = 8_192
    fun spend(visits: Int): Boolean {
        if (visits > remaining) return false
        remaining -= visits
        state.work += visits
        cancellation.workMeter()?.charge(visits.toLong())
        return !cancellation()
    }
    fun reason(variable: Int): IntArray? {
        if (variable !in 0 until problem.numBoolVars) return null
        val assigned = boolValue(variable) ?: return null
        val antecedents = state.boolAntecedents[variable]
        if (antecedents == null) {
            return if (roots?.boolValueOrNull(variable) == assigned) {
                intArrayOf(Lit.make(variable, assigned))
            } else null
        }
        // Lazy and integer reasons retain the certified prefix rather than materialize an unbounded proof.
        if (isLazyReason(antecedents) || antecedents.size >= 128 || !spend(antecedents.size + 1)) return null
        return intArrayOf(Lit.make(variable, assigned)) + antecedents
    }
    var resolvent = reason(failed) ?: return conflict
    val resolved = HashSet<Int>()
    repeat(128) {
        if (resolvent.isEmpty() || resolvent.size > 128 || !spend(resolvent.size)) return conflict
        val needed = HashSet<Int>()
        var pivot: Int? = null
        for (literal in resolvent) {
            val variable = Lit.variable(literal)
            if (variable >= problem.numBoolVars) return conflict
            val index = assumptions.boolKeys.binarySearch(variable)
            if (index in 0 until applied && assumptions.boolValues[index] == !Lit.isPositive(literal)) {
                needed.add(index + 1)
            } else {
                pivot = literal
                break
            }
        }
        if (pivot == null) {
            if (needed.isEmpty() || !spend(needed.size)) return conflict
            return conflict.copy(conflictLevels = needed.sorted().toIntArray())
        }
        val literal = requireNotNull(pivot)
        val variable = Lit.variable(literal)
        if (!resolved.add(variable) || litTruth(literal) != false) return conflict
        val antecedent = reason(variable) ?: return conflict
        if (!spend(resolvent.size + antecedent.size)) return conflict
        resolvent = resolveSearchClauses(resolvent, antecedent, literal) ?: return conflict
    }
    return conflict
}
