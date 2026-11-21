package com.eignex.klause.solver.pipeline

import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.SparseSourceLpPolicy
import com.eignex.klause.lp.engine.LpClaim
import com.eignex.klause.lp.optimization.LinearAuthorityLp
import com.eignex.klause.lp.optimization.LinearAuthorityQuery
import com.eignex.klause.lp.optimization.LinearPrimalRecoveryProgress
import com.eignex.klause.lp.optimization.LinearResidualCompletion
import com.eignex.klause.lp.optimization.atScope
import com.eignex.klause.lp.optimization.linearCoordinatesSupported
import com.eignex.klause.lp.optimization.recoverLinearPrimal
import com.eignex.klause.lp.optimization.withLinearAuthority
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceDerivation
import com.eignex.klause.solver.evidence.SourceEvidence
import com.eignex.klause.solver.evidence.SourceEvidenceCheck
import com.eignex.klause.solver.evidence.SourceEvidenceVerifier
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.solver.search.SearchInvocationResources
import com.eignex.klause.util.ExecutionControl
import com.eignex.klause.util.WorkMeter
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.magnitudeBitLength
import kotlin.time.TimeSource

internal class LinearIncumbentCompletion(
    private val budget: SourceLpBudget,
    private val originalCheck: ((Sample, BigFraction, ExecutionControl) -> Boolean)? = null,
    private val shared: LinearIncumbentSharing? = null,
    private val ownedSeed: Boolean = false,
    private val observe: (String, Long) -> Unit,
) : AutoCloseable {
    private class Attempt(val key: LinearIncumbentSharing.Key) {
        var query: LinearAuthorityQuery? = null
        var point: List<BigFraction>? = null
        val progress = LinearPrimalRecoveryProgress()
        var residual: LinearResidualCompletion? = null
        var finished = false
        var checked: Sample? = null
    }

    private class ExactCheck(val key: LinearIncumbentSharing.Key, val accepted: Boolean)
    private class OwnedAttempt(val source: SourceProofContext, val seed: Sample, val attempt: Attempt)

    // Entries are never evicted: exhausting retention cannot renew a failed attempt.
    private val attempts = ArrayList<Attempt>()
    private var exactCheck: ExactCheck? = null
    private var ownedAttempt: OwnedAttempt? = null
    private var closed = false
    var preparationInterrupted: Boolean = false
        private set

    fun complete(source: SourceProofContext, seed: Sample, cancellation: ExecutionControl): Sample? {
        preparationInterrupted = false
        val started = TimeSource.Monotonic.markNow()
        var completed: Sample? = null
        var returned = false
        return try {
            completeSeed(source, seed, cancellation).also {
                completed = it
                returned = true
            }
        } finally {
            val attempt = ownedAttempt?.attempt
            if (returned && completed == null && ownedSeed &&
                (attempt == null || attempt.query == null || attempt.point == null) &&
                (cancellation() || budget.funding?.paused == true)) {
                preparationInterrupted = true
                observe("PreparationInterrupted", 1L)
                attempt?.let { finish(it, null) }
            }
            observe("Nanos", started.elapsedNow().inWholeNanoseconds)
        }
    }

    private fun completeSeed(source: SourceProofContext, seed: Sample, cancellation: ExecutionControl): Sample? {
        check(!closed)
        if (cancellation()) return null
        if (seed.exactReals != null) return checkedExact(source, seed, cancellation)
        val token = metered(cancellation, checking = false)
        if (token()) return null
        val owned = ownedAttempt
        if (owned != null && (source !== owned.source || seed !== owned.seed)) return null
        val attempt = owned?.attempt ?: findAttempt(source, seed, token)?.also {
            if (ownedSeed) ownedAttempt = OwnedAttempt(source, seed, it)
        } ?: return null
        if (attempt.finished) {
            observe(if (attempt.checked == null) "DeclineReused" else "CheckedReused", 1L)
            return attempt.checked.takeUnless { token() }
        }
        if (budget.funding?.exhausted == true) return finish(attempt, null)
        shared?.find(attempt.key, budget, token)?.let { point ->
            observe("SharedCheckedReused", 1L)
            return finish(attempt, point)
        }
        if (token()) return null
        if (attempt.query == null) {
            val prepared = prepare(source, seed, token)
            if (prepared == null) {
                if (!token()) finish(attempt, null)
                return null
            }
            val point = seedPoint(prepared, seed, token)
            if (point == null) {
                if (!token()) finish(attempt, null)
                return null
            }
            attempt.query = prepared
            attempt.point = point
        }
        val query = requireNotNull(attempt.query)
        val values = recoverLinearPrimal(query.coordinates, requireNotNull(attempt.point), query.rows,
            query.inactive, token, complete = recovery@{ rows, columns, active ->
                val allocation = 32L * columns.size
                if (active() || budget.funding?.remainingAllocation?.let { allocation > it } == true) {
                    return@recovery null
                }
                budget.observe(preparation = columns.size.toLong())
                budget.funding?.charge(0L, allocation)
                if (budget.funding?.exhausted == true) return@recovery null
                val costs = List(columns.size) { query.costs[columns[it]] }
                val objective = costs.takeIf { it.any { cost -> !cost.isZero } }
                val residual = attempt.residual ?: LinearResidualCompletion(budget, SparseSourceLpPolicy())
                    .also { attempt.residual = it }
                residual.complete(rows, columns.size, active, ::recoveryCost, costs = objective) {
                    if (objective != null) null else LinearAuthorityLp(budget).use { completion ->
                        completion.solve(rows, costs, BigFraction.ZERO, active,
                            claim = LpClaim.FEASIBLE_COMPLETION)?.result?.exactPrimal?.take(columns.size)
                    }
                }.also { attempt.progress.pending = residual.paused }
            }, observe = ::recoveryCost, progress = attempt.progress)
        if (values == null) {
            if (budget.funding?.exhausted == true || (!token() && !attempt.progress.pending)) {
                observe("Unavailable", 1L)
                finish(attempt, null)
            }
            return null
        }
        val reals = query.coordinates.indices.filter {
            query.coordinates[it].kind == SourceCoordinate.Kind.Real
        }.map { values[it] }
        val proposed = seed.copy(reals = DoubleArray(reals.size) { reals[it].toDouble() }, exactReals = reals,
            algebraicReals = null, symbolicReals = null)
        val checking = metered(cancellation, checking = true)
        val evidence = SourceEvidence(source.scope, SourceClaim.Witness(proposed), SourceDerivation.SourceWitness)
        val authority = source.withLinearAuthority()
        val checked = SourceEvidenceVerifier(authority).verify(evidence, checking)
        val exact = if (checked == SourceEvidenceCheck.Checked) source.linearValue(proposed, checking) else null
        val objectiveChecked = exact?.let {
            authority.semanticsChecker?.objectiveEquals(proposed, SourceValue.Rational(it), source.scope, checking)
        }
        val accepted = objectiveChecked == SourceEvidenceCheck.Checked &&
            originalCheck?.invoke(proposed, requireNotNull(exact), checking) != false
        if (checking()) return null
        if (accepted && originalCheck != null) observe("OriginalChecked", 1L)
        observe(if (accepted) "Checked" else "Rejected", 1L)
        val point = finish(attempt, proposed.takeIf { accepted })
        if (point != null && shared?.remember(this, attempt.key, point, budget, token) == true) {
            observe("SharedRetained", 1L)
        }
        return point
    }

    private fun findAttempt(source: SourceProofContext, seed: Sample, token: ExecutionControl): Attempt? {
        if (seed.algebraicReals != null || seed.symbolicReals != null) {
            observe("UnsupportedSeed", 1L)
            return null
        }
        var seedBytes = 256L + 16L * seed.numBoolVars + 8L * seed.numRealVars
        for (id in 0 until seed.numIntVars) {
            if (token()) return null
            val bits = seed.exactIntValue(id).magnitudeBitLength()
            budget.observe(preparation = 1L + bits / 64L)
            if (bits > 4096) {
                observe("SeedScalarLimit", 1L)
                return null
            }
            seedBytes += 64L + bits / 4L
        }
        val previous = attempts.firstOrNull { it.key.matches(source, seed, originalCheck, token, budget) }
        if (token()) return null
        if (previous != null) return previous
        if (attempts.size >= MAX_ATTEMPTS) {
            observe("RetentionExhausted", 1L)
            return null
        }
        if (budget.funding?.let { seedBytes > it.remainingAllocation } == true) {
            observe("RetentionAllocation", 1L)
            return null
        }
        return Attempt(LinearIncumbentSharing.Key(source, seed, originalCheck)).also {
            budget.funding?.charge(0L, seedBytes)
            attempts += it
            observe("Attempts", 1L)
        }
    }

    private fun checkedExact(source: SourceProofContext, seed: Sample, cancellation: ExecutionControl): Sample? {
        val original = originalCheck ?: return seed
        val checking = metered(cancellation, checking = true)
        val previous = exactCheck
        if (previous != null && previous.key.matches(source, seed, original, checking, budget)) {
            if (checking()) return null
            observe(if (previous.accepted) "ExactCheckedReused" else "ExactDeclineReused", 1L)
            return seed.takeIf { previous.accepted }
        }
        if (checking()) return null
        exactCheck = null
        val key = exactKey(source, seed, checking)
        if (checking()) return null
        val evidence = SourceEvidence(source.scope, SourceClaim.Witness(seed), SourceDerivation.SourceWitness)
        val checked = SourceEvidenceVerifier(source.withLinearAuthority()).verify(evidence, checking)
        if (checked != SourceEvidenceCheck.Checked) {
            observe(if (checked is SourceEvidenceCheck.Rejected) "ExactPointRejected" else "ExactPointUnavailable", 1L)
            if (checked is SourceEvidenceCheck.Rejected && !checking() && key != null) {
                exactCheck = ExactCheck(key, accepted = false)
            }
            return null
        }
        val value = source.linearValue(seed, checking) ?: return null
        val accepted = original(seed, value, checking)
        if (checking()) {
            observe("ExactOriginalUnavailable", 1L)
            return null
        }
        observe(if (accepted) "ExactOriginalChecked" else "ExactOriginalRejected", 1L)
        if (key != null) exactCheck = ExactCheck(key, accepted)
        return seed.takeIf { accepted }
    }

    private fun exactKey(source: SourceProofContext, seed: Sample,
        checking: ExecutionControl): LinearIncumbentSharing.Key? {
        if (seed.algebraicReals != null || seed.symbolicReals != null) return null
        var bytes = 256L + 16L * (seed.numBoolVars.toLong() + seed.numIntVars + seed.numRealVars)
        for (value in seed.exactInts.orEmpty()) {
            if (checking()) return null
            val bits = value.magnitudeBitLength()
            budget.observe(preparation = 1L + bits / 64L)
            if (bits > 4096) return null
            bytes += 64L + bits / 4L
        }
        for (value in seed.exactReals.orEmpty()) {
            if (checking()) return null
            val bits = value.num.magnitudeBitLength().toLong() + value.den.magnitudeBitLength()
            budget.observe(preparation = 1L + bits / 64L)
            if (bits > 4096L) return null
            bytes += 64L + bits / 4L
        }
        val work = 1L + seed.numBoolVars + seed.numIntVars + seed.numRealVars
        if (checking() || budget.funding?.let {
                it.availableWork <= work + 2L || it.remainingAllocation <= bytes
            } == true
        ) return null
        budget.observe(preparation = work)
        budget.funding?.charge(0L, bytes)
        return LinearIncumbentSharing.Key(source, seed, originalCheck).takeUnless { checking() }
    }

    private fun seedPoint(query: LinearAuthorityQuery, seed: Sample, token: ExecutionControl): List<BigFraction>? {
        val point = ArrayList<BigFraction>(query.coordinates.size)
        for (coordinate in query.coordinates) {
            if (token()) return null
            // Floating coordinates guide reconstruction; source checks establish exact authority.
            val value = if (coordinate.kind == SourceCoordinate.Kind.Integer) {
                BigFraction.of(seed.exactIntValue(coordinate.id), BIG_ONE)
            } else BigFraction.ofDouble(seed.approximateRealValue(coordinate.id)) ?: BigFraction.ZERO
            val bits = maxOf(value.num.magnitudeBitLength(), value.den.magnitudeBitLength())
            budget.observe(preparation = 1L + bits / 64L)
            budget.funding?.charge(0L, 64L + bits / 4L)
            point += value
        }
        return point.takeUnless { token() }
    }

    private fun prepare(source: SourceProofContext, seed: Sample, token: ExecutionControl): LinearAuthorityQuery? {
        val original = source.scope.condition
        val mapEntries = 1L + source.coordinateCount + source.premiseCount + original.coordinateRestrictionCount
        val mapWork = 16L * mapEntries
        val mapBytes = 512L * mapEntries
        if (token() || budget.funding?.let {
                it.availableWork <= mapWork || it.remainingAllocation <= mapBytes
            } == true) return null
        budget.observe(preparation = mapWork)
        budget.funding?.charge(0L, mapBytes)
        val coordinates = source.coordinates
        if (!source.affineObjectiveAvailable || !source.linearCoordinatesSupported() ||
            coordinates.count { it.kind == SourceCoordinate.Kind.Boolean } != seed.numBoolVars ||
            coordinates.count { it.kind == SourceCoordinate.Kind.Integer } != seed.numIntVars ||
            coordinates.count { it.kind == SourceCoordinate.Kind.Real } != seed.numRealVars
        ) return null
        val bools = original.bools.toMutableMap()
        val lower = original.lower.toMutableMap()
        val upper = original.upper.toMutableMap()
        for (coordinate in coordinates) {
            if (token()) return null
            when (coordinate.kind) {
                SourceCoordinate.Kind.Boolean -> {
                    val value = seed.boolValue(coordinate.id)
                    if (bools[coordinate.id]?.let { it != value } == true) return null
                    bools[coordinate.id] = value
                }
                SourceCoordinate.Kind.Integer -> {
                    val value = BigFraction.of(seed.exactIntValue(coordinate.id), BIG_ONE)
                    if (lower[coordinate]?.let { it > value } == true ||
                        upper[coordinate]?.let { it < value } == true) return null
                    lower[coordinate] = value
                    upper[coordinate] = value
                }
                SourceCoordinate.Kind.Real -> Unit
            }
        }
        var allocation = 128L * source.coordinates.size
        var entries = 0L
        for (premise in source.premises.values) {
            if (token()) return null
            entries += premise.inequality.supportSize + 1L
            if (entries > 65_536L) return null
            allocation += 128L + 128L * premise.inequality.supportSize
            budget.observe(preparation = premise.inequality.supportSize.toLong() + 1L)
        }
        budget.funding?.charge(0L, allocation)
        if (token()) return null
        val condition = original.copy(bools = bools, lower = lower, upper = upper)
        return LinearAuthorityQuery.prepare(source.atScope(source.scope.copy(condition = condition)), null, token)
    }

    private fun recoveryCost(stage: String, count: Long) {
        if (stage == "PrimalRecoveryWork") budget.observe(refinement = count)
        if (stage == "PrimalRecoveryAllocation") budget.funding?.charge(0L, count)
        observe(stage, count)
    }

    fun checking(cancellation: ExecutionControl): ExecutionControl = metered(cancellation, checking = true)

    private fun metered(cancellation: ExecutionControl, checking: Boolean): ExecutionControl =
        object : ExecutionControl() {
            private val meter = WorkMeter { units ->
                if (checking) budget.observe(boundCertification = units) else budget.observe(preparation = units)
            }
            override fun isCancelled(): Boolean {
                charge(1L)
                return closed || cancellation() || budget.funding?.paused == true
            }
            override fun deadline() = cancellation.deadline()
            override val accounting: WorkMeter get() = meter
        }

    private fun finish(attempt: Attempt, point: Sample?): Sample? {
        attempt.checked = point
        attempt.finished = true
        attempt.query = null
        attempt.point = null
        attempt.progress.completed.clear()
        attempt.residual?.close()
        attempt.residual = null
        return point
    }

    override fun close() {
        if (closed) return
        closed = true
        val resources = SearchInvocationResources()
        for (attempt in attempts) attempt.residual?.let(resources::own)
        try {
            resources.close()
        } finally {
            val sharingWork = shared?.release(this) ?: 0
            budget.observe(preparation = attempts.size.toLong() + sharingWork)
            attempts.clear()
            ownedAttempt = null
            exactCheck = null
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 16
    }
}
