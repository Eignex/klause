package com.eignex.klause.factor.global

import com.eignex.klause.factor.OptPresence
import com.eignex.klause.factor.OptionalFactor
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.FactorKind
import com.eignex.klause.ir.MixedVars
import com.eignex.klause.ir.StructuralKey
import com.eignex.klause.ir.VarList
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.util.EmptyIntArray
import com.eignex.klause.util.MutableLongIntMap

/**
 * Global Cardinality Constraint (GCC). Covers the four MiniZinc variants in one factor:
 *
 *  - `global_cardinality(xs, cover, counts)` — `counts(k) = #{i : xs(i) = cover(k)}`. Use
 *    [countVars] (`size = cover.size`) and [closed] = `false`.
 *  - `global_cardinality_low_up(xs, cover, lo, up)` — `lo(k) ≤ #{i : xs(i) = cover(k)} ≤ up(k)`.
 *    Use [countLow] / [countHigh] (constant arrays) and [countVars] = `null`.
 *  - `_closed` variants additionally require every `xs(i) ∈ cover` — i.e. no value outside
 *    the cover set may appear. Pass [closed] = `true`.
 *
 * Exactly one of ([countVars], [countLow]+[countHigh]) is non-null — the constructor
 * validates.
 *
 * Propagation: count-bound tightening (definite/possible matchers per cover value) plus
 * max-flow GAC. The flow has lower bounds on `cover_k → sink` (matching the
 * cover lo/hi or current `countVars(k)` domain), is reduced to standard max-flow via the
 * super-source/super-sink trick, solved by Edmonds-Karp, then the residual graph is
 * SCC'd. Any `xᵢ → cover_k` edge with zero flow whose endpoints sit in different SCCs
 * cannot extend to a feasible solution and is pruned from `dom(xᵢ)`.
 */
class GlobalCardinality(
    /** Variable ids the constraint ranges over. */
    val xs: IntArray,
    /** Values whose occurrence counts are bounded. */
    val cover: LongArray,
    val countVars: IntArray? = null,
    val countLow: IntArray? = null,
    val countHigh: IntArray? = null,
    val closed: Boolean = false,
    /** Per-xs presence literals; empty for the non-opt fast path. Absent positions
     *  contribute nothing to any cover-value count and don't trip the closed check. */
    override val presents: IntArray = EmptyIntArray,
) : Factor,
    OptionalFactor {

    init {
        require(xs.isNotEmpty()) { "gcc: empty xs" }
        require(cover.isNotEmpty()) { "gcc: empty cover" }
        if (countVars != null) {
            require(countVars.size == cover.size) { "gcc: countVars size mismatch" }
            require(countLow == null && countHigh == null) { "gcc: pass either countVars OR countLow+countHigh" }
        } else {
            require(countLow != null && countHigh != null) { "gcc: missing countLow/countHigh" }
            require(countLow.size == cover.size && countHigh.size == cover.size) { "gcc: lo/hi size mismatch" }
        }
        require(presents.isEmpty() || presents.size == xs.size) {
            "gcc: presents must be empty or match xs arity"
        }
    }

    override fun remap(mapping: VarRemap): Factor = GlobalCardinality(
        mapping.ints(xs),
        cover,
        countVars?.let(mapping::ints),
        countLow,
        countHigh,
        closed,
        mapping.lits(presents),
    )

    // xs is a set (counts are per cover value, order-independent) so xs/presents pairs are sorted by
    // var id; cover triples are sorted by value. Encodes every distinguishing field — fine enough
    // that two non-equivalent GCCs never collide (a coarser key would let a symmetry swap through).
    // Not migrated to the KeySink allocation-free hash: the `pairsByKey(xs){ presents[it] }` value is a
    // Boolean literal (remappable), which the sink's constant-value pair methods can't remap.
    override fun structuralKey(): StructuralKey = StructuralKey.of(FactorKind.GLOBAL_CARDINALITY) {
        bool(closed)
        bool(presents.isEmpty())
        if (presents.isEmpty()) sortedInts(xs) else pairsByKey(xs) { presents[it].toLong() }
        bool(countVars != null)
        int(cover.size)
        for (i in cover.indices.sortedBy { cover[it] }) {
            long(cover[i])
            if (countVars != null) {
                int(countVars[i])
            } else {
                int(requireNotNull(countLow)[i])
                int(requireNotNull(countHigh)[i])
            }
        }
    }

    /** Relabel the cover values. Only the constant-count form is value-relabelable: with count
     *  *variables* the counts live in a second value universe that one map can't relabel, so that form
     *  blocks value symmetry (returns `null`). A value transposition is a bijection, so the relabeled
     *  cover stays distinct. */
    override fun remapValues(valueMap: (Long) -> Long): Factor? {
        if (countVars != null) return null
        return GlobalCardinality(
            xs,
            LongArray(cover.size) { valueMap(cover[it]) },
            null,
            countLow,
            countHigh,
            closed,
            presents,
        )
    }

    override val variables: VarList = MixedVars(
        spanInts = run {
            val cv = countVars
            if (cv != null) xs + cv else xs
        },
        boolVars = OptPresence.presenceVarIds(presents),
    )

    /** Cover value → its 0-based index in [cover]. Used for O(1) per-probe lookup during
     *  propagation and LS delta computation; `-1` for values outside the cover. */
    internal val coverIndexByValue: MutableLongIntMap =
        MutableLongIntMap().apply { for (i in cover.indices) put(cover[i], i) }
}
