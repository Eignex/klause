package com.eignex.klause.lp.cut

import com.eignex.klause.lp.engine.Cut
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.util.MutableIntLongMap
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * An activity-managed pool of globally-valid cuts. Cuts are added with deduplication by
 * [Cut.key]; a hard [maxCuts] cap bounds the per-node LP cost the pool imposes once its cuts are folded
 * into every node's relaxation. When the cap is exceeded the least-active cuts are evicted, where
 * activity is tightness at the LP optimum — the cut-management signal: a cut the LP point sits
 * on shapes the relaxation face, while a slack cut is dead weight on every solve.
 *
 * Eviction is sound: every pooled cut is valid at every solution ([Cut.global]), so dropping one only
 * loosens the relaxation — it never removes a feasible point. The pool replaces the unbounded
 * accumulation the root harvest used, which could grow without limit (the ghoulomb over-harvest: ~15795
 * cuts for zero prunes). Below the cap the pool preserves insertion order, so it is behaviour-neutral
 * on a harvest that never overflows.
 */
internal class CutPool(val maxCuts: Int = DEFAULT_MAX_CUTS) {
    private val seen = HashSet<String>()
    private val entries = ArrayList<Cut>()

    /** Number of pooled cuts. */
    val size: Int get() = entries.size

    /** Add [cut] unless an equal one (by [Cut.key]) is already pooled; returns true if newly added. */
    fun add(cut: Cut): Boolean {
        if (!seen.add(cut.key())) return false
        entries.add(cut)
        return true
    }

    /** Add each of [cuts] (deduplicated); returns how many were newly added. */
    fun addAll(cuts: Iterable<Cut>): Int {
        var added = 0
        for (c in cuts) if (add(c)) added++
        return added
    }

    /** The pooled cuts, in insertion order (after any [retainMostActive] eviction). */
    fun cuts(): List<Cut> = entries

    /**
     * Evict the least-active cuts until at most [maxCuts] remain, ranking by tightness at the LP point
     * [primal] (per structural column): a cut's slack `|rhs − Σ coeffs·primal|` measures how far the
     * point sits inside it, so ascending slack is most-active-first. Ties keep insertion order (the
     * sort is stable). A no-op when the pool is within the cap, so it leaves a non-overflowing harvest
     * untouched. Sound — the evicted cuts are globally valid, so dropping them only loosens the bound.
     */
    fun retainMostActive(primal: DoubleArray) {
        if (entries.size <= maxCuts) return
        entries.sortBy { slack(it, primal) }
        while (entries.size > maxCuts) entries.removeAt(entries.size - 1)
        seen.clear()
        for (c in entries) seen.add(c.key())
    }

    /** Distance of the LP [primal] point from cut tightness — 0 when the point sits on the cut. */
    private fun slack(cut: Cut, primal: DoubleArray): Double {
        var lhs = 0.0
        for (k in cut.cols.indices) {
            val col = cut.cols[k]
            if (col in primal.indices) lhs += cut.coeffs[k] * primal[col]
        }
        return abs(cut.rhs - lhs)
    }

    /**
     * Select up to [max] pooled cuts to add to the LP at the current point [primal]: rank by
     * **efficacy** (the normalised violation
     * `violation / ‖coeffs‖₂` — how deeply the point cuts past the inequality) and add greedily,
     * skipping a candidate that is near-parallel to one already chosen (an **orthogonality** filter on
     * the cosine of their coefficient vectors). A cut the point already satisfies (efficacy below
     * [minEfficacy]) is dropped — adding it would not move the bound. The returned cuts are a subset of
     * the pool, so the relaxation stays valid: selecting fewer cuts only loosens the bound, never
     * removes a feasible point. Insertion order breaks ties (the efficacy sort is stable).
     *
     * @param primal the current LP point cuts are scored against.
     * @param max the maximum number of cuts to return.
     * @param minEfficacy reject cuts whose normalised violation is below this.
     * @param minOrthogonality require each added cut's cosine-to-nearest-selected `≤ 1 − this`.
     */
    fun select(
        primal: DoubleArray,
        max: Int,
        minEfficacy: Double = MIN_EFFICACY,
        minOrthogonality: Double = MIN_ORTHOGONALITY,
    ): List<Cut> {
        if (max <= 0) return emptyList()
        val scored = entries
            .map { it to efficacy(it, primal) }
            .filter { it.second >= minEfficacy }
            .sortedByDescending { it.second }
        val selected = ArrayList<Cut>()
        val maxCos = 1.0 - minOrthogonality
        for ((cut, _) in scored) {
            if (selected.size >= max) break
            if (selected.none { cosine(it, cut) > maxCos }) selected.add(cut)
        }
        return selected
    }

    /** Normalised violation of [cut] at [primal] — `violation / ‖coeffs‖₂`, `0` when satisfied. The
     *  violation is how far the point sits on the infeasible side of the inequality. */
    private fun efficacy(cut: Cut, primal: DoubleArray): Double {
        var lhs = 0.0
        for (k in cut.cols.indices) {
            val col = cut.cols[k]
            if (col in primal.indices) lhs += cut.coeffs[k] * primal[col]
        }
        val violation = when (cut.rel) {
            Relation.GE -> cut.rhs - lhs

            // `Σ ≥ rhs` violated when below
            Relation.LE -> lhs - cut.rhs

            // `Σ ≤ rhs` violated when above
            Relation.EQ -> abs(lhs - cut.rhs)
        }
        if (violation <= 0.0) return 0.0
        val norm = l2(cut)
        return if (norm > 0.0) violation / norm else 0.0
    }

    /** Euclidean norm of [cut]'s coefficient vector. */
    private fun l2(cut: Cut): Double {
        var s = 0.0
        for (c in cut.coeffs) s += c.toDouble() * c.toDouble()
        return sqrt(s)
    }

    /** Cosine similarity of two cuts' coefficient vectors over their shared columns (`0` when disjoint,
     *  `1` when parallel). Used to keep the selected set near-orthogonal. */
    private fun cosine(a: Cut, b: Cut): Double {
        val na = l2(a)
        val nb = l2(b)
        if (na == 0.0 || nb == 0.0) return 0.0
        // Map b's columns for an O(|a|) shared-support dot product.
        val bIndex = MutableIntLongMap(b.cols.size * 2)
        for (k in b.cols.indices) bIndex.put(b.cols[k], b.coeffs[k])
        var dot = 0.0
        for (k in a.cols.indices) {
            if (!bIndex.containsKey(a.cols[k])) continue
            val bc = bIndex.getOrDefault(a.cols[k], 0L)
            dot += a.coeffs[k].toDouble() * bc.toDouble()
        }
        return abs(dot) / (na * nb)
    }

    internal companion object {
        /**
         * Default cap on the pooled cuts. Bounds the per-node LP solve a large root harvest would
         * otherwise impose, while sitting well above a normal harvest's output so it only bites on a
         * pathological over-harvest. The reported cut count ([size]) reflects any eviction.
         */
        const val DEFAULT_MAX_CUTS: Int = 2048

        /** Minimum normalised violation for [select] to add a cut (efficacy floor). */
        const val MIN_EFFICACY: Double = 1e-4

        /** Minimum orthogonality for [select]: an added cut's cosine to any already-selected cut must
         *  be at most `1 − this`, so near-duplicate faces are not piled onto the LP. */
        const val MIN_ORTHOGONALITY: Double = 0.05
    }
}
