package com.eignex.klause.factor.global

import com.eignex.klause.factor.OptPresence
import com.eignex.klause.factor.OptionalFactor
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.remapLits
import com.eignex.klause.factor.remapVars
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.lp.Contribution
import com.eignex.klause.lp.HullFamily
import com.eignex.klause.lp.LpSizeEstimate
import com.eignex.klause.lp.RelaxationBuilder
import com.eignex.klause.propagation.IntEvent
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.FactorKind
import com.eignex.klause.solver.FactorReduction
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.StructuralKey
import com.eignex.klause.util.EmptyIntArray
import com.eignex.klause.util.EmptyLongArray
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.LongArrayList
import com.eignex.klause.util.MutableLongIntMap

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

    override fun remap(boolMap: IntArray, intMap: IntArray): Factor =
        NValue(intMap[n], xs.remapVars(intMap), mode, presents.remapLits(boolMap))

    // A fixed distinct-count target degenerates the exact `nvalue` into a simpler global: `n = |xs|`
    // forces all values distinct (an [AllDifferent] over the union domain), and `n = 1` forces them all
    // equal (a chain of equalities). Solution-set exact; only the non-optional `Eq` mode qualifies.
    override fun structuralReduce(domains: Array<IntDomain>): FactorReduction {
        if (mode != Mode.Eq || presents.isNotEmpty()) return FactorReduction.Unchanged
        val nDom = domains[n]
        if (nDom.min != nDom.max) return FactorReduction.Unchanged
        val target = nDom.min
        return when {
            target == xs.size.toLong() && xs.size >= 2 -> allDifferent(domains)

            target == 1L -> FactorReduction.Rewrite(
                (1 until xs.size).map { Linear(intArrayOf(1, -1), intArrayOf(xs[it], xs[0]), LinearOp.EQ, 0) },
            )

            else -> FactorReduction.Unchanged
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
        if (span < 1L || span > Int.MAX_VALUE.toLong()) return FactorReduction.Unchanged
        return FactorReduction.Rewrite(listOf(AllDifferent(xs, domainMin = lo, domainSize = span.toInt())))
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

    override val boolVars: IntArray = OptPresence.presenceVarIds(presents)
    override val intVars: IntArray = xs + intArrayOf(n)

    override fun asPropagator(): Propagator {
        // Advisor subscription for the non-optional variant: the distinct-count bounds read each
        // variable's full domain (union membership + domain-overlap disjointness), so subscribe to every
        // kind and consume the dirty-variable delta to skip fires where nothing changed. The optional
        // variant keeps occurrence wakeup — a presence-bool flip changes the count but is not in the
        // int-domain delta, so it must not be gated out.
        val initialIntEventWatchesVal: IntArray? = if (presents.isNotEmpty()) {
            null
        } else {
            val distinct = intVars.toHashSet()
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
        val consumesIntEventDeltaVal = presents.isEmpty()
        return NValuePropagator(
            boolVars,
            intVars,
            n,
            xs,
            mode,
            presents,
            initialIntEventWatchesVal,
            consumesIntEventDeltaVal,
            { idx, state -> definitelyAbsent(idx, state) },
            { idx, state -> definitelyPresent(idx, state) },
        )
    }

    override fun asInvariant(): Invariant = NValueInvariant(
        n,
        xs,
        mode,
        presents,
        { state, idx -> present(state, idx) },
    )

    override val hullFamily: HullFamily = HullFamily.NVALUE

    /**
     * One-hot value model: a per-value "used" column `y_v ∈ [0,1]`, a one-hot selector `z_iv ∈ [0,1]`
     * per variable/value with `Σ_v z_iv = 1` and the channel `Σ_v v·z_iv = xs(i)`, and `y_v ≥ z_iv`. The
     * distinct count `Σ_v y_v` relates to `n` by the mode (`Eq → =`, `AtMost → ≥`, `AtLeast → ≤`), so
     * minimising `n` reads a real lower bound. Gated by [MAX_NVALUE_CELLS]; optional-presence is skipped.
     */
    override fun linearize(builder: RelaxationBuilder, factorId: Int) {
        if (!builder.hullEnabled()) return
        if (presents.isNotEmpty()) return // count is over present vars only — defer
        var cells = 0L
        for (x in xs) cells += builder.declaredDomain(x).size.toLong()
        if (cells == 0L || cells > MAX_NVALUE_CELLS) return
        val yCols = IntArrayList()
        val yByValue = MutableLongIntMap()
        fun yOf(v: Long): Int {
            val existing = yByValue.getOrDefault(v, -1) // columns are non-negative, so -1 marks absent
            if (existing >= 0) return existing
            // The "used" indicator is free in [0,1] regardless of the live domains — an empty
            // requirement keeps it present so the relaxation stays persistent.
            val col = builder.auxColumn(0L, 1L, presence = EmptyLongArray)
            yCols.add(col)
            yByValue.put(v, col)
            return col
        }
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
                builder.row(intArrayOf(z, yOf(v)), longArrayOf(1L, -1L), LinearOp.LE, 0L, Contribution.HULL) // y_v ≥ z
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
        if (yCols.isEmpty()) return
        // (Σ_v y_v) − n  {EQ | LE | GE}  0, per the mode (see KDoc).
        val op = when (mode) {
            Mode.Eq -> LinearOp.EQ
            Mode.AtMost -> LinearOp.LE
            Mode.AtLeast -> LinearOp.GE
        }
        val m = yCols.size
        val cols = IntArray(m + 1)
        val vals = LongArray(m + 1)
        for (idx in 0 until m) {
            cols[idx] = yCols[idx]
            vals[idx] = 1L
        }
        cols[m] = builder.intColumn(n)
        vals[m] = -1L
        builder.row(cols, vals, op, 0L, Contribution.HULL)
    }

    override fun lpSizeEstimate(domains: Array<IntDomain>): LpSizeEstimate? {
        if (presents.isNotEmpty()) return null
        var cells = 0L
        for (x in xs) cells += domains[x].size.toLong()
        if (cells == 0L || cells > MAX_NVALUE_CELLS) return null
        // z (per var×value) + y (≤ distinct values ≤ cells) columns; y≥z rows + (Σz=1, channel) per
        // var + the count row.
        return LpSizeEstimate(cols = 2L * cells, rows = cells + 2L * xs.size + 1L)
    }

    private companion object {
        /** NValues whose total domain-cell count exceeds this are skipped — the columns would dominate. */
        const val MAX_NVALUE_CELLS: Int = 1024
    }
}
