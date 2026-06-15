package com.eignex.klause.solver.factor

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.solver.propagation.IntEvent
import com.eignex.klause.solver.propagation.PropagationState
import com.eignex.klause.util.IntHashSet

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

    /** Accepting states as a primitive set for O(1) boxing-free membership in the hot
     *  acceptance checks (`q in acceptingSet`). */
    internal val acceptingSet: IntHashSet = run {
        val s = IntHashSet(accepting.size)
        for (q in accepting) s.add(q)
        s
    }

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
    override fun structuralKey(): String = "regular:$numStates:$alphabetSize:$q0:${transitions.joinToString(",")}:" +
        "${accepting.joinToString(",")}:${seq.joinToString(",")}"

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

    /** Advisor subscription (#623): GAC over interior domains, so subscribe to every kind on every
     *  (distinct) sequence variable and consume the dirty-variable delta (#624) — the incremental
     *  propagator ([RegularIncrementalState]) recomputes only the layers a changed position reaches. */
    override val initialIntEventWatches: IntArray = run {
        val distinct = seq.toHashSet()
        val out = IntArray(distinct.size * IntEvent.COUNT)
        var w = 0
        for (v in distinct) {
            out[w++] = IntEvent.pack(v, IntEvent.LB_RAISED)
            out[w++] = IntEvent.pack(v, IntEvent.UB_LOWERED)
            out[w++] = IntEvent.pack(v, IntEvent.VALUE_REMOVED)
            out[w++] = IntEvent.pack(v, IntEvent.FIXED)
        }
        out
    }

    override val consumesIntEventDelta: Boolean = true

    /** Look up `δ(state, symbol)` with 1-based addressing. Returns 0 for the dead state. */
    private fun delta(state: Int, symbol: Int): Int {
        if (state < 1 || state > numStates) return 0
        if (symbol < 1 || symbol > alphabetSize) return 0
        return transitions[(state - 1) * alphabetSize + (symbol - 1)]
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean = !accepts(state)

    /** Graded violation: the minimum number of sequence positions whose symbol must change for
     *  the DFA to accept — an edit-distance-to-language computed by a forward DP over states —
     *  compressed. `0` iff the current string is accepted; a string whose length admits no
     *  accepted word saturates at `seq.size + 1`. Gives CBLS a gradient toward acceptance
     *  instead of a flat boolean. */
    override fun violationDegree(state: LocalSearchState, factorId: Int): Int =
        compressViolation(acceptDistance { state.assignment.intValue(seq[it]) }.toLong(), state.violationSoftCap)

    /** Min symbol changes to reach an accepting run, where `getSym(i)` is position `i`'s current
     *  symbol (a transition on it costs 0, any other symbol costs 1). */
    private inline fun acceptDistance(getSym: (Int) -> Int): Int {
        val inf = seq.size + 1
        var dp = IntArray(numStates + 1) { inf }
        dp[q0] = 0
        for (i in seq.indices) {
            val cur = getSym(i)
            val ndp = IntArray(numStates + 1) { inf }
            for (q in 1..numStates) {
                val base = dp[q]
                if (base >= inf) continue
                for (sym in 1..alphabetSize) {
                    val nq = delta(q, sym)
                    if (nq == 0) continue
                    val cost = base + (if (sym == cur) 0 else 1)
                    if (cost < ndp[nq]) ndp[nq] = cost
                }
            }
            dp = ndp
        }
        var best = inf
        for (q in 1..numStates) if (q in acceptingSet && dp[q] < best) best = dp[q]
        return best
    }

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int {
        val before = acceptDistance { state.assignment.intValue(seq[it]) }
        val after = acceptDistance {
            val v = seq[it]
            if (v == intVar) newValue else state.assignment.intValue(v)
        }
        return compressViolation(after.toLong(), state.violationSoftCap) -
            compressViolation(before.toLong(), state.violationSoftCap)
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int = 0

    /** Repair by replaying the DFA up to the first dead position; at that position propose
     *  every in-domain symbol that yields a valid transition from the live state, plus
     *  symbols that reach the accepting set when the run completed without accepting. */
    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        if (!isViolated(state, factorId)) return
        var q = q0
        for (i in seq.indices) {
            val s = state.assignment.intValue(seq[i])
            val next = delta(q, s)
            if (next == 0) {
                // First dead position: propose every alphabet symbol in domain that keeps q alive.
                val d = state.problem.intDomains[seq[i]]
                d.forEach { sym ->
                    if (sym != s && delta(q, sym) != 0) sink.addChannelingIntSet(state, seq[i], sym)
                }
                return
            }
            q = next
        }
        // Run completed but final state not accepting. Try last-position symbol changes that
        // would land in an accepting state.
        if (q !in acceptingSet && seq.isNotEmpty()) {
            val last = seq.size - 1
            // Recompute state at last-1.
            var qPrev = q0
            for (i in 0 until last) qPrev = delta(qPrev, state.assignment.intValue(seq[i]))
            val curLast = state.assignment.intValue(seq[last])
            val d = state.problem.intDomains[seq[last]]
            d.forEach { sym ->
                val target = delta(qPrev, sym)
                if (sym != curLast && target in acceptingSet) sink.addChannelingIntSet(state, seq[last], sym)
            }
        }
    }

    private fun accepts(state: LocalSearchState): Boolean {
        var q = q0
        for (i in seq.indices) {
            q = delta(q, state.assignment.intValue(seq[i]))
            if (q == 0) return false
        }
        return q in acceptingSet
    }

    /*
     * Pesant's layered-DAG GAC, now reversible and delta-driven (see [RegularIncrementalState]):
     * per layer a state-bitset records forward-reachability from q0 and backward-co-reachability to
     * an accepting state, both on the engine undo trail. A symbol `s ∈ dom(seq[i])` survives iff some
     * forward-reachable state at `i` transitions on it to a co-reachable state at `i+1`; the conflict
     * is the initial state losing co-reachability at layer 0. A fire recomputes only the layers a
     * changed position reaches, instead of the whole `O(n · Q · |Σ|)` DFA each time.
     */

    /** Hole-aware conflict reason, sharpened to the responsible prefix when [propagate] captured a
     *  forward-collapse layer; falls back to the whole sequence otherwise. */
    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? {
        val prefix = (state.refPayload[factorId] as? RegularIncrementalState)?.conflictPrefix ?: seq
        return collectHoleAndBoundAntecedents(state, prefix)
    }

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        val inc = (state.refPayload[factorId] as? RegularIncrementalState) ?: run {
            val fresh = RegularIncrementalState(state, seq, numStates, alphabetSize, transitions, q0, accepting)
            state.refPayload[factorId] = fresh
            fresh
        }
        return inc.propagate(state, factorId)
    }
}
