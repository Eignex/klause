package com.eignex.klause.solver.factor.global

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.factor.OptPresence
import com.eignex.klause.solver.factor.OptionalFactor
import com.eignex.klause.solver.factor.remapLits
import com.eignex.klause.solver.factor.remapVars
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.propagation.PropagationState
import com.eignex.klause.util.IntIntMap

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
 * Régin-style max-flow GAC. The flow has lower bounds on `cover_k → sink` (matching the
 * cover lo/hi or current `countVars(k)` domain), is reduced to standard max-flow via the
 * super-source/super-sink trick, solved by Edmonds-Karp, then the residual graph is
 * SCC'd. Any `xᵢ → cover_k` edge with zero flow whose endpoints sit in different SCCs
 * cannot extend to a feasible solution and is pruned from `dom(xᵢ)`.
 */
class GlobalCardinality(
    /** Variable ids the constraint ranges over. */
    override val xs: IntArray,
    /** Values whose occurrence counts are bounded. */
    override val cover: IntArray,
    override val countVars: IntArray? = null,
    override val countLow: IntArray? = null,
    override val countHigh: IntArray? = null,
    override val closed: Boolean = false,
    /** Per-xs presence literals; empty for the non-opt fast path. Absent positions
     *  contribute nothing to any cover-value count and don't trip the closed check. */
    override val presents: IntArray = EmptyIntArray,
) : Factor,
    OptionalFactor,
    GlobalCardinalityPropagator,
    GlobalCardinalityInvariant {

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

    override fun remap(boolMap: IntArray, intMap: IntArray): Factor = GlobalCardinality(
        xs.remapVars(intMap),
        cover,
        countVars?.remapVars(intMap),
        countLow,
        countHigh,
        closed,
        presents.remapLits(boolMap),
    )

    // xs is a set (counts are per cover value, order-independent) so xs/presents pairs are sorted by
    // var id; cover triples are sorted by value. Encodes every distinguishing field — fine enough
    // that two non-equivalent GCCs never collide (a coarser key would let a symmetry swap through).
    override fun structuralKey(): String {
        val xsPart = xs.indices.sortedBy { xs[it] }.joinToString(",") { i ->
            if (presents.isEmpty()) "${xs[i]}" else "${xs[i]}@${presents[i]}"
        }
        val coverPart = cover.indices.sortedBy { cover[it] }.joinToString(",") { i ->
            if (countVars != null) {
                "${cover[i]}=v${countVars[i]}"
            } else {
                "${cover[i]}=${requireNotNull(countLow)[i]}_${requireNotNull(countHigh)[i]}"
            }
        }
        return "gcc:$closed:$xsPart:$coverPart"
    }

    /** Relabel the cover values (#374). Only the constant-count form is value-relabelable: with count
     *  *variables* the counts live in a second value universe that one map can't relabel, so that form
     *  blocks value symmetry (returns `null`). A value transposition is a bijection, so the relabeled
     *  cover stays distinct. */
    override fun remapValues(valueMap: (Int) -> Int): Factor? {
        if (countVars != null) return null
        return GlobalCardinality(
            xs,
            IntArray(cover.size) { valueMap(cover[it]) },
            null,
            countLow,
            countHigh,
            closed,
            presents,
        )
    }

    override val boolVars: IntArray = OptPresence.presenceVarIds(presents)
    override val intVars: IntArray = run {
        val cv = countVars
        if (cv != null) xs + cv else xs
    }

    // Cover value → its index. IntIntMap keeps the per-probe lookup unboxed; indices are ≥ 0 so
    // -1 is a safe absent sentinel (a value not in the cover).
    @Suppress("EXPOSED_PROPERTY_TYPE")
    override val coverIndexByValue: IntIntMap =
        IntIntMap.build(cover, IntArray(cover.size) { it }, absent = -1)

    override fun definitelyPresentGcc(idx: Int, state: PropagationState): Boolean = definitelyPresent(idx, state)

    override fun definitelyAbsentGcc(idx: Int, state: PropagationState): Boolean = definitelyAbsent(idx, state)

    override fun presentGccInv(state: LocalSearchState, idx: Int): Boolean = present(state, idx)
}
