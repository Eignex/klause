package com.eignex.klause.solver.factor

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.MoveSink
import com.eignex.klause.solver.SolverState

/**
 * `XOR(lit_1, ..., lit_n) == targetParity`. `targetParity = 1` means an odd number of literals
 * must be true; `targetParity = 0` means even. Payload at `intPayload[factorId]` is the current
 * parity (0 or 1). Each Boolean flip toggles the parity exactly once per occurrence of that var
 * in the literal list.
 */
class Xor(
    val literals: IntArray,
    val targetParity: Int,
    override val isHard: Boolean = true,
    override val weight: Double = 1.0,
) : Factor {

    init {
        require(literals.isNotEmpty()) { "Xor needs at least one literal" }
        require(targetParity == 0 || targetParity == 1) { "targetParity must be 0 or 1" }
    }

    override val boolVars: IntArray = run {
        val unique = LinkedHashSet<Int>()
        for (lit in literals) unique.add(Lit.variable(lit))
        val out = IntArray(unique.size)
        var i = 0
        for (v in unique) out[i++] = v
        out
    }
    override val intVars: IntArray = EMPTY

    override fun initialize(state: SolverState, factorId: Int) {
        var parity = 0
        for (lit in literals) {
            if (Lit.evaluate(lit, state.assignment.boolValue(Lit.variable(lit)))) parity = parity xor 1
        }
        state.intPayload[factorId] = parity
    }

    override fun isViolated(state: SolverState, factorId: Int): Boolean =
        state.intPayload[factorId] != targetParity

    override fun deltaIfBoolFlipped(state: SolverState, factorId: Int, boolVar: Int): Int {
        val parity = state.intPayload[factorId]
        val toggles = countOccurrences(boolVar)
        val newParity = parity xor (toggles and 1)
        val wasViolated = parity != targetParity
        val willViolate = newParity != targetParity
        return (if (willViolate) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    override fun applyBoolFlip(state: SolverState, factorId: Int, boolVar: Int): Int {
        val oldParity = state.intPayload[factorId]
        val toggles = countOccurrences(boolVar)
        val newParity = oldParity xor (toggles and 1)
        state.intPayload[factorId] = newParity
        val wasViolated = oldParity != targetParity
        val nowViolated = newParity != targetParity
        return (if (nowViolated) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    override fun proposeRepairMoves(state: SolverState, factorId: Int, sink: MoveSink) {
        if (state.intPayload[factorId] == targetParity) return
        // Flipping any literal whose variable appears an odd number of times in the list toggles
        // parity. In typical use each variable appears once, so any flip works.
        for (v in boolVars) {
            if ((countOccurrences(v) and 1) == 1) sink.addBoolFlip(v)
        }
    }

    private fun countOccurrences(boolVar: Int): Int {
        var n = 0
        for (lit in literals) if (Lit.variable(lit) == boolVar) n++
        return n
    }

    private companion object {
        val EMPTY: IntArray = IntArray(0)
    }
}
