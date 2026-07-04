package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.lp.LinearRow
import com.eignex.klause.model.PbOp
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.StructuralKey
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet
import com.eignex.klause.util.MutableIntIntMap
import com.eignex.kumulant.math.splitmix64

/** Marks a bool var id into a range disjoint from int var ids in [RedundantConstraints.shallowKey]'s
 *  variable-set sum, so a bool var and an int var of the same id don't cancel or alias. */
private const val BOOL_VAR_MARK = 1L shl 40

/** Opaque handle for the incremental round engine's persistent subsume state, carried across the module
 *  boundary by [com.eignex.klause.presolve.PresolveContext]. Its implementation lives in this file. */
interface SubsumeState

internal object RedundantConstraints {

    /**
     * Subsumption entry point. On the fresh path (no [incremental]) it recomputes from scratch. Given a
     * [SubsumeIncremental] it maintains the phase-1/2 indices across rounds in [SubsumeIncremental.memo],
     * reprocessing only the factors added / dropped since the pass last ran — the whole-set rescan of a
     * fruitless re-run (the ma-path-finding #937 cost) becomes O(delta). Phases 3–5 run over the phase-2
     * survivor list either way.
     */
    fun removeRedundantConstraints(problem: Problem, incremental: SubsumeIncremental? = null): PassDelta {
        if (incremental == null) return computeFull(problem)
        val out = incremental.memo.reconcile(problem, incremental)
        val delta = finishAfterPhase2(problem, out)
        // Phases 3–5 drop factors [reconcile] never saw; feed the full drop set back so the memo retracts
        // every dropped factor's index entry before the next firing.
        incremental.memo.setPendingSelfDrops(delta.droppedIndices.map { problem.factors[it] })
        if (SUBSUME_DIFFERENTIAL_CHECK) assertMatchesFull(problem, delta)
        return delta
    }

    // Flip on to validate the incremental path: every firing also recomputes the full delta and asserts
    // the two agree, so a divergence throws on the exact instance/round instead of surfacing as a wrong
    // presolve output. Off in production (the full recompute defeats the incremental win).
    private const val SUBSUME_DIFFERENTIAL_CHECK = false

    private fun assertMatchesFull(problem: Problem, delta: PassDelta) {
        val full = computeFull(problem)
        val a = delta.droppedIndices.toHashSet()
        val b = full.droppedIndices.toHashSet()
        require(a == b) { "incremental subsume delta $a != full $b (nfac=${problem.factors.size})" }
    }

    /**
     * Constraint subsumption / redundant-constraint removal (#447): drop a constraint implied by
     * another retained one, preserving the feasible set exactly. Two mechanisms:
     *
     *  1. **Exact duplicates** — any factor whose [Factor.structuralKey] equals an earlier kept one is
     *     redundant (the keys are collision-free up to variable identity, so an equal key means an
     *     equal constraint). Unkeyed factors (`null` key) are never matched and always kept.
     *  2. **Same-vector domination** — over the [Linear] / [PseudoBoolean] inequalities, normalising
     *     each `≥` to a `≤` (negating coefficients and bound) and GCD-reducing it, constraints sharing
     *     a reduced coefficient vector are comparable: the tightest (smallest `≤` bound) implies the
     *     rest, so only it is kept. An `=` over the same vector contributes its bound to *both*
     *     directions and implies (drops) any looser `≤` / `≥`, but is itself never dropped here.
     *  3. **Variable-subset / proportional domination** ([dropSubsetDominated], #466) — a `≤`-row whose
     *     support is a strict subset of another's, with coefficients a positive multiple of it on the
     *     shared variables and a bound that implies the larger row's (after charging the extra terms
     *     their maximal activity), drops the larger row across *different* supports.
     *
     * The GCD reduction in step 2 makes the pass effective standalone — proportional rows match even
     * when [CoefficientStrengthening.strengthenCoefficients] hasn't run first. Self-redundant rows
     * (maximal activity already within the bound) are dropped by the strengthen lift, so this pass is
     * purely cross-constraint.
     */
    private fun computeFull(problem: Problem): PassDelta {
        val factors = problem.factors
        // Phase 1: exact-duplicate removal by structural key, two-tier so the full key — which for a
        // Table embeds its entire sorted tuple set and dominates presolve time on table-heavy models —
        // is built only when it can matter. A cheap shallow key (factor type + arity + variable ids,
        // never the tuple payload) buckets the factors; the full [Factor.structuralKey] is computed and
        // compared only for factors that collide on the shallow key (true duplicates always do). A
        // factor with a unique shallow key — the common case — is kept without ever building its key.
        val deduped = ArrayList<Factor>(factors.size)
        val firstByShallow = HashMap<Long, Factor>()
        val keysByShallow = HashMap<Long, HashSet<StructuralKey>>()
        for (f in factors) {
            val sk = shallowKey(f)
            val first = firstByShallow[sk]
            if (first == null) {
                // First factor with this shallow key — no kept factor can be a duplicate of it, so keep
                // it without ever building its (possibly huge) full key.
                firstByShallow[sk] = f
                deduped.add(f)
                continue
            }
            // Shallow collision: now the full keys decide. Seed the set with the first factor's key
            // (built once here, not on every comparison), then keep f only if its key is new.
            val keys = keysByShallow.getOrPut(sk) { hashSetOf(first.structuralKey()) }
            if (keys.add(f.structuralKey())) deduped.add(f)
        }
        // Phase 2: bucket the ≤-normalised Linear inequalities by coefficient vector; the bucket's
        // tightest bound (and whether an `=` provides it) decides which inequalities are implied.
        val bucketMin = HashMap<TermKey, Long>()
        val bucketEqAtMin = HashMap<TermKey, Boolean>()
        fun offer(key: TermKey, bound: Long, fromEq: Boolean) {
            val cur = bucketMin[key]
            if (cur == null || bound < cur) {
                bucketMin[key] = bound
                bucketEqAtMin[key] = fromEq
            } else if (bound == cur && fromEq) {
                bucketEqAtMin[key] = true
            }
        }
        for (f in deduped) {
            // Any factor with an exact linear form (a Linear comparator, or each pair of an increasing
            // chain) feeds the dominator buckets through its rows; PB inequalities, which have no
            // linear-row view, contribute through ineqNormalForm.
            val rows = f.linearRows()
            if (rows != null) {
                for (row in rows) {
                    val n = rowForm(row) ?: continue
                    offer(n.key, n.bound, fromEq = n.fromEq)
                    if (n.opposite != null) offer(n.opposite.key, n.opposite.bound, fromEq = true)
                }
                continue
            }
            val n = ineqNormalForm(f) ?: continue
            offer(n.key, n.bound, fromEq = n.fromEq)
            // An `=` contributes its bound to both directions, so it can dominate either inequality.
            if (n.opposite != null) offer(n.opposite.key, n.opposite.bound, fromEq = true)
        }
        val keptRep = HashSet<TermKey>()
        val out = ArrayList<Factor>(deduped.size)
        for (f in deduped) {
            val n = ineqNormalForm(f)
            // Keep equalities, ≠, and non-(Linear/PseudoBoolean) factors; they are never dropped here.
            if (n == null || n.fromEq) {
                out.add(f)
                continue
            }
            val tightest = bucketMin.getValue(n.key)
            val keep = when {
                n.bound > tightest -> false

                // dominated by a tighter constraint
                bucketEqAtMin[n.key] == true -> false

                // an `=` over the same vector implies this
                else -> keptRep.add(n.key) // keep the first representative at the tightest bound; drop dups
            }
            if (keep) out.add(f)
        }
        return finishAfterPhase2(problem, out)
    }

    /** Phases 3–5 over the phase-1/2 survivor list [out] (in [Problem.factors] order), recovering the
     *  dropped input indices. Shared by the fresh recompute and the incremental path. */
    private fun finishAfterPhase2(problem: Problem, out: List<Factor>): PassDelta {
        val factors = problem.factors
        // Phase 3: variable-subset / proportional domination across different supports (#466).
        val out3 = dropSubsetDominated(problem, out)
        // Phase 4: clique-aware redundancy — a 0/1 knapsack implied by at-most-one cliques (#527).
        val out4 = dropCliqueImpliedKnapsacks(out3)
        // Phase 5: drop globals the current domains make vacuously satisfied (#553); removing one frees
        // a variable contained only in it, which the affine pass then projects out (implied-free).
        val out5 = out4.filterNot { isVacuousGlobal(it, problem.intDomains) }
        if (out5.size == factors.size) return PassDelta()
        // This pass only drops; every survivor in [out5] is identity-equal to an input factor, in input
        // order, so a two-pointer walk recovers the dropped input indices (correct even with reference
        // duplicates, which Phase 1 collapses to one survivor).
        val dropped = IntArrayList(factors.size - out5.size)
        var p = 0
        for (i in factors.indices) {
            if (p < out5.size && factors[i] === out5[p]) p++ else dropped.add(i)
        }
        return PassDelta(dropped.toIntArray())
    }

    /** The factors added / dropped since subsume last ran, plus its persistent [memo]. [rebuild] forces a
     *  full rebuild of the memo from the whole live set (first run, or after a reseed invalidated it). */
    internal class SubsumeIncremental(
        val rebuild: Boolean,
        val addedFactors: List<Factor>,
        val droppedFactors: List<Factor>,
        val memo: SubsumeMemo,
    ) : SubsumeState

    /**
     * Persistent phase-1 (exact-duplicate) and phase-2 (same-vector domination) indices, maintained
     * across subsume firings so a re-run reprocesses only the delta instead of rescanning every factor.
     * Phase 2 keeps at most one representative `≤`-row per coefficient-vector bucket, so per bucket the
     * only live drop-candidate is that representative — a re-run reconciles it against the delta in O(1).
     *
     * A dropped drop-candidate keeps its bucket offer the round it is dropped (the from-scratch pass
     * offers every deduped row *before* deciding drops), so it is retracted only on the next firing:
     * other passes' drops arrive via [SubsumeIncremental.droppedFactors]; subsume's own drops fall before
     * the next change-mark and are carried over to the next firing internally.
     */
    internal class SubsumeMemo {
        private val shallowSingle = HashMap<Long, Factor>()
        private val shallowMulti = HashMap<Long, HashMap<StructuralKey, Factor>>()
        private val buckets = HashMap<TermKey, Bucket>()

        // Subsume's own drops from the last firing, retracted before this one (see class doc). Includes
        // phase-3/4/5 drops (fed in by [setPendingSelfDrops]); excludes phase-1 duplicates, which never
        // entered the indices.
        private var pendingSelfDrops: List<Factor> = emptyList()

        // Phase-1 duplicates dropped in the last [reconcile]: they have no index footprint, so they must
        // be excluded from the self-drops retracted next firing.
        private var lastDups: Set<Factor> = emptySet()

        /** A coefficient-vector bucket: the multiset of `≤` offer bounds (and which are equalities, for
         *  the equality-dominates rule) and the single surviving representative row, if any. */
        private class Bucket {
            val bounds = HashMap<Long, Int>()
            val eqBounds = HashMap<Long, Int>()
            var rep: Factor? = null
            var repBound = 0L

            fun tightest(): Long? = bounds.keys.minOrNull()
            fun eqAtMin(): Boolean = tightest()?.let { eqBounds.containsKey(it) } ?: false

            fun addBound(bound: Long, eq: Boolean) {
                bounds[bound] = (bounds[bound] ?: 0) + 1
                if (eq) eqBounds[bound] = (eqBounds[bound] ?: 0) + 1
            }

            fun removeBound(bound: Long, eq: Boolean) {
                val c = (bounds[bound] ?: 0) - 1
                if (c <= 0) bounds.remove(bound) else bounds[bound] = c
                if (eq) {
                    val e = (eqBounds[bound] ?: 0) - 1
                    if (e <= 0) eqBounds.remove(bound) else eqBounds[bound] = e
                }
            }
        }

        /** Reconcile the memo with the current live set and return the phase-1/2 survivor list in
         *  [Problem.factors] order. On [SubsumeIncremental.rebuild] the memo is rebuilt from scratch;
         *  otherwise only the delta is applied. */
        fun reconcile(problem: Problem, inc: SubsumeIncremental): List<Factor> {
            val drops = HashSet<Factor>()
            val dups = HashSet<Factor>()
            if (inc.rebuild) {
                shallowSingle.clear()
                shallowMulti.clear()
                buckets.clear()
                pendingSelfDrops = emptyList()
                for (f in problem.factors) process(f, drops, dups)
            } else {
                for (f in pendingSelfDrops) retract(f)
                for (f in inc.droppedFactors) retract(f)
                for (f in inc.addedFactors) process(f, drops, dups)
            }
            lastDups = dups
            return if (drops.isEmpty()) problem.factors.asList() else problem.factors.filterNot { it in drops }
        }

        /** Record every factor subsume dropped this firing (phases 1–5, by object) so their index entries
         *  are retracted before the next one. Phase-1 duplicates are filtered out — they never entered the
         *  indices. Called after phases 3–5, whose drops [reconcile] cannot see. */
        fun setPendingSelfDrops(allDropped: List<Factor>) {
            pendingSelfDrops = if (lastDups.isEmpty()) allDropped else allDropped.filterNot { it in lastDups }
        }

        // Phase 1: register [f], returning true if it is a fresh survivor and false if it duplicates a
        // kept factor. Mirrors the from-scratch two-tier dedup — the full structural key is built only on
        // a shallow-key collision — so the lazy single-survivor slot never computes a key.
        private fun registerP1(f: Factor): Boolean {
            val sk = shallowKey(f)
            val multi = shallowMulti[sk]
            if (multi != null) {
                val key = f.structuralKey()
                if (multi.containsKey(key)) return false
                multi[key] = f
                return true
            }
            val single = shallowSingle[sk]
            if (single == null) {
                shallowSingle[sk] = f
                return true
            }
            val singleKey = single.structuralKey()
            val fKey = f.structuralKey()
            if (singleKey == fKey) return false
            shallowMulti[sk] = hashMapOf(singleKey to single, fKey to f)
            shallowSingle.remove(sk)
            return true
        }

        private fun deregisterP1(f: Factor) {
            val sk = shallowKey(f)
            val multi = shallowMulti[sk]
            if (multi != null) {
                multi.remove(f.structuralKey())
                if (multi.size == 1) {
                    shallowSingle[sk] = multi.values.first()
                    shallowMulti.remove(sk)
                }
            } else if (shallowSingle[sk] === f) {
                shallowSingle.remove(sk)
            }
        }

        // Add [f] to the indices, recording into [drops] any factor it drops (itself as a duplicate, or an
        // existing representative it dominates); a phase-1 duplicate also goes to [dups] (no index entry).
        private fun process(f: Factor, drops: HashSet<Factor>, dups: HashSet<Factor>) {
            if (!registerP1(f)) {
                drops.add(f)
                dups.add(f)
                return
            }
            val touched = HashSet<TermKey>()
            forEachOffer(f) { key, bound, eq ->
                bucketFor(key).addBound(bound, eq)
                touched.add(key)
            }
            val member = ineqNormalForm(f)?.takeIf { !it.fromEq }
            for (key in touched) {
                val cand = if (member != null && member.key == key) f else null
                resolve(buckets.getValue(key), cand, member?.bound ?: 0L, drops)
            }
        }

        // Retract a factor that left the live set: remove its offers and phase-1 entry, and clear it as a
        // representative. Only ever called on factors that were survivors (which always added offers), so
        // the multiset counts stay balanced; a phase-1 duplicate never added offers and is never retracted.
        private fun retract(f: Factor) {
            deregisterP1(f)
            forEachOffer(f) { key, bound, eq -> buckets[key]?.removeBound(bound, eq) }
            forEachOffer(f) { key, _, _ -> buckets[key]?.let { if (it.rep === f) it.rep = null } }
        }

        private fun bucketFor(key: TermKey): Bucket = buckets.getOrPut(key) { Bucket() }

        // Reconcile a bucket's representative against the (at most one) new drop-candidate [cand]: the
        // earliest row at the tightest bound survives when no equality dominates the bucket, mirroring the
        // from-scratch `keptRep` rule. The old representative predates [cand], so it wins ties.
        private fun resolve(bucket: Bucket, cand: Factor?, candBound: Long, drops: HashSet<Factor>) {
            val t = bucket.tightest() ?: return
            val dominatedByEq = bucket.eqAtMin()
            val oldRep = bucket.rep
            val oldRepQualifies = oldRep != null && !dominatedByEq && bucket.repBound == t
            val candQualifies = cand != null && !dominatedByEq && candBound == t
            val newRep = when {
                oldRepQualifies -> oldRep
                candQualifies -> cand
                else -> null
            }
            if (oldRep != null && oldRep !== newRep) drops.add(oldRep)
            if (cand != null && cand !== newRep) drops.add(cand)
            bucket.rep = newRep
            if (newRep === cand) bucket.repBound = candBound
        }
    }

    /** Replay the from-scratch phase-2 offer sequence for [f]: each exact `≤`-row (a `≥` folded to `≤`,
     *  an `=` contributing both directions), or the whole-factor normal form when it has no row view. */
    private inline fun forEachOffer(f: Factor, action: (key: TermKey, bound: Long, eq: Boolean) -> Unit) {
        val rows = f.linearRows()
        if (rows != null) {
            for (row in rows) {
                val n = rowForm(row) ?: continue
                action(n.key, n.bound, n.fromEq)
                n.opposite?.let { action(it.key, it.bound, it.fromEq) }
            }
        } else {
            val n = ineqNormalForm(f) ?: return
            action(n.key, n.bound, n.fromEq)
            n.opposite?.let { action(it.key, it.bound, it.fromEq) }
        }
    }

    /** Whether [factor] is a global constraint that the current [domains] make *vacuously* satisfied —
     *  it can never prune, so dropping it preserves the feasible set exactly (#553). Currently detects
     *  an [AllDifferent] whose variables have pairwise-disjoint domains (no two can ever be equal, so
     *  distinctness always holds — for the plain, opt, and `_except` variants alike, since absence and
     *  excepted values only relax the constraint). Disjointness is tested on the `[min, max]` intervals
     *  (a sound sufficient condition; hole-induced disjointness is conservatively not claimed). */
    private fun isVacuousGlobal(factor: Factor, domains: Array<IntDomain>): Boolean {
        if (factor !is AllDifferent || factor.vars.size < 2) return false
        var prevMax = Int.MIN_VALUE
        for (d in factor.vars.map { domains[it] }.sortedBy { it.min }) {
            if (d.min <= prevMax) return false // intervals overlap → a collision is possible
            if (d.max > prevMax) prevMax = d.max
        }
        return true // every pair of intervals is disjoint → all-different holds for every assignment
    }

    /** Cap on the `≤`-rows Phase 3 compares pairwise, to keep the domination scan from going quadratic
     *  on a huge linear system; above it the scan is skipped (sound — it only means fewer drops). */
    private const val SUBSET_DOMINATION_ROW_CAP = 1500

    /** Magnitude past which a Phase-3 activity sum is treated as non-dominating, so the `Long`
     *  comparison can't wrap (real bounds are far below this). */
    private const val OVERFLOW_GUARD = 1_000_000_000_000_000L

    /** A `Σ coeffs·x ≤ bound` row as a reduced per-variable coefficient map (GCD-normalised), or `null`
     *  for non-(`≤`/`≥`) Linear factors. The map keys are variable ids; the value is the reduced
     *  coefficient. */
    private class LeRow(val factorIndex: Int, val coeffByVar: MutableIntIntMap, val bound: Long)

    /** A `≤`-normalised, GCD-reduced [LeRow] for an exact [LinearRow] (from any factor's
     *  [Factor.linearRows]); `null` for a non-(`≤`/`≥`) row. Coalesced terms have distinct vars, so a
     *  plain put per index is faithful; zero coefficients carry no support (and would divide by zero in
     *  the dominance ratio check), so skip them. */
    private fun leRowOf(row: LinearRow, factorIndex: Int): LeRow? {
        val (coeffs, bound) = when (row.op) {
            LinearOp.LE -> row.coeffs to row.bound
            LinearOp.GE -> negated(row.coeffs) to -row.bound
            else -> return null
        }
        val g = PresolveShared.gcdOf(coeffs)
        val map = MutableIntIntMap(row.vars.size)
        for (i in row.vars.indices) {
            if (coeffs[i] == 0) continue
            map.put(row.vars[i], if (g <= 1) coeffs[i] else coeffs[i] / g)
        }
        return LeRow(factorIndex, map, if (g <= 1) bound else bound.floorDiv(g.toLong()))
    }

    /**
     * Variable-subset / proportional constraint domination (#466). Drop a `≤`-row `B` when another
     * `≤`-row `A` has a support that is a strict subset of `B`'s with coefficients a positive integer
     * multiple `k` of `B`'s on the shared variables, and `k·boundA + maxActivity(B-only terms) ≤
     * boundB`. Then `A ⟹ B`: from `Σ_S a·x ≤ boundA` we get `Σ_S k·a·x ≤ k·boundA`, and adding the
     * maximal activity of `B`'s extra terms still stays within `boundB`, so `B` is redundant.
     *
     * Sound even when the dominator `A` is itself dropped by a yet-smaller row: domination by strictly
     * smaller support is transitive, so every dropped row is implied by a surviving minimal one. Only
     * `≤`/`≥` [Linear] rows take part; equalities and globals are untouched. Bounded by
     * [SUBSET_DOMINATION_ROW_CAP] so the pairwise scan can't blow up.
     */
    private fun dropSubsetDominated(problem: Problem, factors: List<Factor>): List<Factor> {
        // Dominators: every exact `≤`-row in the model (so an increasing chain's pairs can dominate
        // too). Drop candidates: only single-row factors — a multi-row factor (an increasing chain) is
        // never dropped here, since one dominated pair does not make the whole chain redundant.
        val dominators = ArrayList<LeRow>()
        val candidates = ArrayList<LeRow>()
        for (i in factors.indices) {
            val f = factors[i]
            val fRows = f.linearRows() ?: continue
            val droppable = f is Linear
            for (row in fRows) {
                val le = leRowOf(row, i) ?: continue
                dominators.add(le)
                if (droppable) candidates.add(le)
            }
        }
        if (candidates.isEmpty() || dominators.size > SUBSET_DOMINATION_ROW_CAP) return factors
        val dropped = IntHashSet()
        for (b in candidates) {
            for (a in dominators) {
                if (a.factorIndex == b.factorIndex || a.coeffByVar.size >= b.coeffByVar.size) continue
                if (dominates(problem, a, b)) {
                    dropped.add(b.factorIndex)
                    break
                }
            }
        }
        if (dropped.isEmpty()) return factors
        return factors.filterIndexed { i, _ -> i !in dropped }
    }

    /** Whether `≤`-row [a] (strict-subset support) dominates [b]: matching coefficients up to a single
     *  positive integer multiple `k` on the shared variables, and `k·boundA + maxExtra ≤ boundB`. */
    private fun dominates(problem: Problem, a: LeRow, b: LeRow): Boolean {
        var k = 0L
        a.coeffByVar.forEach { v, ca ->
            if (!b.coeffByVar.containsKey(v)) return false // a's support must be ⊆ b's
            val cb = b.coeffByVar.getOrDefault(v, 0)
            if (cb % ca != 0) return false
            val ratio = (cb / ca).toLong()
            if (ratio <= 0) return false // k must be a single positive multiple
            if (k == 0L) {
                k = ratio
            } else if (k != ratio) {
                return false
            }
        }
        if (k == 0L) return false
        var maxExtra = 0L
        b.coeffByVar.forEach { v, cb ->
            if (!a.coeffByVar.containsKey(v)) {
                val d = problem.intDomains[v]
                maxExtra += if (cb >= 0) cb.toLong() * d.max else cb.toLong() * d.min
                // Conservative overflow guard: an extra activity this large can't be dominated by a
                // small-bound row anyway, so bail rather than risk a wrapped Long comparison.
                if (maxExtra > OVERFLOW_GUARD || maxExtra < -OVERFLOW_GUARD) return false
            }
        }
        return k * a.bound + maxExtra <= b.bound
    }

    /**
     * Clique-aware redundancy (#527). An at-most-one (AMO) clique over a set of literals — at most one
     * is satisfied — caps the contribution of those literals to a `≤` pseudo-Boolean knapsack at the
     * single largest weight. So if covering a knapsack `Σ wⱼ·lⱼ ≤ b` (positive weights) with the
     * model's AMO cliques brings its clique-aware maximal activity to `≤ b`, the knapsack holds for
     * every clique-respecting assignment and is redundant — drop it (the clique factors stay, so
     * soundness is preserved). The greedy cover yields *some* valid activity upper bound; a looser
     * cover only misses drops, never makes an unsound one.
     *
     * Only [PresolveShared.maximalPersistentAmoCliques] (cliques backed by [Cardinality] / [Clause],
     * never a knapsack) are used: a clique implied by a knapsack holds only while that knapsack stays,
     * so dropping by it could remove the very constraint it rests on.
     *
     * Only redundancy is done here: clique-based coefficient *lifting* (GUB cover lifting) is subtle —
     * the naive clamp to the clique-reduced slack is unsound — and is left to a follow-up.
     */
    private fun dropCliqueImpliedKnapsacks(factors: List<Factor>): List<Factor> {
        val cliques = PresolveShared.maximalPersistentAmoCliques(factors)
        if (cliques.isEmpty()) return factors
        val out = ArrayList<Factor>(factors.size)
        for (f in factors) {
            if (f is PseudoBoolean && f.op == PbOp.LE && cliqueImpliesKnapsack(f, cliques)) continue
            out.add(f)
        }
        return out
    }

    /** Whether the AMO [cliques] force `Σ wⱼ·lⱼ ≤ bound` (all weights > 0): greedily cover the
     *  knapsack literals with cliques (each contributing only its max assigned weight) and compare the
     *  resulting activity upper bound to the bound. */
    private fun cliqueImpliesKnapsack(knapsack: PseudoBoolean, cliques: List<Set<Int>>): Boolean {
        if (knapsack.weights.any { it <= 0 }) return false
        // Every weight is > 0 (guarded above), so 0 doubles as the "literal not in the knapsack"
        // sentinel for [MutableIntIntMap.getOrDefault].
        val weightByLit = MutableIntIntMap(knapsack.literals.size)
        for (i in knapsack.literals.indices) weightByLit.put(knapsack.literals[i], knapsack.weights[i])
        val assigned = IntHashSet()
        var activity = 0L
        for (clique in cliques) {
            var maxW = 0
            var any = false
            for (lit in clique) {
                if (lit in assigned) continue
                val w = weightByLit.getOrDefault(lit, 0)
                if (w == 0) continue
                any = true
                assigned.add(lit)
                if (w > maxW) maxW = w
            }
            if (any) activity += maxW
        }
        for (lit in knapsack.literals) if (lit !in assigned) activity += weightByLit.getOrDefault(lit, 0)
        return activity <= knapsack.bound
    }

    /** A linear / pseudo-Boolean constraint as a `≤`-normalised bucket contribution: [key] is the
     *  coefficient vector (a `≥` folds to `≤` by negating), [bound] the `≤` right-hand side. [fromEq]
     *  marks an equality (it also contributes its [opposite] direction and is never itself dropped).
     *  `null` for `≠` and non-(Linear/PseudoBoolean) factors, which take no part in domination. */
    private class IneqForm(val key: TermKey, val bound: Long, val fromEq: Boolean, val opposite: IneqForm? = null) {
        fun copyWithOpposite(opp: IneqForm) = IneqForm(key, bound, fromEq, opp)
    }

    /** A single exact [LinearRow] as its `≤`-normalised [IneqForm] (an `=` row carries its opposite
     *  direction and is never dropped); `null` for a `≠` row. */
    private fun rowForm(row: LinearRow): IneqForm? = when (row.op) {
        LinearOp.LE -> reducedIneq(row.vars, row.coeffs, row.bound, ::leKey, fromEq = false)

        LinearOp.GE -> reducedIneq(row.vars, negated(row.coeffs), -row.bound, ::leKey, fromEq = false)

        LinearOp.EQ -> reducedIneq(row.vars, row.coeffs, row.bound, ::leKey, fromEq = true).copyWithOpposite(
            reducedIneq(row.vars, negated(row.coeffs), -row.bound, ::leKey, fromEq = true),
        )

        LinearOp.NE -> null
    }

    private fun ineqNormalForm(f: Factor): IneqForm? = when (f) {
        is Linear -> when (f.op) {
            LinearOp.LE -> reducedIneq(f.vars, f.coeffs, f.bound.toLong(), ::leKey, fromEq = false)

            LinearOp.GE -> reducedIneq(f.vars, negated(f.coeffs), -f.bound.toLong(), ::leKey, fromEq = false)

            LinearOp.EQ -> reducedIneq(f.vars, f.coeffs, f.bound.toLong(), ::leKey, fromEq = true).copyWithOpposite(
                reducedIneq(f.vars, negated(f.coeffs), -f.bound.toLong(), ::leKey, fromEq = true),
            )

            LinearOp.NE -> null
        }

        is PseudoBoolean -> when (f.op) {
            PbOp.LE -> reducedIneq(f.literals, f.weights, f.bound.toLong(), ::pbKey, fromEq = false)

            PbOp.GE -> reducedIneq(f.literals, negated(f.weights), -f.bound.toLong(), ::pbKey, fromEq = false)

            PbOp.EQ -> reducedIneq(f.literals, f.weights, f.bound.toLong(), ::pbKey, fromEq = true).copyWithOpposite(
                reducedIneq(f.literals, negated(f.weights), -f.bound.toLong(), ::pbKey, fromEq = true),
            )
        }

        else -> null
    }

    private fun negated(xs: IntArray): IntArray = IntArray(xs.size) { -xs[it] }

    /** A `≤`-form `Σ coeffs·terms ≤ bound`, GCD-reduced so proportional rows (`x+y ≤ 2` and
     *  `2x+2y ≤ 4`) share a bucket even when [CoefficientStrengthening.strengthenCoefficients]
     *  hasn't normalised them first (#466). Dividing by the coefficient GCD `g` and flooring the bound
     *  is exact: the left side is a multiple of `g`, so `Σ c·t ≤ b ⟺ Σ (c/g)·t ≤ ⌊b/g⌋`. [keyOf]
     *  builds the (linear / pb) key. */
    private fun reducedIneq(
        terms: IntArray,
        coeffs: IntArray,
        bound: Long,
        keyOf: (IntArray, IntArray, Boolean) -> TermKey,
        fromEq: Boolean,
    ): IneqForm {
        val g = PresolveShared.gcdOf(coeffs)
        return if (g <= 1) {
            IneqForm(keyOf(terms, coeffs, false), bound, fromEq)
        } else {
            IneqForm(keyOf(terms, IntArray(coeffs.size) { coeffs[it] / g }, false), bound.floorDiv(g.toLong()), fromEq)
        }
    }

    /** A `≤`-normal-form coefficient-vector bucket key: the `(term, coeff)` pairs sorted by term, with
     *  an [isPb] linear / pseudo-Boolean discriminator so the two kinds never share a bucket. */
    private data class TermKey(val isPb: Boolean, val terms: List<Long>)

    /** Build a [TermKey] over `(ids, coeffs)`: pairs sorted by id, each coefficient negated when [negate]
     *  (folding `≥` into `≤`). For pseudo-Boolean keys distinct literal ids for opposite polarities keep
     *  `x` and `¬x` apart (#465). */
    private fun termKey(isPb: Boolean, ids: IntArray, coeffs: IntArray, negate: Boolean): TermKey {
        val sign = if (negate) -1 else 1
        val terms = ArrayList<Long>(ids.size * 2)
        for (i in ids.indices.sortedBy { ids[it] }) {
            terms.add(ids[i].toLong())
            terms.add((sign * coeffs[i]).toLong())
        }
        return TermKey(isPb, terms)
    }

    /** A cheap discriminator for Phase-1 duplicate bucketing: a commutative splitmix sum of the variable
     *  ids (bool ids marked into a disjoint range) plus the arity-derived [Factor.structuralKeyWeight] —
     *  never a Table's tuple payload, the whole point of the two-tier dedup. It is **order-invariant** so
     *  it is implied by full structural-key equality (a [Linear]'s key compares its terms as a var→coeff
     *  map, not positionally, so an order-sensitive hash would split genuine duplicates into different
     *  buckets and miss them). True duplicates therefore always share it; unrelated collisions are
     *  harmless — they only fall through to the exact full-key comparison. */
    private fun shallowKey(f: Factor): Long {
        var vsum = 0L
        for (v in f.intVars) vsum += splitmix64(v.toLong())
        for (v in f.boolVars) vsum += splitmix64(v.toLong() or BOOL_VAR_MARK)
        return vsum * 31 + f.structuralKeyWeight
    }

    private fun leKey(vars: IntArray, coeffs: IntArray, negate: Boolean): TermKey =
        termKey(isPb = false, ids = vars, coeffs = coeffs, negate = negate)

    private fun pbKey(literals: IntArray, weights: IntArray, negate: Boolean): TermKey =
        termKey(isPb = true, ids = literals, coeffs = weights, negate = negate)
}
