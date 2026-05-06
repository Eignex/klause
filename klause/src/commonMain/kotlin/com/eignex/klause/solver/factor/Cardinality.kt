package com.eignex.klause.solver.factor

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.MoveSink
import com.eignex.klause.solver.SolverState

/**
 * `min ≤ (#true literals) ≤ max`. Payload at `intPayload[factorId]` is the count of true
 * literals. AtMostOne, AtLeastOne, ExactlyOne are special cases.
 */
class Cardinality(
    val literals: IntArray,
    val min: Int,
    val max: Int,
    override val isHard: Boolean = true,
    override val weight: Double = 1.0,
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

    override fun proposeRepairMoves(state: SolverState, factorId: Int, sink: MoveSink) {
        val n = state.intPayload[factorId]
        if (n in min..max) return
        val wantIncrease = n < min
        if (boolVars.size == literals.size) {
            // Fast path: each variable appears in exactly one literal. The flip's effect on
            // the count is +1 iff the lit is currently false.
            for (lit in literals) {
                val v = Lit.variable(lit)
                val isTrue = Lit.evaluate(lit, state.assignment.boolValue(v))
                val helpsIncrease = !isTrue
                if (wantIncrease == helpsIncrease) sink.addBoolFlip(v)
            }
            return
        }
        // Slow path: a variable may appear in multiple literals; aggregate the net change.
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
        fun atMostOne(literals: IntArray, isHard: Boolean = true, weight: Double = 1.0): Cardinality =
            Cardinality(literals, min = 0, max = 1, isHard = isHard, weight = weight)

        fun atLeastOne(literals: IntArray, isHard: Boolean = true, weight: Double = 1.0): Cardinality =
            Cardinality(literals, min = 1, max = literals.size, isHard = isHard, weight = weight)

        fun exactlyOne(literals: IntArray, isHard: Boolean = true, weight: Double = 1.0): Cardinality =
            Cardinality(literals, min = 1, max = 1, isHard = isHard, weight = weight)

        private val EMPTY: IntArray = IntArray(0)
    }
}
