package com.eignex.klause.factor.table

import com.eignex.klause.factor.arithmetic.internals.collectHoleAndBoundAntecedents
import com.eignex.klause.factor.table.internals.TableStr2State
import com.eignex.klause.factor.table.internals.allEventWatches
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.util.LongArrayList
import com.eignex.klause.util.LongHashSet

/** CP propagator for [Table]. Constructed by [Table.asPropagator]. */
internal class TablePropagator(
    val boolVars: IntArray,
    val intVars: IntArray,
    private val xs: IntArray,
    private val tuples: LongArray,
    private val arity: Int,
    private val numTuples: Int,
    /** Short-support mask (see [com.eignex.klause.factor.table.Table.wildcards]); null when ground. */
    private val wildcards: LongArray?,
) : Propagator {

    /** Column [col] of tuple [row] is a wildcard — always feasible, and provides support for every
     *  live value of its variable (STR2 short supports). */
    private fun isWild(row: Int, col: Int): Boolean {
        val w = wildcards ?: return false
        val idx = row * arity + col
        return (w[idx ushr 6] ushr (idx and 63)) and 1L != 0L
    }

    /** Advisor subscription (#623): STR2 is hole-aware GAC (tuple feasibility tests membership, the
     *  prune drops interior values), so subscribe to every kind on every column variable and consume
     *  the dirty-variable delta (#624) — a fire re-sweeps only when a column actually changed, instead
     *  of the per-fire O(arity) domain-ref scan. */
    override val initialIntEventWatches: IntArray = allEventWatches(xs)

    override val consumesIntEventDelta: Boolean = true

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        collectHoleAndBoundAntecedents(state, xs)

    /**
     * STR2 (Lecoutre 2011). The propagator maintains a sparse set of currently-feasible
     * tuple indices in [TableStr2State] across propagator calls; on each fire it sweeps only
     * the live prefix to drop newly-infeasible tuples and gather column supports.
     * Backtrack correctness comes from [TableStr2State.numValid] being a reversible cell on the engine's
     * undo trail: a pop restores the live-set size (hence the live set) in O(1).
     */
    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        val s = (state.refPayload[factorId] as? TableStr2State) ?: run {
            val fresh = TableStr2State(IntArray(numTuples) { it }, numTuples, state)
            state.refPayload[factorId] = fresh
            fresh
        }
        val dirty = state.drainIntEventDirtyVars(factorId)
        if (s.started && dirty.isEmpty()) return true
        // The bitset support map is indexed by (value − lo) and sized to the column span, which only
        // works when every column's domain is within Int range and its span is modest. A wider column
        // (a float-scaled table) takes the value-keyed path, which carries no span dependency.
        val bitsetEligible = (0 until arity).all { col ->
            val d = state.intDomains[xs[col]]
            d.min >= Int.MIN_VALUE.toLong() && d.max <= Int.MAX_VALUE.toLong() && d.max - d.min < MAX_BITSET_SPAN
        }
        val ok = if (bitsetEligible) propagateBitset(state, s) else propagateWide(state, s)
        if (ok) s.started = true
        return ok
    }

    /** STR2 sweep + support filtering with a per-column span-sized bitset — the fast path for columns
     *  whose domain is within Int range and narrow ([MAX_BITSET_SPAN]). */
    private fun propagateBitset(state: PropagationState, s: TableStr2State): Boolean {
        val lo = LongArray(arity)
        val hi = LongArray(arity)
        val supportBits = arrayOfNulls<LongArray>(arity)
        // A wildcard in a still-live tuple's column supports every value of that column, so the column
        // is fully supported and skips both bit-gathering and pruning (STR2 short supports).
        val fullySupported = BooleanArray(arity)
        for (col in 0 until arity) {
            val d = state.intDomains[xs[col]]
            lo[col] = d.min
            hi[col] = d.max
            val span = hi[col] - lo[col] + 1
            supportBits[col] = LongArray(((span + 63) ushr 6).toInt())
        }
        var i = 0
        while (i < s.numValid) {
            val row = s.validTuples[i]
            var feasible = true
            for (col in 0 until arity) {
                if (isWild(row, col)) continue
                val v = tuples[row * arity + col]
                if (v !in state.intDomains[xs[col]]) {
                    feasible = false
                    break
                }
            }
            if (!feasible) {
                val last = s.numValid - 1
                if (i != last) {
                    s.validTuples[i] = s.validTuples[last]
                    s.validTuples[last] = row
                }
                s.numValid = last
            } else {
                for (col in 0 until arity) {
                    if (isWild(row, col)) {
                        fullySupported[col] = true
                        continue
                    }
                    val v = tuples[row * arity + col]
                    val off = (v - lo[col]).toInt()
                    val bits = requireNotNull(supportBits[col])
                    bits[off ushr 6] = bits[off ushr 6] or (1L shl (off and 63))
                }
                i++
            }
        }
        if (s.numValid == 0) return false
        val ant = collectHoleAndBoundAntecedents(state, xs)
        for (col in 0 until arity) {
            if (fullySupported[col]) continue
            val bits = requireNotNull(supportBits[col])
            var firstSet = -1
            for (w in bits.indices) {
                if (bits[w] != 0L) {
                    firstSet = (w shl 6) + bits[w].countTrailingZeroBits()
                    break
                }
            }
            if (firstSet < 0) return false
            var lastSet = -1
            for (w in bits.indices.reversed()) {
                if (bits[w] != 0L) {
                    lastSet = (w shl 6) + (63 - bits[w].countLeadingZeroBits())
                    break
                }
            }
            val minSup = lo[col] + firstSet
            val maxSup = lo[col] + lastSet
            if (!state.tightenIntMin(xs[col], minSup, ant)) return false
            if (!state.tightenIntMax(xs[col], maxSup, ant)) return false
            val d = state.intDomains[xs[col]]
            val colLo = lo[col]
            val colHi = hi[col]
            var toRemoveCount = 0
            val toRemove = LongArray(d.size)
            d.forEach { value ->
                if (value in colLo..colHi) {
                    val off = (value - colLo).toInt()
                    if (((bits[off ushr 6] ushr (off and 63)) and 1L) == 0L) toRemove[toRemoveCount++] = value
                } else {
                    toRemove[toRemoveCount++] = value
                }
            }
            for (k in 0 until toRemoveCount) {
                if (!state.excludeIntValue(xs[col], toRemove[k], ant)) return false
            }
        }
        return true
    }

    /** STR2 sweep + support filtering with a value-keyed support set per column — the path for columns
     *  whose domain is outside Int range or too wide for a span-sized bitset. Support membership is by
     *  value, so a tuple value beyond Int range prunes soundly; the bound tightening uses the min/max
     *  supported value directly. (A column over a *contiguous* wide domain still enumerates it in the
     *  removal sweep below; the realistic wide case is a small-cardinality set domain — a bucket table.) */
    private fun propagateWide(state: PropagationState, s: TableStr2State): Boolean {
        val supported = Array(arity) { LongHashSet(numTuples) }
        val minSup = LongArray(arity) { Long.MAX_VALUE }
        val maxSup = LongArray(arity) { Long.MIN_VALUE }
        val fullySupported = BooleanArray(arity)
        var i = 0
        while (i < s.numValid) {
            val row = s.validTuples[i]
            var feasible = true
            for (col in 0 until arity) {
                if (isWild(row, col)) continue
                val v = tuples[row * arity + col]
                if (v !in state.intDomains[xs[col]]) {
                    feasible = false
                    break
                }
            }
            if (!feasible) {
                val last = s.numValid - 1
                if (i != last) {
                    s.validTuples[i] = s.validTuples[last]
                    s.validTuples[last] = row
                }
                s.numValid = last
            } else {
                for (col in 0 until arity) {
                    if (isWild(row, col)) {
                        fullySupported[col] = true
                        continue
                    }
                    val v = tuples[row * arity + col]
                    supported[col].add(v)
                    if (v < minSup[col]) minSup[col] = v
                    if (v > maxSup[col]) maxSup[col] = v
                }
                i++
            }
        }
        if (s.numValid == 0) return false
        val ant = collectHoleAndBoundAntecedents(state, xs)
        for (col in 0 until arity) {
            if (fullySupported[col]) continue
            // Every surviving tuple contributed to every column, so numValid > 0 leaves each column with
            // at least one supported value (minSup/maxSup are set).
            if (!state.tightenIntMin(xs[col], minSup[col], ant)) return false
            if (!state.tightenIntMax(xs[col], maxSup[col], ant)) return false
            val sup = supported[col]
            val toRemove = LongArrayList()
            state.intDomains[xs[col]].forEach { value ->
                if (value !in sup) toRemove.add(value)
            }
            for (k in 0 until toRemove.size) {
                if (!state.excludeIntValue(xs[col], toRemove[k], ant)) return false
            }
        }
        return true
    }

    private companion object {
        /** Columns whose domain is within Int range and narrower than this take the span-sized bitset
         *  support path; wider columns take the value-keyed set path (sound for any magnitude). */
        const val MAX_BITSET_SPAN: Long = 1L shl 24
    }
}
