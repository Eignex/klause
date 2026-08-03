package com.eignex.klause.factor.arithmetic

import com.eignex.klause.factor.arithmetic.internals.findCoeff
import com.eignex.klause.factor.arithmetic.internals.initLinearSum
import com.eignex.klause.factor.bool.internals.linearDegree
import com.eignex.klause.factor.bool.internals.linearHolds
import com.eignex.klause.factor.bool.internals.snapLinearTarget
import com.eignex.klause.localsearch.ChannelingSink
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move.IntSet
import com.eignex.klause.localsearch.MoveSink

/** LS invariant for [Linear]: violation tracking, repair, and structured moves. */
internal class LinearInvariant(
    private val coeffs: LongArray,
    private val vars: IntArray,
    private val op: LinearOp,
    private val bound: Long,
) : Invariant {

    override fun initialize(state: LocalSearchState, factorId: Int) = initLinearSum(state, factorId, coeffs, vars)

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean =
        !linearHolds(state.longPayload[factorId], op, bound)

    override fun violationDegree(state: LocalSearchState, factorId: Int): Int =
        linearDegree(state.longPayload[factorId], op, bound, state.violationSoftCap)

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Long): Int {
        val coeff = findCoeff(coeffs, vars, intVar)
        val old = state.assignment.intValue(intVar)
        val sum = state.longPayload[factorId]
        val newSum = sum + coeff * (newValue - old)
        // The pre-move degree `degree(sum)` is the factor's current violation degree, already
        // maintained in factorDegree — reuse it instead of re-running the residual/compression.
        return linearDegree(newSum, op, bound, state.violationSoftCap) - state.factorDegree[factorId]
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Long): Int {
        val coeff = findCoeff(coeffs, vars, intVar)
        val cur = state.assignment.intValue(intVar)
        val oldSum = state.longPayload[factorId]
        val newSum = oldSum + coeff * (cur - oldValue)
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
                if (c == 0L) continue
                val cur = state.assignment.intValue(v)
                val d = state.rootDomains[v]
                if (cur > d.min) sink.addChannelingIntSet(state, v, cur - 1)
                if (cur < d.max) sink.addChannelingIntSet(state, v, cur + 1)
            }
            return
        }
        for (i in vars.indices) {
            val v = vars[i]
            val c = coeffs[i]
            if (c == 0L) continue
            val cur = state.assignment.intValue(v)
            val sumWithout = sum - c * cur
            val target = snapLinearTarget(op, bound, c, sumWithout, wantHolds = true) ?: continue
            val clamped = state.rootDomains[v].clamp(target)
            if (clamped != cur) sink.addChannelingIntSet(state, v, clamped)
        }
    }

    /** Sum channeling for a currently-satisfied EQ: when [intVar] drifts by `c·Δ`, shift the
     *  lowest-|coeff| other participant by the inverse so `Σ c·x = bound` stays balanced, preferring a
     *  coefficient that divides the drift evenly so the target lands on an integer. A violated EQ is
     *  the constraint the caller is repairing via the int set, so a counter-shift would undo it. */
    override fun contributeChanneling(
        state: LocalSearchState,
        factorId: Int,
        intVar: Int,
        oldValue: Long,
        newValue: Long,
        sink: ChannelingSink,
    ) {
        if (op != LinearOp.EQ || state.violated.contains(factorId)) return
        val coeffV = findCoeff(coeffs, vars, intVar)
        if (coeffV == 0L) return
        val drift = coeffV * (newValue - oldValue)
        var bestIdx = -1
        var bestAbs = Long.MAX_VALUE
        for (i in vars.indices) {
            val u = vars[i]
            if (u == intVar || sink.isPinned(u)) continue
            val cu = coeffs[i]
            if (cu == 0L) continue
            val absC = if (cu < 0) -cu else cu
            if (absC < bestAbs && drift % cu == 0L) {
                bestAbs = absC
                bestIdx = i
            }
        }
        if (bestIdx < 0) return
        val u = vars[bestIdx]
        if (state.assumptions.isFrozenInt(u)) return
        val cu = coeffs[bestIdx]
        val uShift = -drift / cu // (uShift * cu) cancels the drift
        val curU = state.assignment.intValue(u)
        val newU = curU + uShift
        if (newU == curU) return
        val dom = state.rootDomains[u]
        if (newU < dom.min || newU > dom.max) return
        sink.add(IntSet(u, newU))
        sink.pin(u)
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
            if (ca != 0L && cb != 0L) {
                val va = state.assignment.intValue(vars[a])
                val vb = state.assignment.intValue(vars[b])
                // For each Δ ∈ {-1, +1}, the matching Δb is -ca/cb · Δa; if integer and in
                // domain on both sides, propose the compound. Covers the common pure-bool
                // exactly-one decomposition (ca=cb=1, swap 0/1).
                for (delta in intArrayOf(-1, 1)) {
                    if (cb == 0L) continue
                    val needNumerator = -ca * delta
                    if (needNumerator % cb != 0L) continue
                    val deltaB = needNumerator / cb
                    val newA = va + delta
                    val newB = vb + deltaB
                    if (newA == va && newB == vb) continue
                    val domA = state.rootDomains[vars[a]]
                    val domB = state.rootDomains[vars[b]]
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
            if (c == 0L) continue
            val v = vars[i]
            val cur = state.assignment.intValue(v)
            val absC = if (c < 0) -c else c
            val maxStep = slack / absC
            if (maxStep <= 0L) continue
            val dom = state.rootDomains[v]
            // For LE: positive c → decrease v (frees more slack), negative c → increase v.
            // For GE: opposite. Either way, the move stays feasible because |Δ·c| ≤ slack.
            val direction = when (op) {
                LinearOp.LE -> if (c > 0) -1 else 1
                LinearOp.GE -> if (c > 0) 1 else -1
                else -> 0
            }
            val target = cur + direction * maxStep
            val clamped = dom.clamp(target)
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
