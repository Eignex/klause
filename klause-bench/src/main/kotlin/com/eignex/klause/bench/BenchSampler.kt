package com.eignex.klause.bench

import com.eignex.klause.logicng.LogicNGParams
import com.eignex.klause.logicng.LogicNGSampler
import com.eignex.klause.solver.LocalSearchParams
import com.eignex.klause.solver.LocalSearchSolver
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.z3.Z3Params
import com.eignex.klause.z3.Z3Sampler

/** Type-erased sampler wrapper for the harness. Each impl pre-binds its params. */
interface BenchSampler {
    val name: String
    val problem: Problem
    fun solve(): SolveResult
    fun samples(n: Int): List<Sample>
    fun enumerated(n: Int): List<Sample>
}

class LocalSearchBench(
    override val problem: Problem,
    private val params: LocalSearchParams = LocalSearchParams(maxFlips = 10_000L, randomSeed = 0L),
) : BenchSampler {
    private val s = LocalSearchSolver(problem)
    override val name = "local-search"
    override fun solve() = s.solve(params)
    override fun samples(n: Int) = s.samples(params).take(n).toList()
    override fun enumerated(n: Int) = s.enumerate(params).take(n).toList()
}

class LogicNGBench(
    override val problem: Problem,
    private val params: LogicNGParams = LogicNGParams(),
) : BenchSampler {
    private val s = LogicNGSampler(problem)
    override val name = "logicng"
    override fun solve() = s.solve(params)
    override fun samples(n: Int) = s.samples(params).take(n).toList()
    override fun enumerated(n: Int) = s.enumerate(params).take(n).toList()
}

class Z3Bench(
    override val problem: Problem,
    private val params: Z3Params = Z3Params(),
) : BenchSampler {
    private val s = Z3Sampler(problem)
    override val name = "z3"
    override fun solve() = s.solve(params)
    override fun samples(n: Int) = s.samples(params).take(n).toList()
    override fun enumerated(n: Int) = s.enumerate(params).take(n).toList()
}

/** Brute force is added only when the assignment space fits — see [BruteForceSolver.fits]. */
fun defaultSamplers(problem: Problem): List<BenchSampler> = buildList {
    add(LocalSearchBench(problem))
    add(LogicNGBench(problem))
    add(Z3Bench(problem))
    if (com.eignex.klause.solver.BruteForceSolver.fits(problem)) add(BruteForceBench(problem))
}
