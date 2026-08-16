package com.eignex.klause.solver

import com.eignex.klause.solver.intdomain.ContiguousDomain

/** Primitive-`Int` visitor for the [IntDomain] iteration methods. A `fun interface` (not
 *  `(Int) -> Unit`) so `action(v)` passes a primitive `int` with no `Integer` boxing on the
 *  per-value propagation hot paths; lambdas still convert at the call site via SAM conversion, so
 *  `d.forEach { … }` is unchanged. */
fun interface IntConsumer {
    /** Receive one domain [value] from an [IntDomain] iteration. */
    fun accept(value: Long)
}

/**
 * Integer-variable domain: conceptually a finite set of integers. Physically it is one of a small,
 * fixed family of concrete representations (in `com.eignex.klause.solver.intdomain`), each chosen at
 * construction so storage and the hot membership / iteration paths stay compact regardless of how
 * wide the declared span is (spans here reach into the tens of millions):
 *
 *  - [com.eignex.klause.solver.intdomain.ContiguousDomain] — `(min..max)` with no holes; everything
 *    is O(1).
 *  - [com.eignex.klause.solver.intdomain.BitsetDomain] — one bit per value over a narrow span
 *    (`<=` [com.eignex.klause.config.KlauseConfig.bitsetThreshold]); membership is an O(1) bit test
 *    at any density.
 *  - [com.eignex.klause.solver.intdomain.RunsDomain] — a sorted list of disjoint present runs;
 *    membership O(log runs), storage O(runs). The wide-span rep for relatively few runs (few holes,
 *    or clustered survivors).
 *  - [com.eignex.klause.solver.intdomain.SurvivorsDomain] — the sorted present values; membership
 *    O(log survivors), storage O(survivors). The escape for scattered survivor sets and the
 *    alternating "comb", where a run list would cost ~2 ints per survivor.
 *
 * The run and survivor reps together keep both storage and the hot paths **independent of the
 * declared span**: a "sorted hole list" (complement) rep degenerates to O(span) storage and
 * cache-thrashing binary searches once a wide domain is carved down to a small reachable set —
 * a pathological throughput cliff. Between them every wide shape stays O(min(holes, survivors)).
 *
 * **Representation choice** — construct via the factories (`IntDomain(min, max)` for the contiguous
 * case; the internal `intDomainFrom*` factories pick the wide rep): a single run ⇒ contiguous; span
 * `<=` [com.eignex.klause.config.KlauseConfig.bitsetThreshold] ⇒ bitset; otherwise the run list when
 * it is at least as compact as the survivor list (`2·runs <= survivors`), else the survivor list.
 * Domains are immutable, so every mutation returns a fresh value and re-picks — no flip-flop cost.
 *
 * Callers depend only on this interface and `IntDomain(min, max)`; the concrete reps are an
 * implementation detail.
 */
interface IntDomain {
    /** Inclusive lower bound; always an in-domain value. May exceed 32-bit range. */
    val min: Long

    /** Inclusive upper bound; always an in-domain value. May exceed 32-bit range. */
    val max: Long

    /** Number of values in the domain (O(1)). An index/count kept 32-bit: a *materialisable* domain
     *  never holds more than [Int.MAX_VALUE] present values, even when its value span is far wider
     *  (a wide [ContiguousDomain] reports [Int.MAX_VALUE] — its values are never enumerated).
     *  Exact only when [enumerable]; a saturated count carries no information beyond "very large". */
    val size: Int

    /** True when [size] is the exact present-value count, so positional access ([valueAt] over
     *  `0 until size`) covers the whole domain. False for the wide reps whose count exceeds (and
     *  [size] saturates at) [Int.MAX_VALUE] — a contiguous or run domain spanning beyond 32-bit.
     *  Such a domain must be processed through its bounds and [forEachHole], never by value
     *  enumeration or positional indexing. */
    val enumerable: Boolean get() = true

    /** Exact present-value count as a Long, saturating at [Long.MAX_VALUE] only when the count
     *  exceeds Long range (a domain spanning more than 2^63 values). Unlike [size] it stays exact
     *  on wide non-[enumerable] domains, so span-sensitive heuristics can still discriminate
     *  between them. */
    val sizeLong: Long get() = size.toLong()

    /** Number of interior holes: values strictly between [min] and [max] that are absent. O(1) or
     *  O(runs) and span-independent — it never walks the gap value by value. Zero for a contiguous
     *  domain; may exceed [Int] range for a sparse domain over a wide span. The dual of [size], for
     *  callers deciding whether iterating holes or members is cheaper. */
    val holeCount: Long

    /** True iff [value] lies in the domain. */
    operator fun contains(value: Long): Boolean

    /** The `i`-th value present in the domain (0-indexed, ascending). */
    fun valueAt(i: Int): Long

    /** Return a new domain with [value] excluded, or `this` if [value] is absent (idempotent).
     *  Throws if removing [value] would empty the domain. */
    fun excludeValue(value: Long): IntDomain

    /**
     * Return a domain with every value in [values] excluded, `this` if none are present
     * (idempotent), or `null` when excluding them all would empty the domain. [values] must be
     * sorted ascending and distinct; entries outside `min..max` or already absent are ignored.
     * Merges in a single pass keeping survivors — O(present + values), span-independent for the
     * wide reps. Only the value *set* is contractual; the resulting rep is an implementation detail.
     */
    fun excludeValues(values: LongArray): IntDomain?

    /** Copy of the domain with its min raised to at least [newMin]. `this` when already covered;
     *  throws on empty. */
    fun withMinAtLeast(newMin: Long): IntDomain

    /** Copy of the domain with its max tightened to at most [newMax]. `this` when already covered;
     *  throws on empty. */
    fun withMaxAtMost(newMax: Long): IntDomain

    /**
     * Inverse of an interior [excludeValue]: put [value] (strictly inside `min..max` and currently
     * absent) back into the domain. Exists for the undo journal — an interior carve is journaled as
     * the carved value alone instead of a full prior-domain snapshot.
     */
    fun includeInteriorValue(value: Long): IntDomain

    /** Invoke [action] for each value present in the domain, ascending. */
    fun forEach(action: IntConsumer)

    /** Invoke [action] for each value excluded strictly between [min] and [max], ascending.
     *  No-op for a contiguous domain. */
    fun forEachHole(action: IntConsumer)

    /** Invoke [action] for each value in `[lo, hi]` carved *out* of the domain (an interior hole),
     *  ascending, clamped to `[min, max]`. Span-independent for the wide reps — it walks the
     *  runs / survivors crossed, not every integer in the range. */
    fun forEachHoleInRange(lo: Long, hi: Long, action: IntConsumer)

    /** Nearest in-domain value to [value]: clamps to the bounds, then snaps an interior hole to the
     *  closest present value (ties toward the smaller). Identity on the contiguous fast path. */
    fun clamp(value: Long): Long = when {
        value <= min -> min

        value >= max -> max

        value in this -> value

        else -> {
            val lo = lower(value)
            val hi = higher(value)
            if (value - lo <= hi - value) lo else hi
        }
    }

    /** Largest in-domain value strictly less than [value]; requires `value > `[min] so one exists.
     *  Skips interior holes — the contiguous fast path returns `value - 1`. */
    fun lower(value: Long): Long {
        val cand = value - 1
        if (cand in this) return cand
        var lo = 0
        var hi = size - 1
        var ans = min
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val v = valueAt(mid)
            if (v < value) {
                ans = v
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }

    /** Smallest in-domain value strictly greater than [value]; requires `value < `[max] so one exists.
     *  Skips interior holes — the contiguous fast path returns `value + 1`. */
    fun higher(value: Long): Long {
        val cand = value + 1
        if (cand in this) return cand
        var lo = 0
        var hi = size - 1
        var ans = max
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val v = valueAt(mid)
            if (v > value) {
                ans = v
                hi = mid - 1
            } else {
                lo = mid + 1
            }
        }
        return ans
    }

    /** Factory for [IntDomain]; the bitset/wide-rep cutoff is
     *  [com.eignex.klause.config.KlauseConfig.bitsetThreshold]. */
    companion object {
        /** Construct the contiguous domain `(min..max)`. */
        operator fun invoke(min: Long, max: Long): IntDomain = ContiguousDomain(min, max)
    }
}
