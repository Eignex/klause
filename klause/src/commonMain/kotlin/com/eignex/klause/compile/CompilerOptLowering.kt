package com.eignex.klause.compile

import com.eignex.klause.model.AllDifferentOpt
import com.eignex.klause.model.And
import com.eignex.klause.model.BoolExpr
import com.eignex.klause.model.CircuitExpr
import com.eignex.klause.model.CumulativeExpr
import com.eignex.klause.model.CumulativeExprOpt
import com.eignex.klause.model.DisjunctiveExpr
import com.eignex.klause.model.DisjunctiveExprOpt
import com.eignex.klause.model.GccExprOpt
import com.eignex.klause.model.IntCmpOp
import com.eignex.klause.model.IntCompare
import com.eignex.klause.model.IntElement
import com.eignex.klause.model.IntExpr
import com.eignex.klause.model.IntIfThenElse
import com.eignex.klause.model.IntLit
import com.eignex.klause.model.IntRef
import com.eignex.klause.model.IntSum
import com.eignex.klause.model.NValueExprOpt
import com.eignex.klause.model.NValueMode
import com.eignex.klause.model.Not
import com.eignex.klause.model.Or
import com.eignex.klause.model.SubcircuitExpr
import com.eignex.klause.solver.IntDomain

/*
 * Reified (sub-expression-position) lowering for global constraints. Each entry-point
 * returns a Boolean literal `b` such that `b ↔ φ`, where `φ` is the constraint's
 * decomposition into already-supported AST primitives ([IntCompare], [And], [Or],
 * [IntIfThenElse], [IntSum]). The compiler's `reifyIntCompare` / `tseitinAnd` /
 * `tseitinOr` machinery does the actual reification — this file just builds the AST
 * skeleton.
 *
 * Why decomposition rather than reifying the native factor:
 *
 *  - Native factor propagators (Régin matching, Theta-tree, max-flow) detect *infeasibility*
 *    on partial states but don't produce a "the constraint is false right now" witness. A
 *    reified form needs both directions — true *and* false — so we'd have to invent the
 *    false-side machinery from scratch per factor. Decomposition gives both directions for
 *    free via the existing reified primitives.
 *  - Bit-blasting only handles a small set of factor kinds (Clause, Cardinality, Linear,
 *    PseudoBoolean, ReifiedLinear, ReifiedCardinality, ReifiedPseudoBoolean, Xor, Product,
 *    AllDifferent). Decompositions land entirely inside that set, so BitBlaster sees the
 *    reified form for free.
 *
 * Tradeoff: at non-top-level we trade stronger propagation (the native factor) for a
 * sound decomposition. Top-level uses keep the native factor through [CompilerAssertions].
 */

/** Build a 0/1-valued IntExpr equal to 1 iff [cond] holds, 0 otherwise. The downstream
 *  affine-lift turns this into an aux int + reified equalities. */
private fun indicatorInt(cond: BoolExpr): IntExpr = IntIfThenElse(cond, IntLit(1), IntLit(0))

/** `b ↔ AllDifferent(terms)` (non-opt). Pairwise NE conjuncted via Tseitin. */
internal fun Lowering.reifyAllDifferent(terms: List<IntExpr>): Int {
    val pairs = mutableListOf<BoolExpr>()
    for (i in terms.indices) {
        for (j in i + 1 until terms.size) {
            pairs += IntCompare(terms[i], IntCmpOp.NE, terms[j])
        }
    }
    return tseitinAnd(pairs)
}

/** `b ↔ AllDifferentOpt`. Each pair guarded by both presence bits. */
internal fun Lowering.reifyAllDifferentOpt(expr: AllDifferentOpt): Int {
    val pairs = mutableListOf<BoolExpr>()
    for (i in expr.terms.indices) {
        for (j in i + 1 until expr.terms.size) {
            // (p_i ∧ p_j) → x_i ≠ x_j  ≡  ¬p_i ∨ ¬p_j ∨ x_i ≠ x_j.
            pairs += Or(
                listOf(
                    Not(expr.presents[i]),
                    Not(expr.presents[j]),
                    IntCompare(expr.terms[i], IntCmpOp.NE, expr.terms[j]),
                ),
            )
        }
    }
    return tseitinAnd(pairs)
}

/** `b ↔ nvalue(n, xs, mode)` over a presence-gated subset. Enumerate the union domain;
 *  for each candidate value `v`, build the indicator `∃i (p_i ∧ x_i = v)`. Sum the
 *  indicators and compare against `n` per `mode`. */
internal fun Lowering.reifyNValueOpt(expr: NValueExprOpt): Int {
    // Compute the union of static domains for each x_i. Each xs entry must lift to a
    // bare IntRef so we can look up its domain — non-bare arithmetic on opt vars at this
    // position isn't supported (matches the non-opt convention).
    val unionSet = HashSet<Int>()
    for (i in expr.xs.indices) {
        val xLifted = lift(expr.xs[i])
        val d = domainOf(xLifted)
        for (v in d.min..d.max) unionSet.add(v)
    }
    // Iterate in ascending value order (was a sorted set; commonMain has no sortedSetOf).
    val perValue: List<IntExpr> = unionSet.sorted().map { v ->
        val anyHolds = expr.xs.indices.map { i ->
            And(listOf(expr.presents[i], IntCompare(expr.xs[i], IntCmpOp.EQ, IntLit(v))))
        }
        indicatorInt(if (anyHolds.size == 1) anyHolds[0] else Or(anyHolds))
    }
    val sum: IntExpr = if (perValue.size == 1) perValue[0] else IntSum(perValue)
    val op = when (expr.mode) {
        NValueMode.EQ -> IntCmpOp.EQ

        NValueMode.AT_LEAST -> IntCmpOp.LE

        // n ≤ |distinct|
        NValueMode.AT_MOST -> IntCmpOp.GE // n ≥ |distinct|
    }
    return reifyIntCompare(IntCompare(expr.n, op, sum))
}

/** `b ↔ gcc(...)` over a presence-gated subset, with optional closed-set check.
 *  Per cover: `low[k] ≤ Σ_i 1{p_i ∧ x_i=cover[k]} ≤ high[k]`. */
internal fun Lowering.reifyGccOpt(expr: GccExprOpt): Int {
    val pieces = mutableListOf<BoolExpr>()
    for (k in expr.cover.indices) {
        val coverVal = expr.cover[k]
        val perI = expr.xs.indices.map { i ->
            indicatorInt(And(listOf(expr.presents[i], IntCompare(expr.xs[i], IntCmpOp.EQ, IntLit(coverVal)))))
        }
        val sum: IntExpr = if (perI.size == 1) perI[0] else IntSum(perI)
        pieces += IntCompare(sum, IntCmpOp.GE, IntLit(expr.low[k]))
        pieces += IntCompare(sum, IntCmpOp.LE, IntLit(expr.high[k]))
    }
    if (expr.closed) {
        // Every present x_i must equal one of the cover values: p_i → ∨_k x_i = cover[k].
        val coverSet = expr.cover.toSet()
        for (i in expr.xs.indices) {
            val inCover: BoolExpr = if (coverSet.size == 1) {
                IntCompare(expr.xs[i], IntCmpOp.EQ, IntLit(expr.cover[0]))
            } else {
                Or(expr.cover.map { IntCompare(expr.xs[i], IntCmpOp.EQ, IntLit(it)) })
            }
            pieces += Or(listOf(Not(expr.presents[i]), inCover))
        }
    }
    return tseitinAnd(pieces)
}

/** `b ↔ disjunctive(starts, durations)` (non-opt). Pairwise non-overlap via reified Or. */
internal fun Lowering.reifyDisjunctive(expr: DisjunctiveExpr): Int {
    val pieces = mutableListOf<BoolExpr>()
    for (i in expr.starts.indices) {
        for (j in i + 1 until expr.starts.size) {
            val di = expr.durations[i]
            val dj = expr.durations[j]
            if (di == 0 || dj == 0) continue
            pieces += pairwiseNoOverlap(expr.starts[i], di, expr.starts[j], dj)
        }
    }
    return if (pieces.isEmpty()) trueLit() else tseitinAnd(pieces)
}

/** `b ↔ disjunctiveOpt(...)`. Each pair gated by both presence bits. */
internal fun Lowering.reifyDisjunctiveOpt(expr: DisjunctiveExprOpt): Int {
    val pieces = mutableListOf<BoolExpr>()
    for (i in expr.starts.indices) {
        for (j in i + 1 until expr.starts.size) {
            val di = expr.durations[i]
            val dj = expr.durations[j]
            if (di == 0 || dj == 0) continue
            val noOverlap = pairwiseNoOverlap(expr.starts[i], di, expr.starts[j], dj)
            pieces += Or(listOf(Not(expr.presents[i]), Not(expr.presents[j]), noOverlap))
        }
    }
    return if (pieces.isEmpty()) trueLit() else tseitinAnd(pieces)
}

/** `x_i + d_i ≤ x_j ∨ x_j + d_j ≤ x_i`. */
private fun pairwiseNoOverlap(si: IntExpr, di: Int, sj: IntExpr, dj: Int): BoolExpr = Or(
    listOf(
        IntCompare(IntSum(listOf(si, IntLit(di))), IntCmpOp.LE, sj),
        IntCompare(IntSum(listOf(sj, IntLit(dj))), IntCmpOp.LE, si),
    ),
)

/** `b ↔ cumulative(starts, durations, resources, capacity)` (non-opt). Time-tabling
 *  decomposition: at every integer t in the static horizon, the sum of `r_i ·
 *  runs_i(t)` must stay under capacity. The horizon spans `[minEst, maxLct)` derived from
 *  the static start-var domains; absent that information the lowering can't bound the
 *  number of time-point constraints, so each start must lift to a bare [com.eignex.klause.model.IntRef]. */
internal fun Lowering.reifyCumulative(expr: CumulativeExpr): Int =
    cumulativeTimeTabling(expr.starts, expr.durations, expr.resources, expr.capacity, presents = null)

/** Same as [reifyCumulative] but with per-task presence gates folded into each
 *  runs-at-t indicator. */
internal fun Lowering.reifyCumulativeOpt(expr: CumulativeExprOpt): Int =
    cumulativeTimeTabling(expr.starts, expr.durations, expr.resources, expr.capacity, presents = expr.presents)

/**
 * `b ↔ circuit(succ, valueOffset)`. MTZ-style position-vector decomposition: the
 * successor array must be a permutation with no self-loops, and a single visit order
 * exists such that following `succ` from node 0 traverses every node before returning
 * to 0. Cost: `O(n)` extra constraints + `n - 1` aux position vars + the AllDifferent
 * over `succ` and over positions. All produced factors are BitBlaster-supported (Linear,
 * Reified*, AllDifferent, Clause).
 */
internal fun Lowering.reifyCircuit(expr: CircuitExpr): Int {
    val n = expr.succ.size
    val offset = expr.valueOffset
    val pieces = mutableListOf<BoolExpr>()
    // 1. AllDifferent over succ — the assignment is a permutation.
    pieces += allDiffAsAnd(expr.succ)
    // 2. No self-loops: succ[i] ≠ i + offset.
    for (i in 0 until n) {
        pieces += IntCompare(expr.succ[i], IntCmpOp.NE, IntLit(i + offset))
    }
    if (n <= 1) return tseitinAnd(pieces)
    // 3. Position vars. pos[0] = 0 is implied; we allocate pos[1..n-1] ∈ [1, n-1]
    //    pairwise-distinct, then enforce `pos[succ[i] - offset] = pos[i] + 1` whenever
    //    succ[i] != offset (the closing edge to node 0).
    val posVars: List<IntExpr> = (0 until n).map { i ->
        if (i == 0) {
            IntLit(0)
        } else {
            IntRef(newAuxIntVar(IntDomain(1, n - 1)))
        }
    }
    // 4. AllDifferent over the non-zero positions.
    val nonZeroPos: List<IntExpr> = posVars.drop(1)
    if (nonZeroPos.size >= 2) pieces += allDiffAsAnd(nonZeroPos)
    // 5. MTZ chain: for each i, either succ[i] = offset (closing edge to node 0) or
    //    pos[succ[i] - offset] = pos[i] + 1. Use IntElement to index posVars by the
    //    0-indexed successor; subtract valueOffset to bring succ[i] into [0, n-1].
    val posVarsAsList: List<IntExpr> = posVars
    val succIndex0: List<IntExpr> = expr.succ.map { s ->
        if (offset == 0) s else IntSum(listOf(s, IntLit(-offset)))
    }
    for (i in 0 until n) {
        val isClosing = IntCompare(expr.succ[i], IntCmpOp.EQ, IntLit(offset))
        val posAtSucc = IntElement(succIndex0[i], posVarsAsList)
        val advance = IntCompare(posAtSucc, IntCmpOp.EQ, IntSum(listOf(posVars[i], IntLit(1))))
        pieces += Or(listOf(isClosing, advance))
    }
    return tseitinAnd(pieces)
}

/**
 * `b ↔ subcircuit(succ, valueOffset)`. Like [reifyCircuit] but `succ[i] = i + offset`
 * marks node `i` excluded; the included nodes (non-self-loops) form a single Hamiltonian
 * cycle in the induced sub-graph. We still enforce AllDifferent over `succ` and a chain
 * structure on position vars, except the chain advance is relaxed to
 * `pos[succ[i]] = pos[i] + 1 ∨ pos[succ[i]] = pos[i]` so excluded nodes (self-loops) don't force a
 * contradiction. Closing the included sub-cycle is handled by allowing
 * `pos[succ[i]] = 0` when the edge closes the loop.
 */
internal fun Lowering.reifySubcircuit(expr: SubcircuitExpr): Int {
    val n = expr.succ.size
    val offset = expr.valueOffset
    val pieces = mutableListOf<BoolExpr>()
    pieces += allDiffAsAnd(expr.succ)
    if (n == 0) return tseitinAnd(pieces)
    val posVars: List<IntExpr> = (0 until n).map { IntRef(newAuxIntVar(IntDomain(0, n - 1))) }
    val succIndex0: List<IntExpr> = expr.succ.map { s ->
        if (offset == 0) s else IntSum(listOf(s, IntLit(-offset)))
    }
    for (i in 0 until n) {
        val isExcluded = IntCompare(expr.succ[i], IntCmpOp.EQ, IntLit(i + offset))
        val posAtSucc = IntElement(succIndex0[i], posVars)
        // Included edge: pos[succ[i]] = pos[i] + 1, or pos[succ[i]] = 0 to close the loop.
        val advance = IntCompare(posAtSucc, IntCmpOp.EQ, IntSum(listOf(posVars[i], IntLit(1))))
        val closing = IntCompare(posAtSucc, IntCmpOp.EQ, IntLit(0))
        pieces += Or(listOf(isExcluded, advance, closing))
    }
    return tseitinAnd(pieces)
}

/** Pairwise NE over a list of IntExpr, materialised as a single [And] of [IntCompare]s.
 *  Used by the Circuit/Subcircuit reified lowerings; the AllDifferent AST node could be
 *  used directly but the And-of-pairs form lets the surrounding tseitin reify it cleanly. */
private fun allDiffAsAnd(terms: List<IntExpr>): BoolExpr {
    if (terms.size < 2) return IntCompare(IntLit(0), IntCmpOp.EQ, IntLit(0))
    val pairs = mutableListOf<BoolExpr>()
    for (i in terms.indices) {
        for (j in i + 1 until terms.size) {
            pairs += IntCompare(terms[i], IntCmpOp.NE, terms[j])
        }
    }
    return if (pairs.size == 1) pairs[0] else And(pairs)
}

private fun Lowering.cumulativeTimeTabling(
    starts: List<IntExpr>,
    durations: List<Int>,
    resources: List<Int>,
    capacity: Int,
    presents: List<BoolExpr>?,
): Int {
    // Bound the horizon via static domains: [minEstAcrossTasks, maxLctAcrossTasks).
    var horizonLo = Int.MAX_VALUE
    var horizonHi = Int.MIN_VALUE
    for (i in starts.indices) {
        val sLifted = lift(starts[i])
        val d = domainOf(sLifted)
        if (d.min < horizonLo) horizonLo = d.min
        val hi = d.max + durations[i]
        if (hi > horizonHi) horizonHi = hi
    }
    if (horizonLo >= horizonHi) return trueLit()
    val pieces = mutableListOf<BoolExpr>()
    for (t in horizonLo until horizonHi) {
        // Σ_i r_i · 1{start_i ≤ t < start_i + d_i ∧ p_i} ≤ capacity
        val terms = mutableListOf<IntExpr>()
        for (i in starts.indices) {
            val d = durations[i]
            val r = resources[i]
            if (d == 0 || r == 0) continue
            val runsAt = And(
                listOf(
                    IntCompare(starts[i], IntCmpOp.LE, IntLit(t)),
                    IntCompare(IntSum(listOf(starts[i], IntLit(d))), IntCmpOp.GT, IntLit(t)),
                ),
            )
            val gated: BoolExpr = if (presents == null) {
                runsAt
            } else {
                And(listOf(presents[i], runsAt))
            }
            // r * indicator. Scale via IntIfThenElse(gated, r, 0).
            terms += IntIfThenElse(gated, IntLit(r), IntLit(0))
        }
        if (terms.isEmpty()) continue
        val sum: IntExpr = if (terms.size == 1) terms[0] else IntSum(terms)
        pieces += IntCompare(sum, IntCmpOp.LE, IntLit(capacity))
    }
    return if (pieces.isEmpty()) trueLit() else tseitinAnd(pieces)
}
