package com.eignex.klause.bench

import com.eignex.klause.logicng.LogicNGParams
import com.eignex.klause.logicng.LogicNGSampler
import com.eignex.klause.solver.LocalSearchParams
import com.eignex.klause.solver.LocalSearchSolver
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult

/**
 * Type-erased wrapper over [com.eignex.klause.solver.Sampler] so the harness can hold a
 * heterogeneous list of backends in one collection. Each implementation pre-binds whatever
 * params data class its underlying sampler requires; callers don't need to care.
 */
interface BenchSampler {
    val name: String
    val problem: Problem
    fun solve(): SolveResult
    fun samples(n: Int): List<Sample>
    fun enumerated(n: Int): List<Sample>
}

class LocalSearchBench(
    override val problem: Problem,
    private val params: LocalSearchParams = LocalSearchParams(maxFlips = 100_000L, randomSeed = 0L),
) : BenchSampler {
    private val s = LocalSearchSolver(problem)
    override val name = "local-search"
    override fun solve() = s.solve(params)
    override fun samples(n: Int) = s.sample(params).take(n).toList()
    override fun enumerated(n: Int) = s.enumerate(params).take(n).toList()
}

class LogicNGBench(
    override val problem: Problem,
    private val params: LogicNGParams = LogicNGParams(),
) : BenchSampler {
    private val s = LogicNGSampler(problem)
    override val name = "logicng"
    override fun solve() = s.solve(params)
    override fun samples(n: Int) = s.sample(params).take(n).toList()
    override fun enumerated(n: Int) = s.enumerate(params).take(n).toList()
}

/** All backends currently shipping with the harness. Z3 lands in step 3 of the plan. */
fun defaultSamplers(problem: Problem): List<BenchSampler> = listOf(
    LocalSearchBench(problem),
    LogicNGBench(problem),
)
