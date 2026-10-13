package com.eignex.klause.factor.scheduling

import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.MoveSink

internal class DiffnRelocations(
    private val xs: IntArray,
    private val ys: IntArray,
    private val widths: LongArray,
    private val heights: LongArray,
    private val widthVars: IntArray?,
    private val heightVars: IntArray?,
    private val nonStrict: Boolean,
    private val state: LocalSearchState,
    private val sink: MoveSink,
) {
    private var emitted = 0
    private var probes = 0

    fun propose() {
        val start = state.rng.nextInt(xs.size)
        val axis = state.rng.nextBoolean()
        repeat(RECTANGLE_CAP.coerceAtMost(xs.size)) { offset ->
            if (emitted >= MOVE_CAP || probes >= PROBE_CAP) return
            val i = (start + offset) % xs.size
            val j = state.rng.nextInt(xs.size)
            proposeAxis(i, j, if (axis) xs else ys, if (axis) widths else heights,
                if (axis) widthVars else heightVars)
            proposeAxis(i, j, if (axis) ys else xs, if (axis) heights else widths,
                if (axis) heightVars else widthVars)
        }
    }

    private fun proposeAxis(i: Int, j: Int, positions: IntArray, sizes: LongArray, sizeVars: IntArray?) {
        val vi = positions[i]
        if (!sink.allowsInt(vi)) return
        val ci = state.assignment.intValue(vi)
        offer(listOf(Move.IntSet(vi, state.rootDomains[vi].min)))
        if (i == j) return
        val vj = positions[j]
        val cj = state.assignment.intValue(vj)
        val si = size(i, sizes, sizeVars, emptyList())
        val sj = size(j, sizes, sizeVars, emptyList())
        endpoint(cj, sj)?.let { offer(listOf(Move.IntSet(vi, it))) }
        before(cj, si)?.let { offer(listOf(Move.IntSet(vi, it))) }
        offer(listOf(Move.IntSet(vi, cj)))
        // Reorder unequal footprints as one transition; either independent write can overlap.
        val lower = minOf(ci, cj)
        endpoint(lower, si)?.let { offer(listOf(Move.IntSet(vi, lower), Move.IntSet(vj, it))) }
        endpoint(lower, sj)?.let { offer(listOf(Move.IntSet(vj, lower), Move.IntSet(vi, it))) }
        // Translate the pair without closing its internal gap.
        val roomI = before(ci, state.rootDomains[vi].min)
        val roomJ = before(cj, state.rootDomains[vj].min)
        if (roomI != null && roomJ != null) {
            val shift = minOf(roomI, roomJ)
            if (shift > 0L) {
                before(ci, shift)?.let { ti ->
                    before(cj, shift)?.let { tj -> offer(listOf(Move.IntSet(vi, ti), Move.IntSet(vj, tj))) }
                }
            }
        }
    }

    private fun offer(requested: List<Move.IntSet>) {
        if (emitted >= MOVE_CAP || probes >= PROBE_CAP) return
        val parts = ArrayList<Move.IntSet>(requested.size)
        for (part in requested) {
            val previous = parts.firstOrNull { it.varId == part.varId }
            if (previous != null) {
                if (previous.newValue != part.newValue) return
                continue
            }
            if (requested.any { it.varId == part.varId && it.newValue != part.newValue }) return
            if (state.assignment.intValue(part.varId) == part.newValue) continue
            if (!sink.allowsInt(part.varId) || part.newValue !in state.rootDomains[part.varId]) return
            parts.add(part)
        }
        if (parts.isEmpty()) return
        probes++
        if (!feasible(parts)) return
        sink.addCompound(parts)
        emitted++
    }

    private fun value(variable: Int, parts: List<Move.IntSet>): Long =
        parts.firstOrNull { it.varId == variable }?.newValue ?: state.assignment.intValue(variable)

    private fun size(i: Int, sizes: LongArray, variables: IntArray?, parts: List<Move.IntSet>): Long =
        if (variables == null) sizes[i] else value(variables[i], parts)

    private fun feasible(parts: List<Move.IntSet>): Boolean {
        val affected = IntArray(AFFECTED_CAP)
        var count = 0
        for (i in xs.indices) {
            if (parts.none { it.varId == xs[i] || it.varId == ys[i] ||
                    it.varId == widthVars?.get(i) || it.varId == heightVars?.get(i) }) continue
            if (count == AFFECTED_CAP) return false
            affected[count++] = i
        }
        for (index in 0 until count) {
            val i = affected[index]
            val xi = value(xs[i], parts)
            val yi = value(ys[i], parts)
            val wi = size(i, widths, widthVars, parts)
            val hi = size(i, heights, heightVars, parts)
            for (j in xs.indices) {
                if (j == i) continue
                val wj = size(j, widths, widthVars, parts)
                val hj = size(j, heights, heightVars, parts)
                if (nonStrict && (wi == 0L || hi == 0L || wj == 0L || hj == 0L)) continue
                val xj = value(xs[j], parts)
                val yj = value(ys[j], parts)
                if (endBeforeOrAt(xi, wi, xj) || endBeforeOrAt(xj, wj, xi)) continue
                if (endBeforeOrAt(yi, hi, yj) || endBeforeOrAt(yj, hj, yi)) continue
                return false
            }
        }
        return true
    }

    private fun endpoint(origin: Long, size: Long): Long? {
        val target = origin + size
        return if ((origin xor target) and (size xor target) < 0L) null else target
    }

    private fun endBeforeOrAt(origin: Long, size: Long, position: Long): Boolean {
        val end = origin + size
        return if ((origin xor end) and (size xor end) < 0L) size < 0L else end <= position
    }

    private fun before(origin: Long, size: Long): Long? {
        val target = origin - size
        return if ((origin xor size) and (origin xor target) < 0L) null else target
    }

    private companion object {
        const val RECTANGLE_CAP = 8
        const val PROBE_CAP = 24
        const val MOVE_CAP = 4
        const val AFFECTED_CAP = 8
    }
}
