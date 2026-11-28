package com.eignex.klause.presolve.linear

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntegerConstants
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.Term
import com.eignex.klause.ir.UnitConsts
import com.eignex.klause.ir.impliedLinearRows
import com.eignex.klause.model.PbOp
import com.eignex.klause.presolve.ColumnRanges
import com.eignex.klause.presolve.PassDelta
import com.eignex.klause.presolve.PresolveShared
import com.eignex.klause.presolve.equivalentLinear
import com.eignex.klause.presolve.equivalentPseudoBoolean
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.collections.IntArrayList
import com.eignex.util.numbers.CheckedLongOverflowException
import com.eignex.util.numbers.addExact
import com.eignex.util.numbers.mulExact
import com.eignex.util.numbers.subExact

internal object CoefficientStrengthening {

    // Divides an integer or pseudo-Boolean row by its coefficient GCD `g > 1`, flooring `≤` and ceiling `≥` bounds.
    // An equality whose bound `g` does not divide is infeasible; such a `≠` is always true and dropped.
    // Feasible-set-preserving, and tightens the LP relaxation.
    fun strengthenCoefficients(
        problem: Problem,
        cancellation: ExecutionControl = ExecutionControl.Never,
        ranges: ColumnRanges? = null,
    ): PassDelta {
        val dropped = IntArrayList()
        val added = ArrayList<Factor>()
        var infeasible = false
        val factors = problem.factors
        for (i in factors.indices) {
            // A partial pass is sound: each rewrite preserves the feasible set.
            if ((i and STRENGTHEN_CANCEL_POLL_MASK) == 0 && cancellation()) break
            val factor = factors[i]
            cancellation.charge(STRENGTHEN_WORK_WEIGHT * (1L + factor.structuralKeyWeight))
            // Clauses have unit coefficients and a positive bound, so neither reduction applies.
            if (factor is Clause) continue
            if (indivisibleEquality(factor)) {
                infeasible = true
                break
            }
            val linear = factor.equivalentLinear()
            val pb = if (linear == null && canStrengthenBoolean(factor)) factor.equivalentPseudoBoolean() else null
            val input = linear ?: pb ?: factor
            val rewritten = when {
                linear?.integerConstants != null -> strengthenLinear(linear, ranges)
                pb != null -> strengthenPb(pb)
                else -> input
            }
            // `null` means always satisfied.
            if (rewritten !== input) {
                dropped.add(i)
                if (rewritten != null) added.add(rewritten)
            }
        }
        if (dropped.isEmpty()) return PassDelta(infeasible = infeasible)
        return PassDelta(dropped.toIntArray(), added, infeasible = infeasible)
    }

    private fun canStrengthenBoolean(factor: Factor): Boolean {
        val row = (factor.linearForm as? LinearForm.Conjunction)?.rows?.singleOrNull() ?: return false
        val constants = row.constants as? IntegerConstants ?: return false
        val unit = constants.coefficients is UnitConsts || (0 until row.size).all { row.coeff(it) == 1L }
        // Unit rows cannot be GCD-reduced or lifted; only a vacuous bound can remove them.
        if (unit && when (row.relation) {
                LinearOp.LE -> row.bound < row.size
                LinearOp.GE -> row.bound > 0L
                LinearOp.EQ, LinearOp.NE -> true
            }
        ) {
            return false
        }
        return row.isLongUnconditional && (0 until row.size).all { Term.isBool(row.ref(it)) }
    }

    // Work units per factor and term strengthened, relative to one simplex operation.
    private const val STRENGTHEN_WORK_WEIGHT = 15L

    private const val STRENGTHEN_CANCEL_POLL_MASK = 0x3FF

    private fun indivisibleEquality(factor: Factor): Boolean = factor.impliedLinearRows.any { row ->
        row.relation == LinearOp.EQ && row.isLongUnconditional &&
            indivisible((row.constants as IntegerConstants).coeffs, row.bound)
    }

    private fun indivisible(coeffs: LongArray, bound: Long): Boolean {
        val g = PresolveShared.gcdOf(coeffs)
        return g > 1L && bound.mod(g) != 0L
    }

    // Shared by [LinearOp] and [PbOp] so the bound rewrite is written once.
    private enum class Rel { LE, GE, EQ, NE }

    private sealed interface Reduced {
        data class Bound(val bound: Long) : Reduced

        object Drop : Reduced

        object Unchanged : Reduced
    }

    private fun reduceBound(rel: Rel, bound: Long, g: Long): Reduced = when (rel) {
        Rel.LE -> Reduced.Bound(bound.floorDiv(g))
        Rel.GE -> Reduced.Bound(-((-bound).floorDiv(g)))
        Rel.EQ -> if (bound.mod(g) == 0L) Reduced.Bound(bound / g) else Reduced.Unchanged
        Rel.NE -> if (bound.mod(g) == 0L) Reduced.Bound(bound / g) else Reduced.Drop
    }

    private fun strengthenLinear(factor: Linear, ranges: ColumnRanges?): Factor? {
        val row = factor.integerConstants ?: return factor
        val g = PresolveShared.gcdOf(row.coeffs)
        val gcdReduced: Linear = if (g <= 1L) {
            factor
        } else {
            when (val reduced = reduceBound(toRel(factor.op), row.bound, g)) {
                is Reduced.Bound -> Linear(
                    PresolveShared.divAll(row.coeffs, g),
                    factor.vars.copyOf(),
                    factor.op,
                    reduced.bound,
                )

                Reduced.Drop -> return null

                Reduced.Unchanged -> factor
            }
        }
        // The lift charges every column in the row its whole width, so a row mentioning one the model
        // leaves open keeps the GCD reduction alone rather than resting on an invented endpoint.
        if (ranges == null || !ranges.allClosed(gcdReduced.vars)) return gcdReduced
        return liftLinear(gcdReduced, ranges)
    }

    // MIP coefficient tightening over bounded integers `xⱼ ∈ [lⱼ, uⱼ]` (M. W. P. Savelsbergh, "Preprocessing and
    // Probing Techniques for Mixed Integer Programming Problems", ORSA J. Computing 1994,
    // https://doi.org/10.1287/ijoc.6.4.445; T. Achterberg et al., "Presolve Reductions in Mixed Integer
    // Programming", INFORMS J. Computing 2020, https://doi.org/10.1287/ijoc.2018.0857), generalising the 0/1
    // clamp of [liftKnapsack] to `uⱼ > 1`.
    //
    // Shift or complement each column to a positive knapsack `Σ a̅ⱼ zⱼ ≤ B`, `zⱼ ∈ [0, cⱼ]`, `cⱼ = uⱼ − lⱼ`, whose
    // complement is the cover `Σ a̅ⱼ z̄ⱼ ≥ d` with `d = Σ a̅ⱼcⱼ − B`. The clamp `a̅ⱼ → min(a̅ⱼ, d)` is exact for
    // bounded `z̄ⱼ`, not just binary: a term with `z̄ⱼ ≥ 1` and `a̅ⱼ > d` already reaches `d` alone. Mapping back
    // gives the bound `Σ min(a̅ⱼ,d)cⱼ − d`. Fixed columns are never clamped.
    //
    // An unbounded-search column already has capacity `2^62`, and a wrapped activity would drop the row or lift it
    // to an unrelated bound, so any overflow keeps the row untouched.
    private fun liftLinear(l: Linear, ranges: ColumnRanges): Factor? = try {
        liftLinearExact(l, ranges)
    } catch (_: CheckedLongOverflowException) {
        l
    }

    private fun liftLinearExact(l: Linear, ranges: ColumnRanges): Factor? {
        val row = l.integerConstants ?: return l
        // `=` ties both directions and `≠` is not a knapsack, so only `≤` and `≥` lift.
        val negate = when (l.op) {
            LinearOp.LE -> false
            LinearOp.GE -> true
            else -> return l
        }
        val bound = if (negate) subExact(0L, row.bound) else row.bound
        val n = l.vars.size
        var b = bound
        var amax = 0L
        var maxUnfixedMagnitude = 0L
        for (i in 0 until n) {
            val a = if (negate) subExact(0L, row.coeff(i)) else row.coeff(i)
            val v = l.vars[i]
            val cap = subExact(ranges.max(v), ranges.min(v))
            val magnitude = if (a < 0) subExact(0L, a) else a
            val zeroEnd = if (a >= 0) ranges.min(v) else ranges.max(v)
            b = subExact(b, mulExact(a, zeroEnd))
            amax = addExact(amax, mulExact(magnitude, cap))
            if (cap > 0L) maxUnfixedMagnitude = maxOf(maxUnfixedMagnitude, magnitude)
        }
        val d = subExact(amax, b)
        if (d <= 0L) return null
        if (maxUnfixedMagnitude <= d) return l
        val newCoeffs = LongArray(n)
        var newBound = -d
        for (i in 0 until n) {
            val a = if (negate) subExact(0L, row.coeff(i)) else row.coeff(i)
            val v = l.vars[i]
            val cap = subExact(ranges.max(v), ranges.min(v))
            val magnitude = if (a < 0) subExact(0L, a) else a
            val lifted = if (cap > 0L) minOf(magnitude, d) else magnitude
            newCoeffs[i] = if (a >= 0) lifted else -lifted
            newBound = addExact(newBound, mulExact(lifted, cap))
        }
        for (i in 0 until n) {
            val v = l.vars[i]
            val a = newCoeffs[i]
            if (a >= 0) {
                newBound = addExact(newBound, mulExact(a, ranges.min(v)))
            } else {
                newBound = subExact(newBound, mulExact(-a, ranges.max(v)))
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

    // With positive weights, the dual `Σ wⱼ(1−lⱼ) ≥ d`, `d = Σwⱼ − b`, is a cover: a weight above `d` covers no
    // more than `d`, so each `wⱼ` clamps to `min(wⱼ, d)` and the bound becomes `Σ min(wⱼ,d) − d`.
    private fun liftKnapsack(input: PseudoBoolean): Factor? {
        // `Σwⱼlⱼ ≥ b ⟺ Σwⱼ¬lⱼ ≤ Σwⱼ − b`; EQ ties both directions, so it cannot be lifted.
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
        if (d <= 0L) return null
        if (d >= sum) return input
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
