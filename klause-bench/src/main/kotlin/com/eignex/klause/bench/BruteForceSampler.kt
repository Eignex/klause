package com.eignex.klause.bench

import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.Sampler
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.SolverParams
import com.eignex.klause.solver.SolverState
import com.eignex.kpermute.LongPermutation
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
 * and yields every assignment that satisfies the problem.
 *
 * Spaces larger than [Long.MAX_VALUE] are supported by splitting variables into "chunks"
 * whose per-chunk product fits in a `Long`. Each chunk gets its own [LongPermutation]; the
 * walker traverses the cartesian product of chunk permutations as a nested loop. Within a
 * chunk the iteration is shuffled (the kpermute output); across chunks it's
 * lexicographic. For problems with many variables but few constraints, the satisfaction
 * density is high enough that a satisfying assignment falls out within a handful of inner
 * iterations regardless.
 */
class BruteForceSampler(override val problem: Problem) : Sampler<BruteForceParams> {

    private val chunks: List<Chunk> = buildChunks(problem)

    override fun solve(params: BruteForceParams): SolveResult {
        for (sample in walk(params.randomSeed ?: 0L)) {
            return SolveResult.Sat(sample)
        }
        return SolveResult.Unsat
    }

    /** With replacement: each yield is the first satisfying assignment of a freshly-seeded
     *  permutation walk. Different seeds give different "first hit" positions, so the
     *  sequence behaves like independent draws — duplicates can reappear, as the contract
     *  requires. */
    override fun sample(params: BruteForceParams): Sequence<Sample> = sequence {
        var seed = params.randomSeed ?: 0L
        while (true) {
            val first = walk(seed).firstOrNull() ?: return@sequence
            yield(first)
            seed += 0x9E3779B97F4A7C15uL.toLong()
        }
    }

    override fun enumerate(params: BruteForceParams): Sequence<Sample> = sequence {
        val window = ArrayDeque<Sample>()
        for (s in walk(params.randomSeed ?: 0L)) {
            if (farEnough(s, window, params.minHammingDistance)) {
                yield(s)
                if (params.recentWindow > 0) {
                    if (window.size >= params.recentWindow) window.removeFirst()
                    window.addLast(s)
                }
            }
        }
    }

    private fun walk(seed: Long): Sequence<Sample> {
        val state = SolverState(problem, Random(seed))
        if (chunks.isEmpty()) {
            // No variables — there's exactly one assignment to test.
            return sequence {
                state.recompute()
                if (state.hardCost == 0) yield(state.assignment.snapshot())
            }
        }
        // Use a different sub-seed per chunk so two chunks with the same size don't walk
        // in lock-step. Overflow is irrelevant — kpermute accepts any Long seed.
        val perms = chunks.mapIndexed { i, c ->
            longPermutation(size = c.totalSize, seed = seed + i * 0x9E3779B97F4A7C15uL.toLong())
        }
        return walkChunks(perms, depth = 0, state = state)
    }

    private fun walkChunks(
        perms: List<LongPermutation>,
        depth: Int,
        state: SolverState,
    ): Sequence<Sample> = sequence {
        if (depth == perms.size) {
            state.recompute()
            if (state.hardCost == 0) yield(state.assignment.snapshot())
            return@sequence
        }
        for (idx in perms[depth]) {
            applyChunkIndex(chunks[depth], idx, state)
            yieldAll(walkChunks(perms, depth + 1, state))
        }
    }

    /** Decode [idx] into the variables held by [chunk] and write them into [state]'s
     *  assignment. Mixed-radix: each dim consumes its radix from the running quotient. */
    private fun applyChunkIndex(chunk: Chunk, idx: Long, state: SolverState) {
        var remaining = idx
        for (dim in chunk.dims) {
            val digit = (remaining % dim.radix).toInt()
            remaining /= dim.radix
            when (dim.kind) {
                DimKind.BOOL -> state.assignment.setBool(dim.varId, digit == 1)
                DimKind.INT -> state.assignment.setInt(
                    dim.varId,
                    problem.intDomains[dim.varId].min + digit,
                )
            }
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

    // ---- chunked-space layout ----

    private enum class DimKind { BOOL, INT }
    private data class Dim(val kind: DimKind, val varId: Int, val radix: Long)
    private data class Chunk(val dims: List<Dim>, val totalSize: Long)

    companion object {
        /** Largest per-chunk product. Stays well below [Long.MAX_VALUE] so adding one more
         *  dim's worth of multiplication can't overflow even for radix = Int.MAX_VALUE. */
        const val CHUNK_CAP: Long = 1L shl 60

        /** Heuristic cap used by the bench harness's [fits] gate. Brute-force walking is
         *  exact, but exhausting a 10¹²-assignment space takes longer than the user wants
         *  to wait for a verification run. */
        const val DEFAULT_MAX_SPACE: Long = 1_000_000L

        /** True when the total assignment space fits the harness's heuristic cap. The
         *  sampler itself accepts any size; this is purely a gating decision for callers
         *  that want brute force only when it'll finish quickly. */
        fun fits(problem: Problem, cap: Long = DEFAULT_MAX_SPACE): Boolean {
            val size = computeSpaceSize(problem)
            return size in 1..cap
        }

        /** Total space size, or [Long.MAX_VALUE] if it overflows. */
        private fun computeSpaceSize(problem: Problem): Long {
            var size = 1L
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

        /** Build chunks sized so each per-chunk product stays under [CHUNK_CAP]. Bools
         *  come first, then ints — order doesn't affect correctness, only iteration
         *  shape. */
        private fun buildChunks(problem: Problem): List<Chunk> {
            val dims = ArrayList<Dim>(problem.numBoolVars + problem.numIntVars)
            for (b in 0 until problem.numBoolVars) dims.add(Dim(DimKind.BOOL, b, 2L))
            for (i in 0 until problem.numIntVars) {
                dims.add(Dim(DimKind.INT, i, problem.intDomains[i].size.toLong()))
            }
            val chunks = ArrayList<Chunk>()
            var current = ArrayList<Dim>()
            var currentSize = 1L
            for (dim in dims) {
                require(dim.radix in 1..CHUNK_CAP) {
                    "Variable radix ${dim.radix} (varId ${dim.varId}, kind ${dim.kind}) " +
                        "exceeds per-chunk cap $CHUNK_CAP"
                }
                if (currentSize > CHUNK_CAP / dim.radix) {
                    chunks.add(Chunk(current, currentSize))
                    current = ArrayList()
                    currentSize = 1L
                }
                current.add(dim)
                currentSize *= dim.radix
            }
            if (current.isNotEmpty()) chunks.add(Chunk(current, currentSize))
            return chunks
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
