package com.eignex.klause.solver.factor

import com.eignex.klause.ast.PbOp
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.MoveSink
import com.eignex.klause.solver.SolverState

/**
 * `auxBoolVar ↔ (Σ weights[i] * lit_i ⟨op⟩ bound)`. Payload at `intPayload[factorId]` is the
 * current weighted sum.
 */
class ReifiedPseudoBoolean(
    val auxBoolVar: Int,
    val weights: IntArray,
    val literals: IntArray,
    val op: PbOp,
    val bound: Int,
    override val isHard: Boolean = true,
    override val weight: Double = 1.0,
) : Factor {

    init {
        require(weights.size == literals.size) { "weights/literals length mismatch" }
        require(weights.isNotEmpty()) { "ReifiedPseudoBoolean must have at least one term" }
    }

    override val boolVars: IntArray = run {
        val unique = LinkedHashSet<Int>()
        unique.add(auxBoolVar)
        for (lit in literals) unique.add(Lit.variable(lit))
        val out = IntArray(unique.size)
        var i = 0
        for (v in unique) out[i++] = v
        out
    }
    override val intVars: IntArray = EMPTY

    override fun initialize(state: SolverState, factorId: Int) {
        var sum = 0
        for (i in literals.indices) {
            if (Lit.evaluate(literals[i], state.assignment.boolValue(Lit.variable(literals[i])))) {
                sum += weights[i]
            }
        }
        state.intPayload[factorId] = sum
    }

    override fun isViolated(state: SolverState, factorId: Int): Boolean {
        val aux = state.assignment.boolValue(auxBoolVar)
        return aux != predHolds(state.intPayload[factorId])
    }

    override fun deltaIfBoolFlipped(state: SolverState, factorId: Int, boolVar: Int): Int {
        val aux = state.assignment.boolValue(auxBoolVar)
        val sum = state.intPayload[factorId]
        val wasViolated = aux != predHolds(sum)
        if (boolVar == auxBoolVar) {
            return if (wasViolated) -1 else +1
        }
        val change = changeOnFlip(state, boolVar, current = true)
        val newSum = sum + change
        val willViolate = aux != predHolds(newSum)
        return (if (willViolate) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    override fun applyBoolFlip(state: SolverState, factorId: Int, boolVar: Int): Int {
        val aux = state.assignment.boolValue(auxBoolVar)
        val oldSum = state.intPayload[factorId]
        if (boolVar == auxBoolVar) {
            val nowViolated = aux != predHolds(oldSum)
            return if (nowViolated) +1 else -1
        }
        val change = changeOnFlip(state, boolVar, current = false)
        val newSum = oldSum + change
        state.intPayload[factorId] = newSum
        val wasViolated = aux != predHolds(oldSum)
        val nowViolated = aux != predHolds(newSum)
        return (if (nowViolated) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    override fun proposeRepairMoves(state: SolverState, factorId: Int, sink: MoveSink) {
        val aux = state.assignment.boolValue(auxBoolVar)
        val sum = state.intPayload[factorId]
        if (aux == predHolds(sum)) return
        sink.addBoolFlip(auxBoolVar)
        val wantHolds = aux
        val curDist = distanceToInRange(sum)
        for (i in literals.indices) {
            val lit = literals[i]
            val v = Lit.variable(lit)
            val isTrue = Lit.evaluate(lit, state.assignment.boolValue(v))
            val change = if (isTrue) -weights[i] else weights[i]
            val newDist = distanceToInRange(sum + change)
            // wantHolds=true: drive sum toward the satisfying region (newDist < curDist).
            // wantHolds=false: drive sum away from it (newDist > curDist).
            val improves = if (wantHolds) newDist < curDist else newDist > curDist
            if (improves) sink.addBoolFlip(v)
        }
    }

    private fun predHolds(sum: Int): Boolean = when (op) {
        PbOp.LE -> sum <= bound
        PbOp.GE -> sum >= bound
        PbOp.EQ -> sum == bound
    }

    private fun distanceToInRange(sum: Int): Int = when (op) {
        PbOp.LE -> if (sum > bound) sum - bound else 0
        PbOp.GE -> if (sum < bound) bound - sum else 0
        PbOp.EQ -> if (sum >= bound) sum - bound else bound - sum
    }

    private fun changeOnFlip(state: SolverState, boolVar: Int, current: Boolean): Int {
        val pre = if (current) state.assignment.boolValue(boolVar)
        else !state.assignment.boolValue(boolVar)
        var delta = 0
        for (i in literals.indices) {
            if (Lit.variable(literals[i]) != boolVar) continue
            delta += if (Lit.evaluate(literals[i], pre)) -weights[i] else weights[i]
        }
        return delta
    }

    private companion object {
        val EMPTY: IntArray = IntArray(0)
    }
}
