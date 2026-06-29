package com.eignex.klause.solver.presolve

import com.eignex.klause.model.PbOp
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.LinearRow
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.StructuralKey
import com.eignex.klause.solver.factor.arithmetic.Linear
import com.eignex.klause.solver.factor.arithmetic.LinearOp
import com.eignex.klause.solver.factor.bool.Cardinality
import com.eignex.klause.solver.factor.bool.Clause
import com.eignex.klause.solver.factor.bool.PseudoBoolean
import com.eignex.klause.solver.factor.global.AllDifferent
import com.eignex.klause.util.IntHashSet
import com.eignex.klause.util.MutableIntIntMap
import com.eignex.kumulant.math.splitmix64

/** Marks a bool var id into a range disjoint from int var ids in [RedundantConstraints.shallowKey]'s
 *  variable-set sum, so a bool var and an int var of the same id don't cancel or alias. */
private const val BOOL_VAR_MARK = 1L shl 40

internal object RedundantConstraints {

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
    fun removeRedundantConstraints(problem: Problem): Problem {
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
        // Phase 3: variable-subset / proportional domination across different supports (#466).
        val out3 = dropSubsetDominated(problem, out)
        // Phase 4: clique-aware redundancy — a 0/1 knapsack implied by at-most-one cliques (#527).
        val out4 = dropCliqueImpliedKnapsacks(out3)
        // Phase 5: drop globals the current domains make vacuously satisfied (#553); removing one frees
        // a variable contained only in it, which the affine pass then projects out (implied-free).
        val out5 = out4.filterNot { isVacuousGlobal(it, problem.intDomains) }
        if (out5.size == factors.size) return problem
        return PresolveShared.rebuildProblem(problem, out5)
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
