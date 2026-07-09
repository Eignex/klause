package com.eignex.klause.factor.global

import com.eignex.klause.factor.OptPresence
import com.eignex.klause.factor.OptionalFactor
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.remapLits
import com.eignex.klause.factor.remapVars
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.lp.Contribution
import com.eignex.klause.lp.HullFamily
import com.eignex.klause.lp.LpSizeEstimate
import com.eignex.klause.lp.RelaxationBuilder
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.FactorKind
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.StructuralKey
import com.eignex.klause.util.EmptyIntArray
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.LongArrayList
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
 * Régin-style max-flow GAC. The flow has lower bounds on `cover_k → sink` (matching the
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

    /** Relabel the cover values (#374). Only the constant-count form is value-relabelable: with count
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

    override val boolVars: IntArray = OptPresence.presenceVarIds(presents)
    override val intVars: IntArray = run {
        val cv = countVars
        if (cv != null) xs + cv else xs
    }

    /** Cover value → its 0-based index in [cover]. Used for O(1) per-probe lookup during
     *  propagation and LS delta computation; `-1` for values outside the cover. */
    internal val coverIndexByValue: MutableLongIntMap =
        MutableLongIntMap().apply { for (i in cover.indices) put(cover[i], i) }

    override fun asPropagator(): Propagator = GlobalCardinalityPropagator(
        boolVars,
        intVars,
        xs,
        cover,
        countVars,
        countLow,
        countHigh,
        closed,
        presents,
        coverIndexByValue,
        { idx, state -> definitelyPresent(idx, state) },
        { idx, state -> definitelyAbsent(idx, state) },
    )

    override fun asInvariant(): Invariant = GlobalCardinalityInvariant(
        xs,
        cover,
        countVars,
        countLow,
        countHigh,
        closed,
        presents,
        coverIndexByValue,
        { state, idx -> present(state, idx) },
    )

    override val hullFamily: HullFamily = HullFamily.GCC_COUNT

    /**
     * One-hot selector model for the count-variable form `counts(k) = #{i : xs(i) = cover(k)}`: a one-hot
     * selector `z_iv ∈ [0,1]` per variable/value over `xs[i]`'s declared domain with `Σ_v z_iv = 1` and the
     * channel `Σ_v v·z_iv = xs(i)`, and per cover value the exact count linkage `Σ_i z_{i,cover(k)} =
     * counts(k)` — so a count variable in the objective reads a true LP bound. Gated by [MAX_GCC_CELLS]; the
     * constant-bound and optional-presence forms are skipped. HULL.
     */
    override fun linearize(builder: RelaxationBuilder, factorId: Int) {
        if (!builder.hullEnabled()) return
        if (presents.isNotEmpty()) return // count is over present vars only — defer
        val counts = countVars ?: return // constant-bound form has no count var to bound
        var cells = 0L
        for (x in xs) cells += builder.declaredDomain(x).size.toLong()
        if (cells == 0L || cells > MAX_GCC_CELLS) return
        // Selector columns per cover value, indexed by cover position via [coverIndexByValue], whose
        // Long keys address cover values across the full value range.
        val selByCover = Array(cover.size) { IntArrayList() }
        for (x in xs) {
            val declared = builder.declaredDomain(x)
            val live = builder.liveDomain(x)
            val sel = IntArrayList()
            val selVal = LongArrayList()
            declared.forEach { v ->
                // The selector z_xv is present while value v stays in x's live domain.
                val z = builder.auxColumn(0L, if (live.contains(v)) 1L else 0L, presence = longArrayOf(x.toLong(), v))
                sel.add(z)
                selVal.add(v)
                val ci = coverIndexByValue.getOrDefault(v, -1) // only cover values carry a count row
                if (ci >= 0) selByCover[ci].add(z)
            }
            val k = sel.size
            if (k == 0) return // a variable with no declared values — leave it to propagation
            builder.row(sel.toIntArray(), LongArray(k) { 1L }, LinearOp.EQ, 1L, Contribution.HULL) // Σ_v z = 1
            // Σ_v v·z − xs(i) = 0.
            val cCols = IntArray(k + 1)
            val cVals = LongArray(k + 1)
            for (s in 0 until k) {
                cCols[s] = sel[s]
                cVals[s] = selVal[s]
            }
            cCols[k] = builder.intColumn(x)
            cVals[k] = -1L
            builder.row(cCols, cVals, LinearOp.EQ, 0L, Contribution.HULL)
        }
        // Σ_i z_{i,cover(k)} − counts(k) = 0 per cover value (a cover value in no domain forces 0).
        for (k in cover.indices) {
            val sel = selByCover[k]
            val cols = IntArray(sel.size + 1)
            val vals = LongArray(sel.size + 1)
            for (i in 0 until sel.size) {
                cols[i] = sel[i]
                vals[i] = 1L
            }
            cols[sel.size] = builder.intColumn(counts[k])
            vals[sel.size] = -1L
            builder.row(cols, vals, LinearOp.EQ, 0L, Contribution.HULL)
        }
    }

    override fun lpSizeEstimate(domains: Array<IntDomain>): LpSizeEstimate? {
        if (countVars == null || presents.isNotEmpty()) return null
        var cells = 0L
        for (x in xs) cells += domains[x].size.toLong()
        if (cells == 0L || cells > MAX_GCC_CELLS) return null
        // One z selector per var×declared-value; (Σz=1, channel) per var + one count row per cover value.
        return LpSizeEstimate(cols = cells, rows = 2L * xs.size + cover.size)
    }

    private companion object {
        /** GCCs whose total domain-cell count exceeds this are skipped — the columns would dominate. */
        const val MAX_GCC_CELLS: Int = 1024
    }
}
