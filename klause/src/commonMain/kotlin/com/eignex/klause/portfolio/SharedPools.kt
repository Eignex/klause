package com.eignex.klause.portfolio

import com.eignex.klause.localsearch.InvariantNetworkPreparation
import com.eignex.klause.localsearch.LocalSearchPreparation
import com.eignex.klause.propagation.PropagationProblem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.Candidate
import com.eignex.klause.solver.incumbent.CandidateVerifier
import com.eignex.klause.solver.incumbent.EngineInvocation
import com.eignex.klause.solver.incumbent.EvidenceCertificate
import com.eignex.klause.solver.incumbent.IncumbentExchange
import com.eignex.klause.solver.incumbent.IncumbentSource
import com.eignex.klause.solver.incumbent.ModelEvidence
import com.eignex.klause.solver.incumbent.ModelEvidenceVerifier
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.incumbent.Publication
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.util.ExecutionControl

// The cross-arm sharing channels handed to every arm of one portfolio. Only optimising arms feed bounds,
// varBounds and solutions; an LS-only portfolio gets no pools at all.
internal class SharedPools(
    val clauses: SharedClausePool?,
    val cuts: SharedCutPool?,
    val bounds: SharedObjectiveBound? = null,
    val varBounds: SharedVarBounds? = null,
    val solutions: IncumbentExchange<Sample, Double>? = null,
    val contributions: ContributionTally = ContributionTally(),
    // Shared clause arena and occurrence indices; native arms keep their watches and trail private.
    val nativeProjection: PropagationProblem? = null,
    val localSearchProjection: LocalSearchPreparation? = null,
    val localSearchInvariants: InvariantNetworkPreparation? = null,
    val identity: ModelIdentity? = null,
    val acceptedIncumbents: IncumbentSource<Sample, Double>? = null,
) {
    fun offerSolution(
        model: ModelIdentity,
        sample: Sample,
        value: Double,
        certificate: EvidenceCertificate?,
        invocation: EngineInvocation? = null,
        cancellation: ExecutionControl = ExecutionControl.Never,
    ): Publication<Sample, Double> {
        val exchange = solutions ?: return Publication.Indeterminate("there is no solution exchange")
        if (certificate?.publication != null && invocation == null) {
            return Publication.Indeterminate("native publication requires its containing runtime")
        }
        val expected = identity ?: invocation?.model
        if (expected != null) {
            if (certificate == null) return Publication.Indeterminate("witness certificate is withheld")
            val verifier = ModelEvidenceVerifier<Sample, Double>(expected, CandidateVerifier.trusting())
            val evidence = ModelEvidence.Witness(model, Candidate(sample, value), certificate)
            when (val verdict = verifier.verify(evidence, invocation?.scope, invocation, cancellation)) {
                is Verification.Rejected -> return Publication.Rejected(verdict.reason)
                is Verification.Indeterminate -> return Publication.Indeterminate(verdict.reason)
                is Verification.Accepted -> Unit
            }
        }
        return exchange.offer(sample, value)
    }
}
