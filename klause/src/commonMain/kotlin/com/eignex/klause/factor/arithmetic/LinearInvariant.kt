package com.eignex.klause.factor.arithmetic

import com.eignex.klause.factor.arithmetic.internals.LinearCoeffIndex
import com.eignex.klause.factor.arithmetic.internals.initLinearSum
import com.eignex.klause.factor.bool.internals.linearDegree
import com.eignex.klause.factor.bool.internals.linearHolds
import com.eignex.klause.factor.bool.internals.snapLinearTarget
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.localsearch.ChannelingSink
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move.IntSet
import com.eignex.klause.localsearch.MoveSink

internal class LinearInvariant(
    private val coeffs: LongArray,
    private val vars: IntArray,
    private val op: LinearOp,
    private val bound: Long,
) : Invariant {

    // Move scoring asks for one coefficient per candidate per containing row; O(1) here keeps a
    // wide row's move pick linear instead of quadratic.
    private val coeffIndex = LinearCoeffIndex(coeffs, vars)

    // Term indices in ascending |coeff| (zeros excluded, ties by position) let channeling stop
    // at the first admissible partner whose coefficient divides the drift.
    private val byAbsCoeff: IntArray by lazy {
        val nonZero = (0 until vars.size).filter { coeffs[it] != 0L }
        nonZero.sortedBy { if (coeffs[it] < 0) -coeffs[it] else coeffs[it] }.toIntArray()
    }

    override fun initialize(state: LocalSearchState, factorId: Int) = initLinearSum(state, factorId, coeffs, vars)

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean =
        !linearHolds(state.longPayload[factorId], op, bound)

    override fun violationDegree(state: LocalSearchState, factorId: Int): Int =
        linearDegree(state.longPayload[factorId], op, bound, state.violationSoftCap)

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Long): Int {
        val coeff = coeffIndex.coeffOf(intVar)
        val old = state.assignment.intValue(intVar)
        val sum = state.longPayload[factorId]
        val newSum = sum + coeff * (newValue - old)
        return linearDegree(newSum, op, bound, state.violationSoftCap) - state.factorDegree[factorId]
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Long): Int {
        val coeff = coeffIndex.coeffOf(intVar)
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
            // sum == bound, so any single shift of a nonzero-coefficient term breaks the equality.
            forEachRepairTerm(state) { i ->
                val v = vars[i]
                val c = coeffs[i]
                if (c != 0L) {
                    val cur = state.assignment.intValue(v)
                    val d = state.rootDomains[v]
                    if (cur > d.min) sink.addChannelingIntSet(state, v, d.lower(cur))
                    if (cur < d.max) sink.addChannelingIntSet(state, v, d.higher(cur))
                }
            }
            return
        }
        var sideTargets: ArrayList<IntSet>? = null
        forEachRepairTerm(state) { i ->
            val v = vars[i]
            val c = coeffs[i]
            if (c != 0L) {
                val cur = state.assignment.intValue(v)
                val sumWithout = sum - c * cur
                val target = snapLinearTarget(op, bound, c, sumWithout, wantHolds = true)
                if (target != null) {
                    val domain = state.rootDomains[v]
                    val clamped = domain.clamp(target)
                    if (clamped != cur) sink.addChannelingIntSet(state, v, clamped)
                    if (op == LinearOp.LE || op == LinearOp.GE) {
                        val rounded = snapLinearTarget(op, bound, c, sumWithout, wantHolds = true, domain = domain)
                        if (rounded != null && rounded != clamped && rounded != cur &&
                            linearHolds(sumWithout + c * rounded, op, bound)
                        ) {
                            val targets = sideTargets ?: ArrayList<IntSet>().also { sideTargets = it }
                            targets.add(IntSet(v, rounded))
                        }
                    }
                }
            }
        }
        for (target in sideTargets.orEmpty()) sink.addChannelingIntSet(state, target.varId, target.newValue)
    }

    // A wide row is sampled afresh each pick: enumerating it floods one pick with more candidates than the
    // deadline can score, while successive samples still cover different slices of the row.
    private inline fun forEachRepairTerm(state: LocalSearchState, action: (Int) -> Unit) {
        val n = vars.size
        if (n <= REPAIR_SAMPLE_CAP) {
            for (i in 0 until n) action(i)
        } else {
            val rng = state.rng
            repeat(REPAIR_SAMPLE_CAP) { action(rng.nextInt(n)) }
        }
    }

    // Keeps a satisfied EQ balanced by counter-shifting one partner. A violated EQ is what the move is
    // repairing, so a counter-shift would undo it.
    override fun contributeChanneling(
        state: LocalSearchState,
        factorId: Int,
        intVar: Int,
        oldValue: Long,
        newValue: Long,
        sink: ChannelingSink,
    ) {
        if (op != LinearOp.EQ || state.violated.contains(factorId)) return
        val coeffV = coeffIndex.coeffOf(intVar)
        if (coeffV == 0L) return
        val drift = coeffV * (newValue - oldValue)
        for (i in byAbsCoeff) {
            val u = vars[i]
            if (u == intVar || sink.isPinned(u) || !state.moveSink.allowsInt(u)) continue
            val cu = coeffs[i]
            if (drift % cu != 0L) continue
            val uShift = -drift / cu
            val curU = state.assignment.intValue(u)
            val newU = curU + uShift
            if (newU == curU || newU !in state.rootDomains[u]) continue
            sink.add(IntSet(u, newU))
            sink.pin(u)
            return
        }
    }

    // Feasibility-preserving moves for objective descent: an EQ shifts value between two terms with
    // `coeffs(a)·Δa + coeffs(b)·Δb = 0`, LE/GE shift single terms within the slack. Pairs are sampled
    // because Θ(n²) enumeration would dominate the descent step on wide rows.
    override fun proposeStructuredMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        if (vars.size < 2) return
        when (op) {
            LinearOp.EQ -> proposeEqPairShifts(state, sink)
            LinearOp.LE, LinearOp.GE -> proposeBoundedSlackShifts(state, factorId, sink)
            LinearOp.NE -> { /* no useful structured move — NE is fragile to any change */ }
        }
    }

    private fun proposeEqPairShifts(state: LocalSearchState, sink: MoveSink) {
        val n = vars.size
        val tryPair = { a: Int, b: Int ->
            val ca = coeffs[a]
            val cb = coeffs[b]
            if (ca != 0L && cb != 0L) {
                val va = state.assignment.intValue(vars[a])
                val vb = state.assignment.intValue(vars[b])
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

    private fun proposeBoundedSlackShifts(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        val curSum = state.longPayload[factorId]
        val slack = when (op) {
            LinearOp.LE -> bound - curSum
            LinearOp.GE -> curSum - bound
            else -> 0L
        }
        if (slack <= 0L) return
        forEachRepairTerm(state) inner@{ i ->
            val c = coeffs[i]
            if (c == 0L) return@inner
            val v = vars[i]
            val cur = state.assignment.intValue(v)
            val absC = if (c < 0) -c else c
            val maxStep = slack / absC
            if (maxStep <= 0L) return@inner
            val dom = state.rootDomains[v]
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
        // Each scored move costs an apply and a revert; 32 pairs suffice for sum-of-bools equalities.
        const val PAIR_SAMPLE_CAP: Int = 32

        const val REPAIR_SAMPLE_CAP: Int = 256
    }
}
