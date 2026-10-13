package com.eignex.klause.solver.incumbent

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.portfolio.PortfolioWorker
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceEvidenceCheck
import com.eignex.klause.solver.evidence.SourceInequality
import com.eignex.klause.solver.evidence.SourceObjective
import com.eignex.klause.solver.evidence.SourcePremise
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceResultCapture
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.solver.evidence.sourceProofContext
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CompletedEngineResultTest {
    private val scope = SourceScope(SourceRevision("original", "exact-affine"))
    private val identity = ModelIdentity.of(Any())

    @Test
    fun `a completed refutation cannot issue a different claim kind`() {
        val completion = CompletedEngineResult.Solve(scope, SolveResult.Unsat())

        assertNull(EvidenceCertificate.completed(identity, EvidenceKind.Unbounded, completion))
    }

    @Test
    fun `a worker cannot issue a completion for a foreign invocation`() {
        val worker = PortfolioWorker.ofSolve("fixture", 0) { _, _ -> SolveResult.Unsat() }
            .bindEvidence(identity).bindSourceScope(scope)
        val foreign = scope.copy(revision = scope.revision.copy(identity = "foreign"))
        val completion = CompletedEngineResult.Solve(foreign, SolveResult.Unsat())

        assertNull(worker.certificateFor(EvidenceKind.Infeasible, completion))
    }

    @Test
    fun `a completed engine label cannot confer independent derivation coverage`() {
        val completion = CompletedEngineResult.Solve(scope, SolveResult.Unsat())
        val certificate = assertNotNull(EvidenceCertificate.completed(identity, EvidenceKind.Infeasible, completion))
        val context = SourceProofContext(scope, emptySet(), mapOf(
            "contradiction" to SourcePremise(SourceInequality(emptyMap(), BigFraction.MINUS_ONE)),
        ))

        assertIs<SourceEvidenceCheck.Unavailable>(certificate.verifyDerivation(SourceClaim.Refutation, context))
    }

    @Test
    fun `an outer binding cannot give proof authority to a witness only worker`() {
        val worker = PortfolioWorker.ofSolve("fixture", 0) { _, _ -> SolveResult.Unsat() }
            .bindEvidence(identity, provesResults = false).bindSourceScope(scope).bindEvidence(identity)
        val completion = CompletedEngineResult.Solve(scope, SolveResult.Unsat())

        assertNull(worker.certificateFor(EvidenceKind.Infeasible, completion))
    }
    @Test
    fun `search exhaustion without a retained source cutoff cannot issue a bound`() {
        val scoped = scope.copy(objective = SourceObjective("x", ObjectiveSense.MINIMIZE))
        val result = MinimizeResult.Unknown(TerminationReason.SearchExhausted, SolveStats.EMPTY)

        val certificate = EvidenceCertificate.completed(identity, EvidenceKind.Bound,
            CompletedEngineResult.Minimize(scoped, result))

        assertNull(certificate)
    }

    @Test
    fun `evidence for another witness cannot verify the actual completed assignment`() {
        val model = Problem(0, 1, arrayOf(IntDomain(1, 3)), emptyArray())
        val scoped = SourceScope(SourceRevision("original-ir", "klause-ir-exact-v1"))
        val context = assertNotNull(model.sourceProofContext(scoped))
        val actual = Sample(booleanArrayOf(), longArrayOf(1))
        val other = Sample(booleanArrayOf(), longArrayOf(2))
        val otherResult = SourceResultCapture(context).solve(SolveResult.Sat(other), "fixture")
        val result = SolveResult.Sat(actual, evidence = otherResult.evidence)
        val certificate = assertNotNull(EvidenceCertificate.completed(identity, EvidenceKind.Witness,
            CompletedEngineResult.Solve(scoped, result)))

        val checked = certificate.verifyDerivation(SourceClaim.Witness(other), context)

        assertIs<SourceEvidenceCheck.Rejected>(checked)
    }

    @Test
    fun `an exhausted completion cannot verify a different source cutoff`() {
        val scoped = scope.copy(objective = SourceObjective("x", ObjectiveSense.MINIMIZE))
        val result = MinimizeResult.Unknown(TerminationReason.SearchExhausted)
        val cutoff = SourceClaim.Bound(BigFraction.ONE)
        val certificate = assertNotNull(EvidenceCertificate.completed(identity, EvidenceKind.Bound,
            CompletedEngineResult.Minimize(scoped, result, cutoff)))
        val context = SourceProofContext(scoped, emptySet(), emptyMap())

        val checked = certificate.verifyDerivation(SourceClaim.Bound(BigFraction.ofLong(2)), context)

        assertIs<SourceEvidenceCheck.Rejected>(checked)
    }

}
