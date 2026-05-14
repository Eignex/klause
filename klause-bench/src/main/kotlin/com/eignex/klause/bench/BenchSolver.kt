package com.eignex.klause.bench

import com.eignex.klause.logicng.LogicNGParams
import com.eignex.klause.logicng.LogicNGSolver
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.brute.BruteForceParams
import com.eignex.klause.solver.brute.BruteForceSolver
import com.eignex.klause.solver.localsearch.LocalSearchParams
import com.eignex.klause.solver.localsearch.LocalSearchSolver
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.z3.Z3Params
import com.eignex.klause.z3.Z3Solver

/** Type-erased sampler wrapper for the harness. Each impl pre-binds its params. */
interface BenchSolver {
    val name: String
    val problem: Problem
    fun solve(): SolveResult
    fun samples(n: Int): List<Sample>
    fun enumerated(n: Int): List<Sample>
}

class LocalSearchBench(
    override val problem: Problem,
    private val params: LocalSearchParams = LocalSearchParams(maxFlips = 10_000L, randomSeed = 0L),
) : BenchSolver {
    private val s = LocalSearchSolver(problem)
    override val name = "local-search"
    override fun solve() = s.solve(params)
    override fun samples(n: Int) = s.samples(params).take(n).toList()
    override fun enumerated(n: Int) = s.enumerate(params).take(n).toList()
}

class LogicNGBench(
    override val problem: Problem,
    private val params: LogicNGParams = LogicNGParams(),
) : BenchSolver {
    private val s = LogicNGSolver(problem)
    override val name = "logicng"
    override fun solve() = s.solve(params)
    override fun samples(n: Int) = s.samples(params).take(n).toList()
    override fun enumerated(n: Int) = s.enumerate(params).take(n).toList()
}

class Z3Bench(
    override val problem: Problem,
    private val params: Z3Params = Z3Params(),
) : BenchSolver {
    private val s = Z3Solver(problem)
    override val name = "z3"
    override fun solve() = s.solve(params)
    override fun samples(n: Int) = s.samples(params).take(n).toList()
    override fun enumerated(n: Int) = s.enumerate(params).take(n).toList()
}

class BacktrackBench(
    override val problem: Problem,
    private val params: BacktrackParams = BacktrackParams(maxDecisions = 100_000L, randomSeed = 0L),
) : BenchSolver {
    private val s = BacktrackSolver(problem)
    override val name = "backtrack"
    override fun solve() = s.solve(params)
    override fun samples(n: Int) = s.samples(params).take(n).toList()
    override fun enumerated(n: Int) = s.enumerate(params).take(n).toList()
}

class BruteForceBench(
    override val problem: Problem,
    private val params: BruteForceParams = BruteForceParams(randomSeed = 0L),
) : BenchSolver {
    private val s = BruteForceSolver(problem)
    override val name = "brute-force"
    override fun solve() = s.solve(params)
    override fun samples(n: Int) = s.samples(params).take(n).toList()
    override fun enumerated(n: Int) = s.enumerate(params).take(n).toList()
}

/** Brute force is added only when the assignment space fits — see [BruteForceSolver.fits]. */
fun defaultSolvers(problem: Problem): List<BenchSolver> = buildList {
    add(LocalSearchBench(problem))
    add(BacktrackBench(problem))
    add(LogicNGBench(problem))
    add(Z3Bench(problem))
    if (BruteForceSolver.fits(problem)) add(BruteForceBench(problem))
}
