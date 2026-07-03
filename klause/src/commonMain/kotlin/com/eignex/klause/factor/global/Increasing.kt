package com.eignex.klause.factor.global

import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.remapVars
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.lp.LinearRow
import com.eignex.klause.lp.Linearizer
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.FactorKind
import com.eignex.klause.solver.StructuralKey

/**
 * `increasing(xs)` — the integer chain `xs(0) ⟨≤|<⟩ xs(1) ⟨≤|<⟩ … ⟨≤|<⟩ xs(n−1)`. [strict] selects
 * `<` (strictly increasing) over `≤` (non-decreasing).
 *
 * `decreasing` / `strictly_decreasing` are the same constraint on the reversed sequence
 * (`a ≥ b ≥ c` ⇔ `c ≤ b ≤ a`), so callers post those by reversing [xs]; this factor only ever
 * represents the ascending orientation.
 *
 * Deduction is a Berge-acyclic chain, so the propagator's pairwise bounds sweep already achieves full
 * bounds-consistency — no global algorithm is stronger (#896). Keeping it one factor (rather than
 * decomposing to pairwise [com.eignex.klause.factor.arithmetic.Linear]) buys the local-search
 * invariant, which re-monotonises the whole chain in one cascading step. The exact pairwise rows are
 * still surfaced to the LP relaxation ([asLinearizer]) and to presolve ([linearRows]).
 */
class Increasing(val xs: IntArray, val strict: Boolean) : Factor {

    init {
        require(xs.size >= 2) { "increasing needs at least two variables" }
    }

    /** Minimum gap between adjacent variables: `1` for strict (`<`), `0` for non-decreasing (`≤`). */
    private val gap: Int = if (strict) 1 else 0

    override val boolVars: IntArray = EmptyIntArray
    override val intVars: IntArray = xs

    override fun remap(boolMap: IntArray, intMap: IntArray): Factor = Increasing(xs.remapVars(intMap), strict)

    /** The chain is position-faithful — its order *is* the constraint — so [xs] is keyed positionally. */
    override fun structuralKey(): StructuralKey = StructuralKey.of(FactorKind.INCREASING) {
        bool(strict)
        ints(xs)
    }

    override fun asPropagator(): Propagator = IncreasingPropagator(xs, gap)

    override fun asInvariant(): Invariant = IncreasingInvariant(xs, gap)

    override fun asLinearizer(): Linearizer = IncreasingLinearizer(xs, gap)

    // Each adjacent pair is the exact row `xs(i+1) − xs(i) ≥ gap`; their conjunction is the chain.
    override fun linearRows(): List<LinearRow> = buildList {
        for (i in 0 until xs.size - 1) {
            add(LinearRow(intArrayOf(1, -1), intArrayOf(xs[i + 1], xs[i]), LinearOp.GE, gap.toLong()))
        }
    }
}
