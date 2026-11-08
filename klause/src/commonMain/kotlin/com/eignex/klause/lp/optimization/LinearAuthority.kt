package com.eignex.klause.lp.optimization

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.SourceLpFunding
import com.eignex.klause.lp.SparseSourceLpPolicy
import com.eignex.klause.lp.engine.CutFamily
import com.eignex.klause.lp.engine.LpClaim
import com.eignex.klause.lp.engine.LpVerdict
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceDerivation
import com.eignex.klause.solver.evidence.SourceEvidence
import com.eignex.klause.solver.evidence.SourceEvidenceCheck
import com.eignex.klause.solver.evidence.SourceEvidenceVerifier
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.util.Cancellation
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.compareTo
import com.eignex.util.numbers.fitsLong
import com.eignex.util.numbers.magnitudeBitLength
import com.eignex.util.numbers.times
import com.eignex.util.numbers.toLongExact

internal data class LinearAuthorityResult(
    val evidence: SourceEvidence? = null,
    val bound: SourceEvidence? = null,
    val incumbent: Sample? = null,
    val split: Pair<SourceCoordinate, BigFraction>? = null,
    val fractional: List<Pair<SourceCoordinate, BigFraction>> = emptyList(),
    val relaxedPoint: Map<SourceCoordinate, Double> = emptyMap(),
    val advisoryPoint: Map<SourceCoordinate, Double>? = null,
    val paused: Boolean = false,
    val coordinateConflict: Boolean = false,
    val lpConflict: Boolean = false,
    val primalStages: Map<String, Long> = emptyMap(),
    val cutBoundFamilies: Set<CutFamily> = emptySet(),
    val cutConflictFamilies: Set<CutFamily> = emptySet(),
    val cutTerminalBoundFamilies: Set<CutFamily> = emptySet(),
)

internal class LinearAuthority(
    private val budget: SourceLpBudget,
    onLpShape: (LinearAuthorityLpShape) -> Unit = {},
    private val retainLp: Boolean = false,
    guidancePolicy: SparseSourceLpPolicy = SparseSourceLpPolicy(),
    additionalCuts: List<LinearAuthorityCut> = emptyList(),
    private val aggregateCuts: Boolean = false,
    private val fractionalAggregateOrder: Boolean = false,
) : AutoCloseable {
    init { require(additionalCuts.size <= 64) }
    private val additionalCuts = additionalCuts.toList()
    private var replayCuts: List<LinearAuthorityCut>? = null

    fun useReplayCuts(rows: List<LinearAuthorityCut>) {
        require(rows.size <= 64 && additionalCuts.isEmpty() && !aggregateCuts)
        check(replayCuts == null && template == null && query == null)
        replayCuts = rows.toList()
    }
    private val cutLifecycle = LinearCutLifecycle(budget)
    fun observeCutConsumption(families: Set<CutFamily>, outcome: String) {
        for (family in families) cutLifecycle.observe(family, outcome)
    }
    private val lp = LinearAuthorityLp(budget, onLpShape, retainLp, guidancePolicy,
        onCutMetric = { stage, count ->
            cutLifecycle.shared(stage, count)
        }) { metrics ->
        cutLifecycle.shared("${metrics.phase.name}_Calls", 1L)
        cutLifecycle.shared("${metrics.phase.name}_Work", metrics.work)
        cutLifecycle.shared("${metrics.phase.name}_${metrics.outcome}", 1L)
        if (metrics.phase.name.startsWith("CLAIM_")) {
            val key = "Certification" + metrics.phase.name
            observePrimal(key + "Calls")
            observePrimal(key + "Work", metrics.work)
            observePrimal(key + "_" + metrics.outcome)
        }
    }
    val funding: SourceLpFunding? get() = budget.funding
    val preparations: Long get() = lp.preparations
    val reuses: Long get() = lp.reuses
    private var template: LinearAuthorityTemplate? = null
    private var query: LinearAuthorityQuery? = null
    var capture: LinearAuthorityCapture? = null
    private var capturedPoint: Map<SourceCoordinate, BigFraction>? = null
    private var capturedQuery: LinearAuthorityQuery? = null
    private var capturedTemplate: LinearAuthorityTemplate? = null
    private var pointListener: ((LinearAuthorityQuery, List<BigFraction>) -> Unit)? = null
    private var continuationListener: ((LinearAuthorityQuery, List<BigFraction>) -> Unit)? = null
    private var pointPolicy: LinearAuthorityPointCuts? = null
    private val pointCounts = linkedMapOf<String, Long>()
    private var pointFamily: LinearAuthorityTemplate? = null
    private var pointFallback: LinearAuthorityTemplate? = null
    private var directedFamily: LinearAuthorityTemplate? = null
    private var pointMask: Set<Int>? = null
    private var deferAggregatePreparation = false
    private class PointRound(
        val query: LinearAuthorityQuery, val base: LinearAuthorityResult, val mask: Set<Int>,
        val rows: List<LinearAuthorityCut>,
    ) {
        var reported = false
    }
    private var pointRound: PointRound? = null
    private class PointRecipe(
        val template: LinearAuthorityTemplate, val query: LinearAuthorityQuery,
        val policy: LinearAuthorityPointCuts?, val family: LinearAuthorityTemplate?,
        val fallback: LinearAuthorityTemplate?, val directed: LinearAuthorityTemplate?,
        val mask: Set<Int>?, val primal: PrimalAttempt?,
    )
    private class FractionalRound(
        val previous: PointRecipe, val base: LinearAuthorityResult,
        val mask: Set<Int>, val rows: List<LinearAuthorityCut>,
    ) {
        var reported = false
    }
    private var fractionalRound: FractionalRound? = null
    private var fractionalFamily: LinearAuthorityTemplate? = null
    private var cutPreparationWork = 0L
    private var cutVerificationWork = 0L
    private var retainedCutPeak = 0L
    private var activeCutPeak = 0L
    private val cutEligibility = linkedMapOf<String, Long>()
    private var retainedMixedCutPeak = 0L
    private var activeMixedCutPeak = 0L
    fun cutObservations(): Map<String, Long> = mapOf(
        "numericalCutPreparationWork" to cutPreparationWork,
        "numericalCutVerificationWork" to cutVerificationWork,
        "numericalMaxRetainedCuts" to retainedCutPeak,
        "numericalMaxActiveCuts" to activeCutPeak,
        "numericalMaxRetainedMixedCuts" to retainedMixedCutPeak,
        "numericalMaxActiveMixedCuts" to activeMixedCutPeak,
    ) + cutEligibility + pointCounts + cutLifecycle.snapshot()
    private class PrimalAttempt(
        val query: LinearAuthorityQuery, val point: List<BigFraction>,
    ) {
        val progress = LinearPrimalRecoveryProgress()
        var recovered: List<BigFraction>? = null
        var finished = false
        var refined: List<BigFraction>? = null
        var refinementFinished = false
    }
    private var primalAttempt: PrimalAttempt? = null

    fun invalidateGuidance() {
        fractionalRound?.let { restorePointRecipe(it.previous) }
        fractionalRound = null
        pointRound = null
        lp.invalidateGuidance()
    }

    override fun close() {
        replayCuts = null
        template = null
        query = null
        capturedQuery = null
        capturedTemplate = null
        capturedPoint = null
        pointListener = null
        continuationListener = null
        pointRound = null
        fractionalRound?.previous?.policy?.releaseCandidates()
        fractionalRound = null
        fractionalFamily = null
        pointPolicy?.releaseCandidates()
        pointFamily = null
        pointFallback = null
        directedFamily = null
        pointMask = null
        deferAggregatePreparation = false
        primalAttempt = null
        cutLifecycle.containing("RETIREMENT") { lp.close() }
    }

    fun advanceWithPointCuts(source: SourceProofContext, cancellation: Cancellation,
        retainBasePoint: Boolean = false): LinearAuthorityResult {
        if (!fractionalAggregateOrder || !aggregateCuts) {
            return advanceCurrentPointCuts(source, cancellation, retainBasePoint)
        }
        fractionalRound?.let { pending ->
            val previous = pending.previous.query
            if (previous.matches(source, true, previous.cutMask, previous.checkUnselectedCuts,
                    previous.retainMaskedCuts)) return advanceFractionalRound(source, cancellation, retainBasePoint)
            restorePointRecipe(pending.previous)
            fractionalRound = null
            lp.invalidateGuidance()
        }
        var point: Pair<LinearAuthorityQuery, List<BigFraction>>? = null
        continuationListener = { prepared, values -> point = prepared to values }
        val base = try {
            advanceCurrentPointCuts(source, cancellation, retainBasePoint)
        } finally {
            continuationListener = null
        }
        if (base.paused || base.evidence != null || base.incumbent != null || cancellation() ||
            budget.funding?.paused == true) {
            return base.copy(advisoryPoint = base.advisoryPoint.takeUnless { cancellation() })
        }
        val observed = point ?: return base
        val family = template ?: return base
        if (observed.first !== query || fractionalFamily === family ||
            family.cuts == null || family.cuts.rows.size >= 64) return base
        val ready = cutLifecycle.containing("POINT_SELECTION") {
            currentPoolInstalled(observed.first, family.cuts.rows.size, cancellation)
        }
        if (!ready) return base.copy(advisoryPoint = base.advisoryPoint.takeUnless { cancellation() },
            paused = budget.funding?.let { it.paused && !it.exhausted } == true)
        fractionalFamily = family
        val prepared = cutLifecycle.containing("POINT_SELECTION") {
            val observer = capture?.takeIf { it.fractionalEnabled }
            val recorded = observer?.beginFractionalAttempt(source, observed.first, family, observed.second,
                base, budget, cancellation) { fractionalSnapshot(cancellation) }
            var outcome = "interrupted"
            try {
                prepareFractionalRound(source, family, observed.first, observed.second, base, cancellation,
                    recorded?.rejected).also { selected ->
                    outcome = when {
                        selected -> "query-selected"
                        cancellation() -> "cancelled"
                        budget.funding?.paused == true -> "funding-paused"
                        else -> "declined"
                    }
                }
            } finally {
                observer?.finishFractionalAttempt(recorded, outcome) { fractionalSnapshot(cancellation) }
            }
        }
        if (!prepared) return base.copy(advisoryPoint = base.advisoryPoint.takeUnless { cancellation() },
            paused = budget.funding?.let { it.paused && !it.exhausted } == true)
        return advanceFractionalRound(source, cancellation, retainBasePoint)
    }

    private fun fractionalSnapshot(cancellation: Cancellation): Map<String, Long?> = mapOf(
        "operations" to budget.operations.toLong(),
        "lpPreparations" to preparations,
        "lpReuses" to reuses,
        "availableWork" to budget.funding?.availableWork,
        "remainingWork" to budget.funding?.remainingWork,
        "remainingAllocation" to budget.funding?.remainingAllocation,
        "lpActiveNs" to budget.activeNanos,
        "preparationWork" to budget.measuredPreparationWork,
        "pairAttempts" to (cutEligibility["numericalCutAggregatePairAttempts"] ?: 0L),
        "pointEligible" to (cutEligibility["numericalCutAggregatePairPointEligible"] ?: 0L),
        "lowEfficacy" to (cutEligibility["numericalCutAggregatePairLowEfficacy"] ?: 0L),
        "missingBounds" to (cutEligibility["numericalCutAggregateMissingBounds"] ?: 0L),
        "allocationLimits" to (cutEligibility["numericalCutAggregateAllocationLimit"] ?: 0L),
        "remainingDeadlineNs" to cancellation.deadline()?.let {
            (-it.elapsedNow().inWholeNanoseconds).coerceAtLeast(0L)
        },
    )

    private fun currentPoolInstalled(prepared: LinearAuthorityQuery, rows: Int,
        cancellation: Cancellation): Boolean {
        val policy = pointPolicy ?: return false
        val work = 1L + rows * 2L
        if (cancellation() || budget.funding?.availableWork?.let { work > it } == true ||
            budget.funding?.remainingAllocation?.let { 64L > it } == true) {
            policy.observe("FractionalPoolUnavailable")
            return false
        }
        cutCost(work, 64L)
        if (cancellation() || budget.funding?.paused == true) return false
        policy.observe("FractionalPoolObservations")
        val ready = prepared.checkedCuts.size == rows &&
            prepared.installedCuts.size == rows &&
            (0 until rows).all { it in prepared.checkedCuts && it in prepared.installedCuts }
        policy.observe(if (ready) "FractionalPoolReady" else "FractionalPoolDeferred")
        if (ready) {
            policy.observe("FractionalAttemptPoolRows", rows.toLong())
            policy.observe("FractionalAttemptCheckedRows", prepared.checkedCuts.size.toLong())
            policy.observe("FractionalAttemptInstalledRows", prepared.installedCuts.size.toLong())
        }
        return ready
    }

    private fun newPointPolicy(): LinearAuthorityPointCuts =
        LinearAuthorityPointCuts(budget, onCut = cutLifecycle::observe).also { policy ->
            policy.observeTo { key, amount -> pointCounts[key] = (pointCounts[key] ?: 0L) + amount }
        }

    private fun restorePointRecipe(previous: PointRecipe) {
        pointPolicy?.takeUnless { it === previous.policy }?.releaseCandidates()
        template = previous.template
        query = previous.query
        pointPolicy = previous.policy
        pointFamily = previous.family
        pointFallback = previous.fallback
        directedFamily = previous.directed
        pointMask = previous.mask
        primalAttempt = previous.primal
        fractionalFamily = previous.template
        cutLifecycle.families = previous.query.cutFamilies.values.toSet()
    }

    private fun prepareFractionalRound(
        source: SourceProofContext, family: LinearAuthorityTemplate, prepared: LinearAuthorityQuery,
        values: List<BigFraction>, base: LinearAuthorityResult, cancellation: Cancellation,
        journal: LinearAuthorityAggregateRejections? = null,
    ): Boolean {
        val allocation = 256L + values.size * 8L +
            64L * (base.fractional.size + base.relaxedPoint.size)
        if (values.size > 8192 || values.size != prepared.coordinates.size || cancellation() ||
            budget.funding?.availableWork?.let { values.size.toLong() > it } == true ||
            budget.funding?.remainingAllocation?.let { allocation > it } == true) return false
        cutCost(values.size.toLong(), allocation)
        if (budget.funding?.paused == true || cancellation()) return false
        val point = values.toList()
        val previous = PointRecipe(family, prepared, pointPolicy, pointFamily, pointFallback,
            directedFamily, pointMask, primalAttempt)
        val policy = newPointPolicy()
        pointPolicy = policy
        val refined = preparePointFamily(source, family, prepared, point, cancellation, fractional = true,
            journal = journal)
        if (refined == null || refined.first === family || budget.funding?.paused == true || cancellation()) {
            policy.observe("FractionalDeclined")
            restorePointRecipe(previous)
            return false
        }
        val installed = prepared.installedCuts
        val selected = policy.select(checkNotNull(refined.first.cuts).rows,
            refined.second.coordinates, refined.second.costs, refined.second.checkedCuts,
            point, installed, cancellation)
        val maskWork = 2L * installed.size + selected.size
        val covered = budget.funding?.availableWork?.let { maskWork <= it } != false
        if (covered) cutCost(maskWork, 0L)
        if (cancellation() || budget.funding?.paused == true ||
            !covered ||
            !refined.second.checkedCuts.containsAll(installed) || !selected.containsAll(installed) ||
            selected.none { it !in installed }) {
            policy.observe("FractionalDeclined")
            restorePointRecipe(previous)
            return false
        }
        pointMask = selected
        pointFamily = refined.first
        pointFallback = null
        fractionalFamily = refined.first
        fractionalRound = FractionalRound(previous, base, selected, checkNotNull(refined.first.cuts).rows)
        policy.observe("FractionalRounds")
        return true
    }

    private fun advanceFractionalRound(source: SourceProofContext, cancellation: Cancellation,
        retainBasePoint: Boolean): LinearAuthorityResult {
        val pending = checkNotNull(fractionalRound)
        val result = advance(source, cancellation, cuts = true, cutMask = pending.mask)
        val stages = mergePrimalStages(if (pending.reported) emptyMap() else pending.base.primalStages,
            result.primalStages)
        pending.reported = true
        val stopped = cancellation() || budget.funding?.exhausted == true
        if (result.paused && !stopped) return pending.base.copy(paused = true, primalStages = stages)
        fractionalRound = null
        val completed = !stopped && !result.paused &&
            (result.evidence != null || result.bound != null || result.incumbent != null || result.split != null)
        if (!completed) {
            pointPolicy?.observe("FractionalUnavailableRounds")
            restorePointRecipe(pending.previous)
            lp.invalidateGuidance()
            return pending.base.copy(primalStages = stages,
                advisoryPoint = pending.base.advisoryPoint.takeUnless { stopped })
        }
        val strongest = strongerLinearBound(pending.base.bound, result.bound)
        pointPolicy?.observeRound(pending.rows, result.evidence != null || result.incumbent != null ||
            (strongest != null && strongest !== pending.base.bound))
        pending.previous.policy?.releaseCandidates()
        return result.copy(bound = strongest, primalStages = stages,
            advisoryPoint = (pending.base.advisoryPoint ?: pending.base.relaxedPoint).takeIf {
                retainBasePoint && result.incumbent == null && result.evidence == null
            }, cutBoundFamilies = if (strongest != null && strongest === pending.base.bound) {
                pending.base.cutBoundFamilies
            } else result.cutBoundFamilies)
    }

    private fun mergePrimalStages(before: Map<String, Long>, after: Map<String, Long>): Map<String, Long> =
        (before.keys + after.keys).associateWith { key ->
            val first = before[key] ?: 0L
            val second = after[key] ?: 0L
            if ("Max" in key) maxOf(first, second) else first + second
        }

    private fun advanceCurrentPointCuts(source: SourceProofContext, cancellation: Cancellation,
        retainBasePoint: Boolean): LinearAuthorityResult {
        val policy = pointPolicy ?: newPointPolicy().also { pointPolicy = it }
        if (pointFallback === template && template?.matches(source) == true) {
            policy.observe("CurrentFallbackQueries")
            return advance(source, cancellation, cuts = true)
        }
        if (pointFamily !== template || template?.matches(source) != true || template?.cuts == null) {
            pointMask = null
            pointFallback = null
            pointRound = null
            pointFamily = template
        }
        var pending = pointRound?.takeIf {
            it.query.matches(source, cuts = true, cutMask = it.query.cutMask,
                checkUnselectedCuts = it.query.checkUnselectedCuts,
                retainMaskedCuts = it.query.retainMaskedCuts)
        }
        if (pending == null) {
            pointRound = null
            var point: Pair<LinearAuthorityQuery, List<BigFraction>>? = null
            pointListener = { query, values -> if (point == null) point = query to values }
            val baselineMask = pointMask
            policy.observe("BaseQueries")
            deferAggregatePreparation = baselineMask == null
            val base = try {
                advance(source, cancellation, cuts = true, cutMask = baselineMask)
            } finally {
                deferAggregatePreparation = false
                pointListener = null
            }
            if (base.paused || base.evidence != null || base.incumbent != null || cancellation()) return base
            val observed = point ?: run {
                policy.observe("PointUnavailable")
                return base
            }
            var prepared = observed.first
            var family = template ?: run {
                policy.observe("PointUnavailable")
                return base
            }
            pointFamily = family
            if (family.cuts == null ||
                !prepared.matches(source, cuts = true, cutMask = baselineMask,
                    checkUnselectedCuts = checkUnselectedCuts(baselineMask),
                    retainMaskedCuts = retainMaskedCuts(baselineMask))) {
                policy.observe("PointUnavailable")
                return base
            }
            val baselineFamily = family
            val baselineQuery = prepared
            val currentRows = checkNotNull(family.cuts).rows
            val generatedRecipe = currentRows.isNotEmpty() && currentRows.size <= 64 &&
                currentRows.all { it.family == CutFamily.SOURCE_AGGREGATION || it.family == CutFamily.AGGREGATION_MIR }
            if (!generatedRecipe &&
                !checkNotNull(family.cuts).pointDirected && directedFamily !== family &&
                pointMask == null &&
                (aggregateCuts || checkNotNull(family.cuts).rows.any { it.mixed })) {
                directedFamily = family
                cutLifecycle.families = checkNotNull(family.cuts).rows.mapTo(HashSet()) { it.family }
                val refined = cutLifecycle.containing("POINT_SELECTION") {
                    preparePointFamily(source, family, prepared, observed.second, cancellation)
                }
                if (refined != null) {
                    if (refined.first === baselineFamily && refined.second === baselineQuery) {
                        pointFallback = baselineFamily
                        directedFamily = null
                        policy.releaseCandidates()
                        policy.observe("UnchangedPreparations")
                        policy.observe("CurrentFallback")
                        cutLifecycle.families = baselineQuery.cutFamilies.values.toSet()
                        return base.copy(paused = base.paused || budget.funding?.paused == true)
                    }
                    family = refined.first
                    prepared = refined.second
                    directedFamily = family
                    pointFamily = family
                    policy.observe("PointPrepared")
                } else policy.observe("PointPreparationUnavailable")
            }
            val selected = cutLifecycle.containing("POINT_SELECTION") {
                policy.select(checkNotNull(family.cuts).rows, prepared.coordinates, prepared.costs,
                    prepared.checkedCuts, observed.second, pointMask.orEmpty().intersect(prepared.checkedCuts),
                    cancellation, adoptCurrent = baselineMask == null && family === baselineFamily &&
                        prepared.activeCuts == checkNotNull(family.cuts).rows.size)
            }
            if (budget.funding?.exhausted == true || cancellation()) return base
            if (policy.adoptedCurrent) {
                pointMask = selected
                return base.copy(paused = base.paused || budget.funding?.paused == true)
            }
            if (baselineMask == null && selected.isEmpty()) {
                template = baselineFamily
                query = baselineQuery
                pointFamily = baselineFamily
                pointFallback = baselineFamily
                directedFamily = null
                policy.releaseCandidates()
                policy.observe("CurrentFallback")
                cutLifecycle.families = baselineQuery.cutFamilies.values.toSet()
                return base.copy(paused = base.paused || budget.funding?.paused == true)
            }
            if (selected == pointMask) return base
            pointMask = selected
            pending = PointRound(prepared, base, selected, checkNotNull(family.cuts).rows)
            pointRound = pending
            policy.observe("Rounds")
            budget.funding?.charge(0L, 1024L + 64L * (base.fractional.size + base.relaxedPoint.size))
        }
        if (budget.funding?.exhausted == true || cancellation()) {
            pointRound = null
            return pending.base.copy(primalStages = if (pending.reported) emptyMap() else pending.base.primalStages)
        }
        val result = advance(source, cancellation, cuts = true, cutMask = pending.mask)
        if (!result.paused || budget.funding?.exhausted == true || cancellation()) pointRound = null
        val previousStages = if (pending.reported) emptyMap() else pending.base.primalStages
        pending.reported = true
        val stages = mergePrimalStages(previousStages, result.primalStages)
        if (result.paused && (budget.funding?.exhausted == true || cancellation())) {
            return pending.base.copy(primalStages = stages)
        }
        val completed = !result.paused && budget.funding?.paused != true && !cancellation()
        if (!result.paused && result.evidence == null && result.incumbent == null && result.split == null) {
            if (completed) policy.observeRound(pending.rows, improved = false)
            policy.observe("UnavailableRounds")
            return pending.base.copy(primalStages = stages)
        }
        val strongest = strongerLinearBound(pending.base.bound, result.bound)
        if (completed) {
            policy.observeRound(pending.rows, result.evidence != null || result.incumbent != null ||
                (strongest != null && strongest !== pending.base.bound))
        }
        return result.copy(bound = strongest, primalStages = stages,
            advisoryPoint = pending.base.relaxedPoint.takeIf {
                retainBasePoint && completed && result.incumbent == null && result.evidence == null
            },
            cutBoundFamilies = if (strongest != null && strongest === pending.base.bound) {
                pending.base.cutBoundFamilies
            } else result.cutBoundFamilies)
    }

    private fun preparePointFamily(
        source: SourceProofContext, family: LinearAuthorityTemplate, prepared: LinearAuthorityQuery,
        values: List<BigFraction>,
        cancellation: Cancellation,
        fractional: Boolean = false,
        journal: LinearAuthorityAggregateRejections? = null,
    ): Pair<LinearAuthorityTemplate, LinearAuthorityQuery>? {
        if (values.size != prepared.coordinates.size || values.size > 8192) return null
        val funded = Cancellation { cancellation() || budget.funding?.paused == true }
        var work = 0L
        if (budget.funding?.remainingAllocation?.let { 128L + values.size * 96L > it } == true) return null
        cutCost(0L, 128L)
        val point = HashMap<SourceCoordinate, Double>()
        for (index in values.indices) {
            val value = values[index]
            val words = (value.num.magnitudeBitLength().toLong() + value.den.magnitudeBitLength() + 63L) / 64L
            val cost = 1L + words * words
            if (funded() || cost > 65_536L - work ||
                budget.funding?.availableWork?.let { cost > it } == true) return null
            cutCost(cost, 96L)
            work += cost
            point[prepared.coordinates[index]] = value.toDouble().takeIf(Double::isFinite) ?: return null
        }
        val directed = if (fractional) {
            if (checkNotNull(family.cuts).rows.size > 64) return null
            pointPolicy?.observe("FractionalPreparations")
            family
        } else family.atPoint(point, funded, { cutCost(it, 0L) }, ::cutCost) ?: return null
        val refined = withAdditionalCuts(directed, funded, point, fractional, journal) ?: return null
        if (funded()) return null
        if (sameCurrentCuts(family, prepared, refined, funded, 65_536L - work)) return family to prepared
        if (funded()) return null
        val previous = template
        val previousDirected = directedFamily
        adoptTemplate(refined)
        if (fractional) directedFamily = refined
        query = null
        val checked = prepareQuery(source, funded, cuts = true, cutMask = emptySet())
        if (checked == null || funded() || template !== refined) {
            template = previous
            directedFamily = previousDirected
            return null
        }
        query = checked
        return refined to checked
    }

    private fun sameCurrentCuts(
        original: LinearAuthorityTemplate, query: LinearAuthorityQuery, refined: LinearAuthorityTemplate,
        cancellation: Cancellation, maximumWork: Long,
    ): Boolean {
        val before = original.cuts?.rows ?: return false
        val after = refined.cuts?.rows ?: return false
        if (before.size > 64 || before.size != after.size || query.activeCuts != before.size ||
            query.checkedCuts.size != before.size || cancellation()) return false
        pointPolicy?.observe("PreparationReuseChecks")
        var work = 0L
        fun charge(amount: Long, allocation: Long = 0L): Boolean {
            if (cancellation() || amount > maximumWork - work ||
                budget.funding?.availableWork?.let { amount > it } == true ||
                budget.funding?.remainingAllocation?.let { allocation > it } == true) return false
            cutCost(amount, allocation)
            work += amount
            return !cancellation()
        }
        fun same(left: BigFraction, right: BigFraction): Boolean {
            val bits = left.num.magnitudeBitLength().toLong() + left.den.magnitudeBitLength() +
                right.num.magnitudeBitLength() + right.den.magnitudeBitLength()
            return charge(1L + (bits + 63L) / 64L) && left == right
        }
        if (!charge(1L, 128L)) return false
        for (index in before.indices) {
            val left = before[index]
            val right = after[index]
            if (!charge(1L + left.owners.size + right.owners.size + left.inequality.supportSize, 64L)) return false
            if (index !in query.checkedCuts || left.owner != right.owner || left.owners != right.owners ||
                left.family != right.family || left.mixed != right.mixed ||
                left.inequality.strict != right.inequality.strict) return false
            val first = left.inequality.coefficients
            val second = right.inequality.coefficients
            if (first.size != second.size || !same(left.inequality.rhs, right.inequality.rhs)) return false
            for ((coordinate, value) in first) {
                val other = second[coordinate] ?: return false
                if (!same(value, other)) return false
            }
        }
        return !cancellation()
    }

    private fun adoptTemplate(prepared: LinearAuthorityTemplate) {
        template = prepared
        for (cut in prepared.cuts?.rows.orEmpty()) cutLifecycle.observe(cut.family, "CONSTRUCTED")
        retainedCutPeak = maxOf(retainedCutPeak, prepared.cuts?.rows?.size?.toLong() ?: 0L)
        retainedMixedCutPeak = maxOf(retainedMixedCutPeak,
            prepared.cuts?.rows?.count { cut -> cut.mixed }?.toLong() ?: 0L)
        prepared.cuts?.observations?.forEach { (key, value) ->
            cutEligibility["numericalCut$key"] = maxOf(cutEligibility["numericalCut$key"] ?: 0L, value)
        }
    }

    fun check(source: SourceProofContext, cancellation: Cancellation): LinearAuthorityResult {
        cutLifecycle.families = emptySet()
        relaxedPoint = emptyMap()
        if (cancellation()) {
            lp.close()
            return LinearAuthorityResult()
        }
        if (budget.funding?.paused == true) return LinearAuthorityResult(paused = true)
        val result = checkSource(source, cancellation)
        if (cancellation()) {
            lp.close()
            return LinearAuthorityResult()
        }
        return result.copy(relaxedPoint = relaxedPoint,
            paused = result.paused || (result.evidence == null && result.split == null &&
                result.incumbent == null && budget.funding?.paused == true))
    }

    fun advance(
        source: SourceProofContext, cancellation: Cancellation, cuts: Boolean = false, cutMask: Set<Int>? = null,
    ): LinearAuthorityResult {
        val replay = replayCuts
        cutLifecycle.families = when {
            !cuts -> emptySet()
            replay != null -> replay.mapTo(HashSet()) { it.family }
            else -> setOf(CutFamily.SOURCE_ROUNDING, CutFamily.SOURCE_MIXED_ROUNDING) +
                (if (aggregateCuts || additionalCuts.isNotEmpty()) setOf(CutFamily.SOURCE_AGGREGATION) else emptySet())
        }
        val recipe = if (!cuts) "NO_CUT" else if (cutMask == null) "CURRENT" else "POINT_SELECTION"
        return cutLifecycle.containing(recipe) { advanceInternal(source, cancellation, cuts, cutMask) }
    }

    private fun advanceInternal(
        source: SourceProofContext, cancellation: Cancellation, cuts: Boolean, cutMask: Set<Int>?,
    ): LinearAuthorityResult {
        relaxedPoint = emptyMap()
        capture?.discard()
        capturedPoint = null
        capturedQuery = null
        capturedTemplate = null
        primalStages.clear()
        if (cancellation()) {
            lp.close()
            return LinearAuthorityResult()
        }
        if (budget.funding?.paused == true) return LinearAuthorityResult(paused = true)
        val result = checkSource(source, cancellation, guidance = true, cuts = cuts, cutMask = cutMask)
        if (cancellation()) {
            lp.close()
            return LinearAuthorityResult(incumbent = result.incumbent, primalStages = primalStages.toMap())
        }
        val completed = result.copy(relaxedPoint = relaxedPoint, primalStages = primalStages.toMap(),
            paused = result.paused || (result.evidence == null && result.split == null &&
                result.incumbent == null && budget.funding?.paused == true))
        capture?.record(source, capturedQuery, capturedTemplate, capturedPoint, completed)
        capturedQuery = null
        capturedTemplate = null
        capturedPoint = null
        return completed
    }

    private var relaxedPoint: Map<SourceCoordinate, Double> = emptyMap()
    private val primalStages = linkedMapOf<String, Long>()

    private fun observePrimal(stage: String, count: Long = 1L) {
        val previous = primalStages[stage] ?: 0L
        primalStages[stage] = if ("Max" in stage) maxOf(previous, count) else previous + count
    }

    private fun recover(
        prepared: LinearAuthorityQuery, point: List<BigFraction>, cancellation: Cancellation,
    ): List<BigFraction>? {
        val attempt = primalAttempt?.takeIf { it.query === prepared && it.point == point }
            ?: PrimalAttempt(prepared, point).also { primalAttempt = it }
        if (attempt.finished) {
            observePrimal("PrimalRecoveryReused")
            return attempt.recovered
        }
        val recovered = lp.recover(prepared.coordinates, point, prepared.rows, prepared.inactive,
            cancellation, ::observePrimal, attempt.progress)
        if (!cancellation()) {
            attempt.recovered = recovered
            attempt.finished = recovered != null || (!attempt.progress.pending && budget.funding?.paused != true)
        }
        return recovered
    }

    private fun checkUnselectedCuts(mask: Set<Int>?): Boolean =
        mask == null || pointPolicy?.candidatesRetired(template?.cuts?.rows) != true

    private fun retainMaskedCuts(mask: Set<Int>?): Boolean =
        mask != null && pointPolicy?.retainsCandidateLayout(template?.cuts?.rows) == true

    private fun prepareQuery(
        source: SourceProofContext, cancellation: Cancellation, cuts: Boolean, cutMask: Set<Int>?,
    ): LinearAuthorityQuery? {
        if (!source.affineObjectiveAvailable || !source.linearCoordinatesSupported()) return null
        if ((source.scope.condition.lower.keys + source.scope.condition.upper.keys).any {
                !source.containsCoordinate(it)
            } || source.scope.condition.bools.keys.any {
                !source.containsCoordinate(SourceCoordinate(SourceCoordinate.Kind.Boolean, it))
            }
        ) return null
        if (source.scope.objective == null || source.coordinates.any {
                it.kind == SourceCoordinate.Kind.Boolean && it.id !in source.scope.condition.bools
            }
        ) return null
        if (budget.funding != null) {
            val condition = source.scope.condition
            budget.funding.charge(0L, condition.integerRows.sumOf { 128L + 64L * it.supportSize })
            var work = source.coordinateCount.toLong() + condition.bools.size + condition.lower.size +
                condition.upper.size + condition.integerRows.sumOf { it.supportSize.toLong() + 1L }
            try {
                val complete = source.allPremises { _, premise ->
                    if (cancellation()) return@allPremises false
                    work += 1L + premise.inequality.supportSize + premise.guardCount + premise.integerPinCount
                    true
                }
                if (!complete) return null
            } finally {
                budget.observe(preparation = work)
            }
        }
        val replay = replayCuts
        val prepared = if (retainLp || cuts) {
            template?.takeIf { it.matches(source) && (it.cuts != null) == cuts &&
                (!cuts || it.cuts?.complementary == (cutMask != null) ||
                    pointPolicy?.managesCurrentRecipe(it.cuts?.rows) == true ||
                    (fractionalAggregateOrder && it === directedFamily) ||
                    it.coordinates.all { coordinate -> coordinate.kind == SourceCoordinate.Kind.Integer }) }
                ?: LinearAuthorityTemplate.prepare(source, cancellation, cuts && replay == null,
                    cutMask != null) { work ->
                    cutPreparationWork += work
                    budget.observe(preparation = work)
                }?.let { prepared ->
                    when {
                        !cuts -> prepared
                        replay != null -> prepared.withReplayCuts(replay,
                            Cancellation { cancellation() || budget.funding?.exhausted == true }, ::cutCost)
                        else -> withAdditionalCuts(prepared, cancellation)
                    }
                }?.also(::adoptTemplate) ?: return null
        } else null
        return LinearAuthorityQuery.prepare(source, prepared, cancellation, onCutWork = { work ->
            cutVerificationWork += work
            budget.observe(boundCertification = work)
            for (family in prepared?.cuts?.rows.orEmpty().mapTo(HashSet()) { it.family }) {
                cutLifecycle.observe(family, "ProofVerificationWork", work)
            }
        }, cutMask = cutMask, onCut = cutLifecycle::observe,
            checkUnselectedCuts = checkUnselectedCuts(cutMask),
            retainMaskedCuts = retainMaskedCuts(cutMask))?.also {
            cutLifecycle.families = it.cutFamilies.values.toSet()
            activeCutPeak = maxOf(activeCutPeak, it.activeCuts.toLong())
            activeMixedCutPeak = maxOf(activeMixedCutPeak, it.activeMixedCuts.toLong())
        }
    }

    private fun withAdditionalCuts(
        prepared: LinearAuthorityTemplate, cancellation: Cancellation,
        point: Map<SourceCoordinate, Double>? = null,
        fractional: Boolean = false,
        journal: LinearAuthorityAggregateRejections? = null,
    ): LinearAuthorityTemplate? {
        val generateAggregates = aggregateCuts && !deferAggregatePreparation
        if (!generateAggregates && additionalCuts.isEmpty() &&
            (point == null || prepared.cuts?.rows.orEmpty().size <= 64)) return prepared
        val funded = Cancellation { cancellation() || budget.funding?.paused == true }
        val retainOriginal = fractional && point != null
        val retained = if (retainOriginal) prepared.cuts?.rows.orEmpty().size else additionalCuts.size
        if (retained > 64) return null
        val generated = if (generateAggregates) LinearAuthorityAggregateCuts.prepareSourcePairsObserved(
            prepared.numeric, funded, { name, count ->
                when (name) {
                    "AggregateWork" -> cutCost(count, 0L)
                    "AggregateAllocation" -> cutCost(0L, count)
                    else -> {
                        val key = "numericalCut$name"
                        cutEligibility[key] = (cutEligibility[key] ?: 0L) + count
                        journal?.observe(name, count)
                    }
                }
            }, maximumCandidates = 64 - retained,
            limits = if (point == null) LinearAuthorityAggregateCuts.sourcePairLimits
                else LinearAuthorityAggregateCuts.pointSourcePairLimits,
            point = point, fractionalTargeting = fractional,
            rejected = journal?.let { it::record }) else emptyList()
        if (funded()) return null
        val fixed = if (retainOriginal) emptyList() else additionalCuts
        val additional = if (generated.isEmpty()) fixed else {
            cutCost(1L, 64L + (generated.size + fixed.size) * 8L)
            if (funded()) return null
            fixed + generated
        }
        return prepared.withAdditional(additional, funded,
            maximumCandidates = if (point == null) Int.MAX_VALUE else 64, onCost = ::cutCost)
    }

    private fun cutCost(work: Long, allocation: Long) {
        cutPreparationWork += work
        budget.observe(preparation = work)
        budget.funding?.charge(0L, allocation)
    }

    private fun checkSource(
        source: SourceProofContext, cancellation: Cancellation, guidance: Boolean = false, cuts: Boolean = false,
        cutMask: Set<Int>? = null,
    ): LinearAuthorityResult {
        val cached = if (retainLp || guidance) query?.takeIf {
            it.matches(source, cuts, cutMask, checkUnselectedCuts(cutMask), retainMaskedCuts(cutMask))
        } else null
        val prepared = cached ?: prepareQuery(source, cancellation, cuts, cutMask)?.also {
            if (retainLp || guidance) query = it
        } ?: return LinearAuthorityResult()
        cutLifecycle.families = prepared.cutFamilies.values.toSet()
        if (capture?.accepting == true && capturedQuery == null) {
            capturedQuery = prepared
            capturedTemplate = template
        }
        val coordinates = prepared.coordinates
        fun supportedFamilies(weights: List<BigFraction>): Set<CutFamily> =
            prepared.cutFamilies.entries.filter { (row, _) -> row in weights.indices && !weights[row].isZero }
                .mapTo(HashSet()) { it.value }
        var boundFamilies = emptySet<CutFamily>()
        var conflictFamilies = emptySet<CutFamily>()
        val context = prepared.context
        val cited = prepared.cited
        val rows = prepared.rows
        val inactive = prepared.inactive
        val costs = prepared.costs
        val constant = prepared.constant
        val sign = prepared.sign
        fun combination(coefficients: List<BigFraction>, rhs: BigFraction, strict: Boolean): List<BigFraction>? {
            val selected = rows.indices.filter { it !in inactive }
            val weights = lp.combination(selected.map { rows[it] }, coefficients, rhs, strict, cancellation)
                ?: return null
            return MutableList(rows.size) { BigFraction.ZERO }.also { lifted ->
                for (id in selected.indices) lifted[selected[id]] = weights[id]
            }
        }
        fun derivation(weights: List<BigFraction>): SourceDerivation = SourceDerivation.Combination(
            weights.mapIndexedNotNull { id, weight -> if (weight.isZero) null
                else SourceDerivation.Combination.Term(weight, cited[id].second) })
        fun checked(claim: SourceClaim, proof: SourceDerivation): SourceEvidence? {
            val evidence = SourceEvidence(source.scope, claim, proof)
            return evidence.takeIf {
                SourceEvidenceVerifier(context).verify(it, cancellation) == SourceEvidenceCheck.Checked
            }
        }
        fun refutation(result: LinearAuthorityLpResult): SourceEvidence? {
            if (result.result.verdict != LpVerdict.INFEASIBLE) return null
            result.conflictWeights?.let { weights ->
                checked(SourceClaim.Refutation, LinearPinnedRefutation(derivation(weights)))?.let {
                    conflictFamilies = supportedFamilies(weights)
                    observeCutConsumption(conflictFamilies, "CHECKED_CONFLICT")
                    return it
                }
            }
            if (guidance) return null
            val zeros = List(coordinates.size) { BigFraction.ZERO }
            val weights = combination(zeros, BigFraction.MINUS_ONE, false)
                ?: combination(zeros, BigFraction.ZERO, true) ?: return null
            return checked(SourceClaim.Refutation, LinearPinnedRefutation(derivation(weights)))?.also {
                conflictFamilies = supportedFamilies(weights)
                observeCutConsumption(conflictFamilies, "CHECKED_CONFLICT")
            }
        }
        val closureRows = prepared.closureRows
        val guide = if (guidance) lp.advance(closureRows, costs, constant, source.scope, cancellation, inactive,
            prepared.unconditionalRows)
            ?: return LinearAuthorityResult(paused = budget.funding?.paused == true) else null
        if (guide?.paused == true) return LinearAuthorityResult(paused = true)
        guide?.coordinateConflictWeights?.let { weights ->
            val evidence = checked(SourceClaim.Refutation, LinearPinnedRefutation(derivation(weights)))
            conflictFamilies = if (evidence != null) supportedFamilies(weights) else emptySet()
            return LinearAuthorityResult(evidence = evidence, coordinateConflict = evidence != null,
                cutConflictFamilies = conflictFamilies)
        }
        val solved = guide?.result ?: lp.solve(closureRows, costs, constant, cancellation, inactive,
            claim = LpClaim.ATTAINED_OPTIMUM)
            ?: return LinearAuthorityResult(paused = budget.funding?.paused == true)
        if (pointListener != null || continuationListener != null) {
            val point = guide?.point ?: solved.result.exactPrimal?.take(coordinates.size)
            if (point != null && point.size == coordinates.size) {
                pointListener?.invoke(prepared, point)
                continuationListener?.invoke(prepared, point)
            }
        }
        if (capture?.accepting == true && capturedPoint == null && capturedQuery === prepared) {
            val point = guide?.point ?: solved.result.exactPrimal?.take(coordinates.size)
            if (point != null && point.size == coordinates.size) {
                capturedPoint = capture?.point(coordinates, point)
            }
        }
        val closure = solved.result
        closure.float?.let { result ->
            for (family in prepared.cutFamilies.values.toSet()) {
                cutLifecycle.observe(family, "SolveMaxFillRatioMicrosSum", (result.luMaxFill * 1_000_000.0).toLong())
            }
        }
        if (closure.verdict == LpVerdict.INFEASIBLE) {
            val evidence = refutation(solved)
            if (evidence != null) return LinearAuthorityResult(evidence = evidence, lpConflict = true,
                cutConflictFamilies = conflictFamilies)
            return if (guide != null && budget.admitsDimensions(costs.size, guide.workingRows.toLong())) {
                checkSource(source, cancellation)
            } else LinearAuthorityResult()
        }
        guide?.point?.let { point ->
            val projected = point.map { it.toDouble() }
            if (projected.all(Double::isFinite)) {
                relaxedPoint = coordinates.indices.associate { coordinates[it] to projected[it] }
            }
        }
        if (guide == null) closure.float?.primal?.takeIf {
            it.size == coordinates.size && it.all(Double::isFinite)
        }?.let { point ->
            relaxedPoint = coordinates.indices.associate { coordinates[it] to point[it] }
        }
        val bound = closure.lowerBound?.let { value ->
            fun checkBound(weights: List<BigFraction>): SourceEvidence? =
                checked(SourceClaim.Bound(value * sign), LinearPinnedObjective(derivation(weights)))?.also {
                    boundFamilies = supportedFamilies(weights)
                    observeCutConsumption(boundFamilies, "CHECKED_BOUND")
                }
            val retained = solved.boundWeights?.let(::checkBound)
            if (retained != null || guidance) retained else
                combination(costs.map { it.negated() }, constant - value, false)?.let(::checkBound)
        }
        if (guide != null) {
            var point = guide.point
            val fractionalCoordinates = fractional(coordinates, point)
            observePrimal(when {
                point == null -> "PrimalUnavailable"
                fractionalCoordinates.isNotEmpty() -> "PrimalFractional"
                else -> "PrimalIntegral"
            })
            val fractions = fractionalCoordinates.filter { candidate ->
                val threshold = candidate.floorSplit().second
                val lower = source.scope.condition.lower[candidate.first]
                val upper = source.scope.condition.upper[candidate.first]
                (lower == null || threshold + BigFraction.ONE > lower) &&
                    (upper == null || threshold < upper)
            }
            fun witness(values: List<BigFraction>, stage: String): Sample? {
                val proposed = sample(source, coordinates, values)
                val evidence = SourceEvidence(source.scope, SourceClaim.Witness(proposed),
                    SourceDerivation.SourceWitness)
                return when (SourceEvidenceVerifier(context).verify(evidence, cancellation)) {
                    SourceEvidenceCheck.Checked -> proposed
                    is SourceEvidenceCheck.Rejected -> {
                        observePrimal("${stage}Rejected")
                        null
                    }
                    is SourceEvidenceCheck.Unavailable -> {
                        observePrimal("${stage}Unavailable")
                        null
                    }
                }
            }
            var candidate = point?.takeIf { fractionalCoordinates.isEmpty() }?.let { witness(it, "PrimalSource") }
            if (candidate != null) observePrimal("PrimalSourceChecked")
            if (candidate == null && point != null && fractionalCoordinates.isEmpty()) {
                val recovered = recover(prepared, point, cancellation)
                candidate = recovered?.let { witness(it, "PrimalRecoverySource") }
                if (candidate != null) {
                    point = recovered
                    observePrimal("PrimalRecoverySourceChecked")
                    val attempt = checkNotNull(primalAttempt)
                    val accepted = checkNotNull(recovered)
                    val attained = (bound?.claim as? SourceClaim.Bound)?.value?.let { limit ->
                        val value = (accepted.indices.fold(constant) { total, id ->
                            total + costs[id] * accepted[id]
                        }) * sign
                        limit == SourceValue.Rational(value)
                    } == true
                    if (!attained && !attempt.refinementFinished) {
                        attempt.refined = lp.refine(coordinates, accepted, rows, prepared.inactive,
                            costs, cancellation, ::observePrimal)
                        attempt.refinementFinished = budget.funding?.paused != true && !cancellation()
                    }
                    attempt.refined?.let { refined ->
                        val improved = witness(refined, "PrimalRefinementSource")
                        if (improved != null) {
                            candidate = improved
                            point = refined
                            observePrimal("PrimalRefinementSourceChecked")
                        }
                    }
                }
            }
            if (candidate != null && bound != null) {
                val accepted = checkNotNull(point)
                val value = (accepted.indices.fold(constant) { value, id -> value + costs[id] * accepted[id] }) * sign
                val proof = checked(SourceClaim.Optimal(candidate, SourceValue.Rational(value)),
                    SourceDerivation.Optimum(SourceDerivation.SourceWitness, bound.derivation))
                if (proof != null) return LinearAuthorityResult(proof, bound, candidate,
                    cutBoundFamilies = boundFamilies, cutTerminalBoundFamilies = boundFamilies)
            }
            if (candidate == null && primalAttempt?.progress?.pending == true) {
                return LinearAuthorityResult(bound = bound, cutBoundFamilies = boundFamilies, paused = true)
            }
            if (fractions.isNotEmpty()) return LinearAuthorityResult(bound = bound,
                cutBoundFamilies = boundFamilies, incumbent = candidate,
                split = fractions.first().floorSplit(), fractional = fractions)
            if (budget.admitsDimensions(costs.size, guide.workingRows.toLong())) {
                observePrimal("PrimalExactFallback")
                val exact = checkSource(source, cancellation, cuts = cuts, cutMask = cutMask)
                return exact.copy(bound = exact.bound ?: bound, incumbent = exact.incumbent ?: candidate,
                    cutBoundFamilies = if (exact.bound != null) exact.cutBoundFamilies else boundFamilies)
            }
            if (candidate == null) observePrimal("PrimalExactDimension")
            return LinearAuthorityResult(bound = bound, cutBoundFamilies = boundFamilies, incumbent = candidate)
        }
        val endpoint = closure.exactPrimal?.take(coordinates.size)
        fractional(coordinates, endpoint).takeIf { it.isNotEmpty() }?.let {
            return LinearAuthorityResult(bound = bound, cutBoundFamilies = boundFamilies,
                split = it.first().floorSplit(), fractional = it)
        }
        val endpointSample = endpoint?.let { sample(source, coordinates, it) }
        val endpointAccepted = endpointSample?.takeIf {
            checked(SourceClaim.Witness(it), SourceDerivation.SourceWitness) != null
        }
        if (endpointAccepted != null && bound != null && closure.lowerBound == closure.witness?.objective) {
            val value = (bound.claim as SourceClaim.Bound).value
            val proof = checked(SourceClaim.Optimal(endpointAccepted, value),
                SourceDerivation.Optimum(SourceDerivation.SourceWitness, bound.derivation))
            if (proof != null) return LinearAuthorityResult(proof, bound, endpointAccepted,
                cutBoundFamilies = boundFamilies, cutTerminalBoundFamilies = boundFamilies)
        }
        val feasible = if (endpointAccepted != null) endpointAccepted else {
            val original = lp.solve(rows, List(costs.size) { BigFraction.ZERO }, BigFraction.ZERO,
                cancellation, inactive, claim = LpClaim.FEASIBLE_COMPLETION)
            original?.let { refutation(it) }?.let {
                return LinearAuthorityResult(it, bound, cutBoundFamilies = boundFamilies,
                    cutConflictFamilies = conflictFamilies)
            }
            val primal = original?.result?.exactPrimal?.take(coordinates.size)
            fractional(coordinates, primal).takeIf { it.isNotEmpty() }?.let {
                return LinearAuthorityResult(bound = bound, cutBoundFamilies = boundFamilies,
                    split = it.first().floorSplit(), fractional = it)
            }
            primal?.let { sample(source, coordinates, it) }?.takeIf {
                checked(SourceClaim.Witness(it), SourceDerivation.SourceWitness) != null
            }
        }
        if (feasible == null) return LinearAuthorityResult(bound = bound, cutBoundFamilies = boundFamilies)
        if (bound != null) {
            val proof = checked(SourceClaim.Optimal(feasible, (bound.claim as SourceClaim.Bound).value),
                SourceDerivation.Optimum(SourceDerivation.SourceWitness, bound.derivation))
            if (proof != null) return LinearAuthorityResult(proof, bound, feasible, cutBoundFamilies = boundFamilies,
                cutTerminalBoundFamilies = boundFamilies)
        }
        closure.unboundedness?.let { unbounded ->
            var scale = BIG_ONE
            for (id in coordinates.indices) {
                if (cancellation()) return LinearAuthorityResult(bound = bound, cutBoundFamilies = boundFamilies,
                    incumbent = feasible)
                if (coordinates[id].kind == SourceCoordinate.Kind.Integer) scale *= unbounded.direction[id].den
            }
            val multiplier = BigFraction.of(scale, BIG_ONE)
            val direction = coordinates.zip(unbounded.direction.take(coordinates.size).map { it * multiplier }).toMap()
            val proof = checked(SourceClaim.Ray(feasible, direction), SourceDerivation.SourceRay)
            if (proof != null) return LinearAuthorityResult(proof, bound, feasible, cutBoundFamilies = boundFamilies,
                cutTerminalBoundFamilies = boundFamilies)
        }
        if (bound != null && endpointSample != null && closure.lowerBound == closure.witness?.objective) {
            for (id in 0 until feasible.numIntVars) {
                if (feasible.exactIntValue(id) != endpointSample.exactIntValue(id)) {
                    val left = feasible.exactIntValue(id)
                    val right = endpointSample.exactIntValue(id)
                    val low = if (left < right) left else right
                    return LinearAuthorityResult(bound = bound, cutBoundFamilies = boundFamilies, incumbent = feasible,
                        split = SourceCoordinate(SourceCoordinate.Kind.Integer, id) to BigFraction.of(low, BIG_ONE))
                }
            }
            val value = requireNotNull(closure.lowerBound)
            val weights = combination(costs.map { it.negated() }, constant - value, true)
            if (weights != null) {
                val proof = checked(SourceClaim.Limit(feasible, SourceValue.Rational(value * sign)),
                    LinearApproach(endpointSample, LinearPinnedObjective(derivation(weights))))
                if (proof != null) return LinearAuthorityResult(proof, bound, feasible,
                    cutBoundFamilies = boundFamilies, cutTerminalBoundFamilies = supportedFamilies(weights))
            }
        }
        return LinearAuthorityResult(bound = bound, cutBoundFamilies = boundFamilies, incumbent = feasible)
    }
}

private fun fractional(
    coordinates: List<SourceCoordinate>, point: List<BigFraction>?,
): List<Pair<SourceCoordinate, BigFraction>> {
    if (point == null) return emptyList()
    return coordinates.indices.mapNotNull { id ->
        if (coordinates[id].kind == SourceCoordinate.Kind.Integer && point[id].den != BIG_ONE) {
            coordinates[id] to point[id]
        } else null
    }
}

internal fun Pair<SourceCoordinate, BigFraction>.floorSplit(): Pair<SourceCoordinate, BigFraction> =
    first to BigFraction.of(second.negated().ceilInteger(), BIG_ONE).negated()

private fun sample(source: SourceProofContext, coordinates: List<SourceCoordinate>, point: List<BigFraction>): Sample {
    val integers = coordinates.indices.filter { coordinates[it].kind == SourceCoordinate.Kind.Integer }
        .map { point[it].num }
    val reals = coordinates.indices.filter { coordinates[it].kind == SourceCoordinate.Kind.Real }.map { point[it] }
    return Sample(BooleanArray(source.coordinates.count { it.kind == SourceCoordinate.Kind.Boolean }) {
        source.scope.condition.bools.getValue(it)
    }, if (integers.all { it.fitsLong() }) LongArray(integers.size) { integers[it].toLongExact() } else longArrayOf(),
        DoubleArray(reals.size) { reals[it].toDouble() }, exactReals = reals, exactInts = integers)
}

internal fun strongerLinearBound(left: SourceEvidence?, right: SourceEvidence?): SourceEvidence? {
    if (left == null) return right
    if (right == null) return left
    require(left.scope == right.scope)
    val before = left.claim as SourceClaim.Bound
    val after = right.claim as SourceClaim.Bound
    val first = (before.value as SourceValue.Rational).value
    val second = (after.value as SourceValue.Rational).value
    val improves = if (left.scope.objective?.sense == ObjectiveSense.MINIMIZE) second > first else second < first
    return if (improves || (second == first && after.strict && !before.strict)) right else left
}
