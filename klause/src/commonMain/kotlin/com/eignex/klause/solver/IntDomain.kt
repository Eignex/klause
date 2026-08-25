package com.eignex.klause.solver

import com.eignex.klause.solver.intdomain.ContiguousDomain
import kotlin.random.Random

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

    /** Number of interior holes: values strictly between [min] and [max] that are absent. O(1) or
     *  O(runs) and span-independent — it never walks the gap value by value. Zero for a contiguous
     *  domain; may exceed [Int] range for a sparse domain over a wide span. The dual of the value count, for
     *  callers deciding whether iterating holes or members is cheaper. */
    val holeCount: Long

    /** True iff [value] lies in the domain. */
    operator fun contains(value: Long): Boolean

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

    /**
     * This domain's values when there are at most [maxValues] of them, else null.
     *
     * The only way to obtain values, so a caller states what it can afford before it walks anything —
     * the question the old `sizeLong <= cap` guards asked separately from the walk they guarded.
     * Returns the domain itself, so asking allocates nothing and keeps the packed representation.
     */
    fun spanOrNull(maxValues: Long = Int.MAX_VALUE.toLong()): IntSpan?

    /**
     * This domain's values, or a failure when it has too many to enumerate.
     *
     * For a caller whose constraint only makes sense over an enumerable domain — a table, a value
     * graph, an all-different — so the requirement is stated once at the top of the operation rather
     * than re-derived per access. Prefer [spanOrNull] wherever declining is a real option.
     */
    fun span(): IntSpan = spanOrNull() ?: error("domain [$min, $max] holds too many values to enumerate")

    /** True when exactly one value remains. O(1) on every representation. */
    val isFixed: Boolean get() = min == max

    /**
     * Number of present values, saturating at [Long.MAX_VALUE] when the bounds span more than a
     * `Long` can count.
     *
     * A magnitude, not an enumeration: derived from the bounds and [holeCount], so it answers for
     * every domain however wide, where [spanOrNull] declines. Callers ordering domains by size, or
     * comparing two domains' value counts, want this rather than a span they cannot obtain.
     */
    val valueCount: Long get() {
        val span = max - min
        // A negative span means the subtraction wrapped, and a full-width one has no successor:
        // either way the count is past what a Long states, so saturate rather than wrap.
        if (span < 0L || span == Long.MAX_VALUE) return Long.MAX_VALUE
        return span + 1L - holeCount
    }

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
        var hi = values.size - 1
        var ans = min
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val v = values.valueAt(mid)
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
        var hi = values.size - 1
        var ans = max
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val v = values.valueAt(mid)
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

/**
 * The values of a domain whose constraint only holds over an enumerable one — a table, a value graph,
 * an all-different. Fails loudly on a domain too wide to walk, where the old saturating `size` returned
 * a number that silently meant "very large". A caller that can decline instead should ask
 * [IntDomain.spanOrNull].
 */
val IntDomain.values: IntSpan get() = span()

/**
 * A uniformly random value of the domain, never a hole.
 *
 * Indexes the values when they can be indexed. A wider domain has no index space that reaches past
 * its first 2^31 values, so it is sampled over the bounds and snapped to the nearest present value
 * instead — approximately uniform rather than exactly so, which is what a randomized restart or an
 * initial assignment wants from a domain it cannot enumerate.
 */
fun IntDomain.randomValue(rng: Random): Long {
    val indexable = spanOrNull()
    if (indexable != null) return indexable.valueAt(rng.nextInt(indexable.size))
    val width = max - min
    val sample = if (width < 0L || width == Long.MAX_VALUE) rng.nextLong() else min + rng.nextLong(width + 1L)
    return clamp(sample)
}

/**
 * The values of an [IntDomain], in ascending order, when there are few enough of them to index.
 *
 * Separate from [IntDomain] on purpose. A domain knows its bounds and its holes however wide it is,
 * while enumeration is only meaningful when the present values can be addressed by an `Int` index —
 * so a caller that walks values must obtain this first and handle its absence, rather than checking a
 * predicate and hoping. Obtained from [IntDomain.spanOrNull]; the domain returns itself, so holding a
 * span allocates nothing and keeps the packed representation.
 */
interface IntSpan {
    /** Number of values, exact. */
    val size: Int

    /** The `i`-th value, 0-indexed ascending. */
    fun valueAt(i: Int): Long

    /** Invoke [action] for each value, ascending. */
    fun forEach(action: IntConsumer)
}
