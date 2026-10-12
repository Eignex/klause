package com.eignex.klause.localsearch

import com.eignex.klause.backtrack.composedFixpoint
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.solver.AssignmentCheckCapability
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.executionCapabilities
import com.eignex.klause.solver.incumbent.Candidate
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.util.Cancellation

internal class UnscoredCandidateCheck(problem: Problem) {
    private val factorIds = problem.factors.indices.filter {
        problem.factors[it].executionCapabilities().assignmentCheck == AssignmentCheckCapability.UNSUPPORTED
    }.toIntArray()
    private val unchecked = if (factorIds.isEmpty()) null else
        problem.withFactors(Array(factorIds.size) { problem.factors[factorIds[it]] })
    val required: Boolean get() = unchecked != null

    fun check(candidate: Sample, assumptions: Assumptions, cancellation: Cancellation): Completion {
        val source = unchecked ?: return Completion.Witness(candidate)
        if (cancellation()) return Completion.Undecided()
        // Singleton integer declarations avoid materializing an open or wide source domain.
        val point = source.withIntDomains(Array(source.numIntVars) {
            IntDomain(candidate.intValue(it), candidate.intValue(it))
        })
        val checked = composedFixpoint(point, Candidate(candidate, Unit), cancellation, assumptions)
        if (cancellation()) return Completion.Undecided()
        return when (checked) {
            is Verification.Accepted -> Completion.Witness(candidate)
            is Verification.Rejected -> Completion.Refuted(factorIds)
            is Verification.Indeterminate -> Completion.Undecided()
        }
    }
}
