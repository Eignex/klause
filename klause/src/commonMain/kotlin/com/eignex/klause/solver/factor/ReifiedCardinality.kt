package com.eignex.klause.solver.factor

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.MoveSink
import com.eignex.klause.solver.PropagationState
import com.eignex.klause.solver.SolverState

/**
 * `auxBoolVar ↔ (#true literals in [min, max])`. Created by the compiler when a
 * [com.eignex.klause.ast.CardinalityExpr] / `AtMost` / `AtLeast` appears non-top-level so the
 * Tseitin lowering can treat its truth as a Boolean literal. Payload at `intPayload[factorId]`
 * is the count of true literals, mirrored from [Cardinality].
 */
class ReifiedCardinality(
    val auxBoolVar: Int,
    val literals: IntArray,
    val min: Int,
    val max: Int,
) : Factor {

    init {
        require(min in 0..max) { "Cardinality bounds invalid: $min..$max" }
        require(max <= literals.size) { "max ($max) exceeds literal count (${literals.size})" }
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
        var count = 0
        for (lit in literals) {
            if (Lit.evaluate(lit, state.assignment.boolValue(Lit.variable(lit)))) count++
        }
        state.intPayload[factorId] = count
    }

    override fun isViolated(state: SolverState, factorId: Int): Boolean {
        val aux = state.assignment.boolValue(auxBoolVar)
        val holds = inRange(state.intPayload[factorId])
        return aux != holds
    }

    override fun deltaIfBoolFlipped(state: SolverState, factorId: Int, boolVar: Int): Int {
        val aux = state.assignment.boolValue(auxBoolVar)
        val n = state.intPayload[factorId]
        val wasViolated = aux != inRange(n)
        if (boolVar == auxBoolVar) {
            // aux flips; payload unchanged.
            return if (wasViolated) -1 else +1
        }
        // Some constrained literal flips: count changes by net effect.
        val change = changeOnFlip(state, boolVar, current = true)
        val newN = n + change
        val willViolate = aux != inRange(newN)
        return (if (willViolate) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    override fun applyBoolFlip(state: SolverState, factorId: Int, boolVar: Int): Int {
        val aux = state.assignment.boolValue(auxBoolVar)
        val oldN = state.intPayload[factorId]
        if (boolVar == auxBoolVar) {
            val nowViolated = aux != inRange(oldN)
            return if (nowViolated) +1 else -1
        }
        val change = changeOnFlip(state, boolVar, current = false)
        val newN = oldN + change
        state.intPayload[factorId] = newN
        val wasViolated = aux != inRange(oldN)
        val nowViolated = aux != inRange(newN)
        return (if (nowViolated) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    /**
     * Δ to payload count from flipping `boolVar`. With `current = true` the assignment still
     * holds the pre-flip value (used by [deltaIfBoolFlipped]); with `current = false` the
     * assignment has been updated (used by [applyBoolFlip]).
     */
    private fun changeOnFlip(state: SolverState, boolVar: Int, current: Boolean): Int {
        val pre = if (current) state.assignment.boolValue(boolVar)
        else !state.assignment.boolValue(boolVar)
        var delta = 0
        for (lit in literals) {
            if (Lit.variable(lit) != boolVar) continue
            // Pre-flip evaluation uses pre; the flip inverts that.
            delta += if (Lit.evaluate(lit, pre)) -1 else 1
        }
        return delta
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

        // Fact about the body: definitely in [min, max], or definitely outside?
        val definitelyIn = minPossible >= min && maxPossible <= max
        val definitelyOut = maxPossible < min || minPossible > max
        if (definitelyIn) return state.pinBool(auxBoolVar, true)
        if (definitelyOut) return state.pinBool(auxBoolVar, false)

        // When aux is pinned true, do the same forcing Cardinality does.
        val aux = state.boolValues[auxBoolVar] ?: return true
        if (!aux) return true  // ¬aux: forcing logic is rarely productive at this scope; skip.

        if (trueCount == max && unassigned > 0) {
            for (lit in literals) {
                val v = Lit.variable(lit)
                if (state.boolValues[v] != null) continue
                if (!state.pinBool(v, !Lit.isPositive(lit))) return false
            }
        } else if (trueCount + unassigned == min && unassigned > 0) {
            for (lit in literals) {
                val v = Lit.variable(lit)
                if (state.boolValues[v] != null) continue
                if (!state.pinBool(v, Lit.isPositive(lit))) return false
            }
        }
        return true
    }

    override fun proposeRepairMoves(state: SolverState, factorId: Int, sink: MoveSink) {
        val aux = state.assignment.boolValue(auxBoolVar)
        val n = state.intPayload[factorId]
        if (aux == inRange(n)) return
        sink.addBoolFlip(auxBoolVar)
        // For each literal, flipping it shifts count by ±1; only propose flips that move
        // count toward the desired direction.
        val wantInRange = aux
        for (lit in literals) {
            val v = Lit.variable(lit)
            val isTrue = Lit.evaluate(lit, state.assignment.boolValue(v))
            val newN = n + if (isTrue) -1 else 1
            if (wantInRange == inRange(newN)) sink.addBoolFlip(v)
        }
    }

    private fun inRange(count: Int): Boolean = count in min..max

    private companion object {
        val EMPTY: IntArray = IntArray(0)
    }
}
