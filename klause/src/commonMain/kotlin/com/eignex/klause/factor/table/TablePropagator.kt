package com.eignex.klause.factor.table

import com.eignex.klause.config.DEFAULT_DOMAIN_WALK_CAP
import com.eignex.klause.factor.arithmetic.internals.collectHoleAndBoundAntecedents
import com.eignex.klause.factor.table.internals.TableGroupCache
import com.eignex.klause.factor.table.internals.TableStr2State
import com.eignex.klause.factor.table.internals.allEventWatches
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.values
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
    /** Per-cell upper bound for a short-support table (see [com.eignex.klause.factor.table.Table.hi]);
     *  null when every cell is a point (a ground table). */
    private val hi: LongArray?,
    /** Shared across a `<group>`'s rows over one relation: caches the "sweep prunes
     *  nothing" verdict so later rows with the same column bounds skip re-sweeping the shared table.
     *  Null for a lone table — then every fire sweeps. */
    private val groupCache: TableGroupCache? = null,
) : Propagator {

    override val expensiveBake: Boolean get() = true

    /** Lower/upper bound the cell at (row, col) accepts; equal for a point, `[MIN, MAX]` for a `*`. */
    private fun cellLo(row: Int, col: Int): Long = tuples[row * arity + col]
    private fun cellHi(row: Int, col: Int): Long = hi?.get(row * arity + col) ?: tuples[row * arity + col]

    /** Advisor subscription: STR2 is hole-aware GAC (tuple feasibility tests membership, the
     *  prune drops interior values), so subscribe to every kind on every column variable and consume
     *  the dirty-variable delta — a fire re-sweeps only when a column actually changed, instead
     *  of the per-fire O(arity) domain-ref scan. */
    override val initialIntEventWatches: IntArray = allEventWatches(xs)

    override val consumesIntEventDelta: Boolean = true

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        collectHoleAndBoundAntecedents(state, xs)

    /**
     * Simple tabular reduction, STR2 (Lecoutre 2011). The propagator maintains a sparse set of currently-feasible
     * tuple indices in [TableStr2State] across propagator calls; on each fire it sweeps only
     * the live prefix to drop newly-infeasible tuples and gather column supports.
     * Backtrack correctness comes from [TableStr2State.numValid] being a reversible cell on the engine's
     * undo trail: a pop restores the live-set size (hence the live set) in O(1).
     *
     * Short-support cells (`[Table.hi]`) generalize a column entry from a single value to an interval
     * `[lo, hi]`: a point is `lo == hi`, a `*` wildcard is `[MIN, MAX]`. A cell is feasible when the
     * interval intersects the live domain (hole-aware), and supports every live domain value it covers.
     */
    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        val payload = state.refPayload[factorId]
        val existing = payload as? TableStr2State
        val dirty = state.drainIntEventDirtyVars(factorId)
        if ((existing?.started ?: (payload === NoopStarted)) && dirty.isEmpty()) return true
        // The bitset support map is indexed by (value − lo) and sized to the column span, which only
        // works when every column's domain is within Int range and its span is modest. A wider column
        // (a float-scaled table) takes the value-keyed path, which carries no span dependency.
        val bitsetEligible = (0 until arity).all { col ->
            val d = state.intDomains[xs[col]]
            d.min >= Int.MIN_VALUE.toLong() && d.max <= Int.MAX_VALUE.toLong() && d.max - d.min < MAX_BITSET_SPAN
        }
        // Group reuse: when every column still holds its full contiguous domain, whether the sweep prunes
        // a domain value is a pure function of (relation, column bounds). A dense relation shared across a
        // group's rows supports every value under full domains, so almost every root fire prunes nothing —
        // and re-establishing that costs a full-table scan per row. Once one row records the no-prune
        // verdict for these bounds, siblings with the same full bounds skip their own sweep: they prune
        // nothing either, and leaving their (still-full) tuple set unfiltered only defers cleanup a real
        // later fire redoes. Sound only for contiguous domains — a hole could remove a value's only support.
        val gc = groupCache
        if (gc != null && bitsetEligible) {
            var contiguous = true
            val mins = LongArray(arity)
            val maxs = LongArray(arity)
            for (col in 0 until arity) {
                val d = state.intDomains[xs[col]]
                mins[col] = d.min
                maxs[col] = d.max
                if (d.values.size.toLong() != d.max - d.min + 1) contiguous = false
            }
            if (contiguous && gc.isNoop(mins, maxs)) {
                // This row sweeps nothing, so it has nothing to filter and needs no live set. A group's
                // rows share one relation but each would otherwise allocate its own O(numTuples) tuple
                // index — the dominant term in the build's peak memory on the largest table instances.
                if (existing != null) existing.started = true else state.refPayload[factorId] = NoopStarted
                return true
            }
            val s = existing ?: newLiveSet(state, factorId)
            val ok = propagateBitset(state, s)
            if (ok) {
                s.started = true
                // Record the verdict once: under full contiguous bounds the sweep pruned no domain value
                // iff every column still spans its bounds (any tuple removal doesn't change a domain).
                if (contiguous && gc.noopMins == null) {
                    var noPrune = true
                    for (col in 0 until arity) {
                        if (state.intDomains[xs[col]].values.size.toLong() != maxs[col] - mins[col] + 1) {
                            noPrune = false
                            break
                        }
                    }
                    if (noPrune) gc.setNoop(mins, maxs)
                }
            }
            return ok
        }
        val s = existing ?: newLiveSet(state, factorId)
        val ok = if (bitsetEligible) propagateBitset(state, s) else propagateWide(state, s)
        if (ok) s.started = true
        return ok
    }

    /** The STR2 live set for this factor, installed on first genuine use. Every tuple starts live, which
     *  is exactly the state a sweep would have left: a row reaching here has filtered nothing yet. */
    private fun newLiveSet(state: PropagationState, factorId: Int): TableStr2State {
        val fresh = TableStr2State(IntArray(numTuples) { it }, numTuples, state)
        state.refPayload[factorId] = fresh
        return fresh
    }

    /** Whether the cell at (row, col) — the interval `[cellLo, cellHi]` — has support in domain [d]. */
    private fun cellFeasible(row: Int, col: Int, d: IntDomain): Boolean {
        val lo = cellLo(row, col)
        val hiC = cellHi(row, col)
        return if (lo == hiC) lo in d else domainOverlapsRange(d, lo, hiC)
    }

    /** STR2 sweep + support filtering with a per-column span-sized bitset — the fast path for columns
     *  whose domain is within Int range and narrow ([MAX_BITSET_SPAN]). */
    private fun propagateBitset(state: PropagationState, s: TableStr2State): Boolean {
        val domLo = LongArray(arity)
        val domHi = LongArray(arity)
        val supportBits = arrayOfNulls<LongArray>(arity)
        // A cell whose interval covers the whole domain (a `*`, or a range spanning it) supports every
        // value of that column, so the column is fully supported and skips gathering and pruning.
        val fullySupported = BooleanArray(arity)
        for (col in 0 until arity) {
            val d = state.intDomains[xs[col]]
            domLo[col] = d.min
            domHi[col] = d.max
            val span = domHi[col] - domLo[col] + 1
            supportBits[col] = LongArray(((span + 63) ushr 6).toInt())
        }
        var i = 0
        while (i < s.numValid) {
            val row = s.validTuples[i]
            var feasible = true
            for (col in 0 until arity) {
                if (!cellFeasible(row, col, state.intDomains[xs[col]])) {
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
                    val lo = cellLo(row, col)
                    val hiC = cellHi(row, col)
                    if (lo <= domLo[col] && hiC >= domHi[col]) {
                        fullySupported[col] = true
                        continue
                    }
                    // Every domain value the interval covers is supported; setting bits over the
                    // (in-range) offsets is safe — the prune only ever consults in-domain positions.
                    val bits = requireNotNull(supportBits[col])
                    var off = (maxOf(lo, domLo[col]) - domLo[col]).toInt()
                    val offEnd = (minOf(hiC, domHi[col]) - domLo[col]).toInt()
                    while (off <= offEnd) {
                        bits[off ushr 6] = bits[off ushr 6] or (1L shl (off and 63))
                        off++
                    }
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
            val minSup = domLo[col] + firstSet
            val maxSup = domLo[col] + lastSet
            if (!state.tightenIntMin(xs[col], minSup, ant)) return false
            if (!state.tightenIntMax(xs[col], maxSup, ant)) return false
            val d = state.intDomains[xs[col]]
            val colLo = domLo[col]
            val colHi = domHi[col]
            var toRemoveCount = 0
            val toRemove = LongArray(d.values.size)
            d.values.forEach { value ->
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
                if (!cellFeasible(row, col, state.intDomains[xs[col]])) {
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
                    val d = state.intDomains[xs[col]]
                    val lo = cellLo(row, col)
                    val hiC = cellHi(row, col)
                    if (lo <= d.min && hiC >= d.max) {
                        fullySupported[col] = true
                        continue
                    }
                    var v = maxOf(lo, d.min)
                    val vEnd = minOf(hiC, d.max)
                    while (v <= vEnd) {
                        supported[col].add(v)
                        if (v < minSup[col]) minSup[col] = v
                        if (v > maxSup[col]) maxSup[col] = v
                        v++
                    }
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
            // A column too large to walk keeps its bounds tightening above; skip the per-value support
            // removal rather than walking the span. Sound: such a domain is never a full assignment, and
            // the removal runs once the column narrows below the cap (every leaf is singleton domains).
            if (state.intDomains[xs[col]].spanOrNull(DEFAULT_DOMAIN_WALK_CAP) != null) {
                val toRemove = LongArrayList()
                state.intDomains[xs[col]].values.forEach { value ->
                    if (value !in sup) toRemove.add(value)
                }
                for (k in 0 until toRemove.size) {
                    if (!state.excludeIntValue(xs[col], toRemove[k], ant)) return false
                }
            }
        }
        return true
    }

    private companion object {
        /** Columns whose domain is within Int range and narrower than this take the span-sized bitset
         *  support path; wider columns take the value-keyed set path (sound for any magnitude). */
        const val MAX_BITSET_SPAN: Long = 1L shl 24

        /** Whether domain [d] holds a value in `[lo, hi]` (hole-aware): the clamped range is non-empty
         *  and, when the domain has holes, not entirely holes. */
        private fun domainOverlapsRange(d: IntDomain, lo: Long, hi: Long): Boolean {
            val a = maxOf(lo, d.min)
            val b = minOf(hi, d.max)
            if (a > b) return false
            if (d.holeCount == 0L) return true
            var holes = 0L
            d.forEachHoleInRange(a, b) { holes++ }
            return b - a + 1 > holes
        }
    }
}

/** Payload for a table row that has fired but only ever hit the shared group no-op verdict, so its STR2
 *  live set was never needed. Carries "started" alone; the first fire that must actually sweep replaces
 *  it with a real [TableStr2State]. */
private object NoopStarted
