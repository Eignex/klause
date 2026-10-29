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
import com.eignex.klause.util.WorkMeter

internal class LinearIncumbentCompletion(
    private val budget: SourceLpBudget,
    private val observe: (String, Long) -> Unit,
) : AutoCloseable {
    private class Attempt(val source: SourceProofContext, val seed: Sample) {
        var query: LinearAuthorityQuery? = null
        var point: List<BigFraction>? = null
        val progress = LinearPrimalRecoveryProgress()
        var residual: LinearResidualCompletion? = null
        var finished = false
        var checked: Sample? = null
    }

    // Entries are never evicted: exhausting retention cannot renew a failed attempt.
    private val attempts = ArrayList<Attempt>()
    private var closed = false

    fun complete(source: SourceProofContext, seed: Sample, cancellation: Cancellation): Sample? {
        check(!closed)
        if (cancellation()) return null
        if (seed.exactReals != null) return seed
        val token = metered(cancellation, checking = false)
        val previous = attempts.firstOrNull {
            budget.observe(preparation = seed.numBoolVars.toLong() + seed.numIntVars + seed.numRealVars)
            !token() && sameSource(it.source, source) && it.seed == seed &&
                it.seed.semantics == seed.semantics && it.seed.coordinateFrame === seed.coordinateFrame
        }
        if (previous?.finished == true) {
            observe(if (previous.checked == null) "DeclineReused" else "CheckedReused", 1L)
            return previous.checked.takeUnless { token() }
        }
        if (token()) return null
        if (previous == null && attempts.size >= MAX_ATTEMPTS) {
            observe("RetentionExhausted", 1L)
            return null
        }
        val attempt = previous ?: Attempt(source, seed).also {
            budget.funding?.charge(0L, 256L + 16L * seed.numBoolVars + 64L * seed.numIntVars +
                8L * seed.numRealVars)
            attempts += it
            observe("Attempts", 1L)
        }
        if (budget.funding?.exhausted == true) return finish(attempt, null)
        if (attempt.query == null) {
            val prepared = prepare(source, seed, token)
            if (prepared == null) {
                if (!token()) finish(attempt, null)
                return null
            }
            attempt.query = prepared
            attempt.point = prepared.coordinates.map { coordinate ->
                if (coordinate.kind == SourceCoordinate.Kind.Integer)
                    BigFraction.of(seed.exactIntValue(coordinate.id), BIG_ONE) else BigFraction.ZERO
            }
        }
        val query = requireNotNull(attempt.query)
        val values = recoverLinearPrimal(query.coordinates, requireNotNull(attempt.point), query.rows,
            query.inactive, token, complete = { rows, columns, active ->
                val residual = attempt.residual ?: LinearResidualCompletion(budget, SparseSourceLpPolicy())
                    .also { attempt.residual = it }
                residual.complete(rows, columns, active, ::recoveryCost) {
                    LinearAuthorityLp(budget).use { completion ->
                        completion.solve(rows, List(columns) { BigFraction.ZERO }, BigFraction.ZERO, active,
                            claim = LpClaim.FEASIBLE_COMPLETION)?.result?.exactPrimal?.take(columns)
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
        if (checking()) return null
        val accepted = objectiveChecked == SourceEvidenceCheck.Checked
        observe(if (accepted) "Checked" else "Rejected", 1L)
        return finish(attempt, proposed.takeIf { accepted })
    }

    private fun prepare(source: SourceProofContext, seed: Sample, token: Cancellation): LinearAuthorityQuery? {
        if (!source.affineObjectiveAvailable || !source.linearCoordinatesSupported() ||
            source.coordinates.count { it.kind == SourceCoordinate.Kind.Boolean } != seed.numBoolVars ||
            source.coordinates.count { it.kind == SourceCoordinate.Kind.Integer } != seed.numIntVars ||
            source.coordinates.count { it.kind == SourceCoordinate.Kind.Real } != seed.numRealVars
        ) return null
        var condition = source.scope.condition
        for (coordinate in source.coordinates) {
            if (token()) return null
            when (coordinate.kind) {
                SourceCoordinate.Kind.Boolean -> {
                    val value = seed.boolValue(coordinate.id)
                    if (condition.bools[coordinate.id]?.let { it != value } == true) return null
                    condition = condition.pin(coordinate.id, value)
                }
                SourceCoordinate.Kind.Integer -> {
                    val value = BigFraction.of(seed.exactIntValue(coordinate.id), BIG_ONE)
                    if (condition.lower[coordinate]?.let { it > value } == true ||
                        condition.upper[coordinate]?.let { it < value } == true) return null
                    condition = condition.bound(coordinate, value, upper = false).bound(coordinate, value, upper = true)
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
        return LinearAuthorityQuery.prepare(source.atScope(source.scope.copy(condition = condition)), null, token)
    }

    private fun recoveryCost(stage: String, count: Long) {
        if (stage == "PrimalRecoveryWork") budget.observe(refinement = count)
        if (stage == "PrimalRecoveryAllocation") budget.funding?.charge(0L, count)
        observe(stage, count)
    }

    fun checking(cancellation: Cancellation): Cancellation = metered(cancellation, checking = true)

    private fun metered(cancellation: Cancellation, checking: Boolean): Cancellation = object : Cancellation {
        override fun isCancelled(): Boolean {
            charge(1L)
            return closed || cancellation() || budget.funding?.paused == true
        }
        override fun deadline() = cancellation.deadline()
        override fun workMeter(): WorkMeter = WorkMeter { units ->
            if (checking) budget.observe(boundCertification = units) else budget.observe(preparation = units)
        }
    }

    private fun sameSource(left: SourceProofContext, right: SourceProofContext): Boolean = left === right ||
        (left.scope == right.scope && left.coordinates == right.coordinates && left.premises == right.premises &&
            left.affineObjectiveAvailable == right.affineObjectiveAvailable && left.objective == right.objective &&
            left.objectiveConstant == right.objectiveConstant && left.semantics == right.semantics &&
            left.semanticsChecker === right.semanticsChecker)

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
        for (attempt in attempts) attempt.residual?.close()
        budget.observe(preparation = attempts.size.toLong())
        attempts.clear()
    }

    private companion object {
        const val MAX_ATTEMPTS = 16
    }
}
