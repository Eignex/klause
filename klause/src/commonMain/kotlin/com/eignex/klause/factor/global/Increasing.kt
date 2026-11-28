package com.eignex.klause.factor.global

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.FactorKind
import com.eignex.klause.ir.IntVars
import com.eignex.klause.ir.KeySink
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.StructuralKey
import com.eignex.klause.ir.VarList
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.ir.hashRemappedKey
import com.eignex.klause.ir.linearRows
import com.eignex.klause.ir.materializeKey

/**
 * `increasing(xs)` — the integer chain `xs(0) ⟨≤|<⟩ xs(1) ⟨≤|<⟩ … ⟨≤|<⟩ xs(n−1)`. [strict] selects
 * `<` (strictly increasing) over `≤` (non-decreasing).
 *
 * `decreasing` / `strictly_decreasing` are the same constraint on the reversed sequence
 * (`a ≥ b ≥ c` ⇔ `c ≤ b ≤ a`), so callers post those by reversing [xs]; this factor only ever
 * represents the ascending orientation.
 *
 * The chain is Berge-acyclic, so a pairwise bounds sweep is already bounds-consistent. One factor
 * keeps the whole chain available to local search; [linearRows] still exposes the pairwise rows.
 */
class Increasing(val xs: IntArray, val strict: Boolean) : Factor {

    init {
        require(xs.size >= 2) { "increasing needs at least two variables" }
    }

    internal val gap: Int = if (strict) 1 else 0

    // The chain propagates prefix minima and suffix maxima, so it reads bounds and never a value set.
    override val variables: VarList = IntVars(xs)

    override fun remap(mapping: VarRemap): Factor = Increasing(mapping.ints(xs), strict)

    override fun structuralKey(): StructuralKey = materializeKey(FactorKind.INCREASING, ::buildKey)

    override fun remapStructuralHash(mapping: VarRemap): Int =
        hashRemappedKey(FactorKind.INCREASING, mapping, ::buildKey)

    private fun buildKey(sink: KeySink) {
        sink.bool(strict)
        sink.intVars(xs)
    }

    override val linearForm: LinearForm = LinearForm.Conjunction(
        buildList {
            for (i in 0 until xs.size - 1) {
                add(LinearRow.ofInts(intArrayOf(xs[i + 1], xs[i]), longArrayOf(1, -1), LinearOp.GE, gap.toLong()))
            }
        },
    )
}
