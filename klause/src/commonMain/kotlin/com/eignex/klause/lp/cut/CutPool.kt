package com.eignex.klause.lp.cut

import com.eignex.klause.lp.engine.Cut
import com.eignex.klause.lp.engine.CutFamily
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.lp.relaxation.CutColumnBinding
import com.eignex.klause.lp.relaxation.CutColumnLayout
import com.eignex.klause.lp.relaxation.CutSourceMap
import com.eignex.klause.lp.relaxation.LpRelaxation
import com.eignex.klause.lp.relaxation.layoutFor
import com.eignex.klause.util.ExecutionControl
import com.eignex.klause.util.OriginCounts
import com.eignex.util.arrays.emptyDoubleArray
import com.eignex.util.collections.MutableIntLongMap
import kotlin.math.abs
import kotlin.math.sqrt

// Portable entries retain their source proof and are available only while their guards hold in remap's
// context; raw cuts belong to their original column layout and are unavailable after remapping. Eviction
// only loosens a relaxation.
internal class CutPool(
    val maxCuts: Int = DEFAULT_MAX_CUTS,
    private val maxConsecutiveInactive: Int = DEFAULT_MAX_CONSECUTIVE_INACTIVE,
    private val prepareSources: Boolean = false,
    private val cancellation: ExecutionControl = ExecutionControl.Never,
    private val onPreparationWork: (Long) -> Unit = {},
    private val observe: (CutFamily, String, String) -> Unit = { _, _, _ -> },
) {
    private val seen = HashSet<Any>()
    private val entries = ArrayList<Entry>()
    private val cosineIndex = MutableIntLongMap()
    private var columnLayout: CutColumnLayout? = null
    private var projectionCandidates = 0
    var preparationStorageUnits: Long = 0L
        private set

    private class Entry(
        var cut: Cut?,
        var key: Any,
        var source: SourceCut? = null,
        // The source a shared cut was imported from, or [NO_ORIGIN] for one derived here.
        val origin: Int = NO_ORIGIN,
        var activity: Double = 0.0,
        var inactiveCount: Int = 0,
    ) {
        var mappedOnce = false
        var preparation: SourceCutPreparation? = null
    }

    private data class ScoredCut(val cut: Cut, val norm: Double, val score: Double, val origin: Int)

    // Selections of imported cuts, by the source each came from.
    private val importUses = OriginCounts()

    val size: Int get() = entries.size

    fun add(cut: Cut, origin: Int = NO_ORIGIN): Boolean {
        val proof = cut.provenance
        val row = proof?.conclusion
        val source = if (row != null && cut.global == proof.global) {
            SourceCut(row.expression, row.relation, row.rhs, proof, cut.family)
        } else {
            null
        }
        return store(cut, source, origin)
    }

    fun add(cut: Cut, relaxation: LpRelaxation, origin: Int = NO_ORIGIN): Boolean =
        when (val mapped = SourceCut.fromCut(cut, relaxation)) {
            is CutMapping.Mapped -> add(mapped.value, checkNotNull(relaxation.sourceMap), origin)

            is CutMapping.Declined -> {
                observe(cut.family, "VERIFICATION", mapped.reason.name)
                val unscoped = cut.provenance == null && cut.tableau == null && cut.global
                if (unscoped && mapped.reason == CutMappingDecline.MISSING_SOURCE) add(cut, origin) else false
            }
        }

    fun add(cut: SourceCut, map: CutSourceMap, origin: Int = NO_ORIGIN): Boolean {
        if (cut.provenance.model !== map.model) {
            observe(cut.family, "VERIFICATION", CutMappingDecline.MODEL_SCOPE.name)
            return false
        }
        return store(cut.toCut(map).orNull(), cut, origin)
    }

    private fun store(cut: Cut?, source: SourceCut?, origin: Int): Boolean {
        val key: Any = if (source == null) {
            checkNotNull(cut).key()
        } else {
            source.poolKey
        }
        val family = source?.family ?: cut?.family ?: CutFamily.UNSPECIFIED
        if (key in seen) {
            observe(family, "ADMISSION", "DUPLICATE")
            return false
        }
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
                releasePreparation(mixed)
                mixed.mappedOnce = false
                mixed.source = source
                mixed.cut = cut
                seen.add(key)
            }
            return false
        }
        seen.add(key)
        entries.add(Entry(cut, key, source, origin))
        observe(family, "ADMISSION", if (cut == null) "DORMANT" else "ADMITTED")
        return true
    }

    fun remap(map: CutSourceMap): Map<CutMappingDecline, Int> {
        val declines = HashMap<CutMappingDecline, Int>()
        val columns = bindColumns(map)
        for (entry in entries) {
            val wasActive = entry.cut != null
            val mapped = remapEntry(entry, map, columns)
            entry.cut = mapped?.orNull()
            val family = entry.source?.family ?: CutFamily.UNSPECIFIED
            observe(family, "REMAP", when (mapped) {
                null -> "CANCELLED"
                is CutMapping.Declined -> mapped.reason.name
                is CutMapping.Mapped -> "MAPPED"
            })
            if (wasActive && entry.cut == null) observe(family, "RETRACTION", "UNAVAILABLE")
            if (!wasActive && entry.cut != null) observe(family, "RETRACTION", "REACTIVATED")
            if (mapped is CutMapping.Declined) declines[mapped.reason] = (declines[mapped.reason] ?: 0) + 1
        }
        return declines.toMap()
    }

    private fun bindColumns(map: CutSourceMap): CutColumnBinding? {
        if (!prepareSources || projectionCandidates == 0 || cancellation()) return null
        columnLayout?.let { previous ->
            val bound = previous.bind(map, cancellation, onPreparationWork)
            if (bound != null || cancellation()) return bound
            for (entry in entries) {
                val prepared = entry.preparation ?: continue
                if (!prepared.projection) continue
                onPreparationWork(1L)
                val input = prepared.withoutProjection()
                preparationStorageUnits += input.storageUnits - prepared.storageUnits
                entry.preparation = input
                observe(entry.source?.family ?: CutFamily.UNSPECIFIED, "PROJECTION", "INVALIDATED")
            }
            releaseColumnLayout()
            return null
        }
        val bound = CutColumnLayout.capture(map, MAX_PREPARATION_UNITS - preparationStorageUnits,
            cancellation, onPreparationWork) ?: return null
        val layout = requireNotNull(bound.layoutFor(map))
        if (layout.storageUnits > MAX_PREPARATION_UNITS - preparationStorageUnits || cancellation()) return null
        columnLayout = layout
        preparationStorageUnits += layout.storageUnits
        observe(CutFamily.UNSPECIFIED, "PROJECTION_LAYOUT", "RETAINED")
        return bound
    }

    private fun remapEntry(entry: Entry, map: CutSourceMap, columns: CutColumnBinding?): CutMapping<Cut>? {
        val source = entry.source ?: return CutMapping.Declined(CutMappingDecline.MISSING_PROVENANCE)
        if (prepareSources && cancellation()) return null
        val prepared = entry.preparation
        if (prepared != null) {
            onPreparationWork(1L)
            if (cancellation()) return null
        }
        val upgrade = columns != null && prepared?.supportsProjection == true && !prepared.projection
        val units = if (upgrade) source.projectedPreparationUnits else SourceCutPreparation.STORAGE_UNITS
        val eligible = prepareSources && entry.mappedOnce && (prepared == null || upgrade)
        val room = units <= MAX_PREPARATION_UNITS - preparationStorageUnits + (prepared?.storageUnits ?: 0L)
        val capture: ((SourceCutPreparation) -> Unit)? = if (eligible && room && source.preparationExtent >= units) {
            { next -> retainPreparation(entry, prepared, next) }
        } else null
        val binding = if (prepared?.projection == true || upgrade) columns else null
        val result = source.toCut(map, preparation = prepared, onPrepared = capture, columnBinding = binding)
        if (prepareSources && cancellation()) return null
        if (result is CutMapping.Mapped) {
            entry.mappedOnce = true
            if (prepared != null) {
                observe(source.family, if (prepared.projection) "PROJECTION" else "PREPARATION", "REUSED")
            }
        }
        return result
    }

    private fun retainPreparation(entry: Entry, previous: SourceCutPreparation?, next: SourceCutPreparation) {
        val family = entry.source?.family ?: CutFamily.UNSPECIFIED
        onPreparationWork(next.storageUnits)
        if (previous != null) onPreparationWork(1L)
        val available = MAX_PREPARATION_UNITS - preparationStorageUnits + (previous?.storageUnits ?: 0L)
        if (cancellation() || next.storageUnits > available) {
            observe(family, if (next.projection) "PROJECTION" else "PREPARATION", "CANCELLED")
            return
        }
        if (previous == null) {
            if (next.supportsProjection) projectionCandidates++
        } else {
            preparationStorageUnits -= previous.storageUnits
            observe(family, if (previous.projection) "PROJECTION" else "PREPARATION", "RETIRED")
        }
        entry.preparation = next
        preparationStorageUnits += next.storageUnits
        observe(family, if (next.projection) "PROJECTION" else "PREPARATION", "RETAINED")
    }

    private fun releasePreparation(entry: Entry) {
        val prepared = entry.preparation ?: return
        onPreparationWork(1L)
        preparationStorageUnits -= prepared.storageUnits
        entry.preparation = null
        if (prepared.supportsProjection) projectionCandidates--
        observe(entry.source?.family ?: CutFamily.UNSPECIFIED,
            if (prepared.projection) "PROJECTION" else "PREPARATION", "RETIRED")
        if (projectionCandidates == 0) releaseColumnLayout()
    }

    private fun releaseColumnLayout() {
        val layout = columnLayout ?: return
        onPreparationWork(1L)
        preparationStorageUnits -= layout.storageUnits
        columnLayout = null
        observe(CutFamily.UNSPECIFIED, "PROJECTION_LAYOUT", "RETIRED")
    }

    fun exportGlobalCuts(): List<SharedCut> = entries.mapNotNull { entry ->
        entry.source?.takeIf { it.provenance.global }?.let { SharedCut(it) }
    }

    fun addAll(cuts: Iterable<Cut>): Int {
        var added = 0
        for (c in cuts) if (add(c)) added++
        return added
    }

    fun cuts(): List<Cut> = entries.mapNotNull { it.cut }

    fun constantContradiction(): Cut? = entries.firstNotNullOfOrNull { entry ->
        entry.cut?.takeIf { it.cols.isEmpty() && efficacy(it, emptyDoubleArray(), 0.0) > 0.0 }
    }

    fun observe(primal: DoubleArray) {
        for (entry in entries) {
            val cut = entry.cut ?: continue
            val active = slack(cut, primal) <= ACTIVE_TOLERANCE
            observe(cut.family, "AGING", if (active) "TIGHT" else "SLACK")
            entry.activity *= ACTIVITY_DECAY
            if (active) {
                entry.activity += 1.0
                entry.inactiveCount = 0
            } else {
                entry.inactiveCount++
            }
        }
        entries.removeAll {
            (it.inactiveCount >= maxConsecutiveInactive).also { retired ->
                if (retired) {
                    releasePreparation(it)
                    observe(it.source?.family ?: it.cut?.family ?: CutFamily.UNSPECIFIED,
                        "RETIREMENT", "INACTIVE")
                }
            }
        }
        rebuildSeen()
    }

    // The stable sort lets insertion order break activity ties.
    fun retainMostActive() {
        if (entries.size <= maxCuts) return
        entries.sortByDescending { it.activity }
        while (entries.size > maxCuts) {
            val entry = entries.removeAt(entries.size - 1)
            releasePreparation(entry)
            observe(entry.source?.family ?: entry.cut?.family ?: CutFamily.UNSPECIFIED, "RETIREMENT", "CAPACITY")
        }
        rebuildSeen()
    }

    private fun rebuildSeen() {
        seen.clear()
        for (entry in entries) seen.add(entry.key)
    }

    private fun slack(cut: Cut, primal: DoubleArray): Double {
        var lhs = 0.0
        for (k in cut.cols.indices) {
            val col = cut.cols[k]
            if (col in primal.indices) lhs += cut.coeffs[k] * primal[col]
        }
        return abs(cut.rhs - lhs)
    }

    // Score is efficacy plus objective parallelism (Achterberg, "Constraint Integer Programming", PhD thesis,
    // TU Berlin 2007, https://nbn-resolving.org/urn:nbn:de:kobv:83-opus-16117); greedy selection then skips
    // cuts near-parallel to one already chosen. Selecting a subset of the pool only loosens the bound.
    fun select(
        primal: DoubleArray,
        objective: DoubleArray,
        max: Int,
        minEfficacy: Double = MIN_EFFICACY,
        minOrthogonality: Double = MIN_ORTHOGONALITY,
    ): List<Cut> {
        if (max <= 0) return emptyList()
        val objectiveNorm = l2(objective)
        val scored = entries.mapNotNull { entry ->
            val cut = entry.cut ?: return@mapNotNull null
            val norm = l2(cut)
            val efficacy = efficacy(cut, primal, norm)
            observe(cut.family, "SELECTION", if (efficacy < minEfficacy) "LOW_EFFICACY" else "ELIGIBLE")
            if (efficacy < minEfficacy) {
                null
            } else {
                val score = efficacy + objectiveParallelism(cut, objective, norm, objectiveNorm)
                ScoredCut(cut, norm, score, entry.origin)
            }
        }.sortedByDescending { it.score }
        val selected = selectNonparallelCuts(scored, max, 1.0 - minOrthogonality, cosine = ::cosine)
        for (s in selected) {
            importUses.note(s.origin)
            observe(s.cut.family, "SELECTION", "SELECTED")
        }
        return selected.map { it.cut }
    }

    private fun objectiveParallelism(
        cut: Cut,
        objective: DoubleArray,
        cutNorm: Double,
        objectiveNorm: Double,
    ): Double {
        if (cutNorm == 0.0 || objectiveNorm == 0.0 || !cutNorm.isFinite() || !objectiveNorm.isFinite()) return 0.0
        var dot = 0.0
        for (k in cut.cols.indices) {
            val col = cut.cols[k]
            if (col in objective.indices) dot += cut.coeffs[k].toDouble() * objective[col]
        }
        return abs(dot) / (cutNorm * objectiveNorm)
    }

    private fun efficacy(cut: Cut, primal: DoubleArray, norm: Double): Double {
        var lhs = 0.0
        for (k in cut.cols.indices) {
            val col = cut.cols[k]
            if (col in primal.indices) lhs += cut.coeffs[k] * primal[col]
        }
        val violation = when (cut.rel) {
            Relation.GE -> cut.rhs - lhs

            Relation.LE -> lhs - cut.rhs

            Relation.EQ -> abs(lhs - cut.rhs)
        }
        if (violation <= 0.0) return 0.0
        // A violated constant row certifies infeasibility independently of the point's coordinates.
        return if (norm > 0.0) violation / norm else Double.POSITIVE_INFINITY
    }

    private fun l2(cut: Cut): Double {
        var s = 0.0
        for (c in cut.coeffs) s += c.toDouble() * c.toDouble()
        return sqrt(s)
    }

    private fun l2(values: DoubleArray): Double {
        var s = 0.0
        for (value in values) s += value * value
        return sqrt(s)
    }

    private fun cosine(a: ScoredCut, b: ScoredCut): Double {
        val na = a.norm
        val nb = b.norm
        if (na == 0.0 || nb == 0.0) return 0.0
        val bIndex = cosineIndex
        bIndex.clear()
        for (k in b.cut.cols.indices) bIndex.put(b.cut.cols[k], b.cut.coeffs[k])
        var dot = 0.0
        for (k in a.cut.cols.indices) {
            if (!bIndex.containsKey(a.cut.cols[k])) continue
            val bc = bIndex.getOrDefault(a.cut.cols[k], 0L)
            dot += a.cut.coeffs[k].toDouble() * bc.toDouble()
        }
        return abs(dot) / (na * nb)
    }

    fun drainImportUses(action: (origin: Int, uses: Long) -> Unit) = importUses.drain(action)

    internal companion object {
        const val NO_ORIGIN: Int = -1

        // Bounds the per-node LP cost of a pathological root over-harvest; a normal harvest stays well below.
        const val DEFAULT_MAX_CUTS: Int = 2048
        private const val MAX_PREPARATION_UNITS = 262_144L

        const val MIN_EFFICACY: Double = 1e-4

        const val MIN_ORTHOGONALITY: Double = 0.05

        const val ACTIVITY_DECAY: Double = 0.8

        const val DEFAULT_MAX_CONSECUTIVE_INACTIVE: Int = 100

        const val ACTIVE_TOLERANCE: Double = 1e-6
    }
}
