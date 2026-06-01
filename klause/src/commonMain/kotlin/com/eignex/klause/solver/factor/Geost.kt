package com.eignex.klause.solver.factor

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.localsearch.LocalSearchFactor
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.solver.propagation.PropagationState
import com.eignex.klause.util.IntArrayList
import kotlin.math.max
import kotlin.math.min

/**
 * `geost` — N-dimensional non-overlapping placement of axis-aligned boxes. [origin] is a
 * row-major `[numObjects × numDims]` integer-variable array; [length] is the matching
 * constant size table.
 *
 * **LS cost is graded by total overlap volume** — `raw = Σ_{i<j} ∏_d overlapLen(i, j, d)`,
 * run through [compressViolation]. A pair contributes 0 exactly when disjoint on some axis,
 * and a positive volume that shrinks as the boxes are pulled apart, so CBLS sees a real
 * gradient toward separation (richer than [Diffn]'s binary overlap-pair count). Repair moves
 * shift an overlapping object out of the overlap along each axis (the k-dim generalization of
 * the [Diffn] repair set).
 *
 * Propagation runs a full sweep-line per (target object i, target dimension d). For each
 * other object j we test whether, on EVERY dimension d' ≠ d, the pair (i, j) must overlap
 * regardless of their assigned origins in d' — that is, origin_i.d' is currently confined
 * to the *mandatory-overlap interval* `[j.max + 1 − s_i.d', j.min + s_j.d' − 1]`. If yes
 * for all d' ≠ d, then placing origin_i.d in `[j.max + 1 − s_i.d, j.min + s_j.d − 1]` would
 * force an overlap on d too — a global conflict. We collect those forbidden intervals,
 * union them, and tighten origin_i.d to avoid the union (advance min past leading intervals,
 * retract max past trailing intervals; report failure if min > max).
 *
 * The earlier "forced single-dim" pairwise scan is subsumed by the case where all but one
 * dim's M-intervals are already exhausted.
 */
class Geost(val numDims: Int, val numObjects: Int, val origin: IntArray, val length: IntArray) : LocalSearchFactor {

    init {
        require(numDims >= 1) { "Geost: numDims must be ≥ 1" }
        require(origin.size == numObjects * numDims) { "Geost: origin shape mismatch" }
        require(length.size == numObjects * numDims) { "Geost: length shape mismatch" }
    }

    override val boolVars: IntArray = EmptyIntArray
    override val intVars: IntArray = origin

    /** Total pairwise overlap volume under the current assignment. */
    private class State(var rawV: Long)

    /** Origin of object [i] on dim [d], applying an optional (`ov → nv`) override. */
    private fun start(state: LocalSearchState, i: Int, d: Int, ov: Int, nv: Int): Int {
        val v = origin[i * numDims + d]
        return if (v == ov) nv else state.assignment.intValue(v)
    }

    /** Overlap length of the box pair (i, j) on dim [d] under the optional override. */
    private fun overlapLen(state: LocalSearchState, i: Int, j: Int, d: Int, ov: Int, nv: Int): Int {
        val si = start(state, i, d, ov, nv)
        val li = length[i * numDims + d]
        val sj = start(state, j, d, ov, nv)
        val lj = length[j * numDims + d]
        return max(0, min(si + li, sj + lj) - max(si, sj))
    }

    /** Overlap *volume* of the box pair (i, j): product of per-dim overlap lengths (0 if
     *  disjoint on any axis). */
    private fun overlapVolume(state: LocalSearchState, i: Int, j: Int, ov: Int, nv: Int): Long {
        var vol = 1L
        for (d in 0 until numDims) {
            val o = overlapLen(state, i, j, d, ov, nv)
            if (o == 0) return 0L
            vol *= o.toLong()
        }
        return vol
    }

    /** Total overlap volume over all object pairs, with an optional single-var override. */
    private fun totalVolume(state: LocalSearchState, ov: Int, nv: Int): Long {
        var sum = 0L
        for (i in 0 until numObjects) {
            for (j in i + 1 until numObjects) {
                sum += overlapVolume(state, i, j, ov, nv)
            }
        }
        return sum
    }

    override fun initialize(state: LocalSearchState, factorId: Int) {
        state.refPayload[factorId] = State(totalVolume(state, ov = -1, nv = 0))
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean =
        (state.refPayload[factorId] as State).rawV > 0L

    override fun violationDegree(state: LocalSearchState, factorId: Int): Int =
        compressViolation((state.refPayload[factorId] as State).rawV)

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int {
        val s = state.refPayload[factorId] as State
        return compressViolation(totalVolume(state, intVar, newValue)) - compressViolation(s.rawV)
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int {
        val s = state.refPayload[factorId] as State
        if (state.assignment.intValue(intVar) == oldValue) return 0
        val before = compressViolation(s.rawV)
        s.rawV = totalVolume(state, ov = -1, nv = 0)
        return compressViolation(s.rawV) - before
    }

    /** Repair: for each overlapping pair, propose shifting either object out of the overlap
     *  along each axis (move object i just past object j's near or far face on that dim, and
     *  vice versa). Separating on any single axis removes the overlap — the k-dim form of the
     *  [Diffn] single-axis escape set. */
    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        if (!isViolated(state, factorId)) return
        for (i in 0 until numObjects) {
            for (j in i + 1 until numObjects) {
                if (overlapVolume(state, i, j, ov = -1, nv = 0) == 0L) continue
                for (d in 0 until numDims) {
                    val oiVar = origin[i * numDims + d]
                    val li = length[i * numDims + d]
                    val ojVar = origin[j * numDims + d]
                    val lj = length[j * numDims + d]
                    val si = state.assignment.intValue(oiVar)
                    val sj = state.assignment.intValue(ojVar)
                    val di = state.problem.intDomains[oiVar]
                    val dj = state.problem.intDomains[ojVar]
                    // Move i to just-left (before j) or just-right (after j) on dim d.
                    val iLeft = sj - li
                    val iRight = sj + lj
                    if (iLeft in di && iLeft != si) sink.addChannelingIntSet(state, oiVar, iLeft)
                    if (iRight in di && iRight != si) sink.addChannelingIntSet(state, oiVar, iRight)
                    // Symmetric for j.
                    val jLeft = si - lj
                    val jRight = si + li
                    if (jLeft in dj && jLeft != sj) sink.addChannelingIntSet(state, ojVar, jLeft)
                    if (jRight in dj && jRight != sj) sink.addChannelingIntSet(state, ojVar, jRight)
                }
            }
        }
    }

    /** Origin vars responsible for the conflict that made the last [propagate] return false —
     *  only the involved object pair(s), not all objects. Lets CDCL learn a tight nogood
     *  (an all-vars reason produces useless long clauses → the solver thrashes at shallow
     *  depth on multi-object instances). Falls back to all vars if unset. */
    private var conflictVars: IntArray? = null

    /** Append object [i]'s origin vars (all dims) to [acc]. */
    private fun objectVarsInto(i: Int, acc: IntArrayList) {
        for (d in 0 until numDims) acc.add(origin[i * numDims + d])
    }

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        state.composeIntVarAtomAntecedents(conflictVars ?: intVars)

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        conflictVars = null
        // First sweep: detect "must overlap in every dim" → infeasibility. The conflict is
        // caused solely by the two objects' origin bounds — cite just those.
        for (i in 0 until numObjects) {
            for (j in i + 1 until numObjects) {
                if (mustOverlapEveryDim(state, i, j, exceptDim = -1)) {
                    val acc = IntArrayList()
                    objectVarsInto(i, acc)
                    objectVarsInto(j, acc)
                    conflictVars = acc.toIntArray()
                    return false
                }
            }
        }
        // Per (i, d) sweep.
        val contributors = IntArrayList()
        for (i in 0 until numObjects) {
            for (d in 0 until numDims) {
                val oi = origin[i * numDims + d]
                val si = length[i * numDims + d]
                // Collect forbidden intervals for origin_i.d and the objects that produced them.
                contributors.clear()
                val intervals = collectForbidden(state, i, d, si, contributors)
                if (intervals.isEmpty()) continue
                // The deduction depends on object i's bounds plus each contributing object j's
                // bounds — cite exactly those, not the whole problem.
                val antVars = IntArrayList()
                objectVarsInto(i, antVars)
                for (c in 0 until contributors.size) objectVarsInto(contributors[c], antVars)
                val ant = state.composeIntVarAtomAntecedents(antVars.toIntArray())
                // Sweep min upward.
                var lo = state.intDomains[oi].min
                var hi = state.intDomains[oi].max
                var changed = true
                while (changed) {
                    changed = false
                    for (k in intervals.indices step 2) {
                        val fLo = intervals[k]
                        val fHi = intervals[k + 1]
                        if (lo in fLo..fHi) {
                            lo = fHi + 1
                            changed = true
                        }
                        if (hi in fLo..fHi) {
                            hi = fLo - 1
                            changed = true
                        }
                    }
                }
                if (lo > hi) {
                    conflictVars = antVars.toIntArray()
                    return false
                }
                if (!state.tightenIntMin(oi, lo, ant)) return false
                if (!state.tightenIntMax(oi, hi, ant)) return false
            }
        }
        return true
    }

    /**
     * Return the union of forbidden intervals for origin_i.d, as a flat list of
     * [lo0, hi0, lo1, hi1, …] (inclusive bounds). An interval comes from some j ≠ i
     * for which on every dim d' ≠ d the pair already must overlap. Each contributing object
     * `j` is appended to [contributors] so the caller can build a sharp conflict antecedent.
     */
    private fun collectForbidden(
        state: PropagationState,
        i: Int,
        d: Int,
        si: Int,
        contributors: IntArrayList,
    ): IntArray {
        val acc = IntArray(numObjects * 2)
        var n = 0
        for (j in 0 until numObjects) {
            if (j == i) continue
            if (!mustOverlapAllOtherDims(state, i, j, d)) continue
            val oj = origin[j * numDims + d]
            val sj = length[j * numDims + d]
            val dj = state.intDomains[oj]
            val flo = dj.max + 1 - si
            val fhi = dj.min + sj - 1
            if (flo <= fhi) {
                acc[n] = flo
                acc[n + 1] = fhi
                n += 2
                contributors.add(j)
            }
        }
        return acc.copyOf(n)
    }

    /** True iff for every dim d' ≠ [exceptDim] (or every dim if -1), the pair (i, j) must
     *  overlap regardless of origin_i.d' and origin_j.d' values within current bounds. */
    private fun mustOverlapAllOtherDims(state: PropagationState, i: Int, j: Int, exceptDim: Int): Boolean {
        for (dp in 0 until numDims) {
            if (dp == exceptDim) continue
            if (!mustOverlapDim(state, i, j, dp)) return false
        }
        return true
    }

    private fun mustOverlapEveryDim(state: PropagationState, i: Int, j: Int, exceptDim: Int): Boolean =
        mustOverlapAllOtherDims(state, i, j, exceptDim)

    /** True iff origin_i.d ⊆ [origin_j.d.max + 1 − s_i.d, origin_j.d.min + s_j.d − 1]. */
    private fun mustOverlapDim(state: PropagationState, i: Int, j: Int, d: Int): Boolean {
        val oi = origin[i * numDims + d]
        val oj = origin[j * numDims + d]
        val si = length[i * numDims + d]
        val sj = length[j * numDims + d]
        val di = state.intDomains[oi]
        val dj = state.intDomains[oj]
        val mLo = dj.max + 1 - si
        val mHi = dj.min + sj - 1
        return mLo <= mHi && di.min >= mLo && di.max <= mHi
    }
}
