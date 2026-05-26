package com.eignex.klause.solver.localsearch

import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Move

/**
 * Mutable accumulator that factors push repair-move suggestions into. Optionally consults
 * an [Assumptions] set so a frozen variable never enters the candidate list. LS-only — the
 * propagation contract doesn't use it.
 *
 * Backs `BoolFlip` / `IntSet` additions with a single resizable [LongArray] lane (one Long
 * per move: bit 63 = kind, bits 0..30 = varId, bits 31..62 = value), so a fill-clear cycle
 * — the dominant pattern during local search — touches no per-move objects. Compound moves
 * are boxed in a small side list (only the AllDifferent and Circuit factors use them, and
 * neither mixes compounds with primitives within a single propose call).
 *
 * [list] materializes [Move] objects lazily on first read and caches them until the next
 * mutation. Callers that never read [list] on an empty sink pay zero per-add allocation.
 */
class MoveSink(private var assumptions: Assumptions = Assumptions.None) {
    private var lane: LongArray = LongArray(INITIAL_CAPACITY)
    private var laneSize: Int = 0
    private var compounds: ArrayList<Move.Compound>? = null
    private var cachedList: List<Move>? = null

    /** Materialized [Move] view. Stable across reads until the sink is mutated.
     *  Compound moves follow primitives in the iteration order. */
    val list: List<Move> get() = cachedList ?: materialize().also { cachedList = it }

    /** Replace the [Assumptions] this sink filters against. Called by [LocalSearchState] on
     *  init / restart so per-call assumptions take effect. */
    fun setAssumptions(a: Assumptions) { assumptions = a }

    fun addBoolFlip(varId: Int) {
        if (assumptions.isFrozenBool(varId)) return
        ensureCapacity()
        lane[laneSize++] = encodeBoolFlip(varId)
        cachedList = null
    }

    fun addIntSet(varId: Int, newValue: Int) {
        if (assumptions.isFrozenInt(varId)) return
        ensureCapacity()
        lane[laneSize++] = encodeIntSet(varId, newValue)
        cachedList = null
    }

    /** Add a multi-variable atomic transition. Skips the move entirely if any part
     *  would touch a frozen variable — a Compound is all-or-nothing. */
    fun addCompound(parts: List<Move>) {
        for (p in parts) when (p) {
            is Move.BoolFlip -> if (assumptions.isFrozenBool(p.varId)) return
            is Move.IntSet -> if (assumptions.isFrozenInt(p.varId)) return
            is Move.Compound -> error("Compound parts must be primitive (BoolFlip/IntSet)")
        }
        val side = compounds ?: ArrayList<Move.Compound>(2).also { compounds = it }
        side.add(Move.Compound(parts))
        cachedList = null
    }

    fun clear() {
        laneSize = 0
        compounds = null
        cachedList = null
    }

    private fun ensureCapacity() {
        if (laneSize == lane.size) lane = lane.copyOf(lane.size * 2)
    }

    private fun materialize(): List<Move> {
        val compoundsRef = compounds
        val total = laneSize + (compoundsRef?.size ?: 0)
        if (total == 0) return emptyList()
        val out = ArrayList<Move>(total)
        for (i in 0 until laneSize) out.add(decode(lane[i]))
        if (compoundsRef != null) out.addAll(compoundsRef)
        return out
    }

    companion object {
        private const val INITIAL_CAPACITY: Int = 16
        private const val KIND_BIT: Long = 1L shl 63
        private const val VAR_MASK: Long = (1L shl 31) - 1L

        internal fun encodeBoolFlip(varId: Int): Long = varId.toLong() and VAR_MASK

        /** Layout: bit 63 = 1, bits 31..62 = value (32 bits), bits 0..30 = varId (31 bits). */
        internal fun encodeIntSet(varId: Int, newValue: Int): Long =
            KIND_BIT or
                (varId.toLong() and VAR_MASK) or
                ((newValue.toLong() and 0xFFFF_FFFFL) shl 31)

        internal fun decode(packed: Long): Move =
            if (packed and KIND_BIT == 0L) {
                Move.BoolFlip((packed and VAR_MASK).toInt())
            } else {
                val varId = (packed and VAR_MASK).toInt()
                val value = (packed ushr 31).toInt()
                Move.IntSet(varId, value)
            }
    }
}
