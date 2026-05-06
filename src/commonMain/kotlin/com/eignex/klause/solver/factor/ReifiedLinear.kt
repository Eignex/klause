package com.eignex.klause.solver.factor

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.MoveSink
import com.eignex.klause.solver.SolverState

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
    override val isHard: Boolean = true,
    override val weight: Double = 1.0,
) : Factor {

    init {
        require(coeffs.size == vars.size) { "coeffs/vars length mismatch" }
        require(coeffs.isNotEmpty()) { "ReifiedLinear must have at least one term" }
    }

    override val boolVars: IntArray = intArrayOf(auxBoolVar)
    override val intVars: IntArray = vars

    private val coeffLookup: CoeffLookup = CoeffLookup.build(vars, coeffs)

    override fun initialize(state: SolverState, factorId: Int) {
        var sum = 0
        for (i in vars.indices) sum += coeffs[i] * state.assignment.intValue(vars[i])
        state.intPayload[factorId] = sum
    }

    override fun isViolated(state: SolverState, factorId: Int): Boolean {
        val aux = state.assignment.boolValue(auxBoolVar)
        val holds = holds(state.intPayload[factorId])
        return aux != holds
    }

    override fun deltaIfBoolFlipped(state: SolverState, factorId: Int, boolVar: Int): Int {
        val aux = state.assignment.boolValue(auxBoolVar)
        val holds = holds(state.intPayload[factorId])
        val wasViolated = aux != holds
        return if (wasViolated) -1 else +1
    }

    override fun deltaIfIntSet(state: SolverState, factorId: Int, intVar: Int, newValue: Int): Int {
        val aux = state.assignment.boolValue(auxBoolVar)
        val sum = state.intPayload[factorId]
        val coeff = coeffOf(intVar)
        val newSum = sum + coeff * (newValue - state.assignment.intValue(intVar))
        val wasViolated = aux != holds(sum)
        val willViolate = aux != holds(newSum)
        return (if (willViolate) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    override fun applyBoolFlip(state: SolverState, factorId: Int, boolVar: Int): Int {
        val aux = state.assignment.boolValue(auxBoolVar)
        val holds = holds(state.intPayload[factorId])
        val nowViolated = aux != holds
        return if (nowViolated) +1 else -1
    }

    override fun applyIntSet(state: SolverState, factorId: Int, intVar: Int, oldValue: Int): Int {
        val aux = state.assignment.boolValue(auxBoolVar)
        val coeff = coeffOf(intVar)
        val oldSum = state.intPayload[factorId]
        val newSum = oldSum + coeff * (state.assignment.intValue(intVar) - oldValue)
        state.intPayload[factorId] = newSum
        val wasViolated = aux != holds(oldSum)
        val nowViolated = aux != holds(newSum)
        return (if (nowViolated) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    override fun proposeRepairMoves(state: SolverState, factorId: Int, sink: MoveSink) {
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
}
