package com.eignex.klause.factor.table

import com.eignex.klause.factor.remapVars
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.lp.Linearizer
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.FactorKind
import com.eignex.klause.solver.StructuralKey

/**
 * `regular(seq, Q, S, d, q0, F)` — the sequence `seq` is accepted by the DFA with
 * `Q` states, alphabet size `S`, transition function [transitions] indexed by
 * `(q-1) * S + (s-1)` (row-major, 1-based states and symbols), initial state [q0],
 * and accepting set [accepting]. A transition value of `0` denotes rejection (the
 * "dead state").
 *
 * Decomposed propagation: when every `seq(i)` is singleton, simulate the run and verify
 * acceptance.
 */
class Regular(
    /** Input symbol sequence variable ids. */
    val seq: IntArray,
    /** Number of DFA states. */
    val numStates: Int,
    /** Number of input symbols. */
    val alphabetSize: Int,
    /** `numStates × alphabetSize` row-major transition table; 0 means no transition. */
    val transitions: IntArray,
    /** Initial state. */
    val q0: Int,
    /** Accepting states. */
    val accepting: IntArray,
) : Factor {

    init {
        require(seq.isNotEmpty()) { "regular: empty seq" }
        require(numStates >= 1) { "regular: numStates ≥ 1" }
        require(alphabetSize >= 1) { "regular: alphabetSize ≥ 1" }
        require(transitions.size == numStates * alphabetSize) {
            "regular: transitions must be Q*S = ${numStates * alphabetSize} entries, got ${transitions.size}"
        }
        require(q0 in 1..numStates) { "regular: q0 ($q0) out of [1, $numStates]" }
    }

    override fun remap(boolMap: IntArray, intMap: IntArray): Factor =
        Regular(seq.remapVars(intMap), numStates, alphabetSize, transitions, q0, accepting)

    /** Position-faithful (seq position i matters): keeps the sequence vars in order and folds in the
     *  whole automaton — state/alphabet sizes, the transition table, the initial and accepting states
     *  (#531). */
    override fun structuralKey(): StructuralKey = StructuralKey.of(FactorKind.REGULAR) {
        int(numStates)
        int(alphabetSize)
        int(q0)
        ints(transitions)
        ints(accepting)
        ints(seq)
    }

    /** Symbol-alphabet relabeling (#536): the `seq` values *are* the symbols, so a value permutation
     *  permutes the transition table's symbol axis — `δ'(q, valueMap(s)) = δ(q, s)`. Sound because
     *  Regular has no positional-variable/constant coupling (unlike Element): swapping symbol values in
     *  a sequence and the matching columns preserves acceptance exactly. Returns `null` if [valueMap]
     *  is not a permutation of `1..alphabetSize` (then it can't relabel this automaton's columns). */
    override fun remapValues(valueMap: (Int) -> Int): Factor? {
        val target = IntArray(alphabetSize + 1) // 1-based symbols
        val seen = BooleanArray(alphabetSize + 1)
        for (s in 1..alphabetSize) {
            val t = valueMap(s)
            if (t < 1 || t > alphabetSize || seen[t]) return null
            seen[t] = true
            target[s] = t
        }
        val newTransitions = IntArray(transitions.size)
        for (q in 1..numStates) {
            for (s in 1..alphabetSize) {
                newTransitions[(q - 1) * alphabetSize + (target[s] - 1)] = transitions[(q - 1) * alphabetSize + (s - 1)]
            }
        }
        return Regular(seq, numStates, alphabetSize, newTransitions, q0, accepting)
    }

    override val boolVars: IntArray = EmptyIntArray
    override val intVars: IntArray = seq

    override fun asPropagator(): Propagator =
        RegularPropagator(boolVars, intVars, seq, numStates, alphabetSize, transitions, q0, accepting)

    override fun asInvariant(): Invariant = RegularInvariant(seq, numStates, alphabetSize, transitions, q0, accepting)

    override fun asLinearizer(): Linearizer =
        RegularLinearizer(seq, numStates, alphabetSize, transitions, q0, accepting)

    /*
     * Pesant's layered-DAG GAC, now reversible and delta-driven (see `RegularIncrementalState`):
     * per layer a state-bitset records forward-reachability from q0 and backward-co-reachability to
     * an accepting state, both on the engine undo trail. A symbol `s ∈ dom(seq[i])` survives iff some
     * forward-reachable state at `i` transitions on it to a co-reachable state at `i+1`; the conflict
     * is the initial state losing co-reachability at layer 0. A fire recomputes only the layers a
     * changed position reaches, instead of the whole `O(n · Q · |Σ|)` DFA each time.
     */
}
