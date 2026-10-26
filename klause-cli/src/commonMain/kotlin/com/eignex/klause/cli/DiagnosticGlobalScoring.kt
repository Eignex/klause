package com.eignex.klause.cli

import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.BuiltInScoringProjectionProvider
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.LocalSearchProblem
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.ScoringProjection
import com.eignex.klause.localsearch.ScoringProjectionProvider
import com.eignex.klause.solver.AssignmentCheckCapability
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.solver.ScoringCapability
import com.eignex.klause.solver.pipeline.FiniteEngine

internal fun CommonOptions.diagnosticGlobalScoring(problem: Problem): FactorProjectionProviders {
    val entries = engineParams.filter { it.startsWith("diagnostic-global-scoring=") }
    if (entries.isEmpty()) return FactorProjectionProviders.Default
    val treatment = entries.map { it.substringAfter('=') }.distinct().singleOrNull()
    require(treatment in setOf("native", "pairs"))
    require(resolvedEngine() == FiniteEngine.LOCAL_SEARCH && presolve == "off")
    engineParams.removeAll(entries)
    if (treatment == "native") return FactorProjectionProviders.Default
    val canonical = LocalSearchProblem(problem)
    val invariants = problem.factors.indices.associate { problem.factors[it] to canonical.invariants[it] }
    return FactorProjectionProviders(scoring = ScoringProjectionProvider { factor ->
        if (factor !is AllDifferent || factor.vars.size > 64 || factor.exceptSet.size > 64 ||
            factor.presents.any { it < 0 }
        ) BuiltInScoringProjectionProvider.select(factor) else ScoringProjection(
            ScoringCapability.GRADED, AssignmentCheckCapability.EXACT,
        ) { _, domains ->
            val native = invariants[factor] ?: run {
                val bools = factor.presents.maxOfOrNull { Lit.variable(it) + 1 } ?: 0
                val moves = requireNotNull(domains)
                LocalSearchProblem(Problem(bools, moves.size, moves, arrayOf(factor))).invariants.single()
            }
            DiagnosticPairwiseDistinct(factor, native)
        }
    })
}

private class DiagnosticPairwiseDistinct(private val relation: AllDifferent, native: Invariant) : Invariant by native {
    override fun violationDegree(state: LocalSearchState, factorId: Int): Int = degree(state)

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean = degree(state) != 0

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Long): Int {
        val oldValue = state.assignment.intValue(intVar)
        if (oldValue == newValue) return 0
        val oldCounted = oldValue !in relation.exceptSet
        val newCounted = newValue !in relation.exceptSet
        var delta = 0
        for (left in relation.vars.indices) {
            if (relation.vars[left] != intVar || !present(state, left)) continue
            for (right in relation.vars.indices) {
                if (right == left || (relation.vars[right] == intVar && right < left) ||
                    !present(state, right)
                ) continue
                val before = state.assignment.intValue(relation.vars[right])
                val after = if (relation.vars[right] == intVar) newValue else before
                if (oldCounted && before == oldValue) delta--
                if (newCounted && after == newValue) delta++
            }
        }
        return delta
    }

    override fun deltaIfBoolFlipped(state: LocalSearchState, factorId: Int, boolVar: Int): Int =
        degree(state, boolVar = boolVar) - degree(state)

    private fun degree(state: LocalSearchState, boolVar: Int = -1): Int {
        var pairs = 0
        for (left in relation.vars.indices) {
            if (!present(state, left, boolVar)) continue
            val value = state.assignment.intValue(relation.vars[left])
            if (value in relation.exceptSet) continue
            for (right in left + 1 until relation.vars.size) {
                if (present(state, right, boolVar) && state.assignment.intValue(relation.vars[right]) == value) pairs++
            }
        }
        return pairs
    }

    private fun present(state: LocalSearchState, position: Int, boolVar: Int = -1): Boolean {
        if (relation.presents.isEmpty()) return true
        val literal = relation.presents[position]
        val variable = Lit.variable(literal)
        return Lit.evaluate(literal, state.assignment.boolValue(variable) xor (variable == boolVar))
    }
}
