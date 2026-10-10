package com.eignex.klause.portfolio

import com.eignex.klause.localsearch.InvariantNetwork
import com.eignex.klause.localsearch.LocalSearchPreparation
import com.eignex.klause.propagation.PropagationProblem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.Candidate
import com.eignex.klause.solver.incumbent.CandidateVerifier
import com.eignex.klause.solver.incumbent.EvidenceCertificate
import com.eignex.klause.solver.incumbent.IncumbentExchange
import com.eignex.klause.solver.incumbent.ModelEvidence
import com.eignex.klause.solver.incumbent.ModelEvidenceVerifier
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.incumbent.Publication
import com.eignex.klause.solver.incumbent.Verification

/**
 * The cross-arm sharing channels handed to every backtrack arm of one portfolio: the learned-clause
 * pool ([clauses], always on for a non-LS portfolio), the global-cut pool ([cuts], gated by
 * [PortfolioScenario.shareCuts], on by default), the objective lower-bound manager ([bounds]), the
 * globally-valid level-0 variable-bound manager ([varBounds]), and the verified-incumbent exchange
 * ([solutions]) — the last three present for an optimising portfolio. Each may be null when its sharing
 * does not apply; an LS-only portfolio gets no [SharedPools] at all.
 */
internal class SharedPools(
    val clauses: SharedClausePool?,
    val cuts: SharedCutPool?,
    val bounds: SharedObjectiveBound? = null,
    val varBounds: SharedVarBounds? = null,
    val solutions: IncumbentExchange<Sample, Double>? = null,
    /** How often the arms used each other's shared clauses, cuts and bounds. */
    val contributions: ContributionTally = ContributionTally(),
    /** Shared clause arena and occurrence indices; native arms keep their watches and trail private. */
    val nativeProjection: PropagationProblem? = null,
    val localSearchProjection: LocalSearchPreparation? = null,
    val localSearchInvariants: Lazy<InvariantNetwork?>? = null,
    val identity: ModelIdentity? = null,
) {
    fun offerSolution(
        model: ModelIdentity,
        sample: Sample,
        value: Double,
        certificate: EvidenceCertificate?,
    ): Publication<Sample, Double> {
        val exchange = solutions ?: return Publication.Indeterminate("there is no solution exchange")
        if (identity != null) {
            if (certificate == null) return Publication.Indeterminate("witness certificate is withheld")
            val verifier = ModelEvidenceVerifier<Sample, Double>(identity, CandidateVerifier.trusting())
            when (val verdict = verifier.verify(ModelEvidence.Witness(model, Candidate(sample, value), certificate))) {
                is Verification.Rejected -> return Publication.Rejected(verdict.reason)
                is Verification.Indeterminate -> return Publication.Indeterminate(verdict.reason)
                is Verification.Accepted -> Unit
            }
        }
        return exchange.offer(sample, value)
    }
}
