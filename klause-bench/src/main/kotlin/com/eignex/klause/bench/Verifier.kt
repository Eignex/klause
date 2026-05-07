package com.eignex.klause.bench

import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.SolverState
import kotlin.random.Random

/**
 * Outcome of running every backend in [BenchSampler] on one [Problem]:
 *
 *  - [verdicts] — what each backend's `solve()` returned.
 *  - [agreement] — `Agree` if every exact backend (LogicNG / Z3) returns the same Sat/Unsat
 *    verdict; `Disagree` if two exact backends contradict; `OnlyLocalSearch` if the only
 *    response was an LS `Unknown` (nothing to compare against).
 *  - [sampleChecks] — per backend, for each sample we asked the backend to produce, whether
 *    it actually satisfies the problem. A `false` here is always a bug in that backend.
 */
data class VerificationReport(
    val problem: Problem,
    val verdicts: Map<String, SolveResult>,
    val agreement: Agreement,
    val sampleChecks: Map<String, List<SampleCheck>>,
) {
    val allSamplesSatisfy: Boolean
        get() = sampleChecks.values.all { it.all { check -> check.satisfies } }
}

enum class Agreement { Agree, Disagree, OnlyLocalSearch }

data class SampleCheck(val sample: Sample, val satisfies: Boolean)

object Verifier {
    /**
     * Run every backend, collect verdicts and sample-validity checks, return a report.
     * Doesn't throw on disagreement — caller decides how to react. [sampleCount] is how
     * many `samples()` (with-replacement) draws to verify per backend.
     */
    fun verify(
        problem: Problem,
        samplers: List<BenchSampler> = defaultSamplers(problem),
        sampleCount: Int = 5,
    ): VerificationReport {
        val verdicts = samplers.associate { it.name to it.solve() }
        val agreement = computeAgreement(verdicts)
        val sampleChecks = samplers.associate { sampler ->
            val produced = sampler.samples(sampleCount)
            sampler.name to produced.map { SampleCheck(it, satisfiesProblem(problem, it)) }
        }
        return VerificationReport(problem, verdicts, agreement, sampleChecks)
    }

    private fun computeAgreement(verdicts: Map<String, SolveResult>): Agreement {
        val exact = verdicts.values.filter { it !is SolveResult.Unknown }
        if (exact.isEmpty()) return Agreement.OnlyLocalSearch
        val firstSat = exact.first() is SolveResult.Sat
        val mixed = exact.any { (it is SolveResult.Sat) != firstSat }
        return if (mixed) Agreement.Disagree else Agreement.Agree
    }

    private fun satisfiesProblem(problem: Problem, sample: Sample): Boolean {
        // Build a SolverState in the sample's assignment, recompute, and read off cost.
        val state = SolverState(problem, Random(0))
        for (b in 0 until problem.numBoolVars) state.assignment.setBool(b, sample.bools[b])
        for (i in 0 until problem.numIntVars) state.assignment.setInt(i, sample.ints[i])
        state.recompute()
        return state.cost == 0
    }
}
