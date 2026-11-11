package com.eignex.klause.lp.optimization

import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.cut.CutPool
import com.eignex.klause.lp.cut.selectNonparallelCuts
import com.eignex.klause.lp.engine.CutFamily
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceInequality
import com.eignex.klause.solver.evidence.SourceProofContext
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
    private data class ScoringObjective(
        val coefficients: Map<SourceCoordinate, Double>, val normSquared: Double,
    )
    private val counts = linkedMapOf<String, Long>()
    private var onObservation: (String, Long) -> Unit = { _, _ -> }
    val observations: Map<String, Long> get() = counts.toMap()

    fun observeTo(observer: (String, Long) -> Unit) { onObservation = observer }
    private var family: List<LinearAuthorityCut>? = null
    private var cooldown = 0
    private var misses = 0
    private var retired = false
    private var managedCurrent = false
    private var managedInstalled = false
    private var retainedLayout = false
    private val inactiveScans = HashMap<Int, Int>()
    var adoptedCurrent: Boolean = false
        private set

    fun candidatesRetired(rows: List<LinearAuthorityCut>?): Boolean =
        rows != null && family === rows && retired

    fun managesCurrentRecipe(rows: List<LinearAuthorityCut>?): Boolean =
        rows != null && family === rows && managedCurrent

    fun retainsCandidateLayout(rows: List<LinearAuthorityCut>?): Boolean =
        rows != null && family === rows && retainedLayout

    fun releaseCandidates() {
        family = null
        managedCurrent = false
        managedInstalled = false
        retainedLayout = false
        inactiveScans.clear()
    }

    fun observeRound(rows: List<LinearAuthorityCut>, improved: Boolean) {
        if (family !== rows || retired || managedInstalled) return
        cooldown = if (improved) 0 else 7
        observe(if (improved) "ImprovedRounds" else "StalledRounds")
    }

    fun observe(name: String, amount: Long = 1L) {
        val key = "numericalCutSelection$name"
        counts[key] = (counts[key] ?: 0L) + amount
        onObservation(key, amount)
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

        fun opposite(left: BigFraction, right: BigFraction): Boolean {
            if (!charge(1L)) return false
            if (left.isZero || right.isZero) return left.isZero && right.isZero
            val leftWords = (left.num.magnitudeBitLength().toLong() + left.den.magnitudeBitLength() + 63L) / 64L
            val rightWords = (right.num.magnitudeBitLength().toLong() + right.den.magnitudeBitLength() + 63L) / 64L
            if (leftWords > 64L || rightWords > 64L ||
                !charge(1L + leftWords * leftWords + rightWords * rightWords, 128L + rightWords * 16L)) return false
            return left == right.negated()
        }
    }

    private fun scoringObjective(
        source: SourceProofContext?, rows: List<LinearAuthorityCut>,
        objective: Map<SourceCoordinate, Double>, meter: Meter,
    ): ScoringObjective? {
        if (source == null) return null
        var coordinate: SourceCoordinate? = null
        var cost = 0.0
        for ((id, value) in objective) {
            if (!meter.charge(1L)) return null
            if (value != 0.0) {
                if (coordinate != null) return null
                coordinate = id
                cost = value
            }
        }
        val alias = coordinate ?: return null
        for (row in rows) {
            if (!meter.charge(1L)) return null
            if (row.inequality.coefficient(alias) != null) return null
        }
        observe("ObjectiveProjectionAttempts")
        val definition = objectiveDefinition(source, alias, meter)
        val projected = definition?.let { projectObjective(it, alias, cost, objective, meter) }
        observe(if (projected == null) "ObjectiveProjectionDeclined" else "ObjectiveProjected")
        return projected
    }

    private fun objectiveDefinition(
        source: SourceProofContext, alias: SourceCoordinate, meter: Meter,
    ): SourceInequality? {
        if (!meter.charge(1L, 192L)) return null
        var first: SourceInequality? = null
        var second: SourceInequality? = null
        val complete = source.allPremises { _, premise ->
            if (!meter.charge(1L)) return@allPremises false
            val row = premise.inequality
            when {
                premise.guardCount != 0 || premise.integerPinCount != 0 || row.strict ||
                    row.supportSize < 2 || row.coefficient(alias) == null -> true
                row.supportSize > 128 -> false
                first == null -> { first = row; true }
                second == null -> { second = row; true }
                else -> false
            }
        }
        val row = first ?: return null
        val other = second ?: return null
        if (!complete || row.supportSize != other.supportSize || !meter.opposite(row.rhs, other.rhs)) return null
        return row.takeIf {
            row.forEachCoefficient { coordinate, value ->
                meter.charge(1L) && other.coefficient(coordinate)?.let { meter.opposite(value, it) } == true
            }
        }
    }

    private fun projectObjective(
        row: SourceInequality, alias: SourceCoordinate, cost: Double,
        objective: Map<SourceCoordinate, Double>, meter: Meter,
    ): ScoringObjective? {
        val pivot = meter.scalar(checkNotNull(row.coefficient(alias))) ?: return null
        val scale = -cost / pivot
        if (!scale.isFinite() || scale == 0.0 ||
            !meter.charge(1L, 192L + row.supportSize * 64L)) return null
        val projected = HashMap<SourceCoordinate, Double>()
        var normSquared = 0.0
        val complete = row.forEachCoefficient { coordinate, coefficient ->
            if (!meter.charge(2L)) return@forEachCoefficient false
            if (coordinate == alias) return@forEachCoefficient true
            if (coordinate !in objective) return@forEachCoefficient false
            val scalar = meter.scalar(coefficient) ?: return@forEachCoefficient false
            val value = scale * scalar
            if (!value.isFinite() || value == 0.0) return@forEachCoefficient false
            projected[coordinate] = value
            normSquared += value * value
            normSquared.isFinite()
        }
        return if (complete && projected.isNotEmpty() && normSquared > 0.0)
            ScoringObjective(projected, normSquared) else null
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
        objectiveSource: SourceProofContext? = null,
        fractional: Boolean = false,
    ): Set<Int> {
        adoptedCurrent = false
        if (rows.isEmpty()) return installed
        if (installed.size >= maximumCuts) observe("PoolLimited")
        if (family !== rows) {
            family = rows
            cooldown = 0
            misses = 0
            retired = false
            managedCurrent = false
            managedInstalled = false
            retainedLayout = false
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
                observe("RetainedLayout", rows.size.toLong())
                observe("AdoptedInstalled", rows.size.toLong())
                return checked.toSet()
            }
            val projectedObjective = if (retired) null else scoringObjective(objectiveSource, rows, objective, meter)
            if (meter.limited) return installed
            val scoringCosts = projectedObjective?.coefficients ?: objective
            val objectiveNorm = sqrt(projectedObjective?.normSquared ?: objectiveNormSquared)
            val candidates = ArrayList<Scored>()
            val forced = ArrayList<Scored>()
            if (!meter.charge(installed.size.toLong() + inactiveScans.size,
                    128L + installed.size * 128L)) return installed
            val observedInactive = HashMap<Int, Int>()
            var support = 0
            for ((id, row) in rows.withIndex()) {
                if (retired && id !in installed) {
                    if (!meter.charge(1L)) break
                    continue
                }
                if (id !in checked) continue
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
                    if (!retired) parallel += scalar * (scoringCosts[coordinate] ?: 0.0)
                }
                val rhs = meter.scalar(row.inequality.rhs)
                val norm = sqrt(normSquared)
                val efficacy = if (rhs == null || norm <= 0.0) Double.NaN else (activity - rhs) / norm
                if (!usable || !norm.isFinite() || !efficacy.isFinite()) {
                    observe("ScoreUnavailable")
                    continue
                }
                val alignment = if (objectiveNorm > 0.0 && objectiveNorm.isFinite())
                    abs(parallel) / (norm * objectiveNorm) else 0.0
                val score = efficacy / sqrt(maxOf(1, size).toDouble()) + 0.01 * alignment
                if (!score.isFinite()) {
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
            val maximumAdded = minOf(if (fractional) 8 else 1, maxOf(0, maximumCuts - installed.size))
            if (fractional) {
                observe("FractionalBatchAttempts")
                if (!meter.charge(1L + maximumAdded, 128L + maximumAdded * 96L)) return installed
            }
            val selected = selectNonparallelCuts(candidates.sortedWith(compareByDescending<Scored> { it.score }
                .thenBy { it.id }), maximumAdded,
                1.0 - CutPool.MIN_ORTHOGONALITY, forced) { a, b ->
                if (!meter.charge(a.values.size.toLong() + b.values.size)) Double.POSITIVE_INFINITY else {
                    var dot = 0.0
                    for ((coordinate, value) in a.values) dot += value * (b.values[coordinate] ?: 0.0)
                    val cosine = abs(dot) / (a.norm * b.norm)
                    if (cosine.isFinite()) cosine else Double.POSITIVE_INFINITY
                }
            }
            if (fractional && (meter.limited || cancellation() || budget.funding?.exhausted == true)) {
                return installed
            }
            observe("Selected", selected.size.toLong())
            for (cut in selected) onCut(rows[cut.id].family, "POINT_SELECTED", 1L)
            val layoutPending = !retainedLayout && (installed.isNotEmpty() || selected.isNotEmpty())
            val installedPending = complete && !managedInstalled
            val layoutCovered = (layoutPending || installedPending) && rows.size <= maximumCuts &&
                checked.size == rows.size && !meter.limited && meter.charge(rows.size * 2L) &&
                rows.indices.all { it in checked && (!installedPending || it in installed) }
            var retained = installed
            if (fractional && (meter.limited || cancellation() || budget.funding?.exhausted == true)) {
                return installed
            }
            if (!meter.limited && !cancellation()) {
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
                val expired = observedInactive.filterValues { it >= 4 }.keys
                if (expired.isNotEmpty()) {
                    retained = installed - expired
                    observe("RetiredInstalled", expired.size.toLong())
                    for (id in expired) onCut(rows[id].family, "POINT_INSTALLED_RETIRED", 1L)
                    inactiveScans.keys.removeAll(expired)
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
