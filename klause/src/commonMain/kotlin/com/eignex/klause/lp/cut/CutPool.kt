package com.eignex.klause.lp.cut

import com.eignex.klause.lp.engine.Cut
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.lp.relaxation.CutSourceMap
import com.eignex.klause.lp.relaxation.LpRelaxation
import com.eignex.klause.util.MutableIntLongMap
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * An activity-managed cut pool. Portable entries retain their source proof and become available only
 * while their guards hold in [remap]'s context. Raw cuts belong to their original column layout and
 * become unavailable after remapping. Eviction only loosens a relaxation; insertion order breaks
 * activity ties, and [retainMostActive] enforces the existing [maxCuts] cap.
 */
internal class CutPool(
    val maxCuts: Int = DEFAULT_MAX_CUTS,
    private val maxConsecutiveInactive: Int = DEFAULT_MAX_CONSECUTIVE_INACTIVE,
) {
    private val seen = HashSet<Any>()
    private val entries = ArrayList<Entry>()

    private class Entry(
        var cut: Cut?,
        var key: Any,
        var source: SourceCut? = null,
        var activity: Double = 0.0,
        var inactiveCount: Int = 0,
    )

    /** Number of pooled cuts. */
    val size: Int get() = entries.size

    /** Add [cut] unless an equal one (by [Cut.key]) is already pooled; returns true if newly added. */
    fun add(cut: Cut): Boolean {
        val proof = cut.provenance
        val row = proof?.conclusion
        val source = if (row != null && cut.global == proof.global) {
            SourceCut(row.expression, row.relation, row.rhs, proof)
        } else {
            null
        }
        return store(cut, source)
    }

    fun add(cut: Cut, relaxation: LpRelaxation): Boolean = when (val mapped = SourceCut.fromCut(cut, relaxation)) {
        is CutMapping.Mapped -> add(mapped.value, checkNotNull(relaxation.sourceMap))

        is CutMapping.Declined -> {
            val unscoped = cut.provenance == null && cut.tableau == null && cut.global
            if (unscoped && mapped.reason == CutMappingDecline.MISSING_SOURCE) add(cut) else false
        }
    }

    fun add(cut: SourceCut, map: CutSourceMap): Boolean {
        if (cut.provenance.model !== map.model) return false
        return store(cut.toCut(map).orNull(), cut)
    }

    private fun store(cut: Cut?, source: SourceCut?): Boolean {
        val key: Any = if (source == null) {
            checkNotNull(cut).key()
        } else {
            listOf(
                source.key,
                source.provenance.model,
                source.provenance.facts.filter { !it.global },
                source.provenance.assumptions,
            )
        }
        if (key in seen) return false
        val mixed = if (cut?.global == true) {
            entries.firstOrNull {
            (it.source == null) != (source == null) && it.cut?.global == true && it.cut?.key() == cut.key()
        }
        } else {
            null
        }
        if (mixed != null) {
            if (source != null) {
                seen.remove(mixed.key)
                mixed.key = key
                mixed.source = source
                mixed.cut = cut
                seen.add(key)
            }
            return false
        }
        seen.add(key)
        entries.add(Entry(cut, key, source))
        return true
    }

    fun remap(map: CutSourceMap): Map<CutMappingDecline, Int> {
        val declines = HashMap<CutMappingDecline, Int>()
        for (entry in entries) {
            val mapped = entry.source?.toCut(map) ?: CutMapping.Declined(CutMappingDecline.MISSING_PROVENANCE)
            entry.cut = mapped.orNull()
            if (mapped is CutMapping.Declined) declines[mapped.reason] = (declines[mapped.reason] ?: 0) + 1
        }
        return declines.toMap()
    }

    fun exportGlobalCuts(): List<SharedCut> = entries.mapNotNull { entry ->
        entry.source?.takeIf { it.provenance.global }?.let { SharedCut(it) }
    }

    /** Add each of [cuts] (deduplicated); returns how many were newly added. */
    fun addAll(cuts: Iterable<Cut>): Int {
        var added = 0
        for (c in cuts) if (add(c)) added++
        return added
    }

    /** The pooled cuts, in insertion order (after any [retainMostActive] eviction). */
    fun cuts(): List<Cut> = entries.mapNotNull { it.cut }

    /** Observe one solved LP point, update decayed tightness, and expire cuts inactive for too long. */
    fun observe(primal: DoubleArray) {
        for (entry in entries) {
            val cut = entry.cut ?: continue
            val active = slack(cut, primal) <= ACTIVE_TOLERANCE
            entry.activity *= ACTIVITY_DECAY
            if (active) {
                entry.activity += 1.0
                entry.inactiveCount = 0
            } else {
                entry.inactiveCount++
            }
        }
        entries.removeAll { it.inactiveCount >= maxConsecutiveInactive }
        rebuildSeen()
    }

    /** Enforce [maxCuts], keeping highest decayed activity with insertion order breaking ties. */
    fun retainMostActive() {
        if (entries.size <= maxCuts) return
        entries.sortByDescending { it.activity }
        while (entries.size > maxCuts) entries.removeAt(entries.size - 1)
        rebuildSeen()
    }

    private fun rebuildSeen() {
        seen.clear()
        for (entry in entries) seen.add(entry.key)
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
     * **efficacy plus objective parallelism**: the normalised violation
     * `violation / ‖coeffs‖₂` is added to the absolute cosine with the objective direction, following
     * CP-SAT's linear-constraint-manager policy. Candidates are then added greedily,
     * skipping a candidate that is near-parallel to one already chosen (an **orthogonality** filter on
     * the cosine of their coefficient vectors). A cut the point already satisfies (efficacy below
     * [minEfficacy]) is dropped — adding it would not move the bound. The returned cuts are a subset of
     * the pool, so the relaxation stays valid: selecting fewer cuts only loosens the bound, never
     * removes a feasible point. Insertion order breaks ties (the efficacy sort is stable).
     *
     * @param primal the current LP point cuts are scored against.
     * @param objective the LP objective direction used for parallelism scoring.
     * @param max the maximum number of cuts to return.
     * @param minEfficacy reject cuts whose normalised violation is below this.
     * @param minOrthogonality require each added cut's cosine-to-nearest-selected `≤ 1 − this`.
     */
    fun select(
        primal: DoubleArray,
        objective: DoubleArray,
        max: Int,
        minEfficacy: Double = MIN_EFFICACY,
        minOrthogonality: Double = MIN_ORTHOGONALITY,
    ): List<Cut> {
        if (max <= 0) return emptyList()
        val scored = entries.mapNotNull { entry ->
            val cut = entry.cut ?: return@mapNotNull null
            val efficacy = efficacy(cut, primal)
            if (efficacy < minEfficacy) {
                null
            } else {
                cut to (efficacy + objectiveParallelism(cut, objective))
            }
        }.sortedByDescending { it.second }
        val selected = ArrayList<Cut>()
        val maxCos = 1.0 - minOrthogonality
        for ((cut, _) in scored) {
            if (selected.size >= max) break
            if (selected.none { cosine(it, cut) > maxCos }) selected.add(cut)
        }
        return selected
    }

    private fun objectiveParallelism(cut: Cut, objective: DoubleArray): Double {
        val cutNorm = l2(cut)
        var objectiveNormSquared = 0.0
        for (coefficient in objective) objectiveNormSquared += coefficient * coefficient
        if (cutNorm == 0.0 || objectiveNormSquared == 0.0) return 0.0
        var dot = 0.0
        for (k in cut.cols.indices) {
            val col = cut.cols[k]
            if (col in objective.indices) dot += cut.coeffs[k].toDouble() * objective[col]
        }
        return abs(dot) / (cutNorm * sqrt(objectiveNormSquared))
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

        /** CP-SAT's `cut_active_count_decay`, expressed as a bounded decayed activity accumulator. */
        const val ACTIVITY_DECAY: Double = 0.8

        /** CP-SAT's default `max_consecutive_inactive_count`. */
        const val DEFAULT_MAX_CONSECUTIVE_INACTIVE: Int = 100

        const val ACTIVE_TOLERANCE: Double = 1e-6
    }
}
