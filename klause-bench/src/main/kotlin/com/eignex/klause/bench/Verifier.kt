package com.eignex.klause.bench

import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.SolverState
import kotlin.random.Random

/** Per-backend solve verdicts + per-sample satisfaction checks for one [Problem]. */
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
    fun verify(
        problem: Problem,
        solvers: List<BenchSolver> = defaultSolvers(problem),
        sampleCount: Int = 5,
    ): VerificationReport {
        val verdicts = solvers.associate { it.name to it.solve() }
        val agreement = computeAgreement(verdicts)
        val sampleChecks = solvers.associate { sampler ->
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
        val state = SolverState(problem, Random(0))
        for (b in 0 until problem.numBoolVars) state.assignment.setBool(b, sample.bools[b])
        for (i in 0 until problem.numIntVars) state.assignment.setInt(i, sample.ints[i])
        state.recompute()
        return state.cost == 0
    }
}
