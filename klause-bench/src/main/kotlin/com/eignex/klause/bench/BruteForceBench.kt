package com.eignex.klause.bench

import com.eignex.klause.solver.BruteForceParams
import com.eignex.klause.solver.BruteForceSampler
import com.eignex.klause.solver.Problem

/** [BenchSampler] adapter so the brute-force enumerator joins the harness alongside
 *  LS / LogicNG / Z3. The actual engine lives in `:klause` core; this file is the
 *  bench-side wrapper that erases the params type. */
class BruteForceBench(
    override val problem: Problem,
    private val params: BruteForceParams = BruteForceParams(randomSeed = 0L),
) : BenchSampler {
    private val s = BruteForceSampler(problem)
    override val name = "brute-force"
    override fun solve() = s.solve(params)
    override fun samples(n: Int) = s.samples(params).take(n).toList()
    override fun enumerated(n: Int) = s.enumerate(params).take(n).toList()
}
