package com.eignex.klause.solver.factor

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.localsearch.LocalSearchFactor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.solver.propagation.PropagationState
import com.eignex.klause.solver.localsearch.LocalSearchState

/**
 * `XOR(lit_1, ..., lit_n) == targetParity`. `targetParity = 1` means an odd number of literals
 * must be true; `targetParity = 0` means even. Payload at `intPayload[factorId]` is the current
 * parity (0 or 1). Each Boolean flip toggles the parity exactly once per occurrence of that var
 * in the literal list.
 */
class Xor(
    val literals: IntArray,
    val targetParity: Int,
) : LocalSearchFactor {

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
    override val intVars: IntArray = EmptyIntArray

    /** Per-var parity contribution: precomputed `(occurrences in `literals`) and 1` per `boolVar`.
     *  Flipping a var toggles factor parity by exactly this amount. */
    private val parityByVar: CoeffLookup = run {
        val parities = IntArray(boolVars.size)
        for (i in boolVars.indices) {
            var n = 0
            for (lit in literals) if (Lit.variable(lit) == boolVars[i]) n++
            parities[i] = n and 1
        }
        CoeffLookup.build(boolVars, parities)
    }

    override fun initialize(state: LocalSearchState, factorId: Int) {
        var parity = 0
        for (lit in literals) {
            if (Lit.evaluate(lit, state.assignment.boolValue(Lit.variable(lit)))) parity = parity xor 1
        }
        state.intPayload[factorId] = parity
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean =
        state.intPayload[factorId] != targetParity

    override fun deltaIfBoolFlipped(state: LocalSearchState, factorId: Int, boolVar: Int): Int {
        val parity = state.intPayload[factorId]
        val newParity = parity xor parityByVar.coeffOf(boolVar)
        val wasViolated = parity != targetParity
        val willViolate = newParity != targetParity
        return (if (willViolate) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    override fun applyBoolFlip(state: LocalSearchState, factorId: Int, boolVar: Int): Int {
        val oldParity = state.intPayload[factorId]
        val newParity = oldParity xor parityByVar.coeffOf(boolVar)
        state.intPayload[factorId] = newParity
        val wasViolated = oldParity != targetParity
        val nowViolated = newParity != targetParity
        return (if (nowViolated) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        // Walk literals once: tally parity from pinned literals, collect unassigned variables.
        var pinnedParity = 0
        val unassigned = LinkedHashSet<Int>()
        for (lit in literals) {
            val v = Lit.variable(lit)
            val b = state.boolValues[v]
            if (b == null) unassigned.add(v)
            else if (Lit.evaluate(lit, b)) pinnedParity = pinnedParity xor 1
        }
        // Only variables with odd-count occurrences ("effective") affect parity.
        var effective = -1
        var effectiveCount = 0
        for (v in unassigned) {
            if (parityByVar.coeffOf(v) == 1) {
                effectiveCount++
                if (effectiveCount > 1) return true  // 2+ effective: parity not yet decidable
                effective = v
            }
        }
        if (effectiveCount == 0) return pinnedParity == targetParity
        // Exactly one effective unassigned var: forced. Determine the value matching targetParity.
        val v = effective
        // Contribution when v=true: parity of "+v" occurrences (those evaluate true with v=true).
        // When v=false: parity of "-v" occurrences (those evaluate true with v=false).
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
            else -> true  // both work (shouldn't happen when var has odd parity contribution)
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
    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? {
        return parityAntecedents(state, excludeVar = -1)
    }

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
            out[w++] = Lit.make(v, !b)  // the literal that's currently false for this var
        }
        return out
    }

    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        if (state.intPayload[factorId] == targetParity) return
        // Flipping any literal whose variable appears an odd number of times in the list toggles
        // parity. In typical use each variable appears once, so any flip works.
        for (v in boolVars) {
            if (parityByVar.coeffOf(v) == 1) sink.addBoolFlip(v)
        }
    }}
