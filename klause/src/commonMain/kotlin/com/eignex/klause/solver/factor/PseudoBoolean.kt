package com.eignex.klause.solver.factor

import com.eignex.klause.ast.PbOp
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.MoveSink
import com.eignex.klause.solver.SolverState

/**
 * `Σ weights[i] * lit_i ⟨op⟩ bound` over Boolean literals (each contributing its weight when
 * true, 0 when false). Payload at `intPayload[factorId]` is the current weighted sum.
 */
class PseudoBoolean(
    val weights: IntArray,
    val literals: IntArray,
    val op: PbOp,
    val bound: Int,
) : Factor {

    init {
        require(weights.size == literals.size) { "weights/literals length mismatch" }
        require(weights.isNotEmpty()) { "PseudoBoolean must have at least one term" }
    }

    override val boolVars: IntArray = run {
        val seen = LinkedHashSet<Int>()
        for (lit in literals) seen.add(Lit.variable(lit))
        val out = IntArray(seen.size)
        var i = 0
        for (v in seen) out[i++] = v
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

    override fun isViolated(state: SolverState, factorId: Int): Boolean =
        violates(state.intPayload[factorId])

    override fun deltaIfBoolFlipped(state: SolverState, factorId: Int, boolVar: Int): Int {
        val change = changeOnFlip(state, boolVar, current = true)
        val sum = state.intPayload[factorId]
        return (if (violates(sum + change)) 1 else 0) - (if (violates(sum)) 1 else 0)
    }

    override fun applyBoolFlip(state: SolverState, factorId: Int, boolVar: Int): Int {
        val change = changeOnFlip(state, boolVar, current = false)
        val oldSum = state.intPayload[factorId]
        val newSum = oldSum + change
        state.intPayload[factorId] = newSum
        return (if (violates(newSum)) 1 else 0) - (if (violates(oldSum)) 1 else 0)
    }

    override fun proposeRepairMoves(state: SolverState, factorId: Int, sink: MoveSink) {
        val sum = state.intPayload[factorId]
        if (!violates(sum)) return
        val curDist = distance(sum)
        for (i in literals.indices) {
            val lit = literals[i]
            val v = Lit.variable(lit)
            val isTrue = Lit.evaluate(lit, state.assignment.boolValue(v))
            val change = if (isTrue) -weights[i] else weights[i]
            // Propose any flip that doesn't worsen the violation distance, even neutral flips.
            // Strict reduction is too restrictive on tight constraints where no single flip
            // crosses the boundary; tabu and probSAT scoring break the resulting cycles.
            if (distance(sum + change) <= curDist) sink.addBoolFlip(v)
        }
    }

    private fun violates(sum: Int): Boolean = when (op) {
        PbOp.LE -> sum > bound
        PbOp.GE -> sum < bound
        PbOp.EQ -> sum != bound
    }

    private fun distance(sum: Int): Int = when (op) {
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
