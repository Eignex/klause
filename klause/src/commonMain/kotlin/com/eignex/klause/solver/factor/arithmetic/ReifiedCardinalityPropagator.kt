package com.eignex.klause.solver.factor.arithmetic

import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Propagator
import com.eignex.klause.solver.factor.bool.internals.pbFalseFormAntecedents
import com.eignex.klause.solver.propagation.PropagationState

/** CP propagator for [ReifiedCardinality]: reified cardinality propagation. */
internal class ReifiedCardinalityPropagator(
    private val auxBoolVar: Int,
    private val literals: IntArray,
    private val min: Int,
    private val max: Int,
    val boolVars: IntArray,
    val intVars: IntArray,
) : Propagator {

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? {
        val auxLit = state.boolValues[auxBoolVar]?.let { Lit.make(auxBoolVar, !it) } ?: 0
        return pbFalseFormAntecedents(state, literals, excludeVar = -1, extraLit = auxLit)
    }

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        var trueCount = 0
        var falseCount = 0
        for (lit in literals) {
            val v = Lit.variable(lit)
            val b = state.boolValues[v] ?: continue
            if (Lit.evaluate(lit, b)) trueCount++ else falseCount++
        }
        val unassigned = literals.size - trueCount - falseCount
        val minPossible = trueCount
        val maxPossible = trueCount + unassigned

        val definitelyIn = minPossible >= min && maxPossible <= max
        val definitelyOut = maxPossible < min || minPossible > max
        if (definitelyIn) {
            val ant = pbFalseFormAntecedents(state, literals, excludeVar = auxBoolVar, extraLit = 0)
            return state.pinBool(auxBoolVar, true, ant)
        }
        if (definitelyOut) {
            val ant = pbFalseFormAntecedents(state, literals, excludeVar = auxBoolVar, extraLit = 0)
            return state.pinBool(auxBoolVar, false, ant)
        }

        val aux = state.boolValues[auxBoolVar] ?: return true
        val auxAntecedent = Lit.make(auxBoolVar, !aux)
        if (aux) {
            if (trueCount == max && unassigned > 0) {
                val ant = pbFalseFormAntecedents(state, literals, excludeVar = -1, extraLit = auxAntecedent)
                for (lit in literals) {
                    val v = Lit.variable(lit)
                    if (state.boolValues[v] != null) continue
                    if (!state.pinBool(v, !Lit.isPositive(lit), ant)) return false
                }
            } else if (trueCount + unassigned == min && unassigned > 0) {
                val ant = pbFalseFormAntecedents(state, literals, excludeVar = -1, extraLit = auxAntecedent)
                for (lit in literals) {
                    val v = Lit.variable(lit)
                    if (state.boolValues[v] != null) continue
                    if (!state.pinBool(v, Lit.isPositive(lit), ant)) return false
                }
            }
            return true
        }

        if (unassigned == 0) return true
        val downBranchFeasible = trueCount < min
        val upBranchFeasible = trueCount + unassigned > max
        when {
            !downBranchFeasible && upBranchFeasible -> {
                val need = max - trueCount + 1
                if (need == unassigned) {
                    val ant = pbFalseFormAntecedents(state, literals, excludeVar = -1, extraLit = auxAntecedent)
                    for (lit in literals) {
                        val v = Lit.variable(lit)
                        if (state.boolValues[v] != null) continue
                        if (!state.pinBool(v, Lit.isPositive(lit), ant)) return false
                    }
                }
            }

            !upBranchFeasible && downBranchFeasible -> {
                val cap = min - trueCount - 1
                if (cap == 0) {
                    val ant = pbFalseFormAntecedents(state, literals, excludeVar = -1, extraLit = auxAntecedent)
                    for (lit in literals) {
                        val v = Lit.variable(lit)
                        if (state.boolValues[v] != null) continue
                        if (!state.pinBool(v, !Lit.isPositive(lit), ant)) return false
                    }
                }
            }
        }
        return true
    }
}
