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
 * Cross-arm globally valid level-0 integer-variable bounds of one portfolio: the intersection of the root bounds
 * the arms prove hold at every solution. Incumbent-relative reduced-cost fixings are not shared: they hold only
 * against their cutoff, every LP arm derives its own against the shared incumbent, and transferring them would need
 * cutoff-keyed retraction. The [lock] comes from the executor's [Concurrency].
 */
internal class SharedVarBounds(
    numIntVars: Int,
    private val lock: Mutex = Mutex(concurrent = false),
    private val identity: ModelIdentity? = null,
) {
    private val lo = LongArray(numIntVars) { Long.MIN_VALUE }
    private val hi = LongArray(numIntVars) { Long.MAX_VALUE }
    private var invocation: EngineInvocation? = null

    fun bindInvocation(binding: EngineInvocation) = lock.withLock {
        require(identity?.sameModel(binding.model) != false) { "integer pool belongs to another execution model" }
        require(invocation?.matches(binding) != false) { "integer pool cannot change its runtime invocation" }
        require(invocation != null || (lo.all { it == Long.MIN_VALUE } && hi.all { it == Long.MAX_VALUE })) {
            "integer pool already contains unscoped bounds"
        }
        invocation = binding
    }

    fun publish(publication: CompletedEnginePublication.IntegerWindow, origin: Int = NO_ORIGIN) = publish(
        publication.coordinate, publication.lower, publication.upper, origin,
        publication.model, EvidenceCertificate.published(publication))

    private val loOrigin = IntArray(numIntVars) { NO_ORIGIN }
    private val hiOrigin = IntArray(numIntVars) { NO_ORIGIN }

    fun publish(
        varId: Int,
        lower: Long,
        upper: Long,
        origin: Int = NO_ORIGIN,
        model: ModelIdentity? = null,
        certificate: EvidenceCertificate? = null,
    ) {
        if (varId !in lo.indices) return
        lock.withLock {
            val expected = identity ?: invocation?.model?.forObjective(null)
            if (certificate?.publication != null && invocation == null) return@withLock
            if (expected != null) {
                if (model == null) return@withLock
                val verifier = ModelEvidenceVerifier<Unit, Pair<Long, Long>>(expected, CandidateVerifier.trusting())
                val verified = verifier.verify(ModelEvidence.IntegerWindow(model, varId, lower, upper, certificate),
                    invocation?.scope, invocation)
                if (verified !is Verification.Accepted) return@withLock
            }
            if (lower > lo[varId]) {
                lo[varId] = lower
                loOrigin[varId] = origin
            }
            if (upper < hi[varId]) {
                hi[varId] = upper
                hiOrigin[varId] = origin
            }
        }
    }

    fun lowerOriginOf(varId: Int): Int = lock.withLock { if (varId in lo.indices) loOrigin[varId] else NO_ORIGIN }

    fun upperOriginOf(varId: Int): Int = lock.withLock { if (varId in hi.indices) hiOrigin[varId] else NO_ORIGIN }

    fun lowerOf(varId: Int): Long = lock.withLock { if (varId in lo.indices) lo[varId] else Long.MIN_VALUE }

    fun upperOf(varId: Int): Long = lock.withLock { if (varId in hi.indices) hi[varId] else Long.MAX_VALUE }

    internal companion object {
        const val NO_ORIGIN = -1
    }
}
