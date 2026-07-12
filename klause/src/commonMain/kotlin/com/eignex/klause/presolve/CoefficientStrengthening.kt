package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.model.PbOp
import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.util.IntArrayList

internal object CoefficientStrengthening {

    /**
     * GCD coefficient strengthening (#319) for [Linear] and [PseudoBoolean] constraints. If the
     * coefficients of an integer linear (or pseudo-Boolean) constraint share a common divisor
     * `g > 1`, dividing through by `g` shrinks the coefficients and, because the left-hand side
     * is then a multiple of `g`, lets the bound be tightened by flooring/ceiling:
     *  - `Σ aⱼxⱼ ≤ b`  ⟺  `Σ (aⱼ/g)xⱼ ≤ ⌊b/g⌋`
     *  - `Σ aⱼxⱼ ≥ b`  ⟺  `Σ (aⱼ/g)xⱼ ≥ ⌈b/g⌉`
     *  - `Σ aⱼxⱼ = b`: divisible ⟹ `Σ (aⱼ/g)xⱼ = b/g`; otherwise the left-hand side is always a
     *    multiple of `g` while `b` is not, so the equality is **infeasible** — it is replaced by a
     *    contradiction the bake propagation reports as `Unsat` (see [equalityContradiction]).
     *  - `Σ aⱼxⱼ ≠ b`: divisible ⟹ divide; otherwise the constraint is always true and is dropped.
     *
     * Exact (feasible-set-preserving) and it tightens the LP relaxation the bound participates in.
     * Other factor types pass through untouched.
     */
    fun strengthenCoefficients(problem: Problem, cancellation: Cancellation = Cancellation.Never): PassDelta {
        val dropped = IntArrayList()
        val added = ArrayList<Factor>()
        val factors = problem.factors
        for (i in factors.indices) {
            // Poll the presolve deadline: on a multi-million-row model (Coprime) the per-factor strengthen
            // is O(factors) and must be interruptible. Bailing returns the strengthenings made so far — a
            // partial pass is sound (each rewrite is feasible-set-preserving; the rest stay unstrengthened).
            if ((i and STRENGTHEN_CANCEL_POLL_MASK) == 0 && cancellation()) break
            val factor = factors[i]
            // An equality whose coefficient GCD does not divide its bound can never hold; replace it by
            // an explicit contradiction (the original is redundant once the problem is infeasible).
            val contradiction = equalityContradiction(factor, problem.intDomains)
            if (contradiction != null) {
                dropped.add(i)
                added.addAll(contradiction)
                continue
            }
            val rewritten = when (factor) {
                is Linear -> strengthenLinear(factor, problem.intDomains)
                is PseudoBoolean -> strengthenPb(factor)
                else -> factor
            }
            // A rewrite replaces the input factor; a `null` drops it (always satisfied); an unchanged
            // factor contributes nothing to the delta.
            if (rewritten !== factor) {
                dropped.add(i)
                if (rewritten != null) added.add(rewritten)
            }
        }
        if (dropped.isEmpty()) return PassDelta()
        return PassDelta(dropped.toIntArray(), added)
    }

    /** Poll the cancellation once per this many strengthened factors (power-of-two mask for a cheap test). */
    private const val STRENGTHEN_CANCEL_POLL_MASK = 0x3FF

    /** The factors that make the model infeasible when [factor] is an equality whose coefficient GCD
     *  `g > 1` does not divide its bound, else `null`. Such an equality `Σ coeffs·x = b` has a
     *  left-hand side that is always a multiple of `g`, so it can never equal a non-multiple `b`.
     *  Replacing the original by a contradiction is sound — an infeasible problem has no solutions
     *  regardless of which constraint witnesses the conflict. */
    private fun equalityContradiction(factor: Factor, domains: Array<IntDomain>): List<Factor>? = when (factor) {
        is Linear ->
            if (factor.op == LinearOp.EQ && indivisible(factor.coeffs, factor.bound)) {
                intContradiction(factor.vars[0], domains)
            } else {
                null
            }

        is PseudoBoolean ->
            if (factor.op == PbOp.EQ && indivisible(factor.weights, factor.bound)) {
                boolContradiction(factor.literals[0])
            } else {
                null
            }

        else -> null
    }

    /** Whether `gcd(|coeffs|) > 1` fails to divide [bound] — the modular obstruction that makes an
     *  equality `Σ coeffs·x = bound` unsatisfiable over the integers. */
    private fun indivisible(coeffs: LongArray, bound: Long): Boolean {
        val g = PresolveShared.gcdOf(coeffs)
        return g > 1L && bound.mod(g) != 0L
    }

    /** Two equalities pinning integer variable [v] to consecutive values — jointly unsatisfiable, so
     *  the bake propagation reports `Unsat`. */
    private fun intContradiction(v: Int, domains: Array<IntDomain>): List<Factor> {
        val c = if (domains[v].min < Long.MAX_VALUE) domains[v].min else domains[v].min - 1
        return listOf(
            Linear(longArrayOf(1), intArrayOf(v), LinearOp.EQ, c),
            Linear(longArrayOf(1), intArrayOf(v), LinearOp.EQ, c + 1),
        )
    }

    /** A contradictory unit-clause pair on [lit]'s variable — jointly unsatisfiable (cf. [XorUnits]). */
    private fun boolContradiction(lit: Int): List<Factor> {
        val v = Lit.variable(lit)
        return listOf(Clause(intArrayOf(Lit.make(v, true))), Clause(intArrayOf(Lit.make(v, false))))
    }

    /** Relation common to [LinearOp] and [PbOp] so the bound rewrite is written once. */
    private enum class Rel { LE, GE, EQ, NE }

    private sealed interface Reduced {
        /** Divide through; the rewritten bound. */
        data class Bound(val bound: Long) : Reduced

        /** Constraint is always satisfied — drop it. */
        object Drop : Reduced

        /** Leave the constraint as-is. */
        object Unchanged : Reduced
    }

    private fun reduceBound(rel: Rel, bound: Long, g: Long): Reduced = when (rel) {
        Rel.LE -> Reduced.Bound(bound.floorDiv(g))
        Rel.GE -> Reduced.Bound(-((-bound).floorDiv(g)))
        Rel.EQ -> if (bound.mod(g) == 0L) Reduced.Bound(bound / g) else Reduced.Unchanged
        Rel.NE -> if (bound.mod(g) == 0L) Reduced.Bound(bound / g) else Reduced.Drop
    }

    private fun strengthenLinear(factor: Linear, domains: Array<IntDomain>): Factor? {
        val g = PresolveShared.gcdOf(factor.coeffs)
        val gcdReduced: Linear = if (g <= 1L) {
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
        val coeffs: LongArray
        val bound: Long
        when (l.op) {
            LinearOp.LE -> {
                coeffs = l.coeffs.copyOf()
                bound = l.bound
            }

            LinearOp.GE -> {
                coeffs = LongArray(l.coeffs.size) { -l.coeffs[it] }
                bound = -l.bound
            }

            else -> return l
        }
        val n = coeffs.size
        // Normalise to a positive-coefficient bounded knapsack Σ a̅ⱼzⱼ ≤ B, zⱼ ∈ [0, cⱼ].
        val absA = LongArray(n)
        val cap = LongArray(n)
        var b = bound
        for (i in 0 until n) {
            val a = coeffs[i]
            val dom = domains[l.vars[i]]
            cap[i] = dom.max - dom.min
            absA[i] = if (a < 0) -a else a
            // Fold the variable's contribution at its zero-of-zⱼ end into the bound:
            // aⱼ>0 shifts by aⱼ·lⱼ, aⱼ<0 (complemented) shifts by aⱼ·uⱼ.
            val zeroEnd = if (a >= 0) dom.min else dom.max
            b -= a * zeroEnd
        }
        var amax = 0L
        for (i in 0 until n) amax += absA[i] * cap[i]
        val d = amax - b
        if (d <= 0L) return null // maximal activity within bound ⇒ always satisfied
        var changed = false
        val lifted = LongArray(n) { i ->
            if (cap[i] > 0L && absA[i] > d) {
                changed = true
                d
            } else {
                absA[i]
            }
        }
        if (!changed) return l
        // New bound in z-space: Σ a̅'ⱼcⱼ − d, then de-shift each variable back to xⱼ.
        var newBound = -d
        for (i in 0 until n) newBound += lifted[i] * cap[i]
        val newCoeffs = LongArray(n)
        for (i in 0 until n) {
            val dom = domains[l.vars[i]]
            if (coeffs[i] >= 0) {
                newCoeffs[i] = lifted[i]
                newBound += lifted[i] * dom.min
            } else {
                newCoeffs[i] = -lifted[i]
                newBound -= lifted[i] * dom.max
            }
        }
        return Linear(newCoeffs, l.vars.copyOf(), LinearOp.LE, newBound)
    }

    private fun strengthenPb(factor: PseudoBoolean): Factor? {
        val g = PresolveShared.gcdOf(factor.weights)
        val gcdReduced: PseudoBoolean = if (g <= 1L) {
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
                PseudoBoolean(
                    input.weights.copyOf(),
                    IntArray(input.literals.size) { Lit.negate(input.literals[it]) },
                    PbOp.LE,
                    nb,
                )
            }
        }
        val n = pb.literals.size
        val weights = LongArray(n)
        val lits = IntArray(n)
        var bound = pb.bound
        for (i in 0 until n) {
            val wi = pb.weights[i]
            if (wi < 0) {
                weights[i] = -wi
                lits[i] = Lit.negate(pb.literals[i])
                bound += weights[i]
            } else {
                weights[i] = wi
                lits[i] = pb.literals[i]
            }
        }
        var sum = 0L
        for (w in weights) sum += w
        val d = sum - bound
        if (d <= 0L) return null // always satisfied
        if (d >= sum) return input // no weight exceeds d — leave original
        var changed = false
        var newSum = 0L
        val lifted = LongArray(n) { i ->
            if (weights[i] > d) {
                changed = true
                d
            } else {
                weights[i]
            }.also { newSum += it }
        }
        if (!changed) return input
        val newBound = newSum - d
        return PseudoBoolean(lifted, lits, PbOp.LE, newBound)
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
