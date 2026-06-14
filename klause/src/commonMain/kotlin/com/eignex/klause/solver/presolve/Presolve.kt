package com.eignex.klause.solver.presolve

import com.eignex.klause.model.PbOp
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.factor.AllDifferent
import com.eignex.klause.solver.factor.Cardinality
import com.eignex.klause.solver.factor.Clause
import com.eignex.klause.solver.factor.LexLess
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.factor.PseudoBoolean
import com.eignex.klause.solver.factor.ValuePrecede
import com.eignex.klause.solver.factor.Xor
import com.eignex.klause.util.IntHashSet
import com.eignex.klause.util.MutableIntIntMap

/**
 * Problem-level presolve transforms. Each takes a [Problem] and returns an equivalent one with
 * a smaller / tighter formulation. Pure (no solving); the caller decides when to apply them.
 */
object Presolve {

    /** Cap on a verified-symmetry candidate group; larger groups are skipped (#367 size guard). */
    private const val MAX_VERIFIED_GROUP = 40

    /** Widest bool row a binary-number lex-leader can encode: `2^(m−1)` must fit in `Int`, so
     *  `m ≤ 31`. Wider rows are left unbroken (#373); their lex needs an aux-var encoding. */
    private const val MAX_BOOL_LEX_WIDTH = 31

    /**
     * GCD coefficient strengthening (#319) for [Linear] and [PseudoBoolean] constraints. If the
     * coefficients of an integer linear (or pseudo-Boolean) constraint share a common divisor
     * `g > 1`, dividing through by `g` shrinks the coefficients and, because the left-hand side
     * is then a multiple of `g`, lets the bound be tightened by flooring/ceiling:
     *  - `Σ aⱼxⱼ ≤ b`  ⟺  `Σ (aⱼ/g)xⱼ ≤ ⌊b/g⌋`
     *  - `Σ aⱼxⱼ ≥ b`  ⟺  `Σ (aⱼ/g)xⱼ ≥ ⌈b/g⌉`
     *  - `Σ aⱼxⱼ = b`: divisible ⟹ `Σ (aⱼ/g)xⱼ = b/g`; otherwise left unchanged (the search
     *    catches the infeasibility — no unsound rewrite here).
     *  - `Σ aⱼxⱼ ≠ b`: divisible ⟹ divide; otherwise the constraint is always true and is dropped.
     *
     * Exact (feasible-set-preserving) and it tightens the LP relaxation the bound participates in.
     * Other factor types pass through untouched.
     */
    fun strengthenCoefficients(problem: Problem): Problem {
        val out = ArrayList<Factor>(problem.factors.size)
        var changed = false
        for (factor in problem.factors) {
            val rewritten = when (factor) {
                is Linear -> strengthenLinear(factor, problem.intDomains)
                is PseudoBoolean -> strengthenPb(factor)
                else -> factor
            }
            if (rewritten !== factor) changed = true
            if (rewritten != null) out.add(rewritten)
        }
        if (!changed) return problem
        return Problem(
            numBoolVars = problem.numBoolVars,
            numIntVars = problem.numIntVars,
            intDomains = problem.intDomains.copyOf(),
            factors = out,
            probeFailedLiterals = problem.probeFailedLiterals,
            probeIntBounds = problem.probeIntBounds,
            probeIntHoles = problem.probeIntHoles,
            probeBudgetPerVar = problem.probeBudgetPerVar,
            probeTotalBudget = problem.probeTotalBudget,
            probeSeed = problem.probeSeed,
        )
    }

    /**
     * Affine variable elimination (#318/#335/#445). Eliminates an integer variable `x` defined by an
     * `n`-term equality `c_x·x + Σ_j c_j·y_j = b` with a **unit** pivot coefficient `|c_x| = 1`, i.e.
     * `x = B + Σ_j A_j·y_j` where `A_j = −c_x·c_j` and `B = c_x·b`. The defining equality is dropped,
     * the affine relation is folded into every other [Linear] that mentions `x`, and bounds on the
     * `y_j` are added so `x` stays inside its declared domain; `x` becomes unconstrained and is
     * rebuilt from the solution via [AffineElimination.reconstruct].
     *
     * Two-term equalities are the common case (an alias or a one-partner definition); the `n`-term
     * generalisation (#445) projects out an *implied-free* variable defined by a longer sum (e.g. an
     * auxiliary `x = y1 + y2 − y3` used nowhere a global needs it). The unit-pivot restriction keeps
     * every folded coefficient integral; a non-unit pivot would need residue-class reasoning and is a
     * follow-up.
     *
     * For the **alias** case `x = y` (`n = 2`, `A = 1`, `B = 0`) the substitution `x → y` is a plain
     * variable rename, applied to *every* factor via [Factor.remap] regardless of type (#364).
     * Otherwise the relation can only fold into a weighted sum, so `x` is eliminated only when its
     * other occurrences are all [Linear] — a global constraint (AllDifferent, Element, …) needs `x` as
     * a genuine variable and cannot absorb `B + Σ A_j·y_j`. The #318 contained slice (`x` in no other
     * factor) is the zero-fold special case, and is what lets an `n`-term definition be projected out.
     *
     * Variables in [objectiveIntVars] are never eliminated: the objective reads them directly and
     * the engine optimises over the presolved problem where an eliminated variable is unconstrained.
     */
    fun eliminateAffineSingletons(problem: Problem, objectiveIntVars: Set<Int> = emptySet()): AffineElimination {
        if (problem.numIntVars == 0) return AffineElimination(problem, emptyList())
        var factors = problem.factors.toList()
        val eliminated = BooleanArray(problem.numIntVars)
        val subs = ArrayList<AffineSub>()
        while (true) {
            val cand = findAffineCandidate(factors, eliminated, objectiveIntVars) ?: break
            factors = foldOutVariable(problem, factors, cand)
            eliminated[cand.x] = true
            subs.add(AffineSub(cand.x, cand.constTerm, cand.termVars, cand.termCoeffs))
        }
        // Residue-class doubletons (#522): a 2-term `a·x + b·y = c` with no unit pivot, where `x` is
        // contained, determines `x = (c − b·y)/a` only for the `y` values keeping it an in-domain
        // integer. Restrict `y` to those values (a domain modification, not a folded factor) and
        // reconstruct `x` with the divisor. Runs after the unit-pivot loop, so a residue partner `y`
        // is always a surviving variable.
        val domains = problem.intDomains.copyOf()
        while (true) {
            val r = findResidueCandidate(factors, eliminated, objectiveIntVars, domains) ?: break
            factors = factors.filterIndexed { i, _ -> i != r.defIdx }
            domains[r.y] = r.restrictedY
            eliminated[r.x] = true
            subs.add(AffineSub(r.x, r.constTerm, intArrayOf(r.y), intArrayOf(r.coeffY), divisor = r.divisor))
        }
        if (subs.isEmpty()) return AffineElimination(problem, emptyList())
        return AffineElimination(rebuildProblem(problem, factors, domains), subs)
    }

    /** Cap on a residue partner's domain span: scanning each value to build the restricted domain is
     *  O(span), and a residue class on a very wide domain would flood it with holes, so skip above it. */
    private const val RESIDUE_DOMAIN_SPAN_CAP = 1024

    /** A residue-class doubleton `a·x + b·y = c` (no unit pivot) at [defIdx]: `x` is contained and
     *  reconstructed as `(constTerm + coeffY·y) / divisor` over the [restrictedY] partner domain. */
    private class ResidueCandidate(
        val defIdx: Int,
        val x: Int,
        val y: Int,
        val constTerm: Int,
        val coeffY: Int,
        val divisor: Int,
        val restrictedY: IntDomain,
    )

    private fun findResidueCandidate(
        factors: List<Factor>,
        eliminated: BooleanArray,
        objectiveIntVars: Set<Int>,
        domains: Array<IntDomain>,
    ): ResidueCandidate? {
        for (di in factors.indices) {
            val f = factors[di]
            if (f !is Linear || f.op != LinearOp.EQ || f.vars.size != 2) continue
            for (xi in 0..1) {
                val x = f.vars[xi]
                val y = f.vars[1 - xi]
                val a = f.coeffs[xi]
                val b = f.coeffs[1 - xi]
                // The unit-pivot loop already ran, so a remaining 2-term EQ has no unit coefficient;
                // guard anyway. `x` must be contained (a non-unit fold can't stay integral) and free.
                // `y`'s domain is restricted below, so it too must stay clear of the objective — the
                // pass leaves every objective variable untouched.
                if (a == 1 || a == -1 || eliminated[x] || eliminated[y] || x == y) continue
                if (x in objectiveIntVars || y in objectiveIntVars) continue
                if (!isContained(factors, di, x)) continue
                val domY = domains[y]
                if (domY.max.toLong() - domY.min.toLong() > RESIDUE_DOMAIN_SPAN_CAP) continue
                val restricted = restrictPartnerDomain(domY, domains[x], a, b, f.bound) ?: continue
                return ResidueCandidate(
                    di,
                    x,
                    y,
                    constTerm = f.bound,
                    coeffY = -b,
                    divisor = a,
                    restrictedY = restricted,
                )
            }
        }
        return null
    }

    /** Whether [x] occurs in no factor other than [defIdx]. */
    private fun isContained(factors: List<Factor>, defIdx: Int, x: Int): Boolean {
        for (i in factors.indices) if (i != defIdx && x in factors[i].intVars) return false
        return true
    }

    /** The partner domain restricted to the `y` values for which `x = (c − b·y)/a` is an integer
     *  inside [domX], or `null` if no such `y` exists (leave the constraint for propagation to fail). */
    private fun restrictPartnerDomain(domY: IntDomain, domX: IntDomain, a: Int, b: Int, c: Int): IntDomain? {
        val valid = ArrayList<Int>()
        for (y in domY.min..domY.max) {
            if (y !in domY) continue
            val num = c - b * y
            if (num % a != 0) continue
            val x = num / a
            if (x in domX) valid.add(y)
        }
        if (valid.isEmpty()) return null
        var d = domY.withMinAtLeast(valid.first()).withMaxAtMost(valid.last())
        val keep = valid.toHashSet()
        for (y in valid.first()..valid.last()) if (y !in keep && y in d) d = d.excludeValue(y)
        return d
    }

    /** An `EQ` [Linear] at [defIdx] defining `x = constTerm + Σ termCoeffs·termVars` (unit pivot). The
     *  other occurrences of `x` are either all foldable (Linear) or — for the alias case `x = y` —
     *  substituted via [Factor.remap] into any factor type. */
    private class AffineCandidate(
        val defIdx: Int,
        val x: Int,
        val constTerm: Int,
        val termVars: IntArray,
        val termCoeffs: IntArray,
        val isAlias: Boolean,
    )

    private fun findAffineCandidate(
        factors: List<Factor>,
        eliminated: BooleanArray,
        objectiveIntVars: Set<Int>,
    ): AffineCandidate? {
        for (di in factors.indices) {
            val f = factors[di]
            if (f !is Linear || f.op != LinearOp.EQ || f.vars.size < 2) continue
            for (xi in f.vars.indices) {
                val x = f.vars[xi]
                val cx = f.coeffs[xi]
                if ((cx != 1 && cx != -1) || eliminated[x] || x in objectiveIntVars) continue
                // x = B + Σ A_j·y_j, with B = c_x·bound and A_j = −c_x·c_j for the other terms y_j.
                val termVars = IntArray(f.vars.size - 1)
                val termCoeffs = IntArray(f.vars.size - 1)
                var w = 0
                var partnerEliminated = false
                for (j in f.vars.indices) {
                    if (j == xi) continue
                    if (eliminated[f.vars[j]]) partnerEliminated = true
                    termVars[w] = f.vars[j]
                    termCoeffs[w] = -cx * f.coeffs[j]
                    w++
                }
                if (partnerEliminated) continue
                val constTerm = cx * f.bound
                // The alias case (n = 2, A = 1, B = 0, i.e. x = y) substitutes into ANY factor via
                // remap; otherwise x must occur only in foldable Linear factors.
                val isAlias = termVars.size == 1 && termCoeffs[0] == 1 && constTerm == 0
                if (isAlias || otherOccurrencesAllLinear(factors, di, x)) {
                    return AffineCandidate(di, x, constTerm, termVars, termCoeffs, isAlias)
                }
            }
        }
        return null
    }

    /** Whether every factor other than [defIdx] that mentions [x] is a [Linear] (foldable). */
    private fun otherOccurrencesAllLinear(factors: List<Factor>, defIdx: Int, x: Int): Boolean {
        for (i in factors.indices) {
            if (i != defIdx && x in factors[i].intVars && factors[i] !is Linear) return false
        }
        return true
    }

    /** Drop the defining equality and remove `x`: for the alias case `x = y`, substitute `x → y`
     *  into every other factor via [Factor.remap] (any factor type); otherwise fold
     *  `x = constTerm + Σ termCoeffs·termVars` into every other Linear mentioning `x`. In both cases
     *  bounds on the term vars keep `x` within its domain. */
    private fun foldOutVariable(problem: Problem, factors: List<Factor>, c: AffineCandidate): List<Factor> {
        val out = ArrayList<Factor>(factors.size + 1)
        if (c.isAlias) {
            val boolMap = IntArray(problem.numBoolVars) { it }
            val intMap = IntArray(problem.numIntVars) { it }
            intMap[c.x] = c.termVars[0]
            for (i in factors.indices) if (i != c.defIdx) out.add(factors[i].remap(boolMap, intMap))
        } else {
            for (i in factors.indices) {
                if (i == c.defIdx) continue
                val f = factors[i]
                out.add(if (f is Linear && c.x in f.vars) foldAffineIntoLinear(f, c) else f)
            }
        }
        out.addAll(domainBoundsOnTerms(problem.intDomains[c.x], c))
        return out
    }

    /** [l] with `x` replaced by `constTerm + Σ A_j·y_j`: drop `x`'s term, add `coeff_x·A_j` to each
     *  term var `y_j`, shift the bound by `−coeff_x·constTerm`. The [Linear] constructor re-coalesces
     *  any term var that already occurs in [l]. */
    private fun foldAffineIntoLinear(l: Linear, c: AffineCandidate): Linear {
        val ix = l.vars.indexOf(c.x)
        val cX = l.coeffs[ix]
        val newVars = IntArray(l.vars.size - 1 + c.termVars.size)
        val newCoeffs = IntArray(newVars.size)
        var w = 0
        for (j in l.vars.indices) {
            if (j == ix) continue
            newVars[w] = l.vars[j]
            newCoeffs[w] = l.coeffs[j]
            w++
        }
        for (k in c.termVars.indices) {
            newVars[w] = c.termVars[k]
            newCoeffs[w] = cX * c.termCoeffs[k]
            w++
        }
        return Linear(newCoeffs, newVars, l.op, l.bound - cX * c.constTerm)
    }

    /** Bounds on the term vars enforcing that `x = constTerm + Σ termCoeffs·termVars` stays within
     *  `x`'s domain [domX]. */
    private fun domainBoundsOnTerms(domX: IntDomain, c: AffineCandidate): List<Factor> = listOf(
        Linear(c.termCoeffs.copyOf(), c.termVars.copyOf(), LinearOp.LE, domX.max - c.constTerm),
        Linear(c.termCoeffs.copyOf(), c.termVars.copyOf(), LinearOp.GE, domX.min - c.constTerm),
    )

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
     * when [strengthenCoefficients] hasn't run first. Self-redundant rows (maximal activity already
     * within the bound) are dropped by the strengthen lift, so this pass is purely cross-constraint.
     */
    fun removeRedundantConstraints(problem: Problem): Problem {
        val factors = problem.factors
        // Phase 1: exact-duplicate removal by structural key.
        val deduped = ArrayList<Factor>(factors.size)
        val seenKeys = HashSet<String>()
        for (f in factors) {
            val k = f.structuralKey()
            if (k != null && !seenKeys.add(k)) continue
            deduped.add(f)
        }
        // Phase 2: bucket the ≤-normalised Linear inequalities by coefficient vector; the bucket's
        // tightest bound (and whether an `=` provides it) decides which inequalities are implied.
        val bucketMin = HashMap<String, Long>()
        val bucketEqAtMin = HashMap<String, Boolean>()
        fun offer(key: String, bound: Long, fromEq: Boolean) {
            val cur = bucketMin[key]
            if (cur == null || bound < cur) {
                bucketMin[key] = bound
                bucketEqAtMin[key] = fromEq
            } else if (bound == cur && fromEq) {
                bucketEqAtMin[key] = true
            }
        }
        for (f in deduped) {
            val n = ineqNormalForm(f) ?: continue
            offer(n.key, n.bound, fromEq = n.fromEq)
            // An `=` contributes its bound to both directions, so it can dominate either inequality.
            if (n.opposite != null) offer(n.opposite.key, n.opposite.bound, fromEq = true)
        }
        val keptRep = HashSet<String>()
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
        if (out4.size == factors.size) return problem
        return rebuildProblem(problem, out4)
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
    private class LeRow(val factorIndex: Int, val coeffByVar: Map<Int, Int>, val bound: Long)

    private fun leRowOf(f: Linear, factorIndex: Int): LeRow? {
        val (coeffs, bound) = when (f.op) {
            LinearOp.LE -> f.coeffs to f.bound.toLong()
            LinearOp.GE -> negated(f.coeffs) to -f.bound.toLong()
            else -> return null
        }
        val g = gcdOf(coeffs)
        val map = HashMap<Int, Int>(f.vars.size)
        // Coalesced Linear has distinct vars, so a plain put per index is faithful.
        for (i in f.vars.indices) map[f.vars[i]] = if (g <= 1) coeffs[i] else coeffs[i] / g
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
        val rows = ArrayList<LeRow>()
        for (i in factors.indices) {
            val f = factors[i]
            if (f is Linear) leRowOf(f, i)?.let { rows.add(it) }
        }
        if (rows.size < 2 || rows.size > SUBSET_DOMINATION_ROW_CAP) return factors
        val dropped = IntHashSet()
        for (b in rows) {
            for (a in rows) {
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
        for ((v, ca) in a.coeffByVar) {
            val cb = b.coeffByVar[v] ?: return false // a's support must be ⊆ b's
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
        for ((v, cb) in b.coeffByVar) {
            if (v in a.coeffByVar) continue
            val d = problem.intDomains[v]
            maxExtra += if (cb >= 0) cb.toLong() * d.max else cb.toLong() * d.min
            // Conservative overflow guard: an extra activity this large can't be dominated by a
            // small-bound row anyway, so bail rather than risk a wrapped Long comparison.
            if (maxExtra > OVERFLOW_GUARD || maxExtra < -OVERFLOW_GUARD) return false
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
     * Only redundancy is done here: clique-based coefficient *lifting* (GUB cover lifting) is subtle —
     * the naive clamp to the clique-reduced slack is unsound — and is left to a follow-up.
     */
    private fun dropCliqueImpliedKnapsacks(factors: List<Factor>): List<Factor> {
        val cliques = extractAmoCliques(factors)
        if (cliques.isEmpty()) return factors
        val out = ArrayList<Factor>(factors.size)
        for (f in factors) {
            if (f is PseudoBoolean && f.op == PbOp.LE && cliqueImpliesKnapsack(f, cliques)) continue
            out.add(f)
        }
        return out
    }

    /** At-most-one cliques (each a set of Lit-encoded literals, at most one satisfied) recognised
     *  soundly: a [Cardinality] `0 ≤ Σ lit ≤ 1`, and any binary [Clause] `(l1 ∨ l2)` ⟺ at most one of
     *  `{¬l1, ¬l2}`. */
    private fun extractAmoCliques(factors: List<Factor>): List<Set<Int>> {
        val cliques = ArrayList<Set<Int>>()
        for (f in factors) {
            when {
                f is Cardinality && f.min == 0 && f.max == 1 -> cliques.add(f.literals.toHashSet())

                f is Clause && f.literals.size == 2 ->
                    cliques.add(hashSetOf(Lit.negate(f.literals[0]), Lit.negate(f.literals[1])))
            }
        }
        return cliques
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
    private class IneqForm(val key: String, val bound: Long, val fromEq: Boolean, val opposite: IneqForm? = null) {
        fun copyWithOpposite(opp: IneqForm) = IneqForm(key, bound, fromEq, opp)
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
     *  `2x+2y ≤ 4`) share a bucket even when [strengthenCoefficients] hasn't normalised them first
     *  (#466). Dividing by the coefficient GCD `g` and flooring the bound is exact: the left side is a
     *  multiple of `g`, so `Σ c·t ≤ b ⟺ Σ (c/g)·t ≤ ⌊b/g⌋`. [keyOf] builds the (linear / pb) key. */
    private fun reducedIneq(
        terms: IntArray,
        coeffs: IntArray,
        bound: Long,
        keyOf: (IntArray, IntArray, Boolean) -> String,
        fromEq: Boolean,
    ): IneqForm {
        val g = gcdOf(coeffs)
        return if (g <= 1) {
            IneqForm(keyOf(terms, coeffs, false), bound, fromEq)
        } else {
            IneqForm(keyOf(terms, IntArray(coeffs.size) { coeffs[it] / g }, false), bound.floorDiv(g.toLong()), fromEq)
        }
    }

    /** Canonical key for a linear inequality's `≤`-normal-form coefficient vector: the `(var, coeff)`
     *  pairs sorted by variable, with every coefficient negated when [negate] (folding `≥` into `≤`).
     *  Prefixed so it never shares a bucket with a [pbKey]. */
    private fun leKey(vars: IntArray, coeffs: IntArray, negate: Boolean): String {
        val sign = if (negate) -1 else 1
        return "L:" + vars.indices.sortedBy { vars[it] }.joinToString(",") { "${vars[it]}=${sign * coeffs[it]}" }
    }

    /** The pseudo-Boolean analogue of [leKey] over `(literal, weight)` pairs (#465). Distinct literal
     *  ids for opposite polarities keep `x` and `¬x` apart; prefixed disjoint from [leKey]. */
    private fun pbKey(literals: IntArray, weights: IntArray, negate: Boolean): String {
        val sign = if (negate) -1 else 1
        return "P:" + literals.indices.sortedBy { literals[it] }
            .joinToString(",") { "${literals[it]}=${sign * weights[it]}" }
    }

    /**
     * Dual fixing / dominated-variable reductions (#448). A minimize objective `min Σ cⱼxⱼ` plus the
     * constraint structure can pin a variable to a bound without changing the optimum:
     *  - **down-safe**: lowering `xⱼ` never violates any constraint — it occurs only in `≤` rows with a
     *    positive coefficient or `≥` rows with a negative one; if also `cⱼ ≥ 0` (lowering never raises
     *    the objective), an optimum exists with `xⱼ` at its lower bound, so pin it there.
     *  - **up-safe**: the mirror (`≤`/negative or `≥`/positive, and `cⱼ ≤ 0`) → pin to the upper bound.
     *
     * Integers: a variable whose every occurrence is a `≤`/`≥` [Linear] (an `=`/`≠` row or non-[Linear]
     * global makes the safety undecidable, so it is excluded) is pinned by tightening its domain to a
     * singleton. Booleans (#469/#470): the pure-literal mirror, extended past [Clause] to every
     * *monotone* pseudo-Boolean row — a [Cardinality] `min ≤ Σ ≤ max` (each active side fixes a safe
     * direction per literal) and a [PseudoBoolean] `≤`/`≥`. In all of these, flipping a literal moves
     * the row's sum one known way, so one value of the variable is safe; an `=` pseudo-Boolean, a
     * reified row, or any other bool factor couples both directions and excludes the variable. A
     * safe-direction bool is pinned with a unit clause (a bool already unit-pinned is skipped, keeping
     * the pass idempotent). Coefficients come from [objectiveIntCoeffs] / [objectiveBoolCoeffs]
     * (minimize sense, absent ⇒ 0).
     *
     * The integer side stays `≤`/`≥` [Linear] only: klause's reified rows are full biconditionals
     * (their inner vars affect feasibility both ways) and its globals aren't monotone in a single int
     * var, so there is no sound monotone int factor to add — see #470.
     *
     * No elimination, identity reconstruction. Solution-set altering (discards optimum-equivalent and
     * feasible-but-suboptimal assignments), so the engine runs it only for non-solution-set-sensitive
     * queries.
     */
    fun fixDominatedVariables(
        problem: Problem,
        objectiveIntCoeffs: Map<Int, Long>,
        objectiveBoolCoeffs: Map<Int, Long> = emptyMap(),
    ): Problem {
        val n = problem.numIntVars
        val downSafe = BooleanArray(n) { true }
        val upSafe = BooleanArray(n) { true }
        val intEligible = BooleanArray(n) { true }
        val nb = problem.numBoolVars
        val trueSafe = BooleanArray(nb) { true } // b = true never violates a constraint
        val falseSafe = BooleanArray(nb) { true } // b = false never violates a constraint
        val boolEligible = BooleanArray(nb) { true }
        val alreadyPinned = IntHashSet() // bool vars already forced by a unit clause
        for (f in problem.factors) {
            if (f is Linear && (f.op == LinearOp.LE || f.op == LinearOp.GE)) {
                for (i in f.vars.indices) {
                    val a = f.coeffs[i]
                    if (a == 0) continue
                    // For a nonzero coefficient in a ≤/≥ row exactly one direction is safe: lowering is
                    // safe iff (LE ∧ a>0) ∨ (GE ∧ a<0); raising is the complement.
                    val loweringSafe = if (f.op == LinearOp.LE) a > 0 else a < 0
                    if (loweringSafe) upSafe[f.vars[i]] = false else downSafe[f.vars[i]] = false
                }
            } else {
                for (v in f.intVars) intEligible[v] = false
            }
            markBoolSafety(f, trueSafe, falseSafe, boolEligible, alreadyPinned)
        }
        var changed = false
        val domains = problem.intDomains.copyOf()
        for (v in 0 until n) {
            if (!intEligible[v]) continue
            val d = problem.intDomains[v]
            if (d.min == d.max) continue // already fixed
            val c = objectiveIntCoeffs[v] ?: 0L
            when {
                downSafe[v] && c >= 0L -> {
                    domains[v] = IntDomain(d.min, d.min)
                    changed = true
                }

                upSafe[v] && c <= 0L -> {
                    domains[v] = IntDomain(d.max, d.max)
                    changed = true
                }
            }
        }
        val extra = ArrayList<Factor>()
        for (b in 0 until nb) {
            if (!boolEligible[b] || b in alreadyPinned) continue
            val c = objectiveBoolCoeffs[b] ?: 0L
            when {
                trueSafe[b] && c <= 0L -> extra.add(Clause(intArrayOf(Lit.make(b, true))))
                falseSafe[b] && c >= 0L -> extra.add(Clause(intArrayOf(Lit.make(b, false))))
                else -> continue
            }
            changed = true
        }
        if (!changed) return problem
        return rebuildProblem(problem, problem.factors.toList() + extra, domains)
    }

    /** Fold [f]'s contribution to the Boolean pure-literal safety analysis (#469/#470). A bool var is
     *  pinnable only if it occurs solely in *monotone* rows — [Clause] (an at-least-one lower bound),
     *  [Cardinality] (its active lower/upper sides), and [PseudoBoolean] `≤`/`≥` — where flipping a
     *  literal moves the row's sum in one known direction. Any other bool factor (a reified row, a
     *  `=` pseudo-Boolean, …) couples the two directions, so it excludes its bool vars outright. */
    private fun markBoolSafety(
        f: Factor,
        trueSafe: BooleanArray,
        falseSafe: BooleanArray,
        boolEligible: BooleanArray,
        alreadyPinned: IntHashSet,
    ) {
        when {
            f is Clause -> {
                if (f.literals.size == 1) alreadyPinned.add(Lit.variable(f.literals[0]))
                // A clause is `Σ lit ≥ 1`: unsatisfying a literal lowers the count toward violation.
                for (lit in f.literals) markBoolMonotoneLiteral(lit, 1, false, fallUnsafe = true, trueSafe, falseSafe)
            }

            f is Cardinality -> {
                // `min ≤ Σ lit ≤ max`: the lower side (min > 0) makes unsatisfying risky, the upper
                // side (max < #lits) makes satisfying risky. A two-sided row clears both directions.
                val fallUnsafe = f.min > 0
                val riseUnsafe = f.max < f.literals.size
                for (lit in f.literals) markBoolMonotoneLiteral(lit, 1, riseUnsafe, fallUnsafe, trueSafe, falseSafe)
            }

            f is PseudoBoolean && (f.op == PbOp.LE || f.op == PbOp.GE) -> {
                // `Σ w·lit ≤ b` (rising sum violates) / `≥ b` (falling sum violates).
                val riseUnsafe = f.op == PbOp.LE
                for (i in f.literals.indices) {
                    markBoolMonotoneLiteral(f.literals[i], f.weights[i], riseUnsafe, !riseUnsafe, trueSafe, falseSafe)
                }
            }

            else -> for (v in f.boolVars) boolEligible[v] = false
        }
    }

    /** Clear the unsafe pin direction(s) for the variable behind [lit] in a monotone row. [weight] is
     *  the literal's coefficient (1 for clause/cardinality); the signed weight `w·polarity` is how the
     *  row's sum changes when the variable flips false→true. [riseUnsafe] / [fallUnsafe] say whether a
     *  rising / falling sum can violate the row, so the value that moves the sum that way is unsafe. */
    private fun markBoolMonotoneLiteral(
        lit: Int,
        weight: Int,
        riseUnsafe: Boolean,
        fallUnsafe: Boolean,
        trueSafe: BooleanArray,
        falseSafe: BooleanArray,
    ) {
        val v = Lit.variable(lit)
        val signedW = if (Lit.isPositive(lit)) weight else -weight
        if (signedW == 0) return
        // The value that raises the sum: true if signedW > 0, else false. Mirror for lowering.
        if (riseUnsafe) (if (signedW > 0) trueSafe else falseSafe)[v] = false
        if (fallUnsafe) (if (signedW > 0) falseSafe else trueSafe)[v] = false
    }

    private fun rebuildProblem(
        problem: Problem,
        factors: List<Factor>,
        intDomains: Array<IntDomain> = problem.intDomains.copyOf(),
    ): Problem = Problem(
        numBoolVars = problem.numBoolVars,
        numIntVars = problem.numIntVars,
        intDomains = intDomains,
        factors = factors,
        probeFailedLiterals = problem.probeFailedLiterals,
        probeIntBounds = problem.probeIntBounds,
        probeIntHoles = problem.probeIntHoles,
        probeBudgetPerVar = problem.probeBudgetPerVar,
        probeTotalBudget = problem.probeTotalBudget,
        probeSeed = problem.probeSeed,
    )

    /** Relation common to [LinearOp] and [PbOp] so the bound rewrite is written once. */
    private enum class Rel { LE, GE, EQ, NE }

    private sealed interface Reduced {
        /** Divide through; the rewritten bound. */
        data class Bound(val bound: Int) : Reduced

        /** Constraint is always satisfied — drop it. */
        object Drop : Reduced

        /** Leave the constraint as-is (non-divisible equality). */
        object Unchanged : Reduced
    }

    private fun reduceBound(rel: Rel, bound: Int, g: Int): Reduced = when (rel) {
        Rel.LE -> Reduced.Bound(bound.floorDiv(g))
        Rel.GE -> Reduced.Bound(-((-bound).floorDiv(g)))
        Rel.EQ -> if (bound.mod(g) == 0) Reduced.Bound(bound / g) else Reduced.Unchanged
        Rel.NE -> if (bound.mod(g) == 0) Reduced.Bound(bound / g) else Reduced.Drop
    }

    private fun strengthenLinear(factor: Linear, domains: Array<IntDomain>): Factor? {
        val g = gcdOf(factor.coeffs)
        val gcdReduced: Linear = if (g <= 1) {
            factor
        } else {
            when (val reduced = reduceBound(toRel(factor.op), factor.bound, g)) {
                is Reduced.Bound -> Linear(divAll(factor.coeffs, g), factor.vars.copyOf(), factor.op, reduced.bound)
                Reduced.Drop -> return null
                Reduced.Unchanged -> factor
            }
        }
        return liftLinear(gcdReduced, domains)
    }

    /**
     * Coefficient lifting (#365 / #372) for an inequality [Linear] over bounded-integer variables
     * `xⱼ ∈ [lⱼ, uⱼ]` — the MIP coefficient-tightening of Savelsbergh / Achterberg, which
     * generalises the 0/1 cover-dual clamp of [liftKnapsack] to `uⱼ > 1`.
     *
     * Reduce to a positive-coefficient bounded knapsack `Σ a̅ⱼ zⱼ ≤ B`, `zⱼ ∈ [0, cⱼ]` with
     * `cⱼ = uⱼ − lⱼ`: shift `zⱼ = xⱼ − lⱼ` when `aⱼ > 0`, complement `zⱼ = uⱼ − xⱼ` (so `a̅ⱼ = −aⱼ`)
     * when `aⱼ < 0`, folding the constants into `B`. Its complement `Σ a̅ⱼ(cⱼ − zⱼ) ≤ B` is the
     * cover `Σ a̅ⱼ z̄ⱼ ≥ d` with `d = Amax − B`, `Amax = Σ a̅ⱼcⱼ` the maximal activity. The clamp
     * `a̅ⱼ → min(a̅ⱼ, d)` is exact on this cover for *bounded* `z̄ⱼ ∈ [0, cⱼ]`, not just binary: the
     * equivalence `Σ a̅ⱼz̄ⱼ ≥ d ⟺ Σ min(a̅ⱼ,d)z̄ⱼ ≥ d` holds pointwise at every nonnegative-integer
     * assignment — whenever some `z̄ⱼ ≥ 1` has `a̅ⱼ > d` the clamped term alone already reaches `d`,
     * and otherwise every active term has `a̅ⱼ ≤ d` so the two sides are identical. Mapping the
     * clamped cover back to `≤` gives the bound `Σ min(a̅ⱼ,d)cⱼ − d` (de-shifted per variable).
     *
     * `d ≤ 0` ⟹ the constraint is always satisfied (dropped). `≥` is complemented to `≤` first;
     * `=` can't be lifted by clamping (it ties both directions) and `≠` isn't a knapsack — both pass
     * through, as do out-of-`Int`-range slacks. Fixed variables (`cⱼ = 0`) are folded into the bound
     * and never clamped (their coefficient is immaterial to the feasible set).
     */
    private fun liftLinear(l: Linear, domains: Array<IntDomain>): Factor? {
        // Only ≤ / ≥ lift by clamping; complement ≥ to ≤ by negating coeffs and bound (#365).
        val coeffs: IntArray
        val bound: Long
        when (l.op) {
            LinearOp.LE -> {
                coeffs = l.coeffs
                bound = l.bound.toLong()
            }

            LinearOp.GE -> {
                coeffs = IntArray(l.coeffs.size) { -l.coeffs[it] }
                bound = -l.bound.toLong()
            }

            else -> return l
        }
        val n = coeffs.size
        // Normalise to a positive-coefficient bounded knapsack Σ a̅ⱼzⱼ ≤ B, zⱼ ∈ [0, cⱼ].
        val absA = IntArray(n)
        val cap = LongArray(n)
        var b = bound
        for (i in 0 until n) {
            val a = coeffs[i]
            val dom = domains[l.vars[i]]
            cap[i] = dom.max.toLong() - dom.min.toLong()
            absA[i] = if (a < 0) -a else a
            // Fold the variable's contribution at its zero-of-zⱼ end into the bound:
            // aⱼ>0 shifts by aⱼ·lⱼ, aⱼ<0 (complemented) shifts by aⱼ·uⱼ.
            val zeroEnd = if (a >= 0) dom.min else dom.max
            b -= a.toLong() * zeroEnd
        }
        var amax = 0L
        for (i in 0 until n) amax += absA[i].toLong() * cap[i]
        val d = amax - b
        if (d <= 0L) return null // maximal activity within bound ⇒ always satisfied
        var changed = false
        val lifted = IntArray(n) { i ->
            if (cap[i] > 0L && absA[i].toLong() > d) {
                changed = true
                d.toInt() // d < absA[i] ≤ Int.MAX here, so the narrowing is safe
            } else {
                absA[i]
            }
        }
        if (!changed) return l
        // New bound in z-space: Σ a̅'ⱼcⱼ − d, then de-shift each variable back to xⱼ.
        var newBound = -d
        for (i in 0 until n) newBound += lifted[i].toLong() * cap[i]
        val newCoeffs = IntArray(n)
        for (i in 0 until n) {
            val dom = domains[l.vars[i]]
            if (coeffs[i] >= 0) {
                newCoeffs[i] = lifted[i]
                newBound += lifted[i].toLong() * dom.min
            } else {
                newCoeffs[i] = -lifted[i]
                newBound -= lifted[i].toLong() * dom.max
            }
        }
        if (newBound !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) return l
        return Linear(newCoeffs, l.vars.copyOf(), LinearOp.LE, newBound.toInt())
    }

    private fun strengthenPb(factor: PseudoBoolean): Factor? {
        val g = gcdOf(factor.weights)
        val gcdReduced: PseudoBoolean = if (g <= 1) {
            factor
        } else {
            when (val reduced = reduceBound(toRel(factor.op), factor.bound, g)) {
                is Reduced.Bound -> PseudoBoolean(
                    divAll(factor.weights, g),
                    factor.literals.copyOf(),
                    factor.op,
                    reduced.bound,
                )

                Reduced.Drop -> return null

                Reduced.Unchanged -> factor
            }
        }
        return liftKnapsack(gcdReduced)
    }

    /**
     * Knapsack coefficient lifting (#333) for a `≤` pseudo-Boolean `Σ wⱼ lⱼ ≤ b`. After normalising
     * to positive weights (a negative `wⱼ` becomes `|wⱼ|·¬lⱼ` with the bound raised by `|wⱼ|`), the
     * dual `Σ wⱼ(1−lⱼ) ≥ d` with `d = Σwⱼ − b` is a cover: any weight exceeding `d` contributes no
     * more than `d` to covering it, so each `wⱼ` clamps to `min(wⱼ, d)` and the bound becomes
     * `Σ min(wⱼ,d) − d`. Exact (feasible-set-preserving), and it both shrinks coefficients and
     * tightens the relaxation beyond what GCD reduction reaches. `d ≤ 0` ⟹ the constraint is always
     * satisfied (dropped). A `≥` constraint is first complemented to `≤` (#365); an `=` constraint
     * can't be lifted by clamping (it ties both directions) and is left untouched, as are
     * out-of-Int-range slacks.
     */
    private fun liftKnapsack(input: PseudoBoolean): Factor? {
        // GE → ≤ by complementing literals: Σwⱼlⱼ ≥ b ⟺ Σwⱼ¬lⱼ ≤ Σwⱼ − b. EQ can't be lifted by
        // clamping (it ties both directions), so it passes through.
        val pb: PseudoBoolean = when (input.op) {
            PbOp.LE -> input

            PbOp.EQ -> return input

            PbOp.GE -> {
                var s = 0L
                for (w in input.weights) s += w
                val nb = s - input.bound
                if (nb !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) return input
                PseudoBoolean(
                    input.weights.copyOf(),
                    IntArray(input.literals.size) { Lit.negate(input.literals[it]) },
                    PbOp.LE,
                    nb.toInt(),
                )
            }
        }
        val n = pb.literals.size
        val weights = IntArray(n)
        val lits = IntArray(n)
        var bound = pb.bound.toLong()
        for (i in 0 until n) {
            if (pb.weights[i] < 0) {
                weights[i] = -pb.weights[i]
                lits[i] = Lit.negate(pb.literals[i])
                bound += weights[i]
            } else {
                weights[i] = pb.weights[i]
                lits[i] = pb.literals[i]
            }
        }
        var sum = 0L
        for (w in weights) sum += w
        val d = sum - bound
        if (d <= 0L) return null // always satisfied
        if (d >= sum || d > Int.MAX_VALUE) return input // no weight exceeds d, or slack out of range — leave original
        var changed = false
        var newSum = 0L
        val lifted = IntArray(n) { i ->
            if (weights[i] > d) {
                changed = true
                d.toInt()
            } else {
                weights[i]
            }.also { newSum += it }
        }
        if (!changed) return input
        val newBound = newSum - d
        if (newBound !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) return input
        return PseudoBoolean(lifted, lits, PbOp.LE, newBound.toInt())
    }

    private fun toRel(op: LinearOp): Rel = when (op) {
        LinearOp.LE -> Rel.LE
        LinearOp.GE -> Rel.GE
        LinearOp.EQ -> Rel.EQ
        LinearOp.NE -> Rel.NE
    }

    private fun toRel(op: PbOp): Rel = when (op) {
        PbOp.LE -> Rel.LE
        PbOp.GE -> Rel.GE
        PbOp.EQ -> Rel.EQ
    }

    private fun gcdOf(xs: IntArray): Int {
        var g = 0
        for (x in xs) g = gcd(g, x)
        return g
    }

    private fun gcd(a: Int, b: Int): Int {
        var x = if (a < 0) -a else a
        var y = if (b < 0) -b else b
        while (y != 0) {
            val t = x % y
            x = y
            y = t
        }
        return x
    }

    private fun divAll(xs: IntArray, g: Int): IntArray = IntArray(xs.size) { xs[it] / g }

    /**
     * Symmetry breaking by detecting interchangeable variables (#317). Two variables are
     * *provably* interchangeable when they occur in exactly the same set of factors and play a
     * symmetric role in each — equal coefficient in a [Linear], both arguments of an
     * [AllDifferent], or same-polarity / same-weight literals in a [Clause] / [Cardinality] /
     * [Xor] / [PseudoBoolean]. These factor types are all order-insensitive (sum / set / parity),
     * so any permutation of such a group is a genuine automorphism, and ordering the group
     * (`x₀ ≤ x₁ ≤ …` for ints, `¬gⱼ ∨ gⱼ₊₁` for bools) keeps exactly one representative per orbit
     * — sound (never removes the last solution of an orbit).
     *
     * The "same factor set" requirement is what makes this sound: a variable appearing in a
     * factor its candidate-partner does not is *not* interchangeable. Variables touched by any
     * other factor type are conservatively excluded. Variables in [objectiveIntVars] /
     * [objectiveBoolVars] are excluded so an asymmetric objective can't be cut — keep those sets
     * empty for pure feasibility. (Per the issue policy this runs by default except in a pure
     * local-search portfolio.)
     *
     * Scope: this catches interchangeable *variables*; matrix-row/column and value symmetries and
     * full graph-automorphism detection remain follow-ups.
     */
    fun breakSymmetries(
        problem: Problem,
        objectiveIntVars: Set<Int> = emptySet(),
        objectiveBoolVars: Set<Int> = emptySet(),
    ): Problem {
        // Prefer verified detection (any factor swap proven an automorphism); fall back to the
        // sufficient same-factor-set heuristic when a factor type isn't structurally keyed.
        val verified = verifiedSymmetryOrbits(problem, objectiveIntVars, objectiveBoolVars)
        val intGroups = verified?.first ?: interchangeableIntGroups(problem, objectiveIntVars)
        val boolGroups = verified?.second ?: interchangeableBoolGroups(problem, objectiveBoolVars)
        // Block/row symmetry (#367): interchangeable blocks of int vars (e.g. matrix rows defined by
        // isomorphic factors), ordered by lex-leader. Only when verified detection is available.
        val brokenInts = intGroups.flatMap { it.toList() }.toHashSet()
        val blockLex = if (verified == null) emptyList() else verifiedBlockLex(problem, objectiveIntVars, brokenInts)
        // Bool block/row symmetry (#373): the boolean analogue — rows of bool vars defined by
        // isomorphic bool-only factors, ordered by a binary-number lex-leader.
        val brokenBools = boolGroups.flatMap { it.toList() }.toHashSet()
        val boolBlockLex = if (verified == null) {
            emptyList()
        } else {
            verifiedBoolBlockLex(
                problem,
                objectiveBoolVars,
                brokenBools,
            )
        }
        val valuePins = breakValueSymmetry(problem, objectiveIntVars)
        if (intGroups.isEmpty() && boolGroups.isEmpty() && blockLex.isEmpty() &&
            boolBlockLex.isEmpty() && valuePins.isEmpty()
        ) {
            return problem
        }
        val extra = ArrayList<Factor>()
        for (group in intGroups) {
            for (j in 0 until group.size - 1) {
                extra.add(Linear(intArrayOf(1, -1), intArrayOf(group[j], group[j + 1]), LinearOp.LE, 0))
            }
        }
        for (group in boolGroups) {
            for (j in 0 until group.size - 1) {
                extra.add(Clause(intArrayOf(Lit.make(group[j], false), Lit.make(group[j + 1], true))))
            }
        }
        extra.addAll(blockLex)
        extra.addAll(boolBlockLex)
        extra.addAll(valuePins)
        return rebuildProblem(problem, problem.factors.toList() + extra)
    }

    /**
     * Value symmetry breaking (#366, #374). A permutation of values that maps every domain to itself
     * and the factor set to itself is a symmetry. Candidate orbits are values with the same
     * domain-incidence (the set of variables whose domain contains them) — so any transposition
     * within an orbit already maps every domain to itself. Each transposition is then *verified*
     * against the factors: applying it via [Factor.remapValues] and comparing the [Factor.structuralKey]
     * multiset proves the swap is a symmetry, the value analog of the [Factor.remap]-based automorphism check
     * (#334). Transpositions generate the full symmetric group on a verified orbit, so one variable
     * whose domain lies entirely within an orbit is pinned to the orbit minimum — a sound break (a
     * solution can always be relabeled within the orbit so that variable takes the minimum).
     *
     * When every factor is value-anonymous ([Factor.isValueAnonymous] — AllDifferent), verification is
     * skipped: anonymity means every relabeling is a symmetry, so the whole incidence group is one
     * orbit (the #366 fast path). Otherwise verification widens detection to problems with
     * value-relabelable factors (GlobalCardinality, Table, …) that the anonymity gate switched off; a
     * factor that is unkeyed or returns `null` from [Factor.remapValues] conservatively blocks it.
     *
     * The stronger Law–Lee value precedence (ordering first-occurrences across all variables) needs
     * auxiliary variables and a var-growing reconstruction, and is a follow-up.
     */
    private fun breakValueSymmetry(problem: Problem, objectiveIntVars: Set<Int>): List<Factor> {
        if (problem.numIntVars == 0) return emptyList()
        val allAnonymous = problem.factors.all { it.isValueAnonymous() }
        // Verified path needs every factor keyed; build the base multiset (bail if any is unkeyed).
        val base: Map<String, Int>? = if (allAnonymous) {
            null
        } else {
            val m = HashMap<String, Int>()
            for (f in problem.factors) {
                val k = f.structuralKey() ?: return emptyList()
                m[k] = (m[k] ?: 0) + 1
            }
            m
        }
        var lo = Int.MAX_VALUE
        var hi = Int.MIN_VALUE
        for (d in problem.intDomains) {
            if (d.min < lo) lo = d.min
            if (d.max > hi) hi = d.max
        }
        if (lo > hi) return emptyList()
        // Group values by domain-incidence signature: same set of containing variables ⇒ a candidate
        // orbit (a swap within it maps every domain to itself).
        val incidence = HashMap<String, MutableList<Int>>()
        for (value in lo..hi) {
            val sig = StringBuilder()
            for (x in 0 until problem.numIntVars) if (value in problem.intDomains[x]) sig.append(x).append(',')
            if (sig.isNotEmpty()) incidence.getOrPut(sig.toString()) { ArrayList() }.add(value)
        }
        val extra = ArrayList<Factor>()
        for (candidate in incidence.values) {
            if (candidate.size < 2) continue
            // Anonymous: the whole group is one orbit. Otherwise refine into verified-equal orbits.
            val orbits =
                if (allAnonymous) listOf(candidate) else verifyValueOrbits(problem, requireNotNull(base), candidate)
            for (orbit in orbits) {
                if (orbit.size < 2) continue
                val orbitSet = orbit.toHashSet()
                val minValue = orbit.min()
                for (x in 0 until problem.numIntVars) {
                    if (x in objectiveIntVars) continue
                    if (domainWithin(problem.intDomains[x], orbitSet)) {
                        extra.add(Linear(intArrayOf(1), intArrayOf(x), LinearOp.EQ, minValue))
                        break
                    }
                }
            }
        }
        return extra
    }

    /**
     * Law–Lee value precedence (#374), the strong value-symmetry break, posted with the native
     * [ValuePrecede] propagator (#432). For the value-anonymous case (#366: every factor is
     * [Factor.isValueAnonymous], so any value relabeling is a symmetry), each orbit of interchangeable
     * values is forced to be *introduced in sorted order*: the first occurrence of the orbit's `j`-th
     * smallest value precedes the first occurrence of its `(j+1)`-th, over the variables whose domain
     * is that orbit. This is a `value_precede_chain` — one [ValuePrecede] per consecutive value pair.
     * Every solution can be relabeled within the orbit to this canonical "restricted-growth" form, so
     * exactly one representative per symmetry class survives — strictly stronger than pinning a single
     * variable ([breakValueSymmetry]).
     *
     * Only the value-anonymous setting is handled: there an orbit equals a value-incidence class, so
     * every fully-internal variable's domain is *exactly* the orbit (incidence-equality forces it),
     * which is what makes ordering the first occurrences sound. Non-anonymous problems keep the
     * verified single-variable pin. Unlike the original decomposition this needs no auxiliary
     * variables — the native factor reasons over arbitrary (not just consecutive) value pairs — so
     * the variable space is unchanged and no reconstruction is required.
     *
     * Variables in [objectiveIntVars] are excluded (ordering them would change the optimum). Returns
     * the original problem unchanged when nothing is eligible.
     */
    fun breakValuePrecedence(problem: Problem, objectiveIntVars: Set<Int> = emptySet()): Problem {
        val n = problem.numIntVars
        if (n == 0) return problem
        // Value-anonymous fast path: every value relabeling is a symmetry, so each domain-incidence
        // group is one fully-interchangeable orbit. Otherwise verify the orbits against the
        // value-relabelable factors (#442) — needs every factor keyed, else bail.
        val allAnonymous = problem.factors.all { it.isValueAnonymous() }
        val base: Map<String, Int>? = if (allAnonymous) {
            null
        } else {
            val m = HashMap<String, Int>()
            for (f in problem.factors) {
                val k = f.structuralKey() ?: return problem
                m[k] = (m[k] ?: 0) + 1
            }
            m
        }
        var lo = Int.MAX_VALUE
        var hi = Int.MIN_VALUE
        for (d in problem.intDomains) {
            if (d.min < lo) lo = d.min
            if (d.max > hi) hi = d.max
        }
        if (lo > hi) return problem
        val incidence = HashMap<String, MutableList<Int>>()
        for (value in lo..hi) {
            val sig = StringBuilder()
            for (x in 0 until n) if (value in problem.intDomains[x]) sig.append(x).append(',')
            if (sig.isNotEmpty()) incidence.getOrPut(sig.toString()) { ArrayList() }.add(value)
        }
        val extra = ArrayList<Factor>()
        for (candidate in incidence.values) {
            if (candidate.size < 2) continue
            // A verified orbit is interchangeable; ordering its first occurrences is sound. A
            // fully-internal variable (domain ⊆ orbit) exists only when the orbit equals the whole
            // incidence group, so a split orbit simply posts nothing — never unsound.
            val orbits =
                if (allAnonymous) listOf(candidate) else verifyValueOrbits(problem, requireNotNull(base), candidate)
            for (orbit in orbits) {
                if (orbit.size < 2) continue
                val orbitSet = orbit.toHashSet()
                val seq = ArrayList<Int>()
                for (x in 0 until n) {
                    if (x !in objectiveIntVars && domainWithin(problem.intDomains[x], orbitSet)) seq.add(x)
                }
                if (seq.size < 2) continue
                val sortedValues = orbit.sorted()
                val seqArray = seq.toIntArray()
                for (i in 0 until sortedValues.size - 1) {
                    extra.add(ValuePrecede(sortedValues[i], sortedValues[i + 1], seqArray))
                }
            }
        }
        if (extra.isEmpty()) return problem
        return rebuildProblem(problem, problem.factors.toList() + extra)
    }

    /** Refine a domain-incidence candidate [values] into verified-interchangeable value orbits: union
     *  the value pairs whose transposition is verified a symmetry ([verifyValueSwap]). Transpositions
     *  generate the full symmetric group on each resulting orbit. Groups beyond [MAX_VERIFIED_GROUP]
     *  are skipped (the O(n²·factors) guard, as for variables). */
    private fun verifyValueOrbits(problem: Problem, base: Map<String, Int>, values: List<Int>): List<List<Int>> {
        val n = values.size
        if (n > MAX_VERIFIED_GROUP) return emptyList()
        val parent = IntArray(n) { it }
        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) r = parent[r]
            return r
        }
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                if (find(
                        i,
                    ) != find(j) && verifyValueSwap(problem, base, values[i], values[j])
                ) {
                    parent[find(i)] = find(j)
                }
            }
        }
        val byRoot = HashMap<Int, MutableList<Int>>()
        for (i in 0 until n) byRoot.getOrPut(find(i)) { ArrayList() }.add(values[i])
        return byRoot.values.toList()
    }

    /** Whether the value transposition `(v w)` maps the factor multiset to itself — relabel every
     *  factor via [Factor.remapValues] and compare [Factor.structuralKey] counts against [base].
     *  `false` if any factor is not value-relabelable (returns `null`). The value analog of
     *  [isAutomorphism]. */
    private fun verifyValueSwap(problem: Problem, base: Map<String, Int>, v: Int, w: Int): Boolean {
        val swap = { x: Int ->
            if (x == v) {
                w
            } else if (x == w) {
                v
            } else {
                x
            }
        }
        val counts = HashMap<String, Int>(base.size)
        for (f in problem.factors) {
            val key = (f.remapValues(swap) ?: return false).structuralKey() ?: return false
            val next = (counts[key] ?: 0) + 1
            if (next > (base[key] ?: 0)) return false
            counts[key] = next
        }
        return counts == base
    }

    /** Whether every value in [d] lies in [values]. */
    private fun domainWithin(d: IntDomain, values: Set<Int>): Boolean {
        for (v in d.min..d.max) if (v in d && v !in values) return false
        return true
    }

    /**
     * Verified interchangeable-variable detection (#334): a variable transposition is a genuine
     * symmetry iff swapping the two variables maps the factor multiset onto itself. Each candidate
     * swap is *checked* by remapping every factor and comparing structural keys — so it catches
     * symmetries the same-factor-set heuristic misses (variables in different but isomorphic
     * factors, matrix rows), and is sound by construction. Returns `null` when any factor lacks a
     * [Factor.structuralKey] (then the caller uses the conservative heuristic). Returns the int and
     * bool orbits (size ≥ 2) otherwise; objective variables are excluded.
     *
     * Candidate groups come from Weisfeiler–Leman colour refinement ([refineColours], #373): only
     * same-colour variables can be interchangeable, so the colour classes are exactly the candidate
     * groups, finer than the old domain-only / single-bool-group partition. This finds more (finer
     * classes fit under the [MAX_VERIFIED_GROUP] size guard that would skip a large coarse group)
     * and verifies fewer impossible pairs — and stays sound because each candidate is still verified.
     */
    private fun verifiedSymmetryOrbits(
        problem: Problem,
        objectiveIntVars: Set<Int>,
        objectiveBoolVars: Set<Int>,
    ): Pair<List<IntArray>, List<IntArray>>? {
        val base = HashMap<String, Int>()
        for (f in problem.factors) {
            val key = f.structuralKey() ?: return null
            base[key] = (base[key] ?: 0) + 1
        }
        val intMap = IntArray(problem.numIntVars) { it }
        val boolMap = IntArray(problem.numBoolVars) { it }

        val (intColour, boolColour) = refineColours(problem, objectiveIntVars, objectiveBoolVars)
        val intCandidates = HashMap<Int, MutableList<Int>>()
        for (v in 0 until problem.numIntVars) {
            if (v !in objectiveIntVars) intCandidates.getOrPut(intColour[v]) { ArrayList() }.add(v)
        }
        val intOrbits = buildVerifiedOrbits(problem.numIntVars, intCandidates.values.toList()) { u, v ->
            intMap[u] = v
            intMap[v] = u
            val ok = isAutomorphism(problem, base, boolMap, intMap)
            intMap[u] = u
            intMap[v] = v
            ok
        }
        val boolCandidates = HashMap<Int, MutableList<Int>>()
        for (v in 0 until problem.numBoolVars) {
            if (v !in objectiveBoolVars) boolCandidates.getOrPut(boolColour[v]) { ArrayList() }.add(v)
        }
        val boolOrbits = buildVerifiedOrbits(problem.numBoolVars, boolCandidates.values.toList()) { u, v ->
            boolMap[u] = v
            boolMap[v] = u
            val ok = isAutomorphism(problem, base, boolMap, intMap)
            boolMap[u] = u
            boolMap[v] = v
            ok
        }
        return intOrbits to boolOrbits
    }

    /** Sentinel "variable id" marking the focal variable in a [refineColours] port signature; far
     *  above any colour id (colours are small dense counters) so it never collides with one. */
    private const val WL_FOCAL = 1_000_000_000

    /**
     * Weisfeiler–Leman colour refinement (#373) seeding verified-symmetry candidates. Two variables
     * can be interchangeable only if they share a WL colour (colour is an automorphism invariant),
     * so the colour classes are the candidate groups — finer than grouping ints by domain and all
     * bools together. Returns `(intColour, boolColour)`, parallel to the variable ids.
     *
     * Initial colour separates kinds, distinct domains, and each objective variable (a distinguished
     * fixed point). Each round refines a variable's colour by its current colour plus, for every
     * incident factor, that factor's [Factor.structuralKey] computed with the focal variable
     * remapped to [WL_FOCAL] and every other variable to its current colour — the WL "edge"
     * signature, derived generically for any keyed factor with no per-type code. Iterated to a
     * fixpoint (partition stops refining). Soundness never rests on this: the pairwise/block verifier
     * re-checks every candidate, so a wrong colouring can only miss symmetries, never invent one.
     */
    private fun refineColours(
        problem: Problem,
        objectiveIntVars: Set<Int>,
        objectiveBoolVars: Set<Int>,
    ): Pair<IntArray, IntArray> {
        val nInt = problem.numIntVars
        val nBool = problem.numBoolVars
        val intInc = Array(nInt) { ArrayList<Int>() }
        val boolInc = Array(nBool) { ArrayList<Int>() }
        problem.factors.forEachIndexed { fi, f ->
            for (v in f.intVars.distinct()) intInc[v].add(fi)
            for (v in f.boolVars.distinct()) boolInc[v].add(fi)
        }
        val intColour = IntArray(nInt)
        val boolColour = IntArray(nBool)
        val initInt = Array(nInt) { v ->
            if (v in objectiveIntVars) "o$v" else domainKey(problem.intDomains[v])
        }
        val initBool = Array(nBool) { v -> if (v in objectiveBoolVars) "o$v" else "b" }
        var numColours = assignColours(initInt, initBool, intColour, boolColour)
        // Working colour maps reused across all port queries in a round (rebuilt each round).
        val intMap = IntArray(nInt)
        val boolMap = IntArray(nBool)
        repeat(nInt + nBool + 1) {
            for (v in 0 until nInt) intMap[v] = intColour[v]
            for (v in 0 until nBool) boolMap[v] = boolColour[v]
            val sigInt = Array(
                nInt,
            ) { v -> portSignature(problem, intInc[v], v, isBool = false, intMap, boolMap, intColour[v]) }
            val sigBool =
                Array(
                    nBool,
                ) { v -> portSignature(problem, boolInc[v], v, isBool = true, intMap, boolMap, boolColour[v]) }
            val next = assignColours(sigInt, sigBool, intColour, boolColour)
            if (next == numColours) return intColour to boolColour // partition stable
            numColours = next
        }
        return intColour to boolColour
    }

    /** WL signature of variable [v] this round: its [oldColour] plus the sorted multiset of incident
     *  factor keys, each computed with [v] remapped to [WL_FOCAL] (the focal marker) and every other
     *  variable to its current colour (already loaded into [intMap]/[boolMap]). */
    private fun portSignature(
        problem: Problem,
        incident: List<Int>,
        v: Int,
        isBool: Boolean,
        intMap: IntArray,
        boolMap: IntArray,
        oldColour: Int,
    ): String {
        val ports = ArrayList<String>(incident.size)
        for (fi in incident) {
            val saved: Int
            if (isBool) {
                saved = boolMap[v]
                boolMap[v] = WL_FOCAL
            } else {
                saved = intMap[v]
                intMap[v] = WL_FOCAL
            }
            ports.add(problem.factors[fi].remap(boolMap, intMap).structuralKey() ?: "?")
            if (isBool) boolMap[v] = saved else intMap[v] = saved
        }
        ports.sort()
        return "$oldColour|" + ports.joinToString(";")
    }

    /** Re-colour every variable by its signature, writing dense ids into [intColour]/[boolColour] and
     *  returning the number of distinct colours. Int and bool signatures are kept in disjoint spaces
     *  (prefixed) so the two kinds never share a colour. */
    private fun assignColours(
        sigInt: Array<String>,
        sigBool: Array<String>,
        intColour: IntArray,
        boolColour: IntArray,
    ): Int {
        val ids = HashMap<String, Int>()
        for (v in sigInt.indices) intColour[v] = ids.getOrPut("I" + sigInt[v]) { ids.size }
        for (v in sigBool.indices) boolColour[v] = ids.getOrPut("B" + sigBool[v]) { ids.size }
        return ids.size
    }

    /** Test-only view of [refineColours] with no objective variables (#373). */
    internal fun refineColoursForTest(problem: Problem): Pair<IntArray, IntArray> =
        refineColours(problem, emptySet(), emptySet())

    /** Whether remapping every factor through [boolMap]/[intMap] leaves the factor multiset (by
     *  structural key) unchanged — i.e. the maps encode an automorphism of the constraint set. */
    private fun isAutomorphism(problem: Problem, base: Map<String, Int>, boolMap: IntArray, intMap: IntArray): Boolean {
        val counts = HashMap<String, Int>(base.size)
        for (f in problem.factors) {
            val key = f.remap(boolMap, intMap).structuralKey() ?: return false
            val next = (counts[key] ?: 0) + 1
            if (next > (base[key] ?: 0)) return false // already can't match the multiset
            counts[key] = next
        }
        return counts == base
    }

    /**
     * Verified block / row symmetry (#367): groups of int variables defined by *isomorphic* factors
     * (e.g. matrix rows, each an AllDifferent over a distinct row) are interchangeable as blocks.
     * Candidate blocks are the sorted-variable sets of factors sharing a canonical shape; a block
     * pair is verified an automorphism via [isAutomorphism] (with position-wise equal domains), and
     * verified-equal blocks are ordered by a lex-leader [LexLess] chain. Skips bool-touching factors,
     * objective variables, and variables already broken as single-var orbits ([alreadyBroken]) so
     * row and cell breaking don't interact unsoundly.
     */
    private fun verifiedBlockLex(problem: Problem, objectiveIntVars: Set<Int>, alreadyBroken: Set<Int>): List<Factor> {
        val base = HashMap<String, Int>()
        for (f in problem.factors) {
            val k = f.structuralKey() ?: return emptyList()
            base[k] = (base[k] ?: 0) + 1
        }
        val byShape = HashMap<String, MutableList<IntArray>>()
        for (f in problem.factors) {
            if (f.boolVars.isNotEmpty() || f.intVars.isEmpty()) continue
            val block = f.intVars.distinct().sorted().toIntArray()
            if (block.any { it in objectiveIntVars || it in alreadyBroken }) continue
            val shape = canonicalShape(problem, f, block) ?: continue
            byShape.getOrPut(shape) { ArrayList() }.add(block)
        }
        val intMap = IntArray(problem.numIntVars) { it }
        val extra = ArrayList<Factor>()
        for ((_, blocks) in byShape) {
            if (blocks.size < 2 || blocks.size > MAX_VERIFIED_GROUP) continue
            val parent = IntArray(blocks.size) { it }
            fun find(x: Int): Int {
                var r = x
                while (parent[r] != r) r = parent[r]
                return r
            }
            for (i in blocks.indices) {
                for (j in i + 1 until blocks.size) {
                    if (find(i) != find(j) && blocksSwapVerified(problem, base, intMap, blocks[i], blocks[j])) {
                        parent[find(i)] = find(j)
                    }
                }
            }
            val byRoot = HashMap<Int, MutableList<IntArray>>()
            for (i in blocks.indices) byRoot.getOrPut(find(i)) { ArrayList() }.add(blocks[i])
            for (cls in byRoot.values) {
                val ordered = cls.sortedBy { it[0] }
                for (k in 0 until ordered.size - 1) extra.add(LexLess(ordered[k], ordered[k + 1], strict = false))
            }
        }
        return extra
    }

    /** Canonical structure key for a block: remap its (sorted) variables to `0..k-1`, so two
     *  isomorphic factors over disjoint variables share a key. `null` if the factor isn't keyed. */
    private fun canonicalShape(problem: Problem, f: Factor, block: IntArray): String? {
        val intMap = IntArray(problem.numIntVars) { it }
        for (k in block.indices) intMap[block[k]] = k
        return f.remap(IntArray(problem.numBoolVars) { it }, intMap).structuralKey()
    }

    /** Whether swapping disjoint blocks [a] and [b] position-wise (`a[k] ↔ b[k]`) is an automorphism
     *  and each position has equal domains (domains aren't encoded in factors, so checked here). */
    private fun blocksSwapVerified(
        problem: Problem,
        base: Map<String, Int>,
        intMap: IntArray,
        a: IntArray,
        b: IntArray,
    ): Boolean {
        if (a.size != b.size) return false
        for (k in a.indices) {
            if (a[k] in b) return false // overlapping blocks: swap would tangle
            if (domainKey(problem.intDomains[a[k]]) != domainKey(problem.intDomains[b[k]])) return false
        }
        for (k in a.indices) {
            intMap[a[k]] = b[k]
            intMap[b[k]] = a[k]
        }
        val ok = isAutomorphism(problem, base, IntArray(problem.numBoolVars) { it }, intMap)
        for (k in a.indices) {
            intMap[a[k]] = a[k]
            intMap[b[k]] = b[k]
        }
        return ok
    }

    /**
     * Verified bool-block lex (#373): the boolean analogue of [verifiedBlockLex]. Blocks of Boolean
     * variables defined by *isomorphic* bool-only factors (rows of a 0/1 matrix) are interchangeable
     * as blocks; verified-equal blocks are ordered by a lexicographic-leader chain. Skips factors that
     * touch int variables, objective bools, and bools already broken as single-var orbits
     * ([alreadyBroken]) so row and cell breaking don't interact unsoundly.
     *
     * A Boolean lex-leader `a ≤ₗₑₓ b` is posted as a [PseudoBoolean] (see [boolLexLeader]): reading
     * each id-sorted row as a binary number with the first position most-significant, lexicographic
     * order on equal-length 0/1 vectors is exactly numeric order. The weights are powers of two, so a
     * row wider than [MAX_BOOL_LEX_WIDTH] (where `2^(m−1)` overflows `Int`) is skipped — sound, just
     * unbroken; the aux-variable lex encoding for wider rows is a follow-up.
     */
    private fun verifiedBoolBlockLex(
        problem: Problem,
        objectiveBoolVars: Set<Int>,
        alreadyBroken: Set<Int>,
    ): List<Factor> {
        val base = HashMap<String, Int>()
        for (f in problem.factors) {
            val k = f.structuralKey() ?: return emptyList()
            base[k] = (base[k] ?: 0) + 1
        }
        val byShape = HashMap<String, MutableList<IntArray>>()
        for (f in problem.factors) {
            if (f.intVars.isNotEmpty() || f.boolVars.isEmpty()) continue
            val block = f.boolVars.distinct().sorted().toIntArray()
            if (block.size > MAX_BOOL_LEX_WIDTH) continue
            if (block.any { it in objectiveBoolVars || it in alreadyBroken }) continue
            val shape = canonicalBoolShape(problem, f, block) ?: continue
            byShape.getOrPut(shape) { ArrayList() }.add(block)
        }
        val boolMap = IntArray(problem.numBoolVars) { it }
        val extra = ArrayList<Factor>()
        for ((_, blocks) in byShape) {
            if (blocks.size < 2 || blocks.size > MAX_VERIFIED_GROUP) continue
            val parent = IntArray(blocks.size) { it }
            fun find(x: Int): Int {
                var r = x
                while (parent[r] != r) r = parent[r]
                return r
            }
            for (i in blocks.indices) {
                for (j in i + 1 until blocks.size) {
                    if (find(i) != find(j) && boolBlocksSwapVerified(problem, base, boolMap, blocks[i], blocks[j])) {
                        parent[find(i)] = find(j)
                    }
                }
            }
            val byRoot = HashMap<Int, MutableList<IntArray>>()
            for (i in blocks.indices) byRoot.getOrPut(find(i)) { ArrayList() }.add(blocks[i])
            for (cls in byRoot.values) {
                val ordered = cls.sortedBy { it[0] }
                for (k in 0 until ordered.size - 1) extra.add(boolLexLeader(ordered[k], ordered[k + 1]))
            }
        }
        return extra
    }

    /** Canonical structure key for a bool block: remap its (sorted) variables to `0..k-1`, so two
     *  isomorphic bool-only factors over disjoint variables share a key. `null` if unkeyed. */
    private fun canonicalBoolShape(problem: Problem, f: Factor, block: IntArray): String? {
        val boolMap = IntArray(problem.numBoolVars) { it }
        for (k in block.indices) boolMap[block[k]] = k
        return f.remap(boolMap, IntArray(problem.numIntVars) { it }).structuralKey()
    }

    /** Whether swapping disjoint bool blocks [a] and [b] position-wise (`a[k] ↔ b[k]`) is an
     *  automorphism. Bools carry no domain, so (unlike [blocksSwapVerified]) there is no domain
     *  check — only structural verification via [isAutomorphism]. */
    private fun boolBlocksSwapVerified(
        problem: Problem,
        base: Map<String, Int>,
        boolMap: IntArray,
        a: IntArray,
        b: IntArray,
    ): Boolean {
        if (a.size != b.size) return false
        for (k in a.indices) if (a[k] in b) return false // overlapping blocks: swap would tangle
        for (k in a.indices) {
            boolMap[a[k]] = b[k]
            boolMap[b[k]] = a[k]
        }
        val ok = isAutomorphism(problem, base, boolMap, IntArray(problem.numIntVars) { it })
        for (k in a.indices) {
            boolMap[a[k]] = a[k]
            boolMap[b[k]] = b[k]
        }
        return ok
    }

    /** Lex-leader `a ≤ₗₑₓ b` on two equal-length bool rows as a [PseudoBoolean]. Reading each row
     *  as a binary number (position 0 most-significant), lexicographic order is numeric order, so
     *  `Σ 2^(m−1−k)·a`k` − Σ 2^(m−1−k)·b`k` ≤ 0`. Callers must keep `a.size == b.size ≤`
     *  [MAX_BOOL_LEX_WIDTH] so the top weight `2^(m−1)` fits in `Int`. */
    private fun boolLexLeader(a: IntArray, b: IntArray): PseudoBoolean {
        val m = a.size
        val literals = IntArray(2 * m)
        val weights = IntArray(2 * m)
        for (k in 0 until m) {
            val w = 1 shl (m - 1 - k)
            literals[k] = Lit.make(a[k], true)
            weights[k] = w
            literals[m + k] = Lit.make(b[k], true)
            weights[m + k] = -w
        }
        return PseudoBoolean(weights, literals, PbOp.LE, 0)
    }

    /** Union the candidate variables whose pairwise transposition [verify]s as a symmetry, then
     *  return the resulting orbits of size ≥ 2 (each sorted). Transpositions generate the full
     *  symmetric group on an orbit, so a total order over it is a sound symmetry break. */
    private fun buildVerifiedOrbits(
        numVars: Int,
        candidateGroups: List<List<Int>>,
        verify: (Int, Int) -> Boolean,
    ): List<IntArray> {
        val parent = IntArray(numVars) { it }
        fun find(x: Int): Int {
            var root = x
            while (parent[root] != root) root = parent[root]
            var cur = x
            while (parent[cur] != cur) {
                val next = parent[cur]
                parent[cur] = root
                cur = next
            }
            return root
        }
        for (group in candidateGroups) {
            // Size guard (#367): each group costs O(size² × factors) verifications. Skip groups
            // beyond the cap — fewer symmetries broken, never unsound.
            if (group.size > MAX_VERIFIED_GROUP) continue
            for (i in group.indices) {
                for (j in i + 1 until group.size) {
                    val u = group[i]
                    val v = group[j]
                    if (find(u) != find(v) && verify(u, v)) parent[find(u)] = find(v)
                }
            }
        }
        val byRoot = HashMap<Int, MutableList<Int>>()
        for (group in candidateGroups) for (v in group) byRoot.getOrPut(find(v)) { ArrayList() }.add(v)
        return byRoot.values.filter { it.size >= 2 }.map { it.sorted().toIntArray() }
    }

    /** Domain signature so only variables with the *same* domain (bounds and holes) can group. */
    private fun domainKey(d: IntDomain): String = "${d.min}:${d.max}:${d.holes?.joinToString("-").orEmpty()}"

    private fun interchangeableIntGroups(problem: Problem, objectiveVars: Set<Int>): List<IntArray> {
        val n = problem.numIntVars
        if (n == 0) return emptyList()
        val eligible = BooleanArray(n) { true }
        val roles = Array(n) { ArrayList<String>() }
        for (fi in problem.factors.indices) {
            when (val f = problem.factors[fi]) {
                is Linear -> for (i in f.vars.indices) roles[f.vars[i]].add("$fi:lin:${f.coeffs[i]}")
                is AllDifferent -> for (v in f.vars) roles[v].add("$fi:ad")
                else -> for (v in f.intVars) eligible[v] = false // unsupported factor type
            }
        }
        val groups = HashMap<String, MutableList<Int>>()
        for (v in 0 until n) {
            if (!eligible[v] || v in objectiveVars) continue
            roles[v].sort()
            groups.getOrPut("${domainKey(problem.intDomains[v])}|${roles[v].joinToString(",")}") { ArrayList() }.add(v)
        }
        return groups.values.filter { it.size >= 2 }.map { it.toIntArray() }
    }

    private fun interchangeableBoolGroups(problem: Problem, objectiveVars: Set<Int>): List<IntArray> {
        val n = problem.numBoolVars
        if (n == 0) return emptyList()
        val eligible = BooleanArray(n) { true }
        val roles = Array(n) { ArrayList<String>() }
        for (fi in problem.factors.indices) {
            when (val f = problem.factors[fi]) {
                is Clause -> for (l in f.literals) roles[Lit.variable(l)].add("$fi:cl:${Lit.isPositive(l)}")

                is Cardinality -> for (l in f.literals) roles[Lit.variable(l)].add("$fi:card:${Lit.isPositive(l)}")

                is Xor -> for (l in f.literals) roles[Lit.variable(l)].add("$fi:xor:${Lit.isPositive(l)}")

                is PseudoBoolean -> for (i in f.literals.indices) {
                    roles[Lit.variable(f.literals[i])].add("$fi:pb:${f.weights[i]}:${Lit.isPositive(f.literals[i])}")
                }

                else -> for (v in f.boolVars) eligible[v] = false // unsupported factor type
            }
        }
        val groups = HashMap<String, MutableList<Int>>()
        for (v in 0 until n) {
            if (!eligible[v] || v in objectiveVars) continue
            roles[v].sort()
            groups.getOrPut(roles[v].joinToString(",")) { ArrayList() }.add(v)
        }
        return groups.values.filter { it.size >= 2 }.map { it.toIntArray() }
    }
}

/** A single affine elimination `x = (constTerm + Σ termCoeffs·termVars) / divisor` recorded by
 *  [Presolve.eliminateAffineSingletons]. [divisor] is `1` for the unit-pivot cases and the
 *  pivot coefficient for a residue-class doubleton (#522), where the division is always exact on the
 *  values the partner's restricted domain admits. */
internal class AffineSub(
    val x: Int,
    val constTerm: Int,
    val termVars: IntArray,
    val termCoeffs: IntArray,
    val divisor: Int = 1,
)

/**
 * Reduced problem from [Presolve.eliminateAffineSingletons] plus the data to rebuild the
 * eliminated variables. Solve [problem], then pass the solution through [reconstruct] to recover
 * a solution of the original problem.
 */
class AffineElimination internal constructor(
    /** The problem with affine-defined variables eliminated. */
    val problem: Problem,
    private val subs: List<AffineSub>,
) {
    /** Recover the eliminated variables in a solution [sample] of [problem]. Processed in reverse
     *  elimination order: an eliminated `x` may depend on a `y` eliminated later (a chain), and a
     *  later elimination never depends on an earlier one (the candidate scan skips already-eliminated
     *  partners), so reverse order guarantees every `y` is reconstructed before the `x` that reads it. */
    fun reconstruct(sample: Sample): Sample {
        if (subs.isEmpty()) return sample
        val ints = sample.ints.copyOf()
        for (s in subs.asReversed()) {
            var v = s.constTerm
            for (k in s.termVars.indices) v += s.termCoeffs[k] * ints[s.termVars[k]]
            ints[s.x] = if (s.divisor == 1) v else v / s.divisor
        }
        return Sample(sample.bools, ints)
    }
}
