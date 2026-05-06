package com.eignex.klause.bench

import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.Sampler
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.SolverParams
import com.eignex.klause.solver.SolverState
import com.eignex.kpermute.longPermutation
import kotlin.random.Random

/**
 * Per-call params for [BruteForceSampler]. The brute-force engine is exact, so the only
 * stochastic knob is [randomSeed] (controls the iteration order via kpermute). Distance /
 * window filtering applies on top, identical to other backends.
 */
data class BruteForceParams(
    val randomSeed: Long? = null,
    val minHammingDistance: Int = 1,
    val recentWindow: Int = 16,
) : SolverParams

/**
 * Ground-truth [Sampler] for testing. Walks the entire assignment space in a permuted order
 * (via kpermute's [longPermutation]) and yields every assignment that satisfies the problem.
 *
 * Use this to cross-check stochastic / SAT backends on small problems: any sample they
 * produce should be in this enumerator's output, and any verdict (Sat/Unsat) should match.
 *
 * Construction throws if the assignment space exceeds [maxSpaceSize] — the engine is O(N)
 * over the space and trivially refuses to enumerate billions of assignments.
 */
class BruteForceSampler(
    override val problem: Problem,
    private val maxSpaceSize: Long = DEFAULT_MAX_SPACE,
) : Sampler<BruteForceParams> {

    private val totalSize: Long = computeSpaceSize(problem)
    private val intRadices: LongArray = LongArray(problem.numIntVars) {
        problem.intDomains[it].size.toLong()
    }

    init {
        require(totalSize in 1..maxSpaceSize) {
            "BruteForceSampler space size $totalSize exceeds cap $maxSpaceSize " +
                "(boolVars=${problem.numBoolVars}, intVars=${problem.numIntVars})"
        }
    }

    override fun solve(params: BruteForceParams): SolveResult {
        for (sample in walkPermutation(params.randomSeed ?: 0L)) {
            return SolveResult.Sat(sample)
        }
        return SolveResult.Unsat
    }

    /** With replacement is the same walk as [enumerate] for a brute-force engine; the
     *  permutation already produces every satisfying assignment exactly once. */
    override fun sample(params: BruteForceParams): Sequence<Sample> =
        walkPermutation(params.randomSeed ?: 0L)

    override fun enumerate(params: BruteForceParams): Sequence<Sample> = sequence {
        val window = ArrayDeque<Sample>()
        for (s in walkPermutation(params.randomSeed ?: 0L)) {
            if (farEnough(s, window, params.minHammingDistance)) {
                yield(s)
                if (params.recentWindow > 0) {
                    if (window.size >= params.recentWindow) window.removeFirst()
                    window.addLast(s)
                }
            }
        }
    }

    /** All satisfying assignments, in a permuted order keyed by [seed]. */
    private fun walkPermutation(seed: Long): Sequence<Sample> = sequence {
        val perm = longPermutation(size = totalSize, seed = seed)
        val state = SolverState(problem, Random(seed))
        for (idx in perm) {
            decodeIntoState(idx, state)
            state.recompute()
            if (state.hardCost == 0) yield(state.assignment.snapshot())
        }
    }

    private fun decodeIntoState(idx: Long, state: SolverState) {
        var remaining = idx
        for (b in 0 until problem.numBoolVars) {
            state.assignment.setBool(b, (remaining and 1L) == 1L)
            remaining = remaining ushr 1
        }
        for (i in 0 until problem.numIntVars) {
            val radix = intRadices[i]
            val digit = (remaining % radix).toInt()
            state.assignment.setInt(i, problem.intDomains[i].min + digit)
            remaining /= radix
        }
    }

    private fun farEnough(candidate: Sample, window: ArrayDeque<Sample>, minDistance: Int): Boolean {
        if (minDistance <= 0 || window.isEmpty()) return true
        for (p in window) if (hamming(candidate, p) < minDistance) return false
        return true
    }

    private fun hamming(a: Sample, b: Sample): Int {
        var d = 0
        for (i in a.bools.indices) if (a.bools[i] != b.bools[i]) d++
        for (i in a.ints.indices) if (a.ints[i] != b.ints[i]) d++
        return d
    }

    companion object {
        const val DEFAULT_MAX_SPACE: Long = 1_000_000L

        fun fits(problem: Problem, cap: Long = DEFAULT_MAX_SPACE): Boolean {
            val size = computeSpaceSize(problem)
            return size in 1..cap
        }

        private fun computeSpaceSize(problem: Problem): Long {
            var size = 1L
            // 2^numBoolVars
            repeat(problem.numBoolVars) {
                if (size > Long.MAX_VALUE / 2) return Long.MAX_VALUE
                size *= 2
            }
            for (d in problem.intDomains) {
                val r = d.size.toLong()
                if (r <= 0) return Long.MAX_VALUE
                if (size > Long.MAX_VALUE / r) return Long.MAX_VALUE
                size *= r
            }
            return size
        }
    }
}

/** [BenchSampler] adapter so the brute-force enumerator joins the harness alongside LS / LogicNG / Z3. */
class BruteForceBench(
    override val problem: Problem,
    private val params: BruteForceParams = BruteForceParams(randomSeed = 0L),
) : BenchSampler {
    private val s = BruteForceSampler(problem)
    override val name = "brute-force"
    override fun solve() = s.solve(params)
    override fun samples(n: Int) = s.sample(params).take(n).toList()
    override fun enumerated(n: Int) = s.enumerate(params).take(n).toList()
}
