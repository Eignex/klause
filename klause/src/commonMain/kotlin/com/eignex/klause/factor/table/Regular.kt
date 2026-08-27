package com.eignex.klause.factor.table

import com.eignex.klause.ir.FactorKind
import com.eignex.klause.ir.KeySink
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.SpanIntVars
import com.eignex.klause.ir.StructuralKey
import com.eignex.klause.ir.VarList
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.ir.hashRemappedKey
import com.eignex.klause.ir.materializeKey
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.lp.Contribution
import com.eignex.klause.lp.HullFamily
import com.eignex.klause.lp.LpSizeEstimate
import com.eignex.klause.lp.RelaxationBuilder
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.solver.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.values
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet
import com.eignex.klause.util.LongArrayList

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
    /** `numStates × alphabetSize` row-major transition table; 0 means no transition. Entries are DFA
     *  state ids (small); the symbol axis is the row-major column index (`1..alphabetSize`). */
    val transitions: LongArray,
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

    override fun remap(mapping: VarRemap): Factor =
        Regular(mapping.ints(seq), numStates, alphabetSize, transitions, q0, accepting)

    /** Position-faithful (seq position i matters): keeps the sequence vars in order and folds in the
     *  whole automaton — state/alphabet sizes, the transition table, the initial and accepting
     *  states. */
    override fun structuralKey(): StructuralKey = materializeKey(FactorKind.REGULAR, ::buildKey)

    override fun remapStructuralHash(mapping: VarRemap): Int = hashRemappedKey(FactorKind.REGULAR, mapping, ::buildKey)

    private fun buildKey(sink: KeySink) {
        sink.int(numStates)
        sink.int(alphabetSize)
        sink.int(q0)
        sink.constLongs(transitions)
        sink.constInts(accepting)
        sink.intVars(seq)
    }

    /** Symbol-alphabet relabeling: the `seq` values *are* the symbols, so a value permutation
     *  permutes the transition table's symbol axis — `δ'(q, valueMap(s)) = δ(q, s)`. Sound because
     *  Regular has no positional-variable/constant coupling (unlike Element): swapping symbol values in
     *  a sequence and the matching columns preserves acceptance exactly. Returns `null` if [valueMap]
     *  is not a permutation of `1..alphabetSize` (then it can't relabel this automaton's columns). */
    override fun remapValues(valueMap: (Long) -> Long): Factor? {
        val target = IntArray(alphabetSize + 1) // 1-based symbols
        val seen = BooleanArray(alphabetSize + 1)
        for (s in 1..alphabetSize) {
            val t = valueMap(s.toLong())
            if (t < 1 || t > alphabetSize || seen[t.toInt()]) return null
            seen[t.toInt()] = true
            target[s] = t.toInt()
        }
        val newTransitions = LongArray(transitions.size)
        for (q in 1..numStates) {
            for (s in 1..alphabetSize) {
                newTransitions[(q - 1) * alphabetSize + (target[s] - 1)] = transitions[(q - 1) * alphabetSize + (s - 1)]
            }
        }
        return Regular(seq, numStates, alphabetSize, newTransitions, q0, accepting)
    }

    override val variables: VarList = SpanIntVars(seq)

    override fun asPropagator(): Propagator =
        RegularPropagator(boolVars, intVars, seq, numStates, alphabetSize, transitions, q0, accepting)

    override fun asInvariant(): Invariant = RegularInvariant(seq, numStates, alphabetSize, transitions, q0, accepting)

    override val hullFamily: HullFamily = HullFamily.REGULAR

    /**
     * Layer-expanded DFA flow hull — the exact convex hull of the automaton's accepting strings. An arc
     * variable `y ∈ [0,1]` per reachable `(position t, state q, symbol s)` whose transition `δ(q, s)` is
     * live. Rows: a source row `Σ y out of (0, q0) = 1`, flow conservation at every interior `(t, q)`, an
     * acceptance row at the last layer, and a channel `Σ_s s·y = seq[t]` per position. The flow polytope is
     * integral, so the LP is the true convex hull. Forward reachability bounds the arc count
     * ([MAX_REGULAR_ARCS]); above the cap, or when no accepting path survives, it is skipped. HULL.
     */
    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth")
    override fun linearize(builder: RelaxationBuilder, factorId: Int) {
        if (!builder.hullEnabled()) return
        val reach = forwardReach(builder::declaredDomain)?.states ?: return
        val len = seq.size
        val s = alphabetSize
        val trans = transitions
        fun delta(state: Int, sym: Long): Int = trans[(state - 1) * s + (sym - 1).toInt()].toInt() // 1-based; 0 = dead
        val ns = numStates

        val outCols = Array(len) { arrayOfNulls<IntArrayList>(ns + 1) }
        val inCols = Array(len + 1) { arrayOfNulls<IntArrayList>(ns + 1) }
        val chanCols = Array(len) { IntArrayList() }
        val chanSym = Array(len) { IntArrayList() }
        val acceptCols = IntArrayList()
        for (t in 0 until len) {
            val declared = builder.declaredDomain(seq[t])
            val live = builder.liveDomain(seq[t])
            reach[t].forEach { state ->
                declared.values.forEach { sym ->
                    if (sym !in 1..s) return@forEach
                    val nxt = delta(state, sym)
                    if (nxt == 0) return@forEach
                    // The arc is present while symbol sym stays in seq[t]'s live domain.
                    val col = builder.auxColumn(
                        0L,
                        if (live.contains(sym)) 1L else 0L,
                        presence = longArrayOf(seq[t].toLong(), sym),
                    )
                    (outCols[t][state] ?: IntArrayList().also { outCols[t][state] = it }).add(col)
                    (inCols[t + 1][nxt] ?: IntArrayList().also { inCols[t + 1][nxt] = it }).add(col)
                    chanCols[t].add(col)
                    chanSym[t].add(sym.toInt())
                    if (t == len - 1 && nxt in accepting) acceptCols.add(col)
                }
            }
        }
        // Source: one unit leaves (0, q0).
        val src = outCols[0][q0] ?: return
        if (src.isEmpty()) return
        builder.row(src.toIntArray(), LongArray(src.size) { 1L }, LinearOp.EQ, 1L, Contribution.HULL)
        // Flow conservation at every interior node: Σ out − Σ in = 0.
        for (t in 1 until len) {
            reach[t].forEach { state ->
                val cols = IntArrayList()
                val vals = LongArrayList()
                outCols[t][state]?.let {
                    for (k in 0 until it.size) {
                        cols.add(it[k])
                        vals.add(1L)
                    }
                }
                inCols[t][state]?.let {
                    for (k in 0 until it.size) {
                        cols.add(it[k])
                        vals.add(-1L)
                    }
                }
                if (!cols.isEmpty()) {
                    builder.row(cols.toIntArray(), vals.toLongArray(), LinearOp.EQ, 0L, Contribution.HULL)
                }
            }
        }
        // Acceptance: one unit enters an accepting state at the last layer.
        if (acceptCols.isEmpty()) return // no accepting transition reachable — leave to propagation
        builder.row(acceptCols.toIntArray(), LongArray(acceptCols.size) { 1L }, LinearOp.EQ, 1L, Contribution.HULL)
        // Channel: Σ_s s·y = seq[t] at each position.
        for (t in 0 until len) {
            val k = chanCols[t].size
            if (k == 0) return
            val cols = IntArray(k + 1)
            val vals = LongArray(k + 1)
            for (i in 0 until k) {
                cols[i] = chanCols[t][i]
                vals[i] = chanSym[t][i].toLong()
            }
            cols[k] = builder.intColumn(seq[t])
            vals[k] = -1L
            builder.row(cols, vals, LinearOp.EQ, 0L, Contribution.HULL)
        }
    }

    override fun lpSizeEstimate(domains: Array<IntDomain>): LpSizeEstimate? {
        val reach = forwardReach { domains[it] } ?: return null
        // arc columns + conservation (≤ arcs) + channel (len) + source + acceptance.
        return LpSizeEstimate(cols = reach.arcCount, rows = reach.arcCount + seq.size + 2L)
    }

    private class Reach(val states: Array<IntHashSet>, val arcCount: Long)

    /** Forward-reachable states per layer over [domainOf]'s domains plus the total candidate-arc count,
     *  or null when a layer empties (no accepting path) or the arc count is 0 or over [MAX_REGULAR_ARCS].
     *  Shared by [linearize] (which needs the states to lay out columns) and [lpSizeEstimate] (the count). */
    private fun forwardReach(domainOf: (Int) -> IntDomain): Reach? {
        val len = seq.size
        val s = alphabetSize
        val trans = transitions
        val reach = Array(len + 1) { IntHashSet() }
        reach[0].add(q0)
        var arcCount = 0L
        for (t in 0 until len) {
            val dom = domainOf(seq[t])
            reach[t].forEach { state ->
                // Walk the alphabet (1..s), not the domain: only symbols in range have a transition, and
                // s is the automaton's alphabet size (bounded), never the variable's span — so a wide
                // sequence domain is tested by membership, never enumerated.
                for (sym in 1..s) {
                    if (sym.toLong() in dom) {
                        val nxt = trans[(state - 1) * s + (sym - 1)].toInt()
                        if (nxt != 0) {
                            reach[t + 1].add(nxt)
                            arcCount++
                        }
                    }
                }
            }
            if (reach[t + 1].isEmpty()) return null // no accepting path under these domains
        }
        if (arcCount == 0L || arcCount > MAX_REGULAR_ARCS) return null
        return Reach(reach, arcCount)
    }

    private companion object {
        /** Above this many reachable arcs the hull is skipped — the arc columns would dominate. */
        const val MAX_REGULAR_ARCS: Int = 4096
    }

    /*
     * Reversible, delta-driven layered-DAG GAC (see `RegularIncrementalState`):
     * per layer a state-bitset records forward-reachability from q0 and backward-co-reachability to
     * an accepting state, both on the engine undo trail. A symbol `s ∈ dom(seq[i])` survives iff some
     * forward-reachable state at `i` transitions on it to a co-reachable state at `i+1`; the conflict
     * is the initial state losing co-reachability at layer 0. A fire recomputes only the layers a
     * changed position reaches, instead of the whole `O(n · Q · |Σ|)` DFA each time.
     */
}
