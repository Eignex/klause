package com.eignex.klause.lp.optimization

import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.cut.CutPool
import com.eignex.klause.lp.cut.selectNonparallelCuts
import com.eignex.klause.lp.engine.CutFamily
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.util.Cancellation
import com.eignex.util.numbers.magnitudeBitLength
import kotlin.math.abs
import kotlin.math.sqrt

internal class LinearAuthorityPointCuts(
    private val budget: SourceLpBudget,
    private val maximumCuts: Int = 64,
    private val maximumSupport: Int = 8192,
    private val onCut: (CutFamily, String, Long) -> Unit = { _, _, _ -> },
) {
    private data class Scored(
        val id: Int, val values: Map<SourceCoordinate, Double>, val norm: Double, val score: Double,
    )
    private val counts = linkedMapOf<String, Long>()
    val observations: Map<String, Long> get() = counts.toMap()
    private var family: List<LinearAuthorityCut>? = null
    private var cooldown = 0
    private var emptyDiscoveryMisses = 0
    private var emptyDiscoveryCooldown = 0
    var selectionComplete: Boolean = false
        private set
    private var misses = 0
    private var retired = false
    private var managedCurrent = false
    private var managedInstalled = false
    private var retainedLayout = false
    private var trackUsefulness = false
    private var unproductivePoints = 0
    private var utilityFamilies: Set<CutFamily> = emptySet()
    private val inactiveScans = HashMap<Int, Int>()
    var adoptedCurrent: Boolean = false
        private set

    fun candidatesRetired(rows: List<LinearAuthorityCut>?): Boolean =
        rows != null && family === rows && retired

    fun managesCurrentRecipe(rows: List<LinearAuthorityCut>?): Boolean =
        rows != null && family === rows && managedCurrent

    fun retainsCandidateLayout(rows: List<LinearAuthorityCut>?): Boolean =
        rows != null && family === rows && retainedLayout

    fun resetDiscoveryBackoff() {
        emptyDiscoveryMisses = 0
        emptyDiscoveryCooldown = 0
    }

    fun admitDiscovery(cancellation: Cancellation): Boolean {
        if (!chargeDiscoveryDecision(cancellation)) return false
        if (emptyDiscoveryCooldown == 0) return true
        emptyDiscoveryCooldown--
        observe("EmptyDiscoveryCooldownQueries")
        return false
    }

    fun observeDiscovery(selected: Boolean, cancellation: Cancellation) {
        if (!selected && !selectionComplete) return
        if (!chargeDiscoveryDecision(cancellation)) return
        if (selected) {
            resetDiscoveryBackoff()
            return
        }
        emptyDiscoveryMisses++
        observe("EmptyDiscoveryAttempts")
        if (emptyDiscoveryMisses >= 2) {
            emptyDiscoveryMisses = 0
            emptyDiscoveryCooldown = 7
            observe("EmptyDiscoveryBackoffs")
        }
    }

    private fun chargeDiscoveryDecision(cancellation: Cancellation): Boolean {
        val funding = budget.funding
        if (cancellation() || funding?.paused == true ||
            funding?.remainingAllocation?.let { it < 64L } == true) return false
        budget.observe(preparation = 1L)
        funding?.charge(0L, 64L)
        observe("Work", 1L)
        observe("Allocation", 64L)
        return !cancellation() && funding?.paused != true
    }

    fun releaseCandidates() {
        family = null
        managedCurrent = false
        managedInstalled = false
        retainedLayout = false
        trackUsefulness = false
        unproductivePoints = 0
        utilityFamilies = emptySet()
        inactiveScans.clear()
    }

    fun observeUse(rows: List<LinearAuthorityCut>?, family: CutFamily): Boolean {
        if (this.family !== rows || !trackUsefulness || family !in utilityFamilies) return false
        budget.observe(preparation = 1L)
        unproductivePoints = 0
        observe("UsefulRecipeUses")
        return true
    }

    fun observeRound(rows: List<LinearAuthorityCut>, improved: Boolean) {
        if (family !== rows || retired || managedInstalled) return
        cooldown = if (improved) 0 else 7
        observe(if (improved) "ImprovedRounds" else "StalledRounds")
    }

    fun observe(name: String, amount: Long = 1L) {
        val key = "numericalCutSelection$name"
        counts[key] = (counts[key] ?: 0L) + amount
    }

    private inner class Meter(private val cancellation: Cancellation) {
        private val limit = minOf(65_536L, budget.funding?.availableWork ?: 65_536L)
        var work = 0L
        var allocation = 0L
        var limited = false
        fun charge(amount: Long, bytes: Long = 0L): Boolean {
            if (cancellation() || amount > limit - work || bytes > 1_048_576L - allocation ||
                budget.funding?.exhausted == true) {
                limited = true
                return false
            }
            work += amount
            allocation += bytes
            if (bytes > 0L) budget.funding?.charge(0L, bytes)
            if (budget.funding?.exhausted == true) {
                limited = true
                return false
            }
            return true
        }
        fun scalar(value: BigFraction): Double? {
            val words = (value.num.magnitudeBitLength().toLong() + value.den.magnitudeBitLength() + 63L) / 64L
            if (!charge(1L + words * words)) return null
            return value.toDouble().takeIf { it.isFinite() && (it != 0.0 || value.isZero) }
        }
    }

    fun prepareGenerated(
        rows: List<LinearAuthorityCut>, checked: Set<Int>, cancellation: Cancellation,
        trackUsefulness: Boolean = false,
    ): Set<Int> {
        adoptedCurrent = false
        selectionComplete = false
        if (rows.isEmpty() || rows.size > maximumCuts || checked.size != rows.size) return emptySet()
        val meter = Meter(cancellation)
        val visitedFamilies = HashSet<CutFamily>()
        try {
            val allocation = 512L + rows.size * 128L
            if (budget.funding?.remainingAllocation?.let { allocation > it } == true ||
                !meter.charge(rows.size * 4L + inactiveScans.size, allocation) ||
                rows.indices.any { it !in checked } || rows.none {
                    it.family == CutFamily.SOURCE_AGGREGATION || it.family == CutFamily.AGGREGATION_MIR
                }) return emptySet()
            val selected = checked.toSet()
            family = rows
            cooldown = 0
            misses = 0
            retired = false
            managedCurrent = true
            managedInstalled = false
            retainedLayout = true
            this.trackUsefulness = trackUsefulness
            unproductivePoints = 0
            inactiveScans.clear()
            observe("GeneratedRecipeSelected", selected.size.toLong())
            for (row in rows) {
                visitedFamilies.add(row.family)
                onCut(row.family, "POINT_SELECTED", 1L)
            }
            utilityFamilies = if (trackUsefulness) visitedFamilies else emptySet()
            return selected
        } finally {
            budget.observe(preparation = meter.work)
            observe("Work", meter.work)
            observe("Allocation", meter.allocation)
            for (family in visitedFamilies) {
                onCut(family, "POINT_SELECTION_WORK", meter.work)
                onCut(family, "POINT_SELECTION_ALLOCATION", meter.allocation)
            }
            if (meter.limited) observe("WorkLimited")
        }
    }

    fun select(
        rows: List<LinearAuthorityCut>,
        coordinates: List<SourceCoordinate>,
        costs: List<BigFraction>,
        checked: Set<Int>,
        point: List<BigFraction>,
        installed: Set<Int>,
        cancellation: Cancellation,
        adoptCurrent: Boolean = false,
        usefulnessOpportunity: Boolean = false,
        trackSelectedUsefulness: Boolean = false,
    ): Set<Int> {
        adoptedCurrent = false
        selectionComplete = false
        if (rows.isEmpty()) {
            selectionComplete = !cancellation() && budget.funding?.paused != true
            return installed
        }
        if (installed.size >= maximumCuts) observe("PoolLimited")
        if (family !== rows) {
            family = rows
            cooldown = 0
            misses = 0
            retired = false
            managedCurrent = false
            managedInstalled = false
            retainedLayout = false
            trackUsefulness = trackSelectedUsefulness
            unproductivePoints = 0
            utilityFamilies = emptySet()
            inactiveScans.clear()
        }
        if (retired) {
            observe("RetiredQueries")
            if (installed.isEmpty()) return installed
        }
        val complete = rows.size <= maximumCuts && installed.size == rows.size && checked.size == rows.size
        if (cooldown > 0 && !complete) {
            cooldown--
            observe("CooldownQueries")
            return installed
        }
        val meter = Meter(cancellation)
        val visitedFamilies = HashSet<CutFamily>()
        try {
            if (trackUsefulness && !meter.charge(1L)) return installed
            val pointEntryBytes = if (retired) 64L else 128L
            if (point.size != coordinates.size || point.size > maximumSupport ||
                !meter.charge(point.size.toLong(), 128L + point.size * pointEntryBytes)) {
                observe("PointUnavailable")
                return installed
            }
            val values = HashMap<SourceCoordinate, Double>()
            val objective = HashMap<SourceCoordinate, Double>()
            var objectiveNormSquared = 0.0
            var integerOnly = adoptCurrent
            for (id in coordinates.indices) {
                integerOnly = integerOnly && coordinates[id].kind == SourceCoordinate.Kind.Integer
                val value = meter.scalar(point[id]) ?: run {
                    observe("PointUnavailable")
                    return installed
                }
                values[coordinates[id]] = value
                if (!retired) {
                    val cost = meter.scalar(costs[id]) ?: 0.0
                    objective[coordinates[id]] = cost
                    objectiveNormSquared += cost * cost
                }
            }
            var aggregateOnly = adoptCurrent && rows.size <= maximumCuts
            if (aggregateOnly) {
                for (row in rows) {
                    if (!meter.charge(1L)) return installed
                    aggregateOnly = aggregateOnly && (row.family == CutFamily.SOURCE_AGGREGATION ||
                        row.family == CutFamily.AGGREGATION_MIR)
                }
            }
            if ((integerOnly || aggregateOnly) && installed.isEmpty() && rows.size <= maximumCuts &&
                checked.size == rows.size) {
                if (!meter.charge(rows.size.toLong(), 128L + rows.size * 64L) ||
                    rows.indices.any { it !in checked }) return installed
                for (row in rows) {
                    visitedFamilies.add(row.family)
                    onCut(row.family, "POINT_ADOPTED_INSTALLED", 1L)
                }
                adoptedCurrent = true
                managedCurrent = true
                managedInstalled = true
                retainedLayout = true
                utilityFamilies = if (trackUsefulness) visitedFamilies else emptySet()
                observe("RetainedLayout", rows.size.toLong())
                observe("AdoptedInstalled", rows.size.toLong())
                return checked.toSet()
            }
            val objectiveNorm = sqrt(objectiveNormSquared)
            val candidates = ArrayList<Scored>()
            val forced = ArrayList<Scored>()
            if (!meter.charge(installed.size.toLong() + inactiveScans.size,
                    128L + installed.size * 128L)) return installed
            val observedInactive = HashMap<Int, Int>()
            var support = 0
            var scoresComplete = checked.size == rows.size
            for ((id, row) in rows.withIndex()) {
                if (retired && id !in installed) {
                    if (!meter.charge(1L)) break
                    continue
                }
                if (id !in checked) {
                    scoresComplete = false
                    continue
                }
                val size = row.inequality.supportSize
                val rowAllocation = if (retired) 64L else 128L + size * 96L
                if (size > maximumSupport - support || !meter.charge(1L, rowAllocation)) {
                    observe("ScanLimited")
                    meter.limited = true
                    break
                }
                support += size
                visitedFamilies.add(row.family)
                observe("Visited")
                val projected = if (retired) null else LinkedHashMap<SourceCoordinate, Double>()
                var normSquared = 0.0
                var activity = 0.0
                var parallel = 0.0
                var usable = true
                for ((coordinate, coefficient) in row.inequality.coefficients) {
                    val scalar = meter.scalar(coefficient)
                    val value = values[coordinate]
                    if (scalar == null || value == null || !meter.charge(4L)) {
                        usable = false
                        break
                    }
                    projected?.set(coordinate, scalar)
                    normSquared += scalar * scalar
                    activity += scalar * value
                    if (!retired) parallel += scalar * (objective[coordinate] ?: 0.0)
                }
                val rhs = meter.scalar(row.inequality.rhs)
                val norm = sqrt(normSquared)
                val efficacy = if (rhs == null || norm <= 0.0) Double.NaN else (activity - rhs) / norm
                if (!usable || !norm.isFinite() || !efficacy.isFinite()) {
                    scoresComplete = false
                    observe("ScoreUnavailable")
                    continue
                }
                val alignment = if (objectiveNorm > 0.0 && objectiveNorm.isFinite())
                    abs(parallel) / (norm * objectiveNorm) else 0.0
                val score = efficacy / sqrt(maxOf(1, size).toDouble()) + 0.01 * alignment
                if (!score.isFinite()) {
                    scoresComplete = false
                    observe("ScoreUnavailable")
                    continue
                }
                onCut(row.family, when {
                    id in installed -> "POINT_RETAINED"
                    efficacy >= CutPool.MIN_EFFICACY -> "POINT_ELIGIBLE"
                    else -> "POINT_LOW_EFFICACY"
                }, 1L)
                if (id in installed) {
                    if (projected != null) forced += Scored(id, projected, norm, score)
                    observedInactive[id] = if (activity - checkNotNull(rhs) >= -CutPool.ACTIVE_TOLERANCE) 0
                        else minOf(4, (inactiveScans[id] ?: 0) + 1)
                } else if (efficacy >= CutPool.MIN_EFFICACY) {
                    candidates += Scored(id, checkNotNull(projected), norm, score)
                }
            }
            val selected = selectNonparallelCuts(candidates.sortedWith(compareByDescending<Scored> { it.score }
                .thenBy { it.id }), minOf(1, maximumCuts - installed.size),
                1.0 - CutPool.MIN_ORTHOGONALITY, forced) { a, b ->
                if (!meter.charge(a.values.size.toLong() + b.values.size)) Double.POSITIVE_INFINITY else {
                    var dot = 0.0
                    for ((coordinate, value) in a.values) dot += value * (b.values[coordinate] ?: 0.0)
                    val cosine = abs(dot) / (a.norm * b.norm)
                    if (cosine.isFinite()) cosine else Double.POSITIVE_INFINITY
                }
            }
            observe("Selected", selected.size.toLong())
            for (cut in selected) onCut(rows[cut.id].family, "POINT_SELECTED", 1L)
            val layoutPending = !retainedLayout && (installed.isNotEmpty() || selected.isNotEmpty())
            val installedPending = complete && !managedInstalled
            val layoutCovered = (layoutPending || installedPending) && rows.size <= maximumCuts &&
                checked.size == rows.size && !meter.limited && meter.charge(rows.size * 2L) &&
                rows.indices.all { it in checked && (!installedPending || it in installed) }
            var retained = installed
            if (!meter.limited && !cancellation()) {
                selectionComplete = scoresComplete
                if (layoutCovered) {
                    if (layoutPending) {
                        retainedLayout = true
                        observe("RetainedLayout", rows.size.toLong())
                    }
                    if (installedPending) {
                        managedInstalled = true
                        observe("ManagedInstalled", rows.size.toLong())
                    }
                }
                inactiveScans.keys.retainAll(installed)
                inactiveScans.putAll(observedInactive)
                if (!retired) {
                    misses = if (selected.isEmpty()) misses + 1 else 0
                    if (misses >= 4) {
                        retired = true
                        observe("RetiredFamilies")
                        for (family in visitedFamilies) onCut(family, "POINT_CANDIDATES_RETIRED", 1L)
                    }
                }
                if (trackUsefulness) {
                    utilityFamilies = visitedFamilies
                    val opportunity = usefulnessOpportunity && installed.isNotEmpty()
                    unproductivePoints = if (opportunity) minOf(4, unproductivePoints + 1) else 0
                    if (opportunity) observe("UsefulnessOpportunities")
                }
                val unproductive = trackUsefulness && unproductivePoints >= 4
                val expired = if (unproductive) installed else observedInactive.filterValues { it >= 4 }.keys
                if (expired.isNotEmpty()) {
                    retained = installed - expired
                    observe("RetiredInstalled", expired.size.toLong())
                    for (id in expired) onCut(rows[id].family, "POINT_INSTALLED_RETIRED", 1L)
                    inactiveScans.keys.removeAll(expired)
                }
                if (unproductive) {
                    retired = true
                    if (!managedCurrent) retainedLayout = false
                    trackUsefulness = false
                    utilityFamilies = emptySet()
                    observe("UnproductiveRecipeRetired")
                    for (id in expired) onCut(rows[id].family, "POINT_UNPRODUCTIVE_RETIRED", 1L)
                    return emptySet()
                }
                cooldown = if (managedInstalled) 0 else 7
            }
            return retained + selected.map { it.id }
        } finally {
            budget.observe(preparation = meter.work)
            observe("Work", meter.work)
            observe("Allocation", meter.allocation)
            for (family in visitedFamilies) {
                onCut(family, "POINT_SELECTION_WORK", meter.work)
                onCut(family, "POINT_SELECTION_ALLOCATION", meter.allocation)
            }
            if (meter.limited) observe("WorkLimited")
        }
    }
}
