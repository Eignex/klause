package com.eignex.klause.factor.global

import com.eignex.klause.ir.FactorKind
import com.eignex.klause.ir.IntVars
import com.eignex.klause.ir.KeySink
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.StructuralKey
import com.eignex.klause.ir.VarList
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.ir.hashRemappedKey
import com.eignex.klause.ir.materializeKey
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.lp.LinearRow
import com.eignex.klause.lp.RelaxationBuilder
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.solver.Factor

/**
 * `increasing(xs)` — the integer chain `xs(0) ⟨≤|<⟩ xs(1) ⟨≤|<⟩ … ⟨≤|<⟩ xs(n−1)`. [strict] selects
 * `<` (strictly increasing) over `≤` (non-decreasing).
 *
 * `decreasing` / `strictly_decreasing` are the same constraint on the reversed sequence
 * (`a ≥ b ≥ c` ⇔ `c ≤ b ≤ a`), so callers post those by reversing [xs]; this factor only ever
 * represents the ascending orientation.
 *
 * Deduction is a Berge-acyclic chain, so the propagator's pairwise bounds sweep already achieves full
 * bounds-consistency — no global algorithm is stronger. Keeping it one factor (rather than
 * decomposing to pairwise [com.eignex.klause.factor.arithmetic.Linear]) buys the local-search
 * invariant, which re-monotonises the whole chain in one cascading step. The exact pairwise rows in
 * [linearRows] are still surfaced to presolve and, via the [Factor.linearize] default, to the LP
 * relaxation.
 */
class Increasing(val xs: IntArray, val strict: Boolean) : Factor {

    init {
        require(xs.size >= 2) { "increasing needs at least two variables" }
    }

    /** Minimum gap between adjacent variables: `1` for strict (`<`), `0` for non-decreasing (`≤`). */
    private val gap: Int = if (strict) 1 else 0

    // The chain propagates prefix minima and suffix maxima, so it reads bounds and never a value set.
    override val variables: VarList = IntVars(xs)

    override fun remap(mapping: VarRemap): Factor = Increasing(mapping.ints(xs), strict)

    /** The chain is position-faithful — its order *is* the constraint — so [xs] is keyed positionally. */
    override fun structuralKey(): StructuralKey = materializeKey(FactorKind.INCREASING, ::buildKey)

    override fun remapStructuralHash(mapping: VarRemap): Int =
        hashRemappedKey(FactorKind.INCREASING, mapping, ::buildKey)

    private fun buildKey(sink: KeySink) {
        sink.bool(strict)
        sink.intVars(xs)
    }

    override fun asPropagator(): Propagator = IncreasingPropagator(xs, gap)

    override fun asInvariant(): Invariant = IncreasingInvariant(xs, gap)

    // Each adjacent pair is the exact row `xs(i+1) − xs(i) ≥ gap`; their conjunction is the chain. The
    // rows are surfaced to presolve; the LP relaxation emits the same pairwise rows directly.
    override val linearRows: List<LinearRow>
        get() = buildList {
            for (i in 0 until xs.size - 1) {
                add(LinearRow.ofInts(intArrayOf(xs[i + 1], xs[i]), longArrayOf(1, -1), LinearOp.GE, gap.toLong()))
            }
        }

    override fun linearize(builder: RelaxationBuilder, factorId: Int) {
        val coeffs = longArrayOf(1, -1)
        for (i in 0 until xs.size - 1) {
            builder.linearRow(LinearOp.GE, intArrayOf(xs[i + 1], xs[i]), coeffs, gap.toLong())
        }
    }
}
