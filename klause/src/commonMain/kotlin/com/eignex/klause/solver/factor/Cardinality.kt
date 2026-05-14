package com.eignex.klause.solver.factor

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.MoveSink
import com.eignex.klause.solver.PropagationState
import com.eignex.klause.solver.SolverState

/**
 * `min ≤ (#true literals) ≤ max`. Payload at `intPayload[factorId]` is the count of true
 * literals. AtMostOne, AtLeastOne, ExactlyOne are special cases.
 */
class Cardinality(
    val literals: IntArray,
    val min: Int,
    val max: Int,
) : Factor {

    init {
        require(min in 0..max) { "Cardinality bounds invalid: $min..$max" }
        require(max <= literals.size) { "max ($max) exceeds literal count (${literals.size})" }
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
        var count = 0
        for (lit in literals) {
            if (Lit.evaluate(lit, state.assignment.boolValue(Lit.variable(lit)))) count++
        }
        state.intPayload[factorId] = count
    }

    override fun isViolated(state: SolverState, factorId: Int): Boolean {
        val n = state.intPayload[factorId]
        return n < min || n > max
    }

    override fun deltaIfBoolFlipped(state: SolverState, factorId: Int, boolVar: Int): Int {
        val pre = state.assignment.boolValue(boolVar)
        var change = 0
        for (lit in literals) {
            if (Lit.variable(lit) != boolVar) continue
            change += if (Lit.evaluate(lit, pre)) -1 else 1
        }
        val n = state.intPayload[factorId]
        val newN = n + change
        val wasViolated = n < min || n > max
        val willViolate = newN < min || newN > max
        return (if (willViolate) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    override fun applyBoolFlip(state: SolverState, factorId: Int, boolVar: Int): Int {
        val post = state.assignment.boolValue(boolVar)
        var change = 0
        for (lit in literals) {
            if (Lit.variable(lit) != boolVar) continue
            change += if (Lit.evaluate(lit, post)) 1 else -1
        }
        val oldN = state.intPayload[factorId]
        val newN = oldN + change
        state.intPayload[factorId] = newN
        val wasViolated = oldN < min || oldN > max
        val willViolate = newN < min || newN > max
        return (if (willViolate) 1 else 0) - (if (wasViolated) 1 else 0)
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
        if (trueCount > max) return false
        if (trueCount + unassigned < min) return false
        // Force remaining literals to false when at the upper bound.
        if (trueCount == max && unassigned > 0) {
            for (lit in literals) {
                val v = Lit.variable(lit)
                if (state.boolValues[v] != null) continue
                // literal must be false → pin var to ¬positivity
                if (!state.pinBool(v, !Lit.isPositive(lit))) return false
            }
            return true
        }
        // Force remaining literals to true when at the lower bound.
        if (trueCount + unassigned == min && unassigned > 0) {
            for (lit in literals) {
                val v = Lit.variable(lit)
                if (state.boolValues[v] != null) continue
                if (!state.pinBool(v, Lit.isPositive(lit))) return false
            }
        }
        return true
    }

    override fun proposeRepairMoves(state: SolverState, factorId: Int, sink: MoveSink) {
        val n = state.intPayload[factorId]
        if (n in min..max) return
        val wantIncrease = n < min
        if (boolVars.size == literals.size) {
            // Each var appears in exactly one literal — flip helps iff the lit is currently false.
            for (lit in literals) {
                val v = Lit.variable(lit)
                val isTrue = Lit.evaluate(lit, state.assignment.boolValue(v))
                val helpsIncrease = !isTrue
                if (wantIncrease == helpsIncrease) sink.addBoolFlip(v)
            }
            return
        }
        // Repeated-var fallback — aggregate the per-variable net change.
        for (v in boolVars) {
            var netChange = 0
            for (lit in literals) {
                if (Lit.variable(lit) != v) continue
                netChange += if (Lit.evaluate(lit, state.assignment.boolValue(v))) -1 else +1
            }
            if (wantIncrease && netChange > 0) sink.addBoolFlip(v)
            else if (!wantIncrease && netChange < 0) sink.addBoolFlip(v)
        }
    }

    companion object {
        fun atMostOne(literals: IntArray): Cardinality =
            Cardinality(literals, min = 0, max = 1)

        fun atLeastOne(literals: IntArray): Cardinality =
            Cardinality(literals, min = 1, max = literals.size)

        fun exactlyOne(literals: IntArray): Cardinality =
            Cardinality(literals, min = 1, max = 1)

        private val EMPTY: IntArray = IntArray(0)
    }
}
