package com.eignex.klause.lp.cut

import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.factor.global.GlobalCardinality
import com.eignex.klause.factor.global.Inverse
import com.eignex.klause.factor.global.SymmetricAllDifferent
import com.eignex.klause.lp.LpModel
import com.eignex.klause.lp.LpOverflowException
import com.eignex.klause.lp.Relation
import com.eignex.klause.lp.addExact
import com.eignex.klause.lp.bound.MinCostAssignment
import com.eignex.klause.lp.mulExact
import com.eignex.klause.lp.relaxation.LpRelaxation
import com.eignex.klause.model.PbOp
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.values
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet
import com.eignex.klause.util.LongArrayList
import com.eignex.klause.util.MutableIntObjectMap
import com.eignex.klause.util.MutableLongIntMap

/**
 * Variable groups that are pairwise all-different, harvested from the LP-relevant globals so the
 * Hall-set sum cut ([AllDifferentSeparator]) and the assignment-objective cut
 * ([AssignmentObjectiveCut]) — both valid over any all-different set — reach beyond plain
 * [AllDifferent]:
 *  - [AllDifferent] itself: its variables.
 *  - [SymmetricAllDifferent]: `xs` is a self-inverse permutation, hence all-different.
 *  - [Inverse]: each side (`f`, `g`) is injective (a channelled bijection), so each is its own
 *    all-different set; the two are returned separately.
 */
internal fun allDifferentGroups(problem: Problem): List<IntArray> {
    val groups = ArrayList<IntArray>()
    for (factor in problem.factors) {
        when (factor) {
            is AllDifferent -> groups.add(factor.vars)

            is SymmetricAllDifferent -> groups.add(factor.xs)

            is Inverse -> {
                groups.add(factor.f)
                groups.add(factor.g)
            }

            else -> Unit
        }
    }
    return groups
}

/**
 * A linear inequality `Σ coeffs[k]·x_{cols[k]} rel rhs` over LP columns, added to the relaxation to
 * cut off a fractional LP point. Columns index the relaxation's structural columns; the cut
 * must be valid — satisfied by every integer-feasible point — so it never removes a real solution.
 *
 * [global] says the cut is satisfied by every integer **solution of the problem**, not merely by
 * the points inside the separating node's box: a cut whose derivation read only factor structure
 * (knapsack cover, clique, circuit cutset) or unbranched root domains is global; one derived from
 * live tightened domains (Hall/GCC/assignment sums deeper in the tree, Gomory/MIR tableau cuts) is
 * not. The flag flows into [LpModel.rowGlobal], which gates whether LP certificates over the
 * cut-augmented model may be learned. Defaults to `false` — the sound direction.
 */
internal class Cut(
    val cols: IntArray,
    val coeffs: LongArray,
    val rel: Relation,
    val rhs: Long,
    val global: Boolean = false,
) {
    /** A stable key for deduplicating cuts across separation rounds (ignores column order). */
    fun key(): String {
        val terms = cols.indices.sortedBy { cols[it] }.joinToString(",") { "${cols[it]}:${coeffs[it]}" }
        return "$terms|$rel|$rhs"
    }
}

/** Everything a separator needs: the problem, the current relaxation, its LP solution, the session. */
internal class CutContext(
    val problem: Problem,
    val relaxation: LpRelaxation,
    /** Per-structural-column LP primal value (`RevisedSimplex.FloatLpResult.primal`); the point to separate. */
    val primal: DoubleArray,
    val session: PropagationSession,
) {
    /** The LP primal value of structural column [col] (0 outside the primal vector, e.g. an unmapped column). */
    fun primalOf(col: Int): Double = if (col in primal.indices) primal[col] else 0.0
}

/**
 * Separates violated cuts from a fractional LP solution. Implementations inspect the LP point
 * and the problem structure and return cuts the point violates. Returning only violated cuts (rather
 * than all valid ones) keeps the LP from growing with constraints it does not need.
 */
internal interface CutSeparator {
    fun separate(ctx: CutContext): List<Cut>
}

/** True when every [vars] member's live `[min, max]` equals its declared interval — a bound derived
 *  from the live intervals is then valid at every solution, not only inside the node's box. */
private fun liveIntervalsAreDeclared(ctx: CutContext, vars: IntArray): Boolean {
    for (v in vars) {
        val live = ctx.session.intDomain(v)
        val declared = ctx.problem.intDomains[v]
        if (live.min != declared.min || live.max != declared.max) return false
    }
    return true
}

/** Hole-aware version of [liveIntervalsAreDeclared]: the live domain is always a subset of the
 *  declared one, so equal sizes mean equal value sets. */
private fun liveDomainsAreDeclared(ctx: CutContext, vars: IntArray): Boolean {
    for (v in vars) {
        if (ctx.session.intDomain(v).valueCount != ctx.problem.intDomains[v].valueCount) return false
    }
    return true
}

/**
 * AllDifferent cuts. AllDifferent is skipped by the base relaxation; this re-introduces
 * its strength linearly. For a set S of all-different variables, any assignment uses |S| distinct
 * values, so `Σ_{i∈S} x_i` is bounded below by the sum of the |S| smallest distinct values available
 * across their domains, and above by the sum of the |S| largest — Hall-set bounds. Treating each
 * domain as its `[min, max]` interval (ignoring holes) only widens the value pool, so the bounds stay
 * valid (a sound under-/over-estimate). The full variable set is the |S| = n Hall set; this first
 * implementation separates that set (the dominant cut) when the LP point violates it.
 *
 * The set S is any group from [allDifferentGroups], so this also covers [SymmetricAllDifferent] and
 * each side of [Inverse] — all injective, hence all-different.
 */
internal class AllDifferentSeparator : CutSeparator {
    private val tol = 1e-6

    override fun separate(ctx: CutContext): List<Cut> {
        val cuts = ArrayList<Cut>()
        for (vars in allDifferentGroups(ctx.problem)) {
            if (vars.size < 2) continue
            val cols = IntArray(vars.size)
            var ok = true
            for (k in vars.indices) {
                val c = ctx.relaxation.intColOf[vars[k]]
                if (c < 0) {
                    ok = false
                    break
                }
                cols[k] = c
            }
            if (!ok) continue

            val (minSum, maxSum) = distinctSumBounds(vars, ctx.session)
            // The Hall bounds read only the live [min, max] intervals, so the cut is global exactly
            // when those are still the declared intervals (always at the root).
            val global = liveIntervalsAreDeclared(ctx, vars)
            var lpSum = 0.0
            for (c in cols) lpSum += ctx.primalOf(c)
            val ones = LongArray(cols.size) { 1L }
            if (lpSum < minSum - tol) cuts.add(Cut(cols.copyOf(), ones, Relation.GE, minSum, global))
            if (lpSum > maxSum + tol) {
                cuts.add(Cut(cols.copyOf(), LongArray(cols.size) { 1L }, Relation.LE, maxSum, global))
            }
        }
        return cuts
    }

    /**
     * Sum of the [vars].size smallest, and largest, distinct values across the union of the live
     * `[min, max]` domain intervals — a valid lower/upper bound on `Σ x_i` under all-different.
     */
    private fun distinctSumBounds(vars: IntArray, session: PropagationSession): Pair<Long, Long> {
        // Merge domain intervals into disjoint ascending ranges.
        val ranges = vars.map {
            val d = session.intDomain(it)
            d.min to d.max
        }
            .sortedBy { it.first }
        val merged = ArrayList<LongArray>() // [lo, hi]
        for ((lo, hi) in ranges) {
            val last = merged.lastOrNull()
            if (last != null && lo <= last[1] + 1) {
                last[1] = maxOf(last[1], hi)
            } else {
                merged.add(longArrayOf(lo, hi))
            }
        }
        val n = vars.size
        var minSum = 0L
        var taken = 0
        for (r in merged) {
            var v = r[0]
            while (v <= r[1] && taken < n) {
                minSum = addExact(minSum, v)
                taken++
                v++
            }
            if (taken == n) break
        }
        var maxSum = 0L
        taken = 0
        for (i in merged.indices.reversed()) {
            val r = merged[i]
            var v = r[1]
            while (v >= r[0] && taken < n) {
                maxSum = addExact(maxSum, v)
                taken++
                v--
            }
            if (taken == n) break
        }
        return minSum to maxSum
    }
}

/**
 * Lagrangian-augmented LP cut: the objective-weighted AllDifferent bound. For an
 * AllDifferent over variables `V`, the minimum of `Σ_{i∈V} c_i·x_i` subject to all-different is the
 * exact min-cost assignment of the objective coefficients to distinct values ([MinCostAssignment]) —
 * a stronger statement than the unweighted Hall sum cut whenever the `c_i` differ. Emitting
 * `Σ_{i∈V} c_i·x_i ≥ assignmentMin` as a cut injects that global, integral bound into the LP, which
 * is exactly the synergy the Lagrangian-augmented LP path provides: a plain multiplier→coefficient
 * adjustment buys nothing for an LP relaxation (LP strong duality), but the integral assignment bound
 * does. The cut is emitted only when the LP point violates it.
 */
internal class AssignmentObjectiveCut(private val intCoef: LongArray) : CutSeparator {
    private val tol = 1e-6

    override fun separate(ctx: CutContext): List<Cut> {
        val cuts = ArrayList<Cut>()
        for (vars in allDifferentGroups(ctx.problem)) {
            if (vars.size < 2) continue
            // Need a column for every variable and at least one nonzero objective coefficient.
            if (vars.any { ctx.relaxation.intColOf[it] < 0 }) continue
            if (vars.none { intCoef.getOrElse(it) { 0L } != 0L }) continue

            val assignmentMin = assignmentMin(vars, ctx.session) ?: continue
            // Cut is over the nonzero-cost columns; zero-cost variables only shaped the assignment.
            val cols = IntArrayList()
            val coeffs = LongArrayList()
            var lpLhs = 0.0
            for (v in vars) {
                val c = intCoef.getOrElse(v) { 0L }
                if (c == 0L) continue
                val col = ctx.relaxation.intColOf[v]
                cols.add(col)
                coeffs.add(c)
                lpLhs += c.toDouble() * ctx.primalOf(col)
            }
            if (lpLhs < assignmentMin - tol) {
                // The assignment enumerated the live value sets hole-aware, so globality needs full
                // domain equality with the declared sets, not just matching intervals.
                val global = liveDomainsAreDeclared(ctx, vars)
                cuts.add(Cut(cols.toIntArray(), coeffs.toLongArray(), Relation.GE, assignmentMin, global))
            }
        }
        return cuts
    }

    /**
     * Exact minimum of `Σ_{i∈V} c_i·x_i` over distinct assignments of the live domains, via
     * [MinCostAssignment]. Returns null when the value set is too large to assign over or the
     * arithmetic overflows (then no cut is produced — sound, just no strengthening).
     */
    private fun assignmentMin(vars: IntArray, session: PropagationSession): Long? {
        val valueIndex = MutableLongIntMap()
        val values = LongArrayList()
        for (v in vars) {
            // Abort before walking a domain that is too large to assign over — in particular a wide
            // (>2^31-span) domain, whose `sizeLong` saturates well past the cap, so it is never
            // enumerated value-by-value. Sound: no cut is produced, only strengthening is skipped.
            if (session.intDomain(v).spanOrNull(MAX_VALUES.toLong()) == null) return null
            session.intDomain(v).values.forEach { value ->
                if (!valueIndex.containsKey(value)) {
                    valueIndex.put(value, values.size)
                    values.add(value)
                }
            }
        }
        if (values.size > MAX_VALUES || values.size < vars.size) return null
        return try {
            val assign = MinCostAssignment(vars.size, values.size)
            for (i in vars.indices) {
                val c = intCoef.getOrElse(vars[i]) { 0L }
                session.intDomain(vars[i]).values.forEach { value ->
                    assign.addOption(i, valueIndex.getOrDefault(value, -1), mulExact(c, value))
                }
            }
            val r = assign.solve()
            if (r.feasible) r.cost else null
        } catch (_: LpOverflowException) {
            null
        }
    }

    private companion object {
        const val MAX_VALUES: Int = 512
    }
}

/**
 * GlobalCardinality sum cuts — the value-multiplicity generalization of the AllDifferent
 * Hall cut. A *closed* GCC pins every `x_i` to a cover value `v_k`, each used within `[low_k, high_k]`
 * times, so `Σ_{i} x_i` is bounded by the cheapest (and dearest) value distribution honouring those
 * occurrence caps: fill the `low_k` forced occurrences, then spread the remaining slots over the
 * smallest-valued (resp. largest-valued) residual capacity. Ignoring each variable's own domain only
 * relaxes the problem, so the greedy min/max stay valid bounds. With every `high_k = 1` this reduces
 * exactly to the AllDifferent Hall sum.
 *
 * Only the fully-present, closed form is separated. An *open* GCC lets `x_i` take values outside the
 * cover (potentially below every cover value), so the cover-only greedy could over-estimate the true
 * minimum — unsound for a `≥` cut — and is skipped. A factor with optional (maybe-absent) variables is
 * skipped too: the present-count is then a range, which the fixed-`n` greedy does not model.
 */
internal class GccSeparator : CutSeparator {
    private val tol = 1e-6

    override fun separate(ctx: CutContext): List<Cut> {
        val cuts = ArrayList<Cut>()
        for (factor in ctx.problem.factors) {
            if (factor !is GlobalCardinality || !factor.closed || factor.presents.isNotEmpty()) continue
            val xs = factor.xs
            if (xs.size < 2 || xs.size > MAX_VARS) continue
            val cols = IntArray(xs.size)
            var ok = true
            for (k in xs.indices) {
                val c = ctx.relaxation.intColOf[xs[k]]
                if (c < 0) {
                    ok = false
                    break
                }
                cols[k] = c
            }
            if (!ok) continue

            val bounds = sumBounds(factor, xs.size, ctx.session) ?: continue
            // The greedy distribution reads only the occurrence windows: factor constants make the
            // cut global outright; count variables make it global while their live intervals are
            // still the declared ones.
            val countVars = factor.countVars
            val global = if (factor.countLow != null && factor.countHigh != null) {
                true
            } else {
                countVars != null && liveIntervalsAreDeclared(ctx, countVars)
            }
            var lpSum = 0.0
            for (c in cols) lpSum += ctx.primalOf(c)
            if (lpSum < bounds[0] - tol) {
                cuts.add(
                    Cut(cols.copyOf(), LongArray(cols.size) { 1L }, Relation.GE, bounds[0], global),
                )
            }
            if (lpSum > bounds[1] + tol) {
                cuts.add(
                    Cut(cols.copyOf(), LongArray(cols.size) { 1L }, Relation.LE, bounds[1], global),
                )
            }
        }
        return cuts
    }

    /**
     * `[minSum, maxSum]` of `Σ x_i` over closed distributions of `n` variables honouring each cover
     * value's `[low_k, high_k]` occurrence window. Returns null when the distribution is infeasible
     * (too few/many slots), the cover is too large, or the arithmetic overflows — no cut, sound.
     */
    private fun sumBounds(gcc: GlobalCardinality, n: Int, session: PropagationSession): LongArray? {
        val cover = gcc.cover
        if (cover.isEmpty() || cover.size > MAX_VALUES) return null
        val low = LongArray(cover.size)
        val cap = LongArray(cover.size) // residual capacity high_k - low_k
        var forcedSlots = 0L
        var totalCap = 0L
        val countLow = gcc.countLow
        val countHigh = gcc.countHigh
        val countVars = gcc.countVars
        for (k in cover.indices) {
            val lo: Long
            val hi: Long
            if (countLow != null && countHigh != null) {
                lo = countLow[k].toLong()
                hi = countHigh[k].toLong()
            } else if (countVars != null) {
                val d = session.intDomain(countVars[k])
                lo = d.min
                hi = d.max
            } else {
                return null
            }
            if (hi < lo || lo < 0L) return null
            low[k] = lo
            cap[k] = hi - lo
            forcedSlots += lo
            totalCap += hi
        }
        if (forcedSlots > n || totalCap < n) return null // distribution infeasible: leave it to propagation

        var forcedSum = 0L
        for (k in cover.indices) forcedSum = addExact(forcedSum, mulExact(low[k], cover[k]))

        val order = cover.indices.sortedBy { cover[it] }
        return try {
            longArrayOf(
                fill(forcedSum, n - forcedSlots, cover, cap, order),
                fill(forcedSum, n - forcedSlots, cover, cap, order.asReversed()),
            )
        } catch (_: LpOverflowException) {
            null
        }
    }

    /** Spread [remaining] free slots over the residual capacities in [order] (cheapest- or dearest-first). */
    private fun fill(base: Long, remaining: Long, cover: LongArray, cap: LongArray, order: List<Int>): Long {
        var sum = base
        var left = remaining
        for (k in order) {
            if (left == 0L) break
            val take = minOf(cap[k], left)
            sum = addExact(sum, mulExact(take, cover[k]))
            left -= take
        }
        return sum
    }

    private companion object {
        const val MAX_VARS: Int = 4096
        const val MAX_VALUES: Int = 512
    }
}

/**
 * At-most-one conflict structure read off a problem's factors: [adjacency] maps a Boolean variable to
 * the variables mutually exclusive with it — an edge per binary clause `¬a ∨ ¬b`, all pairs of an
 * at-most-one factor (`Cardinality(max = 1)`, unit-weight `Σ x ≤ 1` PseudoBoolean) over positive
 * literals — and [baseCliques] lists those at-most-one factors' member variables (pairwise adjacent by
 * construction). Global by construction: every edge is implied by a factor of the original problem.
 */
internal class ConflictGraph(val adjacency: MutableIntObjectMap<IntHashSet>, val baseCliques: List<IntArray>)

/** Builds the [ConflictGraph] the knapsack-lifting and clique separators share. */
internal fun conflictGraph(problem: Problem): ConflictGraph {
    val adj = MutableIntObjectMap<IntHashSet>()
    fun edge(a: Int, b: Int) {
        if (a == b) return
        adj.getOrPut(a) { IntHashSet() }.add(b)
        adj.getOrPut(b) { IntHashSet() }.add(a)
    }

    val baseCliques = ArrayList<IntArray>()
    fun atMostOne(literals: IntArray) {
        if (literals.size < 2 || literals.any { !Lit.isPositive(it) }) return
        val vars = IntArray(literals.size) { Lit.variable(literals[it]) }
        for (i in vars.indices) for (j in i + 1 until vars.size) edge(vars[i], vars[j])
        baseCliques.add(vars)
    }
    for (factor in problem.factors) {
        when (factor) {
            is Clause -> if (factor.literals.size == 2 && factor.literals.none { Lit.isPositive(it) }) {
                edge(Lit.variable(factor.literals[0]), Lit.variable(factor.literals[1]))
            }

            is Cardinality -> if (factor.max == 1) atMostOne(factor.literals)

            is PseudoBoolean -> if (factor.op == PbOp.LE && factor.bound == 1L && factor.weights.all { it == 1L }) {
                atMostOne(factor.literals)
            }

            else -> Unit
        }
    }
    return ConflictGraph(adj, baseCliques)
}

/**
 * Knapsack cover cuts for a `Σ w_i·x_i ≤ b` PseudoBoolean row with positive weights over
 * 0/1 variables — the shape the dropped `Knapsack` factor decomposes to, so these recover its
 * strength. A *cover* `C` is a set of items with `Σ_{C} w_i > b`: no feasible 0/1 point can set all of
 * `C`, so `Σ_{i∈C} x_i ≤ |C| − 1` is a valid inequality. Separation finds a violated cover greedily by
 * fractional value: take the highest-`x*` items until their weight exceeds `b`; if the resulting
 * cover's `Σ x*` exceeds `|C| − 1` the cut is violated and emitted. The cover is then minimised and
 * **lifted**: every non-cover variable is up-lifted by sequential lifting — its coefficient is
 * `αₖ = (|C| − 1) − max{ Σ aᵢxᵢ : Σ wᵢxᵢ ≤ b − wₖ }` over the already-lifted items, where the max is a
 * small DP. When an at-most-one clique graph is present (binary clauses / AMO factors), the max is
 * solved as a GUB knapsack (at most one item per clique), shrinking it and so strengthening the lift —
 * such cuts are valid in conjunction with the clique rows, which are global and in the relaxation.
 * Mixed-sign rows (negated literals or non-positive weights) are skipped — their cover form needs
 * complementing, deferred.
 */
internal class KnapsackCoverSeparator : CutSeparator {
    private val tol = 1e-6

    override fun separate(ctx: CutContext): List<Cut> {
        val cuts = ArrayList<Cut>()
        // At-most-one conflict graph (binary clauses + AMO factors), shared across all knapsacks. Used
        // for GUB lifting: within a clique at most one item is 1, which strengthens the lift.
        val conflict = conflictGraph(ctx.problem).adjacency
        for (factor in ctx.problem.factors) {
            if (factor !is PseudoBoolean || factor.op != PbOp.LE) continue
            if (factor.weights.any { it <= 0 } || factor.literals.any { !Lit.isPositive(it) }) continue
            val k = factor.literals.size
            if (k < 2) continue
            val b = factor.bound
            val cols = IntArray(k)
            val xstar = DoubleArray(k)
            var ok = true
            for (i in 0 until k) {
                val col = ctx.relaxation.boolColOf[Lit.variable(factor.literals[i])]
                if (col < 0) {
                    ok = false
                    break
                }
                cols[i] = col
                xstar[i] = ctx.primalOf(col)
            }
            if (!ok) continue
            // Greedy cover: highest fractional value first, until the weight sum exceeds the bound.
            val order = (0 until k).sortedByDescending { xstar[it] }
            val inCover = BooleanArray(k)
            var coverCount = 0
            var wsum = 0L
            for (i in order) {
                inCover[i] = true
                coverCount++
                wsum = addExact(wsum, factor.weights[i])
                if (wsum > b) break
            }
            if (wsum <= b) continue // whole set fits under the bound — no cover, no cut
            // Minimise the cover: drop the lightest members while the sum still exceeds the bound, so
            // the base inequality `Σ_C x ≤ |C| − 1` is as strong as possible before lifting.
            run {
                var cw = wsum
                for (i in (0 until k).filter { inCover[it] }.sortedBy { factor.weights[it] }) {
                    if (cw - factor.weights[i] > b) {
                        inCover[i] = false
                        coverCount--
                        cw -= factor.weights[i]
                    }
                }
            }
            val r = (coverCount - 1).toLong()
            // Sequential up-lifting: start from the cover (coefficient 1) and lift each non-cover
            // variable k with the exact coefficient αₖ = r − max{ Σ aᵢxᵢ : Σ wᵢxᵢ ≤ b − wₖ over lifted
            // items, at most one per AMO clique }. The clique cap (GUB lifting) shrinks that max, giving
            // a larger — still valid — αₖ. The max is a small GUB-knapsack solved by DP; skip lifting
            // when the capacity would make the DP too large (emit the bare minimal cover then).
            val liftedPos = IntArrayList(coverCount)
            val liftedCoeff = LongArrayList(coverCount)
            for (i in 0 until k) {
                if (inCover[i]) {
                    liftedPos.add(i)
                    liftedCoeff.add(1L)
                }
            }
            if (b <= MAX_LIFT_CAP) {
                val groupOf = cliquePartition(k, factor.literals, conflict)
                val nonCover = (0 until k).filter { !inCover[it] }.sortedByDescending { factor.weights[it] }
                for (kk in nonCover) {
                    val cap = b - factor.weights[kk]
                    val maxv = if (cap < 0) {
                        0L
                    } else {
                        gubKnapsackMax(
                            liftedPos,
                            liftedCoeff,
                            factor.weights,
                            groupOf,
                            cap.toInt(),
                        )
                    }
                    val alpha = r - maxv
                    if (alpha > 0) {
                        liftedPos.add(kk)
                        liftedCoeff.add(alpha)
                    }
                }
            }
            var lhs = 0.0
            for (t in 0 until liftedPos.size) lhs += liftedCoeff[t] * xstar[liftedPos[t]]
            if (lhs > r + tol) {
                val cutCols = IntArray(liftedPos.size) { cols[liftedPos[it]] }
                val cutCoeff = LongArray(liftedPos.size) { liftedCoeff[it] }
                // Read off the row's weights, bound, and the (global) clique graph — global by construction.
                cuts.add(Cut(cutCols, cutCoeff, Relation.LE, r, global = true))
            }
        }
        return cuts
    }

    /** Greedy clique partition of the `k` knapsack positions over the [conflict] graph: each group is a
     *  set of pairwise mutually-exclusive items. Used as the GUB structure for [gubKnapsackMax]. Using
     *  only a partition's worth of edges (cross-group conflicts are ignored) keeps the lifting max an
     *  over-estimate, so the derived coefficients stay valid. */
    private fun cliquePartition(k: Int, literals: IntArray, conflict: MutableIntObjectMap<IntHashSet>): IntArray {
        val vars = IntArray(k) { Lit.variable(literals[it]) }
        fun adjacent(i: Int, j: Int): Boolean = conflict[vars[i]]?.contains(vars[j]) == true
        val group = IntArray(k) { -1 }
        var g = 0
        for (i in 0 until k) {
            if (group[i] != -1) continue
            group[i] = g
            val members = arrayListOf(i)
            for (j in i + 1 until k) {
                if (group[j] == -1 && members.all { adjacent(it, j) }) {
                    group[j] = g
                    members.add(j)
                }
            }
            g++
        }
        return group
    }

    /** Max `Σ coeffᵢ·xᵢ` over the [lifted] items with `Σ weightᵢ·xᵢ ≤ cap` and at most one item taken
     *  per clique group ([groupOf] over the items' positions). A GUB (generalised-upper-bound) knapsack
     *  solved by DP over the capacity, processing one clique group at a time so each contributes ≤ 1. */
    private fun gubKnapsackMax(
        lifted: IntArrayList,
        coeff: LongArrayList,
        weights: LongArray,
        groupOf: IntArray,
        cap: Int,
    ): Long {
        val byGroup = MutableIntObjectMap<IntArrayList>()
        for (t in 0 until lifted.size) byGroup.getOrPut(groupOf[lifted[t]]) { IntArrayList() }.add(t)
        val dp = LongArray(cap + 1)
        byGroup.forEach { _, idxs ->
            val next = dp.copyOf() // "take none from this group"
            idxs.forEach { t ->
                val w = weights[lifted[t]]
                if (w > cap) return@forEach // an item heavier than the capacity is never taken
                val wi = w.toInt() // w <= cap here, so the DP index stays in range
                val v = coeff[t]
                // dp[c - w] is the pre-group value, so at most one item from the group is taken.
                for (c in cap downTo wi) {
                    val cand = dp[c - wi] + v
                    if (cand > next[c]) next[c] = cand
                }
            }
            for (c in 0..cap) dp[c] = next[c]
        }
        return dp[cap]
    }

    private companion object {
        /** Capacity ceiling for the lifting DP (array size `cap + 1`); above it, emit the bare cover. */
        const val MAX_LIFT_CAP: Long = 4096L
    }
}

/**
 * Clique cuts for set-packing structure. Two Boolean variables are *mutually exclusive* when at most
 * one can be true; a set of pairwise mutually exclusive variables is a clique, and `Σ_{clique} x ≤ 1`
 * is a valid inequality. The conflict graph is read straight off the problem: a binary clause
 * `¬a ∨ ¬b` is an edge, and an at-most-one constraint (a `Cardinality` with `max = 1`, or a unit-weight
 * `Σ x ≤ 1` PseudoBoolean) over positive literals is a base clique whose members are all pairwise
 * adjacent. Each base clique is greedily extended with the highest-fractional variables that are
 * adjacent to every current member — keeping it a true clique — and the cut is emitted when the
 * extended clique's LP value exceeds 1. The base constraint alone is already in the relaxation; the
 * value is the extension across constraints.
 */
internal class CliqueCutSeparator : CutSeparator {
    private val tol = 1e-6

    override fun separate(ctx: CutContext): List<Cut> {
        val graph = conflictGraph(ctx.problem)
        val adj = graph.adjacency
        val baseCliques = graph.baseCliques
        if (baseCliques.isEmpty()) return emptyList()

        // Variables with a Boolean column, ordered by descending fractional value — the extension order.
        val adjKeys = ArrayList<Int>(adj.size)
        adj.forEach { k, _ -> adjKeys.add(k) }
        val ranked = adjKeys
            .filter { ctx.relaxation.boolColOf[it] >= 0 }
            .sortedByDescending { ctx.primalOf(ctx.relaxation.boolColOf[it]) }

        val cuts = ArrayList<Cut>()
        val emitted = HashSet<String>()
        for (base in baseCliques) {
            val clique = base.filter { ctx.relaxation.boolColOf[it] >= 0 }.toMutableList()
            if (clique.size < 2) continue
            val members = IntHashSet()
            clique.forEach { members.add(it) }
            for (cand in ranked) {
                if (cand in members) continue
                val neigh = adj[cand] ?: continue
                if (clique.all { it in neigh }) {
                    clique.add(cand)
                    members.add(cand)
                }
            }
            val cols = IntArray(clique.size) { ctx.relaxation.boolColOf[clique[it]] }
            var lhs = 0.0
            for (c in cols) lhs += ctx.primalOf(c)
            if (lhs <= 1.0 + tol) continue
            val key = cols.sorted().joinToString(",")
            if (!emitted.add(key)) continue
            // The conflict graph is read off binary clauses and at-most-one factors — global.
            cuts.add(Cut(cols, LongArray(cols.size) { 1L }, Relation.LE, 1L, global = true))
        }
        return cuts
    }
}
