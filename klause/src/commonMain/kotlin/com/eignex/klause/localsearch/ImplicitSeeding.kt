package com.eignex.klause.localsearch

import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.propagatedImplicationGraph
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.IntArrayList

/**
 * Implicit-solving setup for an ongoing solve: the elected structured globals, the scope-disjoint
 * seed set, the per-int-var owner map, and the binary-implication graph. All built lazily on first
 * access and paid once per solve rather than per move.
 */
class ImplicitSeeding(
    private val problem: Problem,
    private val projection: LocalSearchProblem = LocalSearchProblem(problem),
    /** Free-Boolean probe cap for [implicationGraph]. */
    private val maxImplicationCandidates: Int = IMPLICATION_SEED_MAX_CANDIDATES,
) {

    /** Factor ids elected for implicit-solving structured neighbourhoods — structural globals whose
     *  [Invariant.proposeStructuredMoves] preserves their own feasibility (see
     *  [Invariant.providesImplicitNeighbourhood]). The engine draws their feasibility-preserving moves
     *  even during infeasibility, and seeds them feasible at search start. Built once on first
     *  access. */
    private var preparedElected: IntArray? = null
    val electedImplicit: IntArray
        get() = preparedElected ?: electImplicitFactors().also { preparedElected = it }

    /** Scope-disjoint subset of [electedImplicit] for feasible-init seeding: greedily chosen
     *  largest-scope-first so no two seed factors share an int variable. Disjointness guarantees one
     *  factor's [Invariant.seedFeasible] never overwrites another's seeded vars, so the post-seed
     *  assignment satisfies every seeded global simultaneously. */
    private var preparedSeedFactors: IntArray? = null
    val implicitSeedFactors: IntArray
        get() {
            seedSetSteps().forEach { }
            return checkNotNull(preparedSeedFactors)
        }

    /** Implicit-solving owner map over int vars: `ownerInt[v]` is the factor id that owns int var
     *  `v`, or `-1` if `v` is searched freely. A variable is owned once [seedImplicitFeasible] seeds
     *  its [implicitSeedFactors] global feasibly; from then on only that global's structure-preserving
     *  [Invariant.proposeStructuredMoves] may change it, so the generic neighbourhood can never break
     *  the implicitly-solved constraint. `null` until the first seeding pass, and only ever populated
     *  when implicit feasible-init is enabled — so a search that does not seed implicitly is
     *  unaffected. The [MoveSink] enforces the filter via [MoveSink.setOwners]. */
    var ownerInt: IntArray? = null
        private set

    /** Binary-implication graph of [problem], literal-indexed at `2·numBoolVars`: `graph[Lit.make(v,
     *  value)]` lists every literal that pinning `v = value` forces (sound, from probing-style
     *  propagation). Built once on first access — the implication-aware move sources
     *  ([com.eignex.klause.localsearch.movesource.FlipAndPropagate]) bundle a flip's forced
     *  literals into one atomic move. Probing is bounded to [maxImplicationCandidates] free Booleans
     *  (in id order), mirroring the presolve implication-graph pass: an uncapped `numBoolVars` harvest
     *  materialises an O(numBoolVars²) adjacency that exhausts the heap on Boolean-heavy models. Seeds
     *  past the cap simply find no forced literals, so the move source degrades to lone flips. */
    val implicationGraph: Array<IntArray> by lazy {
        problem.propagatedImplicationGraph(maxImplicationCandidates, Cancellation.Never)
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
                        problem.factors[candidates[left]].intVars.size >= problem.factors[candidates[right]].intVars.size)
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

    /** Implicit-solving feasible init; costs are reconciled by the caller. */
    internal fun seedImplicitFeasible(state: LocalSearchState) {
        seedSteps(state).forEach { }
    }

    internal fun seedSteps(state: LocalSearchState): Sequence<Unit> = sequence {
        yieldAll(seedSetSteps())
        val seeds = checkNotNull(preparedSeedFactors)
        if (seeds.isEmpty()) return@sequence
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

/** Free-Boolean probe cap for [ImplicitSeeding.implicationGraph], mirroring the presolve
 *  implication-graph pass: each candidate costs up to two `propagate` calls to harvest its outgoing
 *  implications, and the uncapped full-`numBoolVars` build materialised an O(numBoolVars²) adjacency
 *  that OOM'd on Boolean-heavy CSP instances (only [com.eignex.klause.localsearch.movesource.FlipAndPropagate]
 *  forces the build). */
private const val IMPLICATION_SEED_MAX_CANDIDATES = 2_048
