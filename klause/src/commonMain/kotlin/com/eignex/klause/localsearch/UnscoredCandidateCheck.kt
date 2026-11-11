package com.eignex.klause.localsearch

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.AssignmentCheckCapability
import com.eignex.klause.solver.PreparedFactorProjections
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.executionCapabilities
import com.eignex.klause.solver.incumbent.Candidate
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.validation.FinitePointCheck
import com.eignex.klause.util.Cancellation

internal class UnscoredCandidateCheck(
    problem: Problem,
    private val selection: PreparedFactorProjections? = (problem as? BakedProblem)?.factorProjections,
    enforcingPeers: Set<Int> = emptySet(),
) {
    private val factorIds = problem.factors.indices.filter {
        it !in enforcingPeers &&
            ((selection?.capabilities(it) ?: problem.factors[it].executionCapabilities()).assignmentCheck ==
                AssignmentCheckCapability.UNSUPPORTED || selection?.requiresOriginalScoringCheck(it) == true)
    }.toIntArray()
    private val unchecked = if (factorIds.isEmpty()) null else
        problem.withFactors(Array(factorIds.size) { problem.factors[factorIds[it]] })
    private val uncheckedSelection = unchecked?.let { selection?.projected(it, factorIds) }
    val required: Boolean get() = unchecked != null
    val requiresReals: Boolean = factorIds.any { problem.factors[it].variables.reals.isNotEmpty() }

    // A baked source has already materialized its closed domains, so one bake of the unchecked view serves every
    // candidate. Open declarations get singleton domains per candidate instead.
    private val reusable = problem is BakedProblem &&
        (0 until problem.numIntVars).all { problem.intBounds.hasLower(it) && problem.intBounds.hasUpper(it) }
    private var retained: FinitePointCheck? = null

    private fun withinDeclared(source: Problem, candidate: Sample): Boolean = (0 until source.numIntVars).all { id ->
        val value = candidate.intValue(id)
        value >= source.intBounds.lower(id) && value <= source.intBounds.upper(id) &&
            source.intDomainOrNull(id)?.let { value in it } != false
    }

    private fun retainedCheck(source: Problem, cancellation: Cancellation): FinitePointCheck {
        retained?.let { return it }
        val baked = uncheckedSelection?.let { source.bake(it.forEngineView(source), cancellation) }
            ?: source.bake(cancellation)
        // A cancelled bake keeps sound but weaker root deductions; the next candidate bakes again.
        return FinitePointCheck(baked).also { if (!cancellation()) retained = it }
    }

    fun check(candidate: Sample, assumptions: Assumptions, cancellation: Cancellation): Completion {
        val source = unchecked ?: return Completion.Witness(candidate)
        if (cancellation()) return Completion.Undecided()
        val points = if (reusable && withinDeclared(source, candidate)) retainedCheck(source, cancellation) else {
            // Singleton integer declarations avoid materializing an open source domain, and leave a value outside
            // the declared domains to the factors alone.
            val point = source.withIntDomains(Array(source.numIntVars) {
                IntDomain(candidate.intValue(it), candidate.intValue(it))
            })
            FinitePointCheck(uncheckedSelection?.let { point.bake(it.forEngineView(point), cancellation) } ?: point)
        }
        val checked = points.check(Candidate(candidate, Unit), cancellation, assumptions)
        if (cancellation()) return Completion.Undecided()
        return when (checked) {
            is Verification.Accepted -> Completion.Witness(candidate)
            is Verification.Rejected -> Completion.Refuted(factorIds)
            is Verification.Indeterminate -> Completion.Undecided()
        }
    }
}
