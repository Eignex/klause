package com.eignex.klause.solver.result

import com.eignex.klause.simplex.exact.ExactContinuationMetrics
import com.eignex.klause.simplex.exact.ExactSimplexRunResult
import com.eignex.klause.simplex.exact.ExactSimplexStage
import com.eignex.klause.simplex.exact.Frac128Escalation
import com.eignex.klause.simplex.exact.RationalSimplexObserver
import com.eignex.klause.solver.search.SearchExplanation
import kotlin.time.TimeMark
import kotlin.time.TimeSource.Monotonic

/** Exact-arithmetic telemetry for complete SMT theory routes. */
data class SmtStats(
    /** Accepted exact checks spent by the private exact-integer DFS. */
    val privateChecks: Long = 0,
    /** Exact-theory conflicts returned to the shared search. */
    val conflicts: Long = 0,
    /** Returned conflicts with a clause-form explanation. */
    val explainedConflicts: Long = 0,
    /** Returned conflicts without a clause-form explanation. */
    val unexplainedConflicts: Long = 0,
    /** Literals across [explainedConflicts] only. */
    val conflictLiterals: Long = 0,
    /** Calls to the LIRA reduction cache, including cache hits. */
    val reductionRequests: Long = 0,
    /** Reduction-cache hits. */
    val reductionCacheHits: Long = 0,
    /** Reduction requests with a decisive bounded or infeasible result. */
    val reductionAccepted: Long = 0,
    /** Reduction requests interrupted before a decisive result. */
    val reductionDeclined: Long = 0,
    /** Reduction time excluding nested simplex and escalation time. */
    val reductionNs: Long = 0,
    /** Exact-simplex invocations on nonempty models. */
    val simplexAttempts: Long = 0,
    /** Exact-simplex invocations with a feasible or infeasible verdict. */
    val simplexAccepted: Long = 0,
    /** Exact-simplex invocations that returned unknown. */
    val simplexDeclined: Long = 0,
    /** Simplex time excluding escalated BigFraction reruns. */
    val simplexNs: Long = 0,
    /** Frac128 calls made by the exact rational outcome path. */
    val frac128Attempts: Long = 0,
    /** Frac128 calls whose initial tableau was representable. */
    val frac128Eligible: Long = 0,
    /** Eligible Frac128 calls that returned a decisive verdict without escalation. */
    val frac128Accepted: Long = 0,
    /** Frac128 calls that restarted at BigFraction. */
    val frac128Escalations: Long = 0,
    /** Escalations after a latched Frac128 overflow. */
    val frac128OverflowEscalations: Long = 0,
    /** Escalations because the initial Frac128 tableau was not representable. */
    val frac128InputEscalations: Long = 0,
    /** BigFraction rerun time after a Frac128 escalation. */
    val escalationNs: Long = 0,
    /** Exact source-witness candidates made available by an exact theory check. */
    val witnessCandidates: Long = 0,
    /** Existing consumer paths that accepted an exact source witness. */
    val witnessAccepted: Long = 0,
    /** Witness candidates over a source system containing a strict row. */
    val strictWitnessCandidates: Long = 0,
    /** Accepted witnesses over a source system containing a strict row. */
    val strictWitnessAccepted: Long = 0,
    /** Witness candidates with wide exact integer source data. */
    val wideWitnessCandidates: Long = 0,
    /** Accepted witnesses with wide exact integer source data. */
    val wideWitnessAccepted: Long = 0,
    /** Shared LP exact continuation invocation deltas. */
    val continuation: LpContinuationStats = LpContinuationStats(),
) {
    /** Combine independent solve slices. */
    fun mergedWith(other: SmtStats): SmtStats = SmtStats(
        privateChecks + other.privateChecks,
        conflicts + other.conflicts,
        explainedConflicts + other.explainedConflicts,
        unexplainedConflicts + other.unexplainedConflicts,
        conflictLiterals + other.conflictLiterals,
        reductionRequests + other.reductionRequests,
        reductionCacheHits + other.reductionCacheHits,
        reductionAccepted + other.reductionAccepted,
        reductionDeclined + other.reductionDeclined,
        reductionNs + other.reductionNs,
        simplexAttempts + other.simplexAttempts,
        simplexAccepted + other.simplexAccepted,
        simplexDeclined + other.simplexDeclined,
        simplexNs + other.simplexNs,
        frac128Attempts + other.frac128Attempts,
        frac128Eligible + other.frac128Eligible,
        frac128Accepted + other.frac128Accepted,
        frac128Escalations + other.frac128Escalations,
        frac128OverflowEscalations + other.frac128OverflowEscalations,
        frac128InputEscalations + other.frac128InputEscalations,
        escalationNs + other.escalationNs,
        witnessCandidates + other.witnessCandidates,
        witnessAccepted + other.witnessAccepted,
        strictWitnessCandidates + other.strictWitnessCandidates,
        strictWitnessAccepted + other.strictWitnessAccepted,
        wideWitnessCandidates + other.wideWitnessCandidates,
        wideWitnessAccepted + other.wideWitnessAccepted,
        continuation.mergedWith(other.continuation),
    )
}

/** Mutable exact-SMT telemetry for one top-level open-theory request. */
internal class SmtStatsSink : RationalSimplexObserver {
    private var continuation = LpContinuationStats()

    fun observeContinuation(metrics: ExactContinuationMetrics) {
        continuation = continuation.mergedWith(metrics.toStats())
    }

    private var privateChecks = 0L
    private var conflicts = 0L
    private var explainedConflicts = 0L
    private var unexplainedConflicts = 0L
    private var conflictLiterals = 0L
    private var reductionRequests = 0L
    private var reductionCacheHits = 0L
    private var reductionAccepted = 0L
    private var reductionDeclined = 0L
    private var reductionNs = 0L
    private var simplexAttempts = 0L
    private var simplexAccepted = 0L
    private var simplexDeclined = 0L
    private var simplexNs = 0L
    private var frac128Attempts = 0L
    private var frac128Eligible = 0L
    private var frac128Accepted = 0L
    private var frac128Escalations = 0L
    private var frac128OverflowEscalations = 0L
    private var frac128InputEscalations = 0L
    private var escalationNs = 0L
    private var witnessCandidates = 0L
    private var witnessAccepted = 0L
    private var strictWitnessCandidates = 0L
    private var strictWitnessAccepted = 0L
    private var wideWitnessCandidates = 0L
    private var wideWitnessAccepted = 0L

    fun observePrivateCheck() {
        privateChecks++
    }

    fun observeConflict(explanation: SearchExplanation?) {
        conflicts++
        if (explanation == null || explanation.literals.isEmpty()) {
            unexplainedConflicts++
        } else {
            explainedConflicts++
            conflictLiterals += explanation.literals.size
        }
    }

    fun beginReduction(): ReductionMark = ReductionMark(Monotonic.markNow(), simplexNs, escalationNs)

    fun endReduction(mark: ReductionMark, cacheHit: Boolean, accepted: Boolean) {
        reductionRequests++
        if (cacheHit) reductionCacheHits++
        if (accepted) reductionAccepted++ else reductionDeclined++
        val nested = (simplexNs - mark.simplexNs) + (escalationNs - mark.escalationNs)
        val elapsed = mark.mark.elapsedNow().inWholeNanoseconds
        check(elapsed >= nested) { "nested exact-simplex timing exceeded enclosing reduction timing" }
        reductionNs += elapsed - nested
    }

    fun observeWitnessCandidate(strict: Boolean, wide: Boolean) {
        witnessCandidates++
        if (strict) strictWitnessCandidates++
        if (wide) wideWitnessCandidates++
    }

    fun observeWitnessAccepted(strict: Boolean, wide: Boolean) {
        witnessAccepted++
        if (strict) strictWitnessAccepted++
        if (wide) wideWitnessAccepted++
    }

    override fun observeFrac128Attempt(eligible: Boolean) {
        frac128Attempts++
        if (eligible) frac128Eligible++
    }

    override fun observeEscalation(reason: Frac128Escalation) {
        frac128Escalations++
        when (reason) {
            Frac128Escalation.OVERFLOW -> frac128OverflowEscalations++
            Frac128Escalation.INPUT -> frac128InputEscalations++
        }
    }

    override fun observeSimplex(stage: ExactSimplexStage, result: ExactSimplexRunResult, elapsedNs: Long) {
        simplexAttempts++
        when (result) {
            ExactSimplexRunResult.FEASIBLE, ExactSimplexRunResult.INFEASIBLE -> simplexAccepted++
            ExactSimplexRunResult.UNKNOWN -> simplexDeclined++
        }
        if (stage == ExactSimplexStage.ESCALATED_BIG) {
            escalationNs += elapsedNs
        } else {
            simplexNs += elapsedNs
        }
        if (stage == ExactSimplexStage.FRAC128 && result != ExactSimplexRunResult.UNKNOWN) frac128Accepted++
    }

    fun snapshot(): SmtStats = SmtStats(
        privateChecks, conflicts, explainedConflicts, unexplainedConflicts, conflictLiterals,
        reductionRequests, reductionCacheHits, reductionAccepted, reductionDeclined, reductionNs,
        simplexAttempts, simplexAccepted, simplexDeclined, simplexNs,
        frac128Attempts, frac128Eligible, frac128Accepted, frac128Escalations,
        frac128OverflowEscalations, frac128InputEscalations, escalationNs,
        witnessCandidates, witnessAccepted, strictWitnessCandidates, strictWitnessAccepted,
        wideWitnessCandidates, wideWitnessAccepted, continuation,
    )
}

/** One reduction timing interval and the nested simplex totals it started with. */
internal class ReductionMark(val mark: TimeMark, val simplexNs: Long, val escalationNs: Long)
