package com.eignex.klause.factor.global

import com.eignex.klause.factor.compressViolation
import com.eignex.klause.factor.global.internals.reginTryAugment
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet

/** LS invariant logic for `inverse`. */
internal class InverseInvariant(
    private val f: IntArray,
    private val g: IntArray,
    private val fOffset: Int,
    private val gOffset: Int,
) : Invariant {

    /** True when f and g are the same variables in the same order (XCSP3 `channel` over one list):
     *  the constraint is then `f(f(x)) = x`, a self-inverse / involution, and the generic channel swap
     *  would write the shared variables twice. */
    private val selfInverse: Boolean = fOffset == gOffset && f.contentEquals(g)

    private fun fValueToGIndex(j: Long): Long = j - gOffset
    private fun gValueToFIndex(i: Long): Long = i - fOffset

    override fun initialize(state: LocalSearchState, factorId: Int) {
        state.refPayload[factorId] = InverseState(countBrokenPairs(state))
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean {
        val s = state.refPayload[factorId] as InverseState
        return s.violated > 0
    }

    override fun violationDegree(state: LocalSearchState, factorId: Int): Int =
        compressViolation((state.refPayload[factorId] as InverseState).violated.toLong(), state.violationSoftCap)

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Long): Int {
        val s = state.refPayload[factorId] as InverseState
        val current = state.assignment.intValue(intVar)
        if (current == newValue) return 0
        val after = simulateViolations(state, intVar, newValue)
        return compressViolation(after.toLong(), state.violationSoftCap) -
            compressViolation(s.violated.toLong(), state.violationSoftCap)
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Long): Int {
        val s = state.refPayload[factorId] as InverseState
        val before = s.violated
        s.violated = countBrokenPairs(state)
        return compressViolation(s.violated.toLong(), state.violationSoftCap) -
            compressViolation(before.toLong(), state.violationSoftCap)
    }

    override val providesImplicitNeighbourhood: Boolean get() = true

    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        for (i in f.indices) {
            val j = state.assignment.intValue(f[i])
            val gIdx = fValueToGIndex(j)
            if (gIdx !in g.indices) {
                val d = state.problem.intDomains[f[i]]
                val mid = ((d.min + d.max) / 2)
                if (mid in d && mid != j) sink.addChannelingIntSet(state, f[i], mid)
                return
            }
            val gVal = state.assignment.intValue(g[gIdx.toInt()])
            val fBack = (i + fOffset).toLong()
            if (gVal != fBack) {
                val gd = state.problem.intDomains[g[gIdx.toInt()]]
                if (fBack in gd && fBack != gVal) sink.addChannelingIntSet(state, g[gIdx.toInt()], fBack)
                val gFwd = gVal - fOffset
                if (gFwd in 0 until g.size) {
                    val targetFwd = gFwd + gOffset
                    val fd = state.problem.intDomains[f[i]]
                    if (targetFwd in fd && targetFwd != j) sink.addChannelingIntSet(state, f[i], targetFwd)
                }
                val fd = state.problem.intDomains[f[i]]
                for (jPrime in g.indices) {
                    if (state.assignment.intValue(g[jPrime]) == fBack) {
                        val tgt = (jPrime + gOffset).toLong()
                        if (tgt in fd && tgt != j) {
                            sink.addChannelingIntSet(state, f[i], tgt)
                            break
                        }
                    }
                }
                return
            }
        }
    }

    override fun proposeStructuredMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        if (selfInverse) {
            proposeInvolutionMoves(state, sink)
            return
        }
        val n = f.size
        if (n < 2) return
        var emitted = 0
        var attempts = 0
        while (emitted < STRUCTURED_SWAP_CAP && attempts < STRUCTURED_SWAP_CAP * SWAP_ATTEMPT_STRIDE) {
            attempts++
            val i1 = state.rng.nextInt(n)
            val i2 = state.rng.nextInt(n)
            if (i1 == i2) continue
            val a = state.assignment.intValue(f[i1])
            val b = state.assignment.intValue(f[i2])
            if (a == b) continue
            val gIdxA = fValueToGIndex(a)
            val gIdxB = fValueToGIndex(b)
            if (gIdxA !in g.indices || gIdxB !in g.indices) continue
            val gA = g[gIdxA.toInt()]
            val gB = g[gIdxB.toInt()]
            val newGA = (i2 + fOffset).toLong()
            val newGB = (i1 + fOffset).toLong()
            if (b !in state.problem.intDomains[f[i1]]) continue
            if (a !in state.problem.intDomains[f[i2]]) continue
            if (newGA !in state.problem.intDomains[gA]) continue
            if (newGB !in state.problem.intDomains[gB]) continue
            sink.addCompound(
                listOf(
                    Move.IntSet(f[i1], b),
                    Move.IntSet(f[i2], a),
                    Move.IntSet(gA, newGA),
                    Move.IntSet(gB, newGB),
                ),
            )
            emitted++
        }
    }

    /**
     * Involution-preserving structured moves for the self-inverse case. Each move recombines the
     * current pairing into another involution: transpose two fixed points, split a transposition into
     * two fixed points, or recombine two transpositions `(a,a'), (b,b') -> (a,b), (a',b')`. All keep
     * `f(f(x)) = x`, so they preserve feasibility; the identity seed (every point fixed) is already a
     * valid involution.
     */
    private fun proposeInvolutionMoves(state: LocalSearchState, sink: MoveSink) {
        val n = f.size
        if (n < 2) return
        var emitted = 0
        var attempts = 0
        while (emitted < STRUCTURED_SWAP_CAP && attempts < STRUCTURED_SWAP_CAP * SWAP_ATTEMPT_STRIDE) {
            attempts++
            val a = state.rng.nextInt(n)
            val paLong = state.assignment.intValue(f[a]) - gOffset
            val b = state.rng.nextInt(n)
            val pbLong = state.assignment.intValue(f[b]) - gOffset
            if (paLong !in 0 until n || pbLong !in 0 until n) continue
            val pa = paLong.toInt()
            val pb = pbLong.toInt()
            val ok = when {
                pa == a && pb == b && a != b -> emitInvolution(state, sink, intArrayOf(a, b), intArrayOf(b, a))

                pa != a && pb != b && b != a && b != pa && pb != a && pb != pa ->
                    emitInvolution(state, sink, intArrayOf(a, b, pa, pb), intArrayOf(b, a, pb, pa))

                pa != a -> emitInvolution(state, sink, intArrayOf(a, pa), intArrayOf(a, pa))

                else -> false
            }
            if (ok) emitted++
        }
    }

    /** Emit the compound setting `f[indices[k]] = targets[k] + gOffset`, or skip (return false) if any
     *  target is out of its variable's domain. Indices are distinct by construction. */
    private fun emitInvolution(state: LocalSearchState, sink: MoveSink, indices: IntArray, targets: IntArray): Boolean {
        val parts = ArrayList<Move>(indices.size)
        for (k in indices.indices) {
            val v = f[indices[k]]
            val value = (targets[k] + gOffset).toLong()
            if (value !in state.problem.intDomains[v]) return false
            parts.add(Move.IntSet(v, value))
        }
        sink.addCompound(parts)
        return true
    }

    override fun seedFeasible(state: LocalSearchState, factorId: Int): Boolean =
        seedIdentity(state) || seedByMatching(state)

    /** The fast path: the identity permutation `f(i) = i`, `g(i) = i`. Atomic — checks every variable
     *  before writing any, so a failure leaves the assignment untouched for the matching fallback. */
    private fun seedIdentity(state: LocalSearchState): Boolean {
        val n = f.size
        for (i in 0 until n) {
            val fv = (i + gOffset).toLong()
            val gv = (i + fOffset).toLong()
            if (fv !in state.problem.intDomains[f[i]] || gv !in state.problem.intDomains[g[i]]) return false
            if (state.assumptions.isFrozenInt(f[i]) && state.assignment.intValue(f[i]) != fv) return false
            if (state.assumptions.isFrozenInt(g[i]) && state.assignment.intValue(g[i]) != gv) return false
        }
        for (i in 0 until n) {
            if (!state.assumptions.isFrozenInt(f[i])) state.assignment.setInt(f[i], (i + gOffset).toLong())
            if (!state.assumptions.isFrozenInt(g[i])) state.assignment.setInt(g[i], (i + fOffset).toLong())
        }
        return true
    }

    /**
     * Matching-based seed for restricted domains where the identity permutation is infeasible (a
     * derangement-shaped channel, say). The bipartite graph puts f-index `i` on the left and the
     * g-index (= the value `f(i)` selects, offset-normalized) on the right, with an edge only when
     * both sides accept it: `i`'s f value lies in `dom(f[i])` and the back-reference `i` lies in
     * `dom(g[that index])`. A perfect matching is exactly a feasible inverse pair, found by reusing
     * the all-different augmenting-path matcher [reginTryAugment].
     *
     * Restricted to disjoint, equal-length, unfrozen `f`/`g`: a self-inverse (overlapping `f`/`g`)
     * needs a symmetric matching the plain matcher does not guarantee, and frozen variables keep the
     * identity-only path, so both fall through to the caller's `false`.
     */
    private fun seedByMatching(state: LocalSearchState): Boolean {
        val n = f.size
        if (g.size != n) return false
        for (i in 0 until n) {
            if (state.assumptions.isFrozenInt(f[i]) || state.assumptions.isFrozenInt(g[i])) return false
        }
        val fVars = IntHashSet(n)
        for (v in f) fVars.add(v)
        for (v in g) if (fVars.contains(v)) return false
        val valuesPerVar = Array(n) { i ->
            val allowed = IntArrayList()
            val fd = state.problem.intDomains[f[i]]
            for (vid in 0 until n) {
                if ((vid + gOffset).toLong() !in fd) continue
                if ((i + fOffset).toLong() !in state.problem.intDomains[g[vid]]) continue
                allowed.add(vid)
            }
            IntArray(allowed.size) { allowed[it] }
        }
        val matchVar = IntArray(n) { -1 }
        val matchVal = IntArray(n) { -1 }
        val visited = BooleanArray(n)
        for (i in 0 until n) {
            visited.fill(false)
            if (!reginTryAugment(i, valuesPerVar, matchVar, matchVal, visited)) return false
        }
        for (i in 0 until n) {
            val vid = matchVar[i]
            state.assignment.setInt(f[i], (vid + gOffset).toLong())
            state.assignment.setInt(g[vid], (i + fOffset).toLong())
        }
        return true
    }

    private fun countBrokenPairs(state: LocalSearchState): Int {
        var bad = 0
        for (i in f.indices) {
            val j = state.assignment.intValue(f[i])
            val gIdx = fValueToGIndex(j)
            if (gIdx !in g.indices) {
                bad++
                continue
            }
            val gVal = state.assignment.intValue(g[gIdx.toInt()])
            if (gVal != (i + fOffset).toLong()) bad++
        }
        for (i in g.indices) {
            val j = state.assignment.intValue(g[i])
            val fIdx = gValueToFIndex(j)
            if (fIdx !in f.indices) {
                bad++
                continue
            }
            val fVal = state.assignment.intValue(f[fIdx.toInt()])
            if (fVal != (i + gOffset).toLong()) bad++
        }
        return bad
    }

    private fun simulateViolations(state: LocalSearchState, intVar: Int, newValue: Long): Int {
        var bad = 0
        for (i in f.indices) {
            val j = if (f[i] == intVar) newValue else state.assignment.intValue(f[i])
            val gIdx = fValueToGIndex(j)
            if (gIdx !in g.indices) {
                bad++
                continue
            }
            val gv = g[gIdx.toInt()]
            val gVal = if (gv == intVar) newValue else state.assignment.intValue(gv)
            if (gVal != (i + fOffset).toLong()) bad++
        }
        for (i in g.indices) {
            val j = if (g[i] == intVar) newValue else state.assignment.intValue(g[i])
            val fIdx = gValueToFIndex(j)
            if (fIdx !in f.indices) {
                bad++
                continue
            }
            val fv = f[fIdx.toInt()]
            val fVal = if (fv == intVar) newValue else state.assignment.intValue(fv)
            if (fVal != (i + gOffset).toLong()) bad++
        }
        return bad
    }

    companion object {
        const val STRUCTURED_SWAP_CAP: Int = 4
        const val SWAP_ATTEMPT_STRIDE: Int = 6
    }
}

internal class InverseState(var violated: Int)
