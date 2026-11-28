package com.eignex.klause.localsearch

import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.propagatedImplicationGraph
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.collections.IntArrayList

/**
 * Lazily built implicit-solving setup for one solve: elected structured globals, the scope-disjoint seed set, the
 * int-var owner map and the binary-implication graph.
 */
class ImplicitSeeding(
    private val problem: Problem,
    private val projection: LocalSearchProblem = LocalSearchProblem(problem),
    private val maxImplicationCandidates: Int = IMPLICATION_SEED_MAX_CANDIDATES,
) {

    private var preparedElected: IntArray? = null
    private var preparedSeedFactors: IntArray? = null

    /** Factors whose structured moves preserve their own feasibility ([Invariant.providesImplicitNeighbourhood]);
     *  the engine draws those moves even while infeasible and seeds them feasible at start. */
    val electedImplicit: IntArray
        get() = preparedElected ?: electImplicitFactors().also { preparedElected = it }

    /** Largest-scope-first subset of [electedImplicit] sharing no int variable, so seeding one never overwrites
     *  another and the seeded assignment satisfies all of them. */
    val implicitSeedFactors: IntArray
        get() {
            seedSetSteps().forEach { }
            return checkNotNull(preparedSeedFactors)
        }

    /** `ownerInt(v)` is the factor owning int var `v`, or `-1` when `v` is searched freely. Once its global is seeded
     *  feasibly, only that global's structured moves change it. Null until the first seeding pass. */
    var ownerInt: IntArray? = null
        private set

    /** Literal-indexed implication graph: `graph(Lit.make(v, value))` lists the literals pinning `v = value` forces.
     *  Probing covers the first [maxImplicationCandidates] free Booleans; later seeds find none. */
    val implicationGraph: Array<IntArray> by lazy {
        problem.propagatedImplicationGraph(maxImplicationCandidates, ExecutionControl.Never)
    }

    private fun electImplicitFactors(): IntArray {
        val out = IntArrayList()
        for (id in 0 until problem.numFactors) {
            if (projection.invariants[id].providesImplicitNeighbourhood) out.add(id)
        }
        return IntArray(out.size) { out[it] }
    }

    private fun seedSetSteps(): Sequence<Unit> = sequence {
        if (preparedSeedFactors != null) return@sequence
        val elected = IntArrayList()
        for (id in 0 until problem.numFactors) {
            if (projection.invariants[id].providesImplicitNeighbourhood) elected.add(id)
            if ((id + 1) % INITIALIZATION_BATCH == 0) yield(Unit)
        }
        val electedIds = elected.toIntArray()
        yield(Unit)
        var candidates = electedIds.copyOf()
        yield(Unit)
        var sorted = IntArray(candidates.size)
        yield(Unit)
        var visits = 0
        var width = 1
        // Stable merging retains largest-scope-first election and factor-id tie order.
        while (width < candidates.size) {
            var from = 0
            while (from < candidates.size) {
                val mid = minOf(from.toLong() + width, candidates.size.toLong()).toInt()
                val end = minOf(mid.toLong() + width, candidates.size.toLong()).toInt()
                var left = from
                var right = mid
                for (out in from until end) {
                    val takeLeft = right == end || (left < mid &&
                        problem.factors[candidates[left]].intVars.size >=
                        problem.factors[candidates[right]].intVars.size)
                    sorted[out] = if (takeLeft) candidates[left++] else candidates[right++]
                    if (++visits % INITIALIZATION_BATCH == 0) yield(Unit)
                }
                from = end
            }
            val swap = candidates
            candidates = sorted
            sorted = swap
            width = minOf(width.toLong() * 2, candidates.size.toLong()).toInt()
        }
        val owned = BooleanArray(problem.numIntVars)
        yield(Unit)
        val seeds = IntArrayList()
        for (id in candidates) {
            if (++visits % INITIALIZATION_BATCH == 0) yield(Unit)
            val scope = problem.factors[id].intVars
            var disjoint = true
            for (v in scope) {
                if (++visits % INITIALIZATION_BATCH == 0) yield(Unit)
                if (owned[v]) {
                    disjoint = false
                    break
                }
            }
            if (!disjoint) continue
            for (v in scope) {
                owned[v] = true
                if (++visits % INITIALIZATION_BATCH == 0) yield(Unit)
            }
            seeds.add(id)
        }
        val selected = seeds.toIntArray()
        preparedElected = electedIds
        preparedSeedFactors = selected
    }

    internal fun seedImplicitFeasible(state: LocalSearchState) {
        seedSteps(state).forEach { }
    }

    internal fun seedSteps(state: LocalSearchState): Sequence<Unit> = sequence {
        yieldAll(seedSetSteps())
        val seeds = checkNotNull(preparedSeedFactors)
        if (seeds.isEmpty()) return@sequence
        state.boardCoverage.invalidate()
        val owners = ownerInt ?: IntArray(problem.numIntVars)
        yield(Unit)
        for (from in owners.indices step INITIALIZATION_BATCH) {
            owners.fill(-1, from, minOf(from + INITIALIZATION_BATCH, owners.size))
            yield(Unit)
        }
        for (fid in seeds) {
            yield(Unit)
            // A failed seed leaves its coordinates searchable by ordinary repair.
            if (projection.invariants[fid].seedFeasible(state, fid)) {
                val scope = problem.factors[fid].intVars
                for (i in scope.indices) {
                    owners[scope[i]] = fid
                    if ((i + 1) % INITIALIZATION_BATCH == 0) yield(Unit)
                }
            }
        }
        ownerInt = owners
        state.moveSink.setOwners(owners)
    }
}

// Each candidate costs up to two propagations, and an uncapped build materializes an O(numBoolVars²) adjacency.
private const val IMPLICATION_SEED_MAX_CANDIDATES = 2_048
