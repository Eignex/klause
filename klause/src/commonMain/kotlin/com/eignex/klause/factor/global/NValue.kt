package com.eignex.klause.factor.global

import com.eignex.klause.factor.OptPresence
import com.eignex.klause.factor.OptionalFactor
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.FactorKind
import com.eignex.klause.ir.FactorReduction
import com.eignex.klause.ir.FactorReduction.Rewrite
import com.eignex.klause.ir.FactorReduction.Unchanged
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.MixedVars
import com.eignex.klause.ir.StructuralKey
import com.eignex.klause.ir.VarList
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.util.EmptyIntArray

/**
 * `nvalue(n, xs)` — `n` equals the count of distinct values appearing in [xs]. Plus
 * variants:
 *
 *  - [Mode.Eq] (default): `n = |distinct(xs)|`.
 *  - [Mode.AtLeast]: `n ≤ |distinct(xs)|`.
 *  - [Mode.AtMost]:  `n ≥ |distinct(xs)|`.
 *
 * One factor with a mode flag so all three MiniZinc predicates (`fzn_nvalue`,
 * `fzn_atleast_nvalues`, `fzn_atmost_nvalues`) lower to the same factor type.
 */
class NValue(
    /** Integer variable id holding the distinct-value count target. */
    val n: Int,
    /** Integer variable ids whose distinct values are counted. */
    val xs: IntArray,
    /** How [n] relates to the actual distinct-value count. */
    val mode: Mode = Mode.Eq,
    /** Per-index presence literals; empty for the non-opt fast path. */
    override val presents: IntArray = EmptyIntArray,
) : Factor,
    OptionalFactor {

    /** How an `nvalue` constraint's target relates to the actual distinct-value count. */
    enum class Mode {
        /** Distinct count equals [n]. */
        Eq,

        /** Distinct count is at least [n]. */
        AtLeast,

        /** Distinct count is at most [n]. */
        AtMost,
    }

    init {
        require(xs.isNotEmpty()) { "nvalue: empty xs" }
        require(presents.isEmpty() || presents.size == xs.size) {
            "nvalue: presents must be empty or match xs arity"
        }
    }

    override fun remap(mapping: VarRemap): Factor =
        NValue(mapping.int(n), mapping.ints(xs), mode, mapping.lits(presents))

    // A fixed distinct-count target degenerates the exact `nvalue` into a simpler global: `n = |xs|`
    // forces all values distinct (an [AllDifferent] over the union domain), and `n = 1` forces them all
    // equal (a chain of equalities). Solution-set exact; only the non-optional `Eq` mode qualifies.
    override fun structuralReduce(domains: Array<IntDomain>): FactorReduction {
        if (mode != Mode.Eq || presents.isNotEmpty()) return Unchanged
        val nDom = domains[n]
        if (nDom.min != nDom.max) return Unchanged
        val target = nDom.min
        return when {
            target == xs.size.toLong() && xs.size >= 2 -> allDifferent(domains)

            target == 1L -> Rewrite(
                (1 until xs.size).map { Linear(intArrayOf(1, -1), intArrayOf(xs[it], xs[0]), LinearOp.EQ, 0) },
            )

            else -> Unchanged
        }
    }

    private fun allDifferent(domains: Array<IntDomain>): FactorReduction {
        var lo = Long.MAX_VALUE
        var hi = Long.MIN_VALUE
        for (x in xs) {
            val d = domains[x]
            if (d.min < lo) lo = d.min
            if (d.max > hi) hi = d.max
        }
        val span = hi - lo + 1
        // Guard an oversized union domain: AllDifferent tracks per-value counts over `[lo, lo + span)`.
        if (span < 1L || span > Int.MAX_VALUE.toLong()) return Unchanged
        return Rewrite(listOf(AllDifferent(xs, domainMin = lo, domainSize = span.toInt())))
    }

    /** The distinct-value count ignores the order of [xs], so the counted vars are sorted (paired with
     *  their presence literal to keep an opt position with its presence); [n] (the count var) and
     *  [mode] are positional constants. */
    // Not migrated to the KeySink allocation-free hash: the `pairsByKey(xs){ presents… }` value is a
    // Boolean literal (remappable), which the sink's constant-value pair methods can't remap.
    override fun structuralKey(): StructuralKey = StructuralKey.of(FactorKind.NVALUE) {
        enum(mode)
        int(n)
        pairsByKey(xs) { presents.getOrElse(it) { -1 }.toLong() }
    }

    override val variables: VarList = MixedVars(
        spanInts = xs + intArrayOf(n),
        boolVars = OptPresence.presenceVarIds(presents),
    )
}
