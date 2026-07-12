package com.eignex.klause.localsearch

import com.eignex.klause.presolve.Presolve
import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.Problem
import com.eignex.klause.util.IntArrayList

/**
 * Implicit-solving setup for an ongoing solve: the elected structured globals, the scope-disjoint
 * seed set, the per-int-var owner map, and the binary-implication graph. All built lazily on first
 * access and paid once per solve rather than per move.
 */
class ImplicitSeeding(
    private val problem: Problem,
    /** Free-Boolean probe cap for [implicationGraph]. */
    private val maxImplicationCandidates: Int = IMPLICATION_SEED_MAX_CANDIDATES,
) {

    /** Factor ids elected for implicit-solving structured neighbourhoods — structural globals whose
     *  [Invariant.proposeStructuredMoves] preserves their own feasibility (see
     *  [Invariant.providesImplicitNeighbourhood]). The engine draws their feasibility-preserving moves
     *  even during infeasibility, and seeds them feasible at search start. Built once on first
     *  access. */
    val electedImplicit: IntArray by lazy { electImplicitFactors() }

    /** Scope-disjoint subset of [electedImplicit] for feasible-init seeding: greedily chosen
     *  largest-scope-first so no two seed factors share an int variable. Disjointness guarantees one
     *  factor's [Invariant.seedFeasible] never overwrites another's seeded vars, so the post-seed
     *  assignment satisfies every seeded global simultaneously. */
    val implicitSeedFactors: IntArray by lazy { electImplicitSeedSet() }

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
        Presolve.implicationGraph(problem, maxImplicationCandidates, Cancellation.Never)
    }

    private fun electImplicitFactors(): IntArray {
        val out = IntArrayList()
        for (id in 0 until problem.numFactors) {
            if (problem.invariants[id].providesImplicitNeighbourhood) out.add(id)
        }
        return IntArray(out.size) { out[it] }
    }

    private fun electImplicitSeedSet(): IntArray {
        // Largest scope first to seed the most variables; ties broken by factor id for determinism
        // (election must be reproducible, so the RNG never enters it).
        val candidates = electedImplicit.sortedWith(
            compareByDescending<Int> { problem.factors[it].intVars.size }.thenBy { it },
        )
        val owned = BooleanArray(problem.numIntVars)
        val seeds = IntArrayList()
        for (id in candidates) {
            val scope = problem.factors[id].intVars
            var disjoint = true
            for (v in scope) {
                if (owned[v]) {
                    disjoint = false
                    break
                }
            }
            if (!disjoint) continue
            for (v in scope) owned[v] = true
            seeds.add(id)
        }
        return IntArray(seeds.size) { seeds[it] }
    }

    /** Implicit-solving feasible init: seed every [implicitSeedFactors] global into a satisfying
     *  configuration (skipping vars frozen by [state]'s assumptions). Caller is responsible for the
     *  subsequent recompute. */
    internal fun seedImplicitFeasible(state: LocalSearchState) {
        val seeds = implicitSeedFactors
        if (seeds.isEmpty()) return
        val owners = ownerInt ?: IntArray(problem.numIntVars) { -1 }
        owners.fill(-1)
        for (i in seeds.indices) {
            val fid = seeds[i]
            // Own a global's variables only when it actually seeded feasible: a failed seed (e.g. an
            // all-different with no perfect matching) leaves its vars infeasible, so they must stay in
            // the generic neighbourhood to be repaired rather than be frozen out as "implicitly solved".
            if (problem.invariants[fid].seedFeasible(state, fid)) {
                for (v in problem.factors[fid].intVars) owners[v] = fid
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
