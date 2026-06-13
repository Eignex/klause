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
import com.eignex.klause.solver.factor.Xor

/**
 * Problem-level presolve transforms. Each takes a [Problem] and returns an equivalent one with
 * a smaller / tighter formulation. Pure (no solving); the caller decides when to apply them.
 */
object Presolve {

    /** Cap on a verified-symmetry candidate group; larger groups are skipped (#367 size guard). */
    private const val MAX_VERIFIED_GROUP = 40

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
     * Affine variable elimination (#318/#335). Eliminates an integer variable `x` defined by a
     * two-term equality `c_x·x + c_y·y = b` with `|c_x| = 1`, i.e. `x = A·y + B` where `A = −c_x·c_y`
     * and `B = c_x·b`. The defining equality is dropped, the affine relation is folded into every
     * other factor that mentions `x`, and bounds on `y` are added so `x` stays inside its declared
     * domain; `x` becomes unconstrained and is rebuilt from the solution via
     * [AffineElimination.reconstruct].
     *
     * For the **alias** case `x = y` (`A = 1`, `B = 0`) the substitution `x → y` is a plain variable
     * rename, applied to *every* factor via [Factor.remap] regardless of type (#364). Otherwise the
     * relation can only fold into a weighted sum, so `x` is eliminated only when its other
     * occurrences are all [Linear] — a global constraint (AllDifferent, Element, …) needs `x` as a
     * genuine variable and cannot absorb `A·y + B` for non-unit `A` / non-zero `B`. The #318
     * contained slice (`x` in no other factor) is the zero-fold special case.
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
            subs.add(AffineSub(cand.x, cand.cx, cand.cy, cand.y, cand.bound))
        }
        if (subs.isEmpty()) return AffineElimination(problem, emptyList())
        return AffineElimination(rebuildProblem(problem, factors), subs)
    }

    /** A 2-term `EQ` [Linear] at [defIdx] defining `x` (unit coefficient). The other occurrences of
     *  `x` are either all foldable (Linear) or — for the alias case `x = y` — substituted via
     *  [Factor.remap] into any factor type. */
    private class AffineCandidate(val defIdx: Int, val x: Int, val cx: Int, val y: Int, val cy: Int, val bound: Int)

    private fun findAffineCandidate(
        factors: List<Factor>,
        eliminated: BooleanArray,
        objectiveIntVars: Set<Int>,
    ): AffineCandidate? {
        for (di in factors.indices) {
            val f = factors[di]
            if (f !is Linear || f.op != LinearOp.EQ || f.vars.size != 2) continue
            for (xi in 0..1) {
                val x = f.vars[xi]
                val y = f.vars[1 - xi]
                val cx = f.coeffs[xi]
                if ((cx != 1 && cx != -1) || eliminated[x] || eliminated[y] || x == y || x in objectiveIntVars) continue
                // x = A·y + B; the alias case (A=1, B=0, i.e. x = y) substitutes into ANY factor via
                // remap, otherwise x must occur only in foldable Linear factors.
                val isAlias = -cx * f.coeffs[1 - xi] == 1 && cx * f.bound == 0
                if (isAlias || otherOccurrencesAllLinear(factors, di, x)) {
                    return AffineCandidate(di, x, cx, y, f.coeffs[1 - xi], f.bound)
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
     *  into every other factor via [Factor.remap] (any factor type); otherwise fold `x = A·y + B`
     *  into every other Linear mentioning `x`. In both cases bounds on `y` keep `x` within its
     *  domain. */
    private fun foldOutVariable(problem: Problem, factors: List<Factor>, c: AffineCandidate): List<Factor> {
        val a = -c.cx * c.cy
        val b = c.cx * c.bound
        val out = ArrayList<Factor>(factors.size + 1)
        if (a == 1 && b == 0) {
            val boolMap = IntArray(problem.numBoolVars) { it }
            val intMap = IntArray(problem.numIntVars) { it }
            intMap[c.x] = c.y
            for (i in factors.indices) if (i != c.defIdx) out.add(factors[i].remap(boolMap, intMap))
        } else {
            for (i in factors.indices) {
                if (i == c.defIdx) continue
                val f = factors[i]
                out.add(if (f is Linear && c.x in f.vars) foldAffineIntoLinear(f, c.x, c.y, a, b) else f)
            }
        }
        out.addAll(domainBoundsOnY(problem.intDomains[c.x], c.cx, c.cy, c.y, c.bound))
        return out
    }

    /** [l] with `x` replaced by `A·y + B`: drop `x`'s term, add `A·coeff_x` to `y`, shift the bound
     *  by `−B·coeff_x`. The [Linear] constructor re-coalesces `y` with any existing `y` term. */
    private fun foldAffineIntoLinear(l: Linear, x: Int, y: Int, a: Int, b: Int): Linear {
        val ix = l.vars.indexOf(x)
        val cX = l.coeffs[ix]
        val newVars = IntArray(l.vars.size)
        val newCoeffs = IntArray(l.vars.size)
        var w = 0
        for (j in l.vars.indices) {
            if (j == ix) continue
            newVars[w] = l.vars[j]
            newCoeffs[w] = l.coeffs[j]
            w++
        }
        newVars[w] = y
        newCoeffs[w] = cX * a
        return Linear(newCoeffs, newVars, l.op, l.bound - cX * b)
    }

    /** Bounds on `y` enforcing that `x = c_x·b − c_x·c_y·y` stays within `x`'s domain [domX]. */
    private fun domainBoundsOnY(domX: IntDomain, cx: Int, cy: Int, y: Int, b: Int): List<Factor> {
        val coeff = -cx * cy // coefficient of y in the expression for x
        return listOf(
            Linear(intArrayOf(coeff), intArrayOf(y), LinearOp.LE, domX.max - cx * b),
            Linear(intArrayOf(coeff), intArrayOf(y), LinearOp.GE, domX.min - cx * b),
        )
    }

    private fun rebuildProblem(problem: Problem, factors: List<Factor>): Problem = Problem(
        numBoolVars = problem.numBoolVars,
        numIntVars = problem.numIntVars,
        intDomains = problem.intDomains.copyOf(),
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
        val valuePins = breakValueSymmetry(problem, objectiveIntVars)
        if (intGroups.isEmpty() && boolGroups.isEmpty() && blockLex.isEmpty() && valuePins.isEmpty()) return problem
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
        extra.addAll(valuePins)
        return rebuildProblem(problem, problem.factors.toList() + extra)
    }

    /**
     * Value symmetry breaking (#366). When every factor is value-anonymous ([Factor.isValueAnonymous]
     * — AllDifferent and the like, where distinctness ignores which values are used), any permutation
     * of values that maps every domain to itself is a symmetry. Values with the same domain-incidence
     * (the set of variables whose domain contains them) are therefore mutually interchangeable. For
     * each such orbit this pins one variable whose domain lies entirely within the orbit to the
     * orbit's minimum value — a sound break (a solution can always be relabeled within the orbit so
     * that variable takes the minimum). Returns the pinning constraints.
     *
     * This is the value analog of [breakSymmetries]; the stronger Law–Lee value precedence (which
     * orders first-occurrences across all variables) needs auxiliary variables and is a follow-up.
     */
    private fun breakValueSymmetry(problem: Problem, objectiveIntVars: Set<Int>): List<Factor> {
        if (problem.numIntVars == 0) return emptyList()
        if (problem.factors.any { !it.isValueAnonymous() }) return emptyList()
        var lo = Int.MAX_VALUE
        var hi = Int.MIN_VALUE
        for (d in problem.intDomains) {
            if (d.min < lo) lo = d.min
            if (d.max > hi) hi = d.max
        }
        if (lo > hi) return emptyList()
        // Orbit values by domain-incidence signature: same set of containing variables ⇒ interchangeable.
        val orbits = HashMap<String, MutableList<Int>>()
        for (value in lo..hi) {
            val incidence = StringBuilder()
            for (x in 0 until problem.numIntVars) if (value in problem.intDomains[x]) incidence.append(x).append(',')
            if (incidence.isNotEmpty()) orbits.getOrPut(incidence.toString()) { ArrayList() }.add(value)
        }
        val extra = ArrayList<Factor>()
        for (values in orbits.values) {
            if (values.size < 2) continue
            val orbitSet = values.toHashSet()
            val minValue = values.min()
            for (x in 0 until problem.numIntVars) {
                if (x in objectiveIntVars) continue
                if (domainWithin(problem.intDomains[x], orbitSet)) {
                    extra.add(Linear(intArrayOf(1), intArrayOf(x), LinearOp.EQ, minValue))
                    break
                }
            }
        }
        return extra
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

        val intCandidates = HashMap<String, MutableList<Int>>()
        for (v in 0 until problem.numIntVars) {
            if (v !in objectiveIntVars) intCandidates.getOrPut(domainKey(problem.intDomains[v])) { ArrayList() }.add(v)
        }
        val intOrbits = buildVerifiedOrbits(problem.numIntVars, intCandidates.values.toList()) { u, v ->
            intMap[u] = v
            intMap[v] = u
            val ok = isAutomorphism(problem, base, boolMap, intMap)
            intMap[u] = u
            intMap[v] = v
            ok
        }
        val boolVarsCand = (0 until problem.numBoolVars).filter { it !in objectiveBoolVars }
        val boolOrbits = buildVerifiedOrbits(problem.numBoolVars, listOf(boolVarsCand)) { u, v ->
            boolMap[u] = v
            boolMap[v] = u
            val ok = isAutomorphism(problem, base, boolMap, intMap)
            boolMap[u] = u
            boolMap[v] = v
            ok
        }
        return intOrbits to boolOrbits
    }

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

/** A single affine elimination `x = c_x·b − c_x·c_y·y` recorded by [Presolve.eliminateAffineSingletons]. */
internal data class AffineSub(val x: Int, val cx: Int, val cy: Int, val y: Int, val bound: Int)

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
        for (s in subs.asReversed()) ints[s.x] = s.cx * s.bound - s.cx * s.cy * ints[s.y]
        return Sample(sample.bools, ints)
    }
}
