package com.eignex.klause.portfolio

import com.eignex.klause.solver.incumbent.CandidateVerifier
import com.eignex.klause.solver.incumbent.EvidenceCertificate
import com.eignex.klause.solver.incumbent.ModelEvidence
import com.eignex.klause.solver.incumbent.ModelEvidenceVerifier
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.kumulant.core.Concurrency
import com.eignex.kumulant.stream.Mutex
import com.eignex.kumulant.stream.lock

/**
 * The cross-arm objective **lower** bound of one minimisation portfolio — the dual of the
 * shared incumbent (the upper-bound cutoff already threaded through every arm's
 * `BacktrackParams.objectiveBoundSupplier`). Each arm publishes the global lower bounds it proves (its
 * root LP relaxation bound, raised objective floors); the manager keeps their **maximum**, the tightest
 * valid lower bound any arm has shown on the optimum.
 *
 * The bound is monotone non-decreasing and every published value is a sound global lower bound on the
 * optimum, so the max is too. Combined with the pool's best incumbent it lets the executor prove
 * optimality across arms — an arm that only finds a good incumbent and one that only proves a strong
 * bound together close the gap that neither closes alone — without any arm transferring incumbent-relative
 * deductions (interior-node deductions still cross only as learned clauses, and Farkas nogoods as the
 * globally-valid clauses they already are).
 *
 * The [lock] comes from the executor's [Concurrency]: a no-op under the single-core sequential executor,
 * a platform mutex under the parallel one.
 */
internal class SharedObjectiveBound(
    private val lock: Mutex = Concurrency.None.lock(),
    private val identity: ModelIdentity? = null,
) {
    private var lb = Double.NEGATIVE_INFINITY

    /** Fold a proven global lower bound on the optimum into the shared maximum, returning how far it raised a
     *  finite bound: `0` when it raised nothing or set the first one. Ignores a non-finite value (no information). */
    fun publish(value: Double, model: ModelIdentity? = null, certificate: EvidenceCertificate? = null): Double {
        if (!value.isFinite()) return 0.0
        if (identity != null) {
            if (model == null) return 0.0
            val verifier = ModelEvidenceVerifier<Unit, Double>(identity, CandidateVerifier.trusting())
            if (verifier.verify(ModelEvidence.Bound(model, value, certificate)) !is Verification.Accepted) return 0.0
        }
        return lock.withLock {
            val raise = if (value > lb && lb.isFinite()) value - lb else 0.0
            if (value > lb) lb = value
            raise
        }
    }

    /** The tightest lower bound any arm has published, or `−∞` if none. */
    fun current(): Double = lock.withLock { lb }
}
