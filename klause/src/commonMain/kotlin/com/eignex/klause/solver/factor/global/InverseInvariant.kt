package com.eignex.klause.solver.factor.global

import com.eignex.klause.solver.Invariant
import com.eignex.klause.solver.Move
import com.eignex.klause.solver.factor.compressViolation
import com.eignex.klause.solver.factor.global.internals.reginTryAugment
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet

/** LS invariant logic for `inverse`. */
internal class InverseInvariant(
    private val f: IntArray,
    private val g: IntArray,
    private val fOffset: Int,
    private val gOffset: Int,
) : Invariant {

    private fun fValueToGIndex(j: Int): Int = j - gOffset
    private fun gValueToFIndex(i: Int): Int = i - fOffset

    override fun initialize(state: LocalSearchState, factorId: Int) {
        state.refPayload[factorId] = InverseState(countBrokenPairs(state))
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean {
        val s = state.refPayload[factorId] as InverseState
        return s.violated > 0
    }

    override fun violationDegree(state: LocalSearchState, factorId: Int): Int =
        compressViolation((state.refPayload[factorId] as InverseState).violated.toLong(), state.violationSoftCap)

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int {
        val s = state.refPayload[factorId] as InverseState
        val current = state.assignment.intValue(intVar)
        if (current == newValue) return 0
        val after = simulateViolations(state, intVar, newValue)
        return compressViolation(after.toLong(), state.violationSoftCap) -
            compressViolation(s.violated.toLong(), state.violationSoftCap)
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int {
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
            val gVal = state.assignment.intValue(g[gIdx])
            if (gVal != i + fOffset) {
                val gd = state.problem.intDomains[g[gIdx]]
                val target = i + fOffset
                if (target in gd && target != gVal) sink.addChannelingIntSet(state, g[gIdx], target)
                val gFwd = gVal - fOffset
                if (gFwd in 0 until g.size) {
                    val targetFwd = gFwd + gOffset
                    val fd = state.problem.intDomains[f[i]]
                    if (targetFwd in fd && targetFwd != j) sink.addChannelingIntSet(state, f[i], targetFwd)
                }
                val fd = state.problem.intDomains[f[i]]
                for (jPrime in g.indices) {
                    if (state.assignment.intValue(g[jPrime]) == i + fOffset) {
                        val tgt = jPrime + gOffset
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
            val newGA = i2 + fOffset
            val newGB = i1 + fOffset
            if (b !in state.problem.intDomains[f[i1]]) continue
            if (a !in state.problem.intDomains[f[i2]]) continue
            if (newGA !in state.problem.intDomains[g[gIdxA]]) continue
            if (newGB !in state.problem.intDomains[g[gIdxB]]) continue
            sink.addCompound(
                listOf(
                    Move.IntSet(f[i1], b),
                    Move.IntSet(f[i2], a),
                    Move.IntSet(g[gIdxA], newGA),
                    Move.IntSet(g[gIdxB], newGB),
                ),
            )
            emitted++
        }
    }

    override fun seedFeasible(state: LocalSearchState, factorId: Int): Boolean =
        seedIdentity(state) || seedByMatching(state)

    /** The fast path: the identity permutation `f(i) = i`, `g(i) = i`. Atomic — checks every variable
     *  before writing any, so a failure leaves the assignment untouched for the matching fallback. */
    private fun seedIdentity(state: LocalSearchState): Boolean {
        val n = f.size
        for (i in 0 until n) {
            val fv = i + gOffset
            val gv = i + fOffset
            if (fv !in state.problem.intDomains[f[i]] || gv !in state.problem.intDomains[g[i]]) return false
            if (state.assumptions.isFrozenInt(f[i]) && state.assignment.intValue(f[i]) != fv) return false
            if (state.assumptions.isFrozenInt(g[i]) && state.assignment.intValue(g[i]) != gv) return false
        }
        for (i in 0 until n) {
            if (!state.assumptions.isFrozenInt(f[i])) state.assignment.setInt(f[i], i + gOffset)
            if (!state.assumptions.isFrozenInt(g[i])) state.assignment.setInt(g[i], i + fOffset)
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
                if (vid + gOffset !in fd) continue
                if (i + fOffset !in state.problem.intDomains[g[vid]]) continue
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
            state.assignment.setInt(f[i], vid + gOffset)
            state.assignment.setInt(g[vid], i + fOffset)
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
            val gVal = state.assignment.intValue(g[gIdx])
            if (gVal != i + fOffset) bad++
        }
        for (i in g.indices) {
            val j = state.assignment.intValue(g[i])
            val fIdx = gValueToFIndex(j)
            if (fIdx !in f.indices) {
                bad++
                continue
            }
            val fVal = state.assignment.intValue(f[fIdx])
            if (fVal != i + gOffset) bad++
        }
        return bad
    }

    private fun simulateViolations(state: LocalSearchState, intVar: Int, newValue: Int): Int {
        var bad = 0
        for (i in f.indices) {
            val j = if (f[i] == intVar) newValue else state.assignment.intValue(f[i])
            val gIdx = fValueToGIndex(j)
            if (gIdx !in g.indices) {
                bad++
                continue
            }
            val gVal = if (g[gIdx] == intVar) newValue else state.assignment.intValue(g[gIdx])
            if (gVal != i + fOffset) bad++
        }
        for (i in g.indices) {
            val j = if (g[i] == intVar) newValue else state.assignment.intValue(g[i])
            val fIdx = gValueToFIndex(j)
            if (fIdx !in f.indices) {
                bad++
                continue
            }
            val fVal = if (f[fIdx] == intVar) newValue else state.assignment.intValue(f[fIdx])
            if (fVal != i + gOffset) bad++
        }
        return bad
    }

    companion object {
        const val STRUCTURED_SWAP_CAP: Int = 4
        const val SWAP_ATTEMPT_STRIDE: Int = 6
    }
}

internal class InverseState(var violated: Int)
