package com.eignex.klause.portfolio

import com.eignex.klause.solver.incumbent.CandidateVerifier
import com.eignex.klause.solver.incumbent.CompletedEnginePublication
import com.eignex.klause.solver.incumbent.EngineInvocation
import com.eignex.klause.solver.incumbent.EvidenceCertificate
import com.eignex.klause.solver.incumbent.ModelEvidence
import com.eignex.klause.solver.incumbent.ModelEvidenceVerifier
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.kumulant.core.Concurrency
import com.eignex.util.concurrent.Mutex

/**
 * The cross-arm objective lower bound of one minimisation portfolio: the maximum of the global lower bounds the
 * arms prove. With the pool's best incumbent it lets the executor prove optimality across arms, an arm with a good
 * incumbent and one with a strong bound closing the gap together, without any arm transferring
 * incumbent-relative deductions. The [lock] comes from the executor's [Concurrency].
 */
internal class SharedObjectiveBound(
    private val lock: Mutex = Mutex(concurrent = false),
    private val identity: ModelIdentity? = null,
) {
    private var lb = Double.NEGATIVE_INFINITY
    private var invocation: EngineInvocation? = null

    fun bindInvocation(binding: EngineInvocation) = lock.withLock {
        require(identity?.matches(binding.model) != false) { "bound pool belongs to another execution model" }
        require(invocation?.matches(binding) != false) { "bound pool cannot change its runtime invocation" }
        require(invocation != null || lb == Double.NEGATIVE_INFINITY) { "bound pool already contains unscoped bounds" }
        invocation = binding
    }

    fun publish(publication: CompletedEnginePublication.ObjectiveBound): Double =
        publish(publication.value, publication.model, EvidenceCertificate.published(publication))

    // Returns how far it raised a finite bound: `0` when it raised nothing or set the first one.
    fun publish(value: Double, model: ModelIdentity? = null, certificate: EvidenceCertificate? = null): Double {
        if (!value.isFinite()) return 0.0
        return lock.withLock {
            val expected = identity ?: invocation?.model
            if (certificate?.publication != null && invocation == null) return@withLock 0.0
            if (expected != null) {
                if (model == null) return@withLock 0.0
                val verifier = ModelEvidenceVerifier<Unit, Double>(expected, CandidateVerifier.trusting())
                if (verifier.verify(ModelEvidence.Bound(model, value, certificate), invocation?.scope, invocation) !is
                        Verification.Accepted
                ) return@withLock 0.0
            }
            val raise = if (value > lb && lb.isFinite()) value - lb else 0.0
            if (value > lb) lb = value
            raise
        }
    }

    fun current(): Double = lock.withLock { lb }
}
