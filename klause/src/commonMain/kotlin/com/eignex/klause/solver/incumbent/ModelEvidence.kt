package com.eignex.klause.solver.incumbent

import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceEvidenceCheck
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.solver.evidence.matchSourceClaims
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.util.Cancellation

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
internal class EvidenceCertificate private constructor(
    val model: ModelIdentity,
    val kind: EvidenceKind,
    val completion: CompletedEngineResult? = null,
    val publication: CompletedEnginePublication? = null,
) {
    fun verifyDerivation(
        claim: SourceClaim, context: SourceProofContext, cancellation: Cancellation = Cancellation.Never,
    ): SourceEvidenceCheck {
        val completed = completion ?: return SourceEvidenceCheck.Unavailable("completed derivation capture unavailable")
        if (completed.scope == null || completed.invocation?.hasUncapturedSourceRestrictions == true) {
            return SourceEvidenceCheck.Unavailable("original source revision or restriction capture unavailable")
        }
        if (completed.scope != context.scope) {
            return SourceEvidenceCheck.Rejected("engine completion has a different original-source invocation")
        }
        val expectedKind = when (kind) {
            EvidenceKind.Witness -> claim is SourceClaim.Witness
            EvidenceKind.Infeasible -> claim == SourceClaim.Refutation
            EvidenceKind.Unbounded -> claim is SourceClaim.Ray
            EvidenceKind.Bound -> claim is SourceClaim.Bound || claim is SourceClaim.Optimal
        }
        if (!expectedKind) return SourceEvidenceCheck.Rejected("completion has a different mathematical claim kind")
        val actual = when (completed) {
            is CompletedEngineResult.Solve -> when (val result = completed.result) {
                is SolveResult.Sat -> SourceClaim.Witness(result.assignment)
                is SolveResult.Unsat -> SourceClaim.Refutation
                is SolveResult.Unknown -> null
            }
            is CompletedEngineResult.Minimize -> when (val result = completed.result) {
                is MinimizeResult.Optimal -> when (claim) {
                    is SourceClaim.Optimal -> SourceClaim.Optimal(result.sample, claim.value)
                    is SourceClaim.Bound -> {
                        if (claim.strict) return SourceEvidenceCheck.Rejected("attainment excludes a strict bound")
                        SourceClaim.Optimal(result.sample, claim.value)
                    }
                    is SourceClaim.Witness -> SourceClaim.Witness(result.sample)
                    else -> null
                }
                is MinimizeResult.Infeasible -> SourceClaim.Refutation
                is MinimizeResult.Unbounded -> if (kind == EvidenceKind.Witness) SourceClaim.Witness(result.sample)
                    else SourceClaim.Ray(result.sample,
                    result.direction.withIndex().associate { (id, step) ->
                        SourceCoordinate(SourceCoordinate.Kind.Real, id) to step
                    })
                is MinimizeResult.BestFound -> if (kind == EvidenceKind.Bound) completed.cutoff
                    else SourceClaim.Witness(result.sample)
                is MinimizeResult.Unknown -> completed.cutoff
            }
        } ?: return SourceEvidenceCheck.Unavailable("completion has no actual claim of the requested kind")
        if (!(actual is SourceClaim.Optimal && claim is SourceClaim.Bound)) {
            val matching = matchSourceClaims(actual, claim, cancellation)
            if (matching != SourceEvidenceCheck.Checked) return matching
        }
        return completed.evidence?.verify(actual, context, cancellation)
            ?: SourceEvidenceCheck.Unavailable("engine completion retains no original-source derivation")
    }

    companion object {
        fun published(publication: CompletedEnginePublication): EvidenceCertificate =
            EvidenceCertificate(publication.model,
                if (publication is CompletedEnginePublication.Witness) EvidenceKind.Witness else EvidenceKind.Bound,
                publication = publication)

        fun completed(
            model: ModelIdentity, kind: EvidenceKind, completion: CompletedEngineResult,
        ): EvidenceCertificate? {
            val foreignCapture = completion.evidence?.let { captured ->
                captured.scope != completion.scope || captured.derivations.any { it.scope != completion.scope }
            } == true
            if (!completion.attests(kind) || foreignCapture) return null
            if (completion.invocation?.model?.matches(model) == false) return null
            if (kind != EvidenceKind.Witness &&
                completion.evidence?.let { it.engineScope != completion.scope } == true
            ) return null
            if ((kind == EvidenceKind.Bound || kind == EvidenceKind.Unbounded) &&
                completion.scope?.objective == null && completion.invocation?.sourceObjective == null
            ) {
                return null
            }
            if ((kind == EvidenceKind.Bound || kind == EvidenceKind.Unbounded) &&
                completion.invocation?.let { it.executionObjective == null } == true
            ) return null
            return EvidenceCertificate(model, kind, completion)
        }

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

    data class IntegerWindow(
        override val model: ModelIdentity,
        val coordinate: Int,
        val lower: Long,
        val upper: Long,
        override val certificate: EvidenceCertificate?,
    ) : ModelEvidence<Nothing, Nothing> {
        override val kind: EvidenceKind get() = EvidenceKind.Bound
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
    fun verify(
        evidence: ModelEvidence<A, V>, scope: SourceScope? = null, invocation: EngineInvocation? = null,
        cancellation: Cancellation = Cancellation.Never,
    ): Verification<ModelEvidence<A, V>, Unit> {
        if (!model.matches(evidence.model)) {
            return Verification.Rejected("evidence belongs to a different model or objective")
        }
        val certificate = evidence.certificate
        if (certificate != null && (!certificate.model.matches(model) || certificate.kind != evidence.kind)) {
            return Verification.Rejected("certificate belongs to a different model, objective or claim")
        }
        val witnessPublication = certificate?.publication as? CompletedEnginePublication.Witness
        if (evidence.kind != EvidenceKind.Witness || certificate?.publication != null) {
            val completedInvocation = certificate?.completion?.invocation ?: certificate?.publication?.invocation
            if (invocation != null && completedInvocation != null) {
                val matching = if (witnessPublication != null) {
                    invocation.preparedPlan === completedInvocation.preparedPlan &&
                        invocation.model.matches(completedInvocation.model)
                } else invocation.matches(completedInvocation)
                if (!matching) return Verification.Rejected("evidence belongs to a different runtime invocation")
            }
            if ((invocation == null) != (completedInvocation == null)) {
                return Verification.Indeterminate("the proof consumer and completion require a runtime invocation")
            }
            val completionScope = certificate?.completion?.scope ?: certificate?.publication?.invocation?.scope
            if (scope != null && completionScope != null) {
                val matching = if (witnessPublication != null) {
                    scope.revision == completionScope.revision && scope.objective == completionScope.objective
                } else scope == completionScope
                if (!matching) {
                    return Verification.Rejected("evidence belongs to a different original-source invocation")
                }
            }
            if ((scope == null) != (completionScope == null)) {
                return Verification.Indeterminate("the proof consumer and completion require a source invocation")
            }
        }
        if (evidence.kind != EvidenceKind.Witness && certificate == null) {
            return Verification.Indeterminate("the required ${evidence.kind.name.lowercase()} certificate is withheld")
        }
        if (evidence is ModelEvidence.IntegerWindow && certificate?.completion != null) {
            return Verification.Rejected("solver completion does not attest an integer window")
        }
        certificate?.publication?.let { publication ->
            val matches = when (publication) {
                is CompletedEnginePublication.Witness -> evidence is ModelEvidence.Witness &&
                    publication.sample.equals(evidence.candidate.assignment) &&
                    publication.value == evidence.candidate.objective
                is CompletedEnginePublication.ObjectiveBound -> evidence is ModelEvidence.Bound &&
                    publication.value.equals(evidence.value)
                is CompletedEnginePublication.IntegerWindow -> evidence is ModelEvidence.IntegerWindow &&
                    evidence.coordinate == publication.coordinate && evidence.lower == publication.lower &&
                    evidence.upper == publication.upper
            }
            if (!matches) return Verification.Rejected("published claim differs from the actual engine publication")
            if (publication is CompletedEnginePublication.Witness) {
                for (binding in listOf(publication.invocation, requireNotNull(invocation))) {
                    when (val checked = binding.checkWitness(publication.sample, cancellation)) {
                        SourceEvidenceCheck.Checked -> Unit
                        is SourceEvidenceCheck.Rejected -> return Verification.Rejected(checked.reason)
                        is SourceEvidenceCheck.Unavailable -> return Verification.Indeterminate(checked.reason)
                    }
                }
            }
        }
        if (evidence is ModelEvidence.Bound && certificate?.completion != null) {
            val actual = (certificate.completion as? CompletedEngineResult.Minimize)?.executionBound
                ?: return Verification.Indeterminate("completion has no retained execution bound")
            if (!actual.equals(evidence.value)) {
                return Verification.Rejected("published bound differs from the actual engine completion")
            }
        }
        val candidate = when (evidence) {
            is ModelEvidence.Witness -> evidence.candidate
            is ModelEvidence.Unbounded -> evidence.candidate
            else -> null
        }
        if (candidate != null) {
            val result = certificate?.completion
            val completedPoint = when (result) {
                is CompletedEngineResult.Solve -> (result.result as? SolveResult.Sat)?.assignment
                is CompletedEngineResult.Minimize -> result.result.assignment
                null -> null
            }
            if (completedPoint != null && !completedPoint.equals(candidate.assignment)) {
                return Verification.Rejected("published point differs from the actual engine completion")
            }
            when (val verdict = witnessVerifier.verify(candidate)) {
                is Verification.Rejected -> return verdict
                is Verification.Indeterminate -> return verdict
                is Verification.Accepted -> Unit
            }
        }
        return Verification.Accepted(Candidate(evidence, Unit))
    }
}
