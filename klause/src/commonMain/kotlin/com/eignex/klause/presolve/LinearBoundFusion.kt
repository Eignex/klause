package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Problem
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet

/**
 * Cross-direction linear bound fusion. Over the [Linear] inequalities sharing one coefficient vector — up
 * to a common GCD and an overall sign — an upper bound `Σ a·x ≤ u` and a lower bound `Σ a·x ≥ l` interact
 * in two ways a per-direction dominator bucket never sees:
 *
 *  - `l > u` proves the problem infeasible outright, reported without materialising the constraints.
 *  - `l = u` pins `Σ a·x = l`; the pair (and any looser same-vector siblings) collapse into a single
 *    equality, which [ELIMINATE_AFFINE_SINGLETONS][PresolvePass.ELIMINATE_AFFINE_SINGLETONS] can then
 *    pivot on to project out a variable.
 *
 * Solution-set exact: `(Σ ≤ v) ∧ (Σ ≥ v) ⟺ (Σ = v)`, and an empty feasible set is reported faithfully.
 * Same-direction domination (two `≤` keep the tighter) already lives in [RedundantConstraints]; this pass
 * adds only the orthogonal `≤`/`≥` combination. Operates on [Linear] factors — the droppable single-row
 * integer inequalities whose fusion most directly unblocks the affine pass; the pseudo-Boolean / Boolean
 * rows are left to a follow-up.
 */
internal object LinearBoundFusion {

    /** The rows on one canonical coefficient vector: the tightest bound seen from each direction, the
     *  representative canonical `(vars, coeffs)` for emitting the fused equality, and the inequality
     *  factor indices eligible to drop into it. An equality among them makes fusion redundant. */
    private class Group {
        var vars: IntArray = intArrayOf()
        var coeffs: LongArray = longArrayOf()
        var upper: Long = Long.MAX_VALUE
        var lower: Long = Long.MIN_VALUE
        var hasEq = false
        val ineqIndices = IntArrayList()
    }

    fun fuseLinearBounds(problem: Problem): PassDelta {
        val factors = problem.factors
        val groups = HashMap<List<Long>, Group>()
        for (i in factors.indices) {
            val f = factors[i]
            if (f !is Linear || !f.isIntegerCore || f.op == LinearOp.NE) continue
            val canon = canonicalize(f) ?: continue
            val g = groups.getOrPut(canon.key) {
                Group().also {
                    it.vars = canon.vars
                    it.coeffs = canon.coeffs
                }
            }
            when {
                f.op == LinearOp.EQ -> {
                    g.hasEq = true
                    if (canon.value < g.upper) g.upper = canon.value
                    if (canon.value > g.lower) g.lower = canon.value
                }

                canon.isUpper -> {
                    if (canon.value < g.upper) g.upper = canon.value
                    g.ineqIndices.add(i)
                }

                else -> {
                    if (canon.value > g.lower) g.lower = canon.value
                    g.ineqIndices.add(i)
                }
            }
        }

        val dropped = IntHashSet()
        val added = ArrayList<Factor>()
        for (g in groups.values) {
            if (g.lower == Long.MIN_VALUE || g.upper == Long.MAX_VALUE) continue // one-directional
            if (g.lower > g.upper) return PassDelta(infeasible = true)
            // Both bounds present and equal, with no equality already asserting it: the inequalities
            // (all on this vector, since l == u == every side's tightest) collapse into one equality.
            if (g.lower == g.upper && !g.hasEq) {
                for (k in 0 until g.ineqIndices.size) dropped.add(g.ineqIndices[k])
                added.add(Linear(g.coeffs, g.vars, LinearOp.EQ, g.upper))
            }
        }
        if (dropped.isEmpty() && added.isEmpty()) return PassDelta()
        return PassDelta(droppedIndices = dropped.toIntArray(), addedFactors = added)
    }

    /** A factor's row in a sign-canonical orientation (leading coefficient positive) and GCD-reduced, so
     *  a `≤` and a `≥` over the same underlying vector land in one [Group]. [value] is the bound in that
     *  orientation, [isUpper] marks a `≤`. `null` for an empty / all-zero support or an equality whose
     *  bound the GCD does not divide (a genuine infeasibility left to [CoefficientStrengthening]). */
    private class Canon(
        val key: List<Long>,
        val vars: IntArray,
        val coeffs: LongArray,
        val value: Long,
        val isUpper: Boolean,
    )

    private fun canonicalize(f: Linear): Canon? {
        val vars = f.vars
        if (vars.isEmpty()) return null
        val g = PresolveShared.gcdOf(f.coeffs)
        if (g < 1L) return null // all-zero coefficients: a trivial row with no support

        // The row is stored `≤` (a `≥` was folded to `≤` with negated sides at construction); GCD-reduce
        // on that form (exact, the left side is a multiple of `g`) before choosing the canonical sign.
        val reducedBound = if (f.op == LinearOp.EQ) {
            if (f.bound % g != 0L) return null
            f.bound / g
        } else {
            f.bound.floorDiv(g)
        }
        val reduced = LongArray(vars.size) { f.coeff(it) / g }
        val lead = leadingIndex(vars)
        val flip = reduced[lead] < 0L
        val coeffs = if (flip) LongArray(reduced.size) { -reduced[it] } else reduced
        // Flipping sign turns the stored `≤ reducedBound` into `≥ -reducedBound`, i.e. a lower bound.
        val value = if (flip) -reducedBound else reducedBound
        return Canon(keyOf(vars, coeffs), vars, coeffs, value, isUpper = !flip)
    }

    /** Index of the lowest variable id — the deterministic anchor whose coefficient sign orients the
     *  canonical vector. */
    private fun leadingIndex(vars: IntArray): Int {
        var lead = 0
        for (i in vars.indices) if (vars[i] < vars[lead]) lead = i
        return lead
    }

    /** The canonical `(var, coeff)` pairs sorted by variable id — the group key. */
    private fun keyOf(vars: IntArray, coeffs: LongArray): List<Long> {
        val terms = ArrayList<Long>(vars.size * 2)
        for (i in vars.indices.sortedBy { vars[it] }) {
            terms.add(vars[i].toLong())
            terms.add(coeffs[i])
        }
        return terms
    }
}
