package com.eignex.klause.solver.factor

import com.eignex.klause.ast.PbOp
import com.eignex.klause.solver.localsearch.LocalSearchFactor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.MoveSink
import com.eignex.klause.solver.propagation.PropagationState
import com.eignex.klause.solver.localsearch.SolverState

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
) : LocalSearchFactor {

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

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        val range = pbSumRange(state, weights, literals)
        val sumLo = range[0]
        val sumHi = range[1]
        val bnd = bound.toLong()
        val alwaysHolds = when (op) {
            PbOp.LE -> sumHi <= bnd
            PbOp.GE -> sumLo >= bnd
            PbOp.EQ -> sumLo == bnd && sumHi == bnd
        }
        val neverHolds = when (op) {
            PbOp.LE -> sumLo > bnd
            PbOp.GE -> sumHi < bnd
            PbOp.EQ -> sumLo > bnd || sumHi < bnd
        }
        if (alwaysHolds) return state.pinBool(auxBoolVar, true)
        if (neverHolds) return state.pinBool(auxBoolVar, false)

        val aux = state.boolValues[auxBoolVar] ?: return true
        return if (aux) {
            propagatePbBounds(state, weights, literals, op, bnd)
        } else when (op) {
            PbOp.LE -> propagatePbBounds(state, weights, literals, PbOp.GE, bnd + 1)
            PbOp.GE -> propagatePbBounds(state, weights, literals, PbOp.LE, bnd - 1)
            // ¬EQ → "sum ≠ bound". No PbOp.NE exists; inline the NE bounds-check (analog
            // of `LinearOp.NE` in `propagateLinearBounds`).
            PbOp.EQ -> propagatePbNotEqual(state, weights, literals, bnd)
        }
    }

    /**
     * Propagate `Σ weights[i] · lit_i ≠ bound`. Returns `false` iff the constraint is
     * infeasible — i.e. the sum is forced to be exactly [bound] regardless of remaining
     * free literals. Otherwise prunes any single literal whose two polarities would both
     * collapse the sum to [bound] (rare; usually nothing to prune).
     */
    private fun propagatePbNotEqual(
        state: PropagationState,
        weights: IntArray,
        literals: IntArray,
        bound: Long,
    ): Boolean {
        val n = literals.size
        val litLo = LongArray(n)
        val litHi = LongArray(n)
        var sumLo = 0L
        var sumHi = 0L
        for (i in 0 until n) {
            val w = weights[i].toLong()
            val v = Lit.variable(literals[i])
            val b = state.boolValues[v]
            val lo: Long
            val hi: Long
            when {
                b == null -> { lo = minOf(0L, w); hi = maxOf(0L, w) }
                Lit.evaluate(literals[i], b) -> { lo = w; hi = w }
                else -> { lo = 0L; hi = 0L }
            }
            litLo[i] = lo
            litHi[i] = hi
            sumLo += lo
            sumHi += hi
        }
        if (sumLo == bound && sumHi == bound) return false
        for (i in 0 until n) {
            val w = weights[i].toLong()
            if (w == 0L) continue
            val v = Lit.variable(literals[i])
            if (state.boolValues[v] != null) continue
            val otherLo = sumLo - litLo[i]
            val otherHi = sumHi - litHi[i]
            // Polarity "literal true" contributes `w` to the sum.
            val trueOk = !(otherLo + w == bound && otherHi + w == bound)
            val falseOk = !(otherLo == bound && otherHi == bound)
            if (!trueOk && !falseOk) return false
            if (!trueOk) {
                if (!state.pinBool(v, !Lit.isPositive(literals[i]))) return false
            } else if (!falseOk) {
                if (!state.pinBool(v, Lit.isPositive(literals[i]))) return false
            }
        }
        return true
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
            // wantHolds=true: drive sum toward the satisfying region (newDist ≤ curDist).
            // wantHolds=false: drive sum away from it (newDist ≥ curDist).
            // Allow neutral flips so tight constraints don't stall; tabu / probSAT scoring break cycles.
            val improves = if (wantHolds) newDist <= curDist else newDist >= curDist
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
