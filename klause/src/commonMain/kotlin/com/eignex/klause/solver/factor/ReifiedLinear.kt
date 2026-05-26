package com.eignex.klause.solver.factor

import com.eignex.klause.solver.localsearch.LocalSearchFactor
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.solver.propagation.PropagationState
import com.eignex.klause.solver.localsearch.LocalSearchState

/**
 * `auxBoolVar ↔ (Σ coeffs[i] * intVars[i] ⟨op⟩ bound)`. Created by the compiler when a
 * multi-variable [com.eignex.klause.ast.IntCompare] appears non-top-level so the rest of the
 * Tseitin lowering can treat its truth as a Boolean literal. Payload at `intPayload[factorId]`
 * is the current weighted sum, mirrored from [Linear].
 */
class ReifiedLinear(
    val auxBoolVar: Int,
    val coeffs: IntArray,
    val vars: IntArray,
    val op: LinearOp,
    val bound: Int,
) : LocalSearchFactor {

    init {
        require(coeffs.size == vars.size) { "coeffs/vars length mismatch" }
        require(coeffs.isNotEmpty()) { "ReifiedLinear must have at least one term" }
    }

    override val boolVars: IntArray = intArrayOf(auxBoolVar)
    override val intVars: IntArray = vars

    private val coeffLookup: CoeffLookup = CoeffLookup.build(vars, coeffs)

    override fun initialize(state: LocalSearchState, factorId: Int) {
        var sum = 0
        for (i in vars.indices) sum += coeffs[i] * state.assignment.intValue(vars[i])
        state.intPayload[factorId] = sum
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean {
        val aux = state.assignment.boolValue(auxBoolVar)
        val holds = holds(state.intPayload[factorId])
        return aux != holds
    }

    override fun deltaIfBoolFlipped(state: LocalSearchState, factorId: Int, boolVar: Int): Int {
        val aux = state.assignment.boolValue(auxBoolVar)
        val holds = holds(state.intPayload[factorId])
        val wasViolated = aux != holds
        return if (wasViolated) -1 else +1
    }

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int {
        val aux = state.assignment.boolValue(auxBoolVar)
        val sum = state.intPayload[factorId]
        val coeff = coeffOf(intVar)
        val newSum = sum + coeff * (newValue - state.assignment.intValue(intVar))
        val wasViolated = aux != holds(sum)
        val willViolate = aux != holds(newSum)
        return (if (willViolate) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    override fun applyBoolFlip(state: LocalSearchState, factorId: Int, boolVar: Int): Int {
        val aux = state.assignment.boolValue(auxBoolVar)
        val holds = holds(state.intPayload[factorId])
        val nowViolated = aux != holds
        return if (nowViolated) +1 else -1
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int {
        val aux = state.assignment.boolValue(auxBoolVar)
        val coeff = coeffOf(intVar)
        val oldSum = state.intPayload[factorId]
        val newSum = oldSum + coeff * (state.assignment.intValue(intVar) - oldValue)
        state.intPayload[factorId] = newSum
        val wasViolated = aux != holds(oldSum)
        val nowViolated = aux != holds(newSum)
        return (if (nowViolated) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        val range = linearSumRange(state, coeffs, vars)
        val sumLo = range[0]
        val sumHi = range[1]
        val bnd = bound.toLong()
        val alwaysHolds = when (op) {
            LinearOp.LE -> sumHi <= bnd
            LinearOp.GE -> sumLo >= bnd
            LinearOp.EQ -> sumLo == bnd && sumHi == bnd
            LinearOp.NE -> sumHi < bnd || sumLo > bnd
        }
        val neverHolds = when (op) {
            LinearOp.LE -> sumLo > bnd
            LinearOp.GE -> sumHi < bnd
            LinearOp.EQ -> sumLo > bnd || sumHi < bnd
            LinearOp.NE -> sumLo == bnd && sumHi == bnd
        }
        // Aux pin antecedents: union of the int-fact antecedents that drove sumLo/sumHi
        // into the always/never-holds region. LCG-style transitive reasoning — each int
        // bound's recorded `intMinAntecedents` / `intMaxAntecedents` traces back to the
        // bool decisions that established it.
        if (alwaysHolds) {
            val ant = composeAuxAntecedents(state)
            return state.pinBool(auxBoolVar, true, ant)
        }
        if (neverHolds) {
            val ant = composeAuxAntecedents(state)
            return state.pinBool(auxBoolVar, false, ant)
        }

        val aux = state.boolValues[auxBoolVar] ?: return true
        // Thread the aux's current pinning as an extra antecedent for every implied int
        // tighten — the body-propagation path was selected by this pin, so any subsequent
        // conflict must trace back through it.
        val auxAntecedent = com.eignex.klause.solver.Lit.make(auxBoolVar, !aux)
        return if (aux) {
            propagateLinearBounds(state, coeffs, vars, op, bnd, extraLit = auxAntecedent)
        } else {
            when (op) {
                LinearOp.LE -> propagateLinearBounds(state, coeffs, vars, LinearOp.GE, bnd + 1, extraLit = auxAntecedent)
                LinearOp.GE -> propagateLinearBounds(state, coeffs, vars, LinearOp.LE, bnd - 1, extraLit = auxAntecedent)
                LinearOp.EQ -> propagateLinearBounds(state, coeffs, vars, LinearOp.NE, bnd, extraLit = auxAntecedent)
                LinearOp.NE -> propagateLinearBounds(state, coeffs, vars, LinearOp.EQ, bnd, extraLit = auxAntecedent)
            }
        }
    }

    /**
     * Compose aux-pin antecedents as per-bound atom-lits over the involved vars. For each
     * var with `min` tighter than its initial domain, emit `¬[v ≥ d.min]`; similarly for
     * the `max` side. The implicit clause `(⋀ premise atom-lits) → aux` resolves cleanly
     * through 1UIP / self-subsuming minimization with finer granularity than the older
     * bool-lit union would have given.
     */
    private fun composeAuxAntecedents(state: PropagationState): IntArray? =
        state.composeIntVarAtomAntecedents(vars)

    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        val aux = state.assignment.boolValue(auxBoolVar)
        val sum = state.intPayload[factorId]
        if (aux == holds(sum)) return
        sink.addBoolFlip(auxBoolVar)
        for (i in vars.indices) {
            val v = vars[i]
            val c = coeffs[i]
            if (c == 0) continue
            val cur = state.assignment.intValue(v)
            val sumWithout = sum - c * cur
            val target = snapTarget(c, sumWithout, aux) ?: continue
            val clamped = state.problem.intDomains[v].clamp(target)
            if (clamped != cur && (aux == holds(sumWithout + c * clamped))) {
                sink.addIntSet(v, clamped)
            }
        }
    }

    private fun holds(sum: Int): Boolean = when (op) {
        LinearOp.LE -> sum <= bound
        LinearOp.EQ -> sum == bound
        LinearOp.GE -> sum >= bound
        LinearOp.NE -> sum != bound
    }

    private fun coeffOf(intVar: Int): Int = coeffLookup.coeffOf(intVar)

    private fun snapTarget(coeff: Int, sumWithout: Int, wantHolds: Boolean): Int? {
        // For the canonical "want sum_with_v op bound" direction (wantHolds=true) the snap is
        // the integer value that makes the equality hold. When wantHolds=false we snap to a
        // value that violates the predicate by one unit.
        val numerator = bound - sumWithout
        if (coeff == 0) return null
        val targetEq = numerator / coeff
        return when (op) {
            LinearOp.EQ -> when {
                wantHolds && numerator % coeff != 0 -> null   // no integer satisfies coeff·v = numerator
                wantHolds -> targetEq
                else -> targetEq + 1
            }
            LinearOp.LE -> if (wantHolds) {
                if (coeff > 0) floorDiv(numerator, coeff) else ceilDiv(numerator, coeff)
            } else {
                if (coeff > 0) floorDiv(numerator, coeff) + 1 else ceilDiv(numerator, coeff) - 1
            }
            LinearOp.GE -> if (wantHolds) {
                if (coeff > 0) ceilDiv(numerator, coeff) else floorDiv(numerator, coeff)
            } else {
                if (coeff > 0) ceilDiv(numerator, coeff) - 1 else floorDiv(numerator, coeff) + 1
            }
            LinearOp.NE -> when {
                // wantHolds (sum ≠ bound): bump var to either side of the equality value.
                wantHolds -> if (numerator % coeff == 0) targetEq + 1 else null
                // !wantHolds (sum == bound): only feasible if numerator divisible by coeff.
                numerator % coeff == 0 -> targetEq
                else -> null
            }
        }
    }

    private fun floorDiv(a: Int, b: Int): Int {
        val q = a / b
        val r = a % b
        return if (r != 0 && (r xor b) < 0) q - 1 else q
    }

    private fun ceilDiv(a: Int, b: Int): Int {
        val q = a / b
        val r = a % b
        return if (r != 0 && (r xor b) >= 0) q + 1 else q
    }

    override val maintainsBreakMakeIncrementally: Boolean get() = true

    /** [boolVars] contains only [auxBoolVar], so a bool flip is always an aux flip. Flipping
     *  aux always toggles violation (sum unchanged), so the aux's own contribution simply
     *  swaps between break and make. */
    override fun updateBoolBreakMakeForFlip(
        state: LocalSearchState, factorId: Int, flippedVar: Int,
    ) {
        val nowViolated = state.assignment.boolValue(auxBoolVar) != holds(state.intPayload[factorId])
        if (nowViolated) {
            state.boolBreakCount[auxBoolVar]--
            state.boolMakeCount[auxBoolVar]++
        } else {
            state.boolMakeCount[auxBoolVar]--
            state.boolBreakCount[auxBoolVar]++
        }
    }
}
