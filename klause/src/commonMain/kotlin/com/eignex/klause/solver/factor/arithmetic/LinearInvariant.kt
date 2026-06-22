package com.eignex.klause.solver.factor.arithmetic

import com.eignex.klause.solver.Invariant
import com.eignex.klause.solver.Move.IntSet
import com.eignex.klause.solver.factor.arithmetic.internals.findCoeff
import com.eignex.klause.solver.factor.bool.internals.linearDegree
import com.eignex.klause.solver.factor.bool.internals.linearHolds
import com.eignex.klause.solver.factor.bool.internals.snapLinearTarget
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink

/** LS invariant for [Linear]: violation tracking, repair, and structured moves. */
internal class LinearInvariant(
    override val boolVars: IntArray,
    override val intVars: IntArray,
    private val coeffs: IntArray,
    private val vars: IntArray,
    private val op: LinearOp,
    private val bound: Int,
) : Invariant {

    override fun initialize(state: LocalSearchState, factorId: Int) {
        var sum = 0L
        for (i in vars.indices) sum += coeffs[i].toLong() * state.assignment.intValue(vars[i])
        state.longPayload[factorId] = sum
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean =
        !linearHolds(state.longPayload[factorId], op, bound)

    override fun violationDegree(state: LocalSearchState, factorId: Int): Int =
        linearDegree(state.longPayload[factorId], op, bound, state.violationSoftCap)

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int {
        val coeff = findCoeff(coeffs, vars, intVar)
        val old = state.assignment.intValue(intVar)
        val sum = state.longPayload[factorId]
        val newSum = sum + coeff.toLong() * (newValue - old)
        // The pre-move degree `degree(sum)` is the factor's current violation degree, already
        // maintained in factorDegree — reuse it instead of re-running the residual/compression.
        return linearDegree(newSum, op, bound, state.violationSoftCap) - state.factorDegree[factorId]
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int {
        val coeff = findCoeff(coeffs, vars, intVar)
        val cur = state.assignment.intValue(intVar)
        val oldSum = state.longPayload[factorId]
        val newSum = oldSum + coeff.toLong() * (cur - oldValue)
        state.longPayload[factorId] = newSum
        return linearDegree(newSum, op, bound, state.violationSoftCap) -
            linearDegree(oldSum, op, bound, state.violationSoftCap)
    }

    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        val sum = state.longPayload[factorId]
        if (linearHolds(sum, op, bound)) return
        if (op == LinearOp.NE) {
            // sum == bound; bump any non-zero-coeff variable by ±1 within its domain. Each
            // single shift changes sum by ±|c_i| ≠ 0 → breaks the equality.
            for (i in vars.indices) {
                val v = vars[i]
                val c = coeffs[i]
                if (c == 0) continue
                val cur = state.assignment.intValue(v)
                val d = state.problem.intDomains[v]
                if (cur > d.min) sink.addChannelingIntSet(state, v, cur - 1)
                if (cur < d.max) sink.addChannelingIntSet(state, v, cur + 1)
            }
            return
        }
        for (i in vars.indices) {
            val v = vars[i]
            val c = coeffs[i]
            if (c == 0) continue
            val cur = state.assignment.intValue(v)
            val sumWithout = sum - c.toLong() * cur
            val target = snapLinearTarget(op, bound, c, sumWithout, wantHolds = true) ?: continue
            val clamped = state.problem.intDomains[v].clampLong(target)
            if (clamped != cur) sink.addChannelingIntSet(state, v, clamped)
        }
    }

    /** Self-preserving move suggestions during objective descent. For [LinearOp.EQ] the
     *  natural structured move is a pair-shift `(IntSet(a, va-Δ), IntSet(b, vb+Δ))` chosen
     *  so `coeffs[a]·Δa + coeffs[b]·Δb = 0` — the equality stays satisfied while the
     *  individual var values change. This is the heart of "swap one item between bins"
     *  for exactly-one decompositions and the analogous "shift Δ between two summed
     *  vars" for weighted equalities. For LE/GE we propose unilateral shifts that
     *  consume / restore slack; the engine scores each by objective delta and applies
     *  the best feasibility-preserving one.
     *
     *  Bounded: we sample up to [PAIR_SAMPLE_CAP] random pairs (or all of them when the
     *  factor's arity is small) rather than enumerate Θ(n²) which would dominate the
     *  inner loop on large decomposed instances. */
    override fun proposeStructuredMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        if (vars.size < 2) return
        when (op) {
            LinearOp.EQ -> proposeEqPairShifts(state, sink)
            LinearOp.LE, LinearOp.GE -> proposeBoundedSlackShifts(state, factorId, sink)
            LinearOp.NE -> { /* no useful structured move — NE is fragile to any change */ }
        }
    }

    /** EQ pair-shift: for each sampled pair (a, b) with non-zero coefficients, propose
     *  the smallest integer Δ such that `coeffs[a]·Δ = -coeffs[b]·Δ'` for some integer
     *  Δ' that fits both vars' domains. Equal-coefficient case (e.g. exactly-one decomp
     *  with all c=1) collapses to a 0/1 value swap. */
    private fun proposeEqPairShifts(state: LocalSearchState, sink: MoveSink) {
        val n = vars.size
        val tryPair = { a: Int, b: Int ->
            val ca = coeffs[a]
            val cb = coeffs[b]
            if (ca != 0 && cb != 0) {
                val va = state.assignment.intValue(vars[a])
                val vb = state.assignment.intValue(vars[b])
                // For each Δ ∈ {-1, +1}, the matching Δb is -ca/cb · Δa; if integer and in
                // domain on both sides, propose the compound. Covers the common pure-bool
                // exactly-one decomposition (ca=cb=1, swap 0/1).
                for (delta in intArrayOf(-1, 1)) {
                    if (cb == 0) continue
                    val needNumerator = -ca * delta
                    if (needNumerator % cb != 0) continue
                    val deltaB = needNumerator / cb
                    val newA = va + delta
                    val newB = vb + deltaB
                    if (newA == va && newB == vb) continue
                    val domA = state.problem.intDomains[vars[a]]
                    val domB = state.problem.intDomains[vars[b]]
                    if (newA !in domA || newB !in domB) continue
                    sink.addCompound(
                        listOf(
                            IntSet(vars[a], newA),
                            IntSet(vars[b], newB),
                        ),
                    )
                }
            }
        }
        // Exhaustive on small arity; sampled on large.
        if (n * (n - 1) / 2 <= PAIR_SAMPLE_CAP) {
            for (a in 0 until n) for (b in a + 1 until n) tryPair(a, b)
        } else {
            val rng = state.rng
            var tried = 0
            while (tried < PAIR_SAMPLE_CAP) {
                val a = rng.nextInt(n)
                val b = rng.nextInt(n)
                if (a != b) tryPair(a, b)
                tried++
            }
        }
    }

    /** LE/GE slack-aware shifts: when slack > 0, propose individual var moves whose
     *  direction consumes some slack. For LE: `sum ≤ bound` has slack `bound - sum`;
     *  we can increase any var with positive coefficient or decrease any with negative
     *  coefficient by up to floor(slack / |c|). For GE: symmetric. Single-var moves —
     *  not pairs — since the inequality direction lets us absorb the delta in slack. */
    private fun proposeBoundedSlackShifts(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        val curSum = state.longPayload[factorId]
        val slack = when (op) {
            LinearOp.LE -> bound - curSum
            LinearOp.GE -> curSum - bound
            else -> 0L
        }
        if (slack <= 0L) return
        for (i in vars.indices) {
            val c = coeffs[i]
            if (c == 0) continue
            val v = vars[i]
            val cur = state.assignment.intValue(v)
            val absC = if (c < 0) -c.toLong() else c.toLong()
            val maxStep = slack / absC
            if (maxStep <= 0L) continue
            val dom = state.problem.intDomains[v]
            // For LE: positive c → decrease v (frees more slack), negative c → increase v.
            // For GE: opposite. Either way, the move stays feasible because |Δ·c| ≤ slack.
            val direction = when (op) {
                LinearOp.LE -> if (c > 0) -1 else 1
                LinearOp.GE -> if (c > 0) 1 else -1
                else -> 0
            }
            val target = cur + direction * maxStep
            val clamped = dom.clampLong(target)
            if (clamped != cur) sink.addChannelingIntSet(state, v, clamped)
        }
    }

    private companion object {
        /** Cap on randomly-sampled (a, b) pairs in [proposeEqPairShifts]. Total proposals
         *  bound matters for engine throughput — each scored move costs a state.apply +
         *  revert. 32 is enough to land useful candidates on the dominant decomposed-CP
         *  shape (sum-of-bools = constant) without dominating descent step cost. */
        const val PAIR_SAMPLE_CAP: Int = 32
    }
}
