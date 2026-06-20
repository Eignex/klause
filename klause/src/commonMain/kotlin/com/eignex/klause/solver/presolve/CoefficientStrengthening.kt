package com.eignex.klause.solver.presolve

import com.eignex.klause.model.PbOp
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.arithmetic.Linear
import com.eignex.klause.solver.factor.arithmetic.LinearOp
import com.eignex.klause.solver.factor.bool.PseudoBoolean

internal object CoefficientStrengthening {

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
        val g = PresolveShared.gcdOf(factor.coeffs)
        val gcdReduced: Linear = if (g <= 1) {
            factor
        } else {
            when (val reduced = reduceBound(toRel(factor.op), factor.bound, g)) {
                is Reduced.Bound -> Linear(
                    PresolveShared.divAll(factor.coeffs, g),
                    factor.vars.copyOf(),
                    factor.op,
                    reduced.bound,
                )

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
        val g = PresolveShared.gcdOf(factor.weights)
        val gcdReduced: PseudoBoolean = if (g <= 1) {
            factor
        } else {
            when (val reduced = reduceBound(toRel(factor.op), factor.bound, g)) {
                is Reduced.Bound -> PseudoBoolean(
                    PresolveShared.divAll(factor.weights, g),
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
}
