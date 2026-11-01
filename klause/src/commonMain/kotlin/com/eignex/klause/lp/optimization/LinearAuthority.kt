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
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.compareTo
import com.eignex.klause.util.fitsLong
import com.eignex.klause.util.magnitudeBitLength
import com.eignex.klause.util.times
import com.eignex.klause.util.toLongExact

internal data class LinearAuthorityResult(
    val evidence: SourceEvidence? = null,
    val bound: SourceEvidence? = null,
    val incumbent: Sample? = null,
    val split: Pair<SourceCoordinate, BigFraction>? = null,
    val fractional: List<Pair<SourceCoordinate, BigFraction>> = emptyList(),
    val relaxedPoint: Map<SourceCoordinate, Double> = emptyMap(),
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
) : AutoCloseable {
    init { require(additionalCuts.size <= 64) }
    private val additionalCuts = additionalCuts.toList()
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
    private var pointPolicy: LinearAuthorityPointCuts? = null
    private var pointFamily: LinearAuthorityTemplate? = null
    private var pointFallback: LinearAuthorityTemplate? = null
    private var directedFamily: LinearAuthorityTemplate? = null
    private var pointMask: Set<Int>? = null
    private var deferAggregatePreparation = false
    private class PointRound(
        val query: LinearAuthorityQuery, val base: LinearAuthorityResult, val mask: Set<Int>,
    ) {
        var reported = false
    }
    private var pointRound: PointRound? = null
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
    ) + cutEligibility + pointPolicy?.observations.orEmpty() + cutLifecycle.snapshot()
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
        pointRound = null
        lp.invalidateGuidance()
    }

    override fun close() {
        template = null
        query = null
        capturedQuery = null
        capturedTemplate = null
        capturedPoint = null
        pointListener = null
        pointRound = null
        pointPolicy?.releaseCandidates()
        pointFamily = null
        pointFallback = null
        directedFamily = null
        pointMask = null
        deferAggregatePreparation = false
        primalAttempt = null
        cutLifecycle.containing("RETIREMENT") { lp.close() }
    }

    fun advanceWithPointCuts(source: SourceProofContext, cancellation: Cancellation): LinearAuthorityResult {
        val policy = pointPolicy ?: LinearAuthorityPointCuts(budget, onCut = cutLifecycle::observe)
            .also { pointPolicy = it }
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
                checkUnselectedCuts = checkUnselectedCuts(it.query.cutMask),
                retainMaskedCuts = retainMaskedCuts(it.query.cutMask))
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
                currentRows.all { it.family == CutFamily.SOURCE_AGGREGATION }
            if (!generatedRecipe && !checkNotNull(family.cuts).pointDirected && directedFamily !== family &&
                pointMask == null &&
                ((aggregateCuts && prepared.coordinates.any { it.kind == SourceCoordinate.Kind.Real }) ||
                    checkNotNull(family.cuts).rows.any { it.mixed })) {
                directedFamily = family
                cutLifecycle.families = checkNotNull(family.cuts).rows.mapTo(HashSet()) { it.family }
                val refined = cutLifecycle.containing("POINT_SELECTION") {
                    preparePointFamily(source, family, prepared, observed.second, cancellation)
                }
                if (refined != null) {
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
            pending = PointRound(prepared, base, selected)
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
        val stages = (previousStages.keys + result.primalStages.keys).associateWith { key ->
            val before = previousStages[key] ?: 0L
            val after = result.primalStages[key] ?: 0L
            if ("Max" in key) maxOf(before, after) else before + after
        }
        if (result.paused && (budget.funding?.exhausted == true || cancellation())) {
            return pending.base.copy(primalStages = stages)
        }
        if (!result.paused && result.evidence == null && result.incumbent == null && result.split == null) {
            policy.observe("UnavailableRounds")
            return pending.base.copy(primalStages = stages)
        }
        val strongest = strongerLinearBound(pending.base.bound, result.bound)
        return result.copy(bound = strongest, primalStages = stages,
            cutBoundFamilies = if (strongest != null && strongest === pending.base.bound) {
                pending.base.cutBoundFamilies
            } else result.cutBoundFamilies)
    }

    private fun preparePointFamily(
        source: SourceProofContext, family: LinearAuthorityTemplate, prepared: LinearAuthorityQuery,
        values: List<BigFraction>,
        cancellation: Cancellation,
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
        val directed = family.atPoint(point, funded, { cutCost(it, 0L) }, ::cutCost) ?: return null
        val refined = withAdditionalCuts(directed, funded, point) ?: return null
        if (funded()) return null
        val previous = template
        adoptTemplate(refined)
        query = null
        val checked = prepareQuery(source, funded, cuts = true, cutMask = emptySet())
        if (checked == null || funded() || template !== refined) {
            template = previous
            return null
        }
        query = checked
        return refined to checked
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
        cutLifecycle.families = if (cuts) setOf(CutFamily.SOURCE_ROUNDING, CutFamily.SOURCE_MIXED_ROUNDING) +
            (if (aggregateCuts || additionalCuts.isNotEmpty()) setOf(CutFamily.SOURCE_AGGREGATION) else emptySet())
            else emptySet()
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
        mask != null && pointPolicy?.managesCurrentRecipe(template?.cuts?.rows) == true

    private fun prepareQuery(
        source: SourceProofContext, cancellation: Cancellation, cuts: Boolean, cutMask: Set<Int>?,
    ): LinearAuthorityQuery? {
        if (!source.affineObjectiveAvailable || !source.linearCoordinatesSupported()) return null
        if ((source.scope.condition.lower.keys + source.scope.condition.upper.keys).any {
                it !in source.coordinates
            } || source.scope.condition.bools.keys.any {
                SourceCoordinate(SourceCoordinate.Kind.Boolean, it) !in source.coordinates
            }
        ) return null
        if (source.scope.objective == null || source.coordinates.any {
                it.kind == SourceCoordinate.Kind.Boolean && it.id !in source.scope.condition.bools
            }
        ) return null
        if (budget.funding != null) {
            val condition = source.scope.condition
            budget.funding.charge(0L, condition.integerRows.sumOf { 128L + 64L * it.supportSize })
            var work = source.coordinates.size.toLong() + condition.bools.size + condition.lower.size +
                condition.upper.size + condition.integerRows.sumOf { it.supportSize.toLong() + 1L }
            try {
                for (premise in source.premises.values) {
                    if (cancellation()) return null
                    work += 1L + premise.inequality.coefficients.size + premise.guards.size + premise.integerPins.size
                }
            } finally {
                budget.observe(preparation = work)
            }
        }
        val prepared = if (retainLp || cuts) {
            template?.takeIf { it.matches(source) && (it.cuts != null) == cuts &&
                (!cuts || it.cuts?.complementary == (cutMask != null) ||
                    pointPolicy?.managesCurrentRecipe(it.cuts?.rows) == true ||
                    it.coordinates.all { coordinate -> coordinate.kind == SourceCoordinate.Kind.Integer }) }
                ?: LinearAuthorityTemplate.prepare(source, cancellation, cuts, cutMask != null) { work ->
                    cutPreparationWork += work
                    budget.observe(preparation = work)
                }?.let { if (cuts) withAdditionalCuts(it, cancellation) else it }?.also(::adoptTemplate) ?: return null
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
    ): LinearAuthorityTemplate? {
        val generateAggregates = aggregateCuts && !deferAggregatePreparation
        if (!generateAggregates && additionalCuts.isEmpty()) return prepared
        val funded = Cancellation { cancellation() || budget.funding?.paused == true }
        val generated = if (generateAggregates) LinearAuthorityAggregateCuts.prepareSourcePairs(
            prepared.numeric, funded, { name, count ->
                when (name) {
                    "AggregateWork" -> cutCost(count, 0L)
                    "AggregateAllocation" -> cutCost(0L, count)
                    else -> {
                        val key = "numericalCut$name"
                        cutEligibility[key] = (cutEligibility[key] ?: 0L) + count
                    }
                }
            }, maximumCandidates = 64 - additionalCuts.size,
            limits = if (point == null) LinearAuthorityAggregateCuts.sourcePairLimits
                else LinearAuthorityAggregateCuts.pointSourcePairLimits,
            point = point) else emptyList()
        if (funded()) return null
        val additional = if (generated.isEmpty()) additionalCuts else {
            cutCost(1L, 64L + (generated.size + additionalCuts.size) * 8L)
            if (funded()) return null
            additionalCuts + generated
        }
        return prepared.withAdditional(additional, funded, ::cutCost)
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
        pointListener?.let { listener ->
            val point = guide?.point ?: solved.result.exactPrimal?.take(coordinates.size)
            if (point != null && point.size == coordinates.size) listener(prepared, point)
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
