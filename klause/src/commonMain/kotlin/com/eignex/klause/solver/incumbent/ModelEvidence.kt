package com.eignex.klause.solver.incumbent

internal class ModelIdentity private constructor(private val source: Any, private val objective: Any?) {
    fun matches(other: ModelIdentity): Boolean = source === other.source && objective === other.objective

    fun sameModel(other: ModelIdentity): Boolean = source === other.source

    fun forObjective(objective: Any?): ModelIdentity = ModelIdentity(source, objective)

    companion object {
        fun of(source: Any, objective: Any? = null): ModelIdentity = ModelIdentity(source, objective)
    }
}

internal enum class EvidenceKind { Witness, Bound, Infeasible, Unbounded }

// Issued only at an adapter whose engine has completed the corresponding source check or proof.
internal class EvidenceCertificate private constructor(val model: ModelIdentity, val kind: EvidenceKind) {
    companion object {
        fun verified(model: ModelIdentity, kind: EvidenceKind): EvidenceCertificate = EvidenceCertificate(model, kind)
    }
}

internal sealed interface ModelEvidence<out A, out V> {
    val model: ModelIdentity
    val certificate: EvidenceCertificate?
    val kind: EvidenceKind

    data class Witness<A, V>(
        override val model: ModelIdentity,
        val candidate: Candidate<A, V>,
        override val certificate: EvidenceCertificate? = null,
    ) : ModelEvidence<A, V> {
        override val kind: EvidenceKind get() = EvidenceKind.Witness
    }

    data class Bound<V>(
        override val model: ModelIdentity,
        val value: V,
        override val certificate: EvidenceCertificate?,
    ) : ModelEvidence<Nothing, V> {
        override val kind: EvidenceKind get() = EvidenceKind.Bound
    }

    data class Infeasible(
        override val model: ModelIdentity,
        override val certificate: EvidenceCertificate?,
    ) : ModelEvidence<Nothing, Nothing> {
        override val kind: EvidenceKind get() = EvidenceKind.Infeasible
    }

    data class Unbounded<A, V>(
        override val model: ModelIdentity,
        val candidate: Candidate<A, V>,
        override val certificate: EvidenceCertificate?,
    ) : ModelEvidence<A, V> {
        override val kind: EvidenceKind get() = EvidenceKind.Unbounded
    }
}

internal class ModelEvidenceVerifier<A, V>(
    private val model: ModelIdentity,
    private val witnessVerifier: CandidateVerifier<A, V>,
) {
    fun verify(evidence: ModelEvidence<A, V>): Verification<ModelEvidence<A, V>, Unit> {
        if (!model.matches(evidence.model)) {
            return Verification.Rejected("evidence belongs to a different model or objective")
        }
        val certificate = evidence.certificate
        if (certificate != null && (!certificate.model.matches(model) || certificate.kind != evidence.kind)) {
            return Verification.Rejected("certificate belongs to a different model, objective or claim")
        }
        if (evidence.kind != EvidenceKind.Witness && certificate == null) {
            return Verification.Indeterminate("the required ${evidence.kind.name.lowercase()} certificate is withheld")
        }
        val candidate = when (evidence) {
            is ModelEvidence.Witness -> evidence.candidate
            is ModelEvidence.Unbounded -> evidence.candidate
            else -> null
        }
        if (candidate != null) {
            when (val verdict = witnessVerifier.verify(candidate)) {
                is Verification.Rejected -> return verdict
                is Verification.Indeterminate -> return verdict
                is Verification.Accepted -> Unit
            }
        }
        return Verification.Accepted(Candidate(evidence, Unit))
    }
}
