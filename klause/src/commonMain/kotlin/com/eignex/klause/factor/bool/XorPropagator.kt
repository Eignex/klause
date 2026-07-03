package com.eignex.klause.factor.bool

import com.eignex.klause.factor.CoeffLookup
import com.eignex.klause.factor.bool.internals.buildParityByVar
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.solver.Lit
import com.eignex.klause.util.IntHashSet

/** CP propagator for [Xor]: parity propagation over a list of Boolean literals. */
internal class XorPropagator(
    val boolVars: IntArray,
    val intVars: IntArray,
    private val literals: IntArray,
    private val targetParity: Int,
) : Propagator {

    /** Per-var parity contribution: precomputed `(occurrences in `literals`) and 1` per `boolVar`. */
    private val parityByVar: CoeffLookup = buildParityByVar(boolVars, literals)

    private fun parityOf(v: Int): Int = parityByVar.coeffOf(v)

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        var pinnedParity = 0
        val unassigned = IntHashSet()
        for (lit in literals) {
            val v = Lit.variable(lit)
            val b = state.boolValues[v]
            if (b == null) {
                unassigned.add(v)
            } else if (Lit.evaluate(lit, b)) {
                pinnedParity = pinnedParity xor 1
            }
        }
        var effective = -1
        var effectiveCount = 0
        unassigned.forEach { v ->
            if (parityOf(v) == 1) {
                effectiveCount++
                if (effectiveCount > 1) return true
                effective = v
            }
        }
        if (effectiveCount == 0) return pinnedParity == targetParity
        val v = effective
        var posOdd = 0
        var negOdd = 0
        for (lit in literals) {
            if (Lit.variable(lit) != v) continue
            if (Lit.isPositive(lit)) posOdd = posOdd xor 1 else negOdd = negOdd xor 1
        }
        val parityIfTrue = pinnedParity xor posOdd
        val parityIfFalse = pinnedParity xor negOdd
        return when {
            parityIfTrue == targetParity && parityIfFalse != targetParity ->
                state.pinBool(v, true, parityAntecedents(state, excludeVar = v))

            parityIfFalse == targetParity && parityIfTrue != targetParity ->
                state.pinBool(v, false, parityAntecedents(state, excludeVar = v))

            parityIfTrue != targetParity && parityIfFalse != targetParity -> false

            else -> true
        }
    }

    /**
     * Clause-form nogood when [propagate] returns false. XOR parity errors aren't natively
     * clause-form (an n-arity XOR needs 2^(n-1) clauses for full equivalence); we emit
     * the weak but sound "at least one of these currently-pinned literals must flip"
     * clause — i.e., the disjunction of currently-*false* literals across all assigned
     * vars. Blocks re-exploring the exact dead-end assignment; subsequent propagations
     * of the same XOR will still re-derive parity inferences from new pin paths.
     */
    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        parityAntecedents(state, excludeVar = -1)

    /** Collect one currently-false literal per pinned variable (excluding [excludeVar]).
     *  Each entry is the literal whose value is false under the current assignment for
     *  that variable; together they describe "the partial assignment that forced this
     *  pin / drove parity to violation". */
    private fun parityAntecedents(state: PropagationState, excludeVar: Int): IntArray? {
        var n = 0
        for (v in boolVars) {
            if (v == excludeVar) continue
            if (state.boolValues[v] != null) n++
        }
        if (n == 0) return null
        val out = IntArray(n)
        var w = 0
        for (v in boolVars) {
            if (v == excludeVar) continue
            val b = state.boolValues[v] ?: continue
            out[w++] = Lit.make(v, !b)
        }
        return out
    }
}
