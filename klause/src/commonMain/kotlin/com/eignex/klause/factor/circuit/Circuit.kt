package com.eignex.klause.factor.circuit

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.FactorKind
import com.eignex.klause.ir.KeySink
import com.eignex.klause.ir.SpanIntVars
import com.eignex.klause.ir.StructuralKey
import com.eignex.klause.ir.VarList
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.ir.hashRemappedKey
import com.eignex.klause.ir.materializeKey

/**
 * Successor-array single-cycle constraint over `n` nodes: `succ(i)` holds the index of node `i`'s
 * successor. Two modes selected by [subcircuit]:
 *
 *  - **Circuit** (`subcircuit = false`): a Hamiltonian cycle — following `succ` from any node visits
 *    every node and returns to the start after exactly `n` steps. Self-loops (`succ(i) = i`) are
 *    violations for `n ≥ 2`; sub-cycles are violations.
 *  - **Subcircuit** (`subcircuit = true`): `succ(i) = i` reads "node `i` is excluded"; the included
 *    nodes (those with `succ(i) ≠ i`) must form a single closed cycle. All-excluded is the valid empty
 *    subcircuit; pointing to an excluded node, or a sub-cycle among included nodes, is a violation.
 *
 * `succ(i) = j` reads "node `j` is the successor of node `i`"; each `succ(i)` must hold a value in
 * `[0, n)` (out-of-range counts as a violation). LS cost is graded ("how far off single-cycle") so
 * strategies see a gradient rather than a broken/satisfied bit.
 *
 * Propagation and the LP relaxation are mode-specific and dispatched by [subcircuit]: the propagator is
 * a [CircuitPropagator] (Hamiltonian bounds / pigeonhole / sub-cycle prevention) or a
 * [SubcircuitPropagator] (self-loop-aware reachability); the invariant likewise.
 */
class Circuit(
    /** Successor variable id per node; the assignment must form one (sub)circuit over `succ`. */
    val succ: IntArray,
    /** When true, `succ(i) = i` excludes node `i` and only the included nodes must form the cycle. */
    val subcircuit: Boolean = false,
) : Factor {

    /** Number of nodes; equal to `succ.size`. */
    val n: Int = succ.size

    override val variables: VarList = SpanIntVars(succ)

    init {
        require(succ.isNotEmpty()) { "Circuit needs at least one var, got ${succ.size}" }
    }

    override fun remap(mapping: VarRemap): Factor = Circuit(mapping.ints(succ), subcircuit)

    /** Position-faithful: `succ(i)` is node i's successor, so the array order is meaningful — the key
     *  keeps the variables in order rather than sorting them. The [subcircuit] mode is a key field
     *  (as [com.eignex.klause.factor.arithmetic.ArrayMinMax] keys its `max`), so a circuit and a
     *  subcircuit over the same successors never share a bucket. */
    override fun structuralKey(): StructuralKey = materializeKey(FactorKind.CIRCUIT, ::buildKey)

    override fun remapStructuralHash(mapping: VarRemap): Int = hashRemappedKey(FactorKind.CIRCUIT, mapping, ::buildKey)

    private fun buildKey(sink: KeySink) {
        sink.bool(subcircuit)
        sink.intVars(succ)
    }

}
