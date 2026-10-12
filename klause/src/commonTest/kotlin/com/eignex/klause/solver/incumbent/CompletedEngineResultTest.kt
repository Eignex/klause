package com.eignex.klause.solver.incumbent

import com.eignex.klause.portfolio.PortfolioWorker
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceEvidenceCheck
import com.eignex.klause.solver.evidence.SourceInequality
import com.eignex.klause.solver.evidence.SourcePremise
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.solver.evidence.SourceScope
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
}
