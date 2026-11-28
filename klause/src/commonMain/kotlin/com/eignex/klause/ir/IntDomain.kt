package com.eignex.klause.ir

import com.eignex.klause.ir.intdomain.AbstractIntDomain
import com.eignex.klause.ir.intdomain.ContiguousDomain
import kotlin.random.Random

/** Primitive-`Long` visitor for the `IntDomain` iteration methods; a `fun interface` rather than
 *  `(Long) -> Unit` so values are not boxed on propagation hot paths. */
fun interface IntConsumer {
    /** Receive one domain [value] from an `IntDomain` iteration. */
    fun accept(value: Long)
}

/**
 * Integer-variable domain: a finite set of integers, stored as one of a fixed family of
 * representations chosen at construction so storage and the hot paths stay compact however wide the
 * declared span is:
 *
 *  - [com.eignex.klause.ir.intdomain.ContiguousDomain] — `(min..max)` with no holes; everything
 *    is O(1).
 *  - [com.eignex.klause.ir.intdomain.BitsetDomain] — one bit per value over a narrow span
 *    (`<=` [DomainStorageSettings.bitsetThreshold]); membership is an O(1) bit test
 *    at any density.
 *  - [com.eignex.klause.ir.intdomain.RunsDomain] — a sorted list of disjoint present runs;
 *    membership O(log runs), storage O(runs).
 *  - [com.eignex.klause.ir.intdomain.SurvivorsDomain] — the sorted present values; membership
 *    O(log survivors), storage O(survivors), for scattered survivors where a run list would cost
 *    ~2 ints per survivor.
 *
 * Between them every wide shape stays O(min(holes, survivors)), independent of the declared span.
 *
 * A single run is contiguous; span `<=` [DomainStorageSettings.bitsetThreshold] is a bitset; otherwise
 * the run list when `2·runs <= survivors`, a bitset when `2·words <= survivors`, else the survivor list.
 * Domains are immutable, so every mutation returns a fresh value and re-picks the representation.
 */
interface IntDomain {
    /** Inclusive lower bound; always an in-domain value. May exceed 32-bit range. */
    val min: Long

    /** Inclusive upper bound; always an in-domain value. May exceed 32-bit range. */
    val max: Long

    /** Number of absent values strictly between [min] and [max], saturating at [Long.MAX_VALUE].
     *  O(1) or O(runs); it never walks the gap value by value. */
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
     * O(present + values), span-independent for the wide reps.
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
     * absent) back into the domain, so the undo journal records an interior carve as the value alone.
     */
    fun includeInteriorValue(value: Long): IntDomain

    /**
     * This domain's values when there are at most [maxValues] of them, else null.
     *
     * The only way to obtain values, so a caller states what it can afford before it walks anything.
     * Returns the domain itself, so asking allocates nothing and keeps the packed representation.
     */
    fun spanOrNull(maxValues: Long = Int.MAX_VALUE.toLong()): IntSpan?

    /**
     * This domain's values, or a failure when it has too many to enumerate. Prefer [spanOrNull]
     * wherever declining is a real option.
     */
    fun span(): IntSpan = spanOrNull() ?: error("domain [$min, $max] holds too many values to enumerate")

    /** True when exactly one value remains. O(1) on every representation. */
    val isFixed: Boolean get() = min == max

    /**
     * Number of present values, saturating at [Long.MAX_VALUE] when a `Long` cannot count them. Answers
     * also where [spanOrNull] declines.
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

    /** Invoke [action] for each interior hole in `[lo, hi]`, ascending, clamped to `[min, max]`.
     *  Walks only the runs or survivors crossed, not every integer in the range. */
    fun forEachHoleInRange(lo: Long, hi: Long, action: IntConsumer)

    /** Nearest in-domain value to [value]: clamps to the bounds, then snaps an interior hole to the
     *  closest present value (ties toward the smaller). */
    fun clamp(value: Long): Long = when {
        value <= min -> min

        value >= max -> max

        value in this -> value

        else -> {
            val lo = lower(value)
            val hi = higher(value)
            if ((value - lo).toULong() <= (hi - value).toULong()) lo else hi
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

    /** Factory for `IntDomain`. */
    companion object {
        /** Construct the contiguous domain `(min..max)`. */
        operator fun invoke(
            min: Long,
            max: Long,
            storage: DomainStorageSettings = defaultDomainStorageSettings,
        ): IntDomain = ContiguousDomain(min, max, storage)
    }
}

/** The values of an enumerable domain; fails on one too wide to walk. Callers that can decline use `spanOrNull`. */
val IntDomain.values: IntSpan get() = span()

internal fun IntDomain.ceilingOrNull(value: Long): Long? = when {
    value <= min -> min
    value > max -> null
    value in this -> value
    else -> higher(value)
}

internal fun IntDomain.floorOrNull(value: Long): Long? = when {
    value >= max -> max
    value < min -> null
    value in this -> value
    else -> lower(value)
}

/**
 * A uniformly random value of the domain, never a hole.
 *
 * A domain too wide to index is sampled over the bounds and snapped to the nearest present value, so
 * it is only approximately uniform.
 */
fun IntDomain.randomValue(rng: Random): Long {
    val indexable = spanOrNull()
    if (indexable != null) return indexable.valueAt(rng.nextInt(indexable.size))
    val width = max - min
    val sample = if (width < 0L || width == Long.MAX_VALUE) rng.nextLong() else min + rng.nextLong(width + 1L)
    return clamp(sample)
}

/**
 * The values of an `IntDomain`, in ascending order, when there are few enough of them to index.
 *
 * Separate from `IntDomain` because enumeration is only meaningful when the present values fit an
 * `Int` index, so a caller that walks values must obtain this first and handle its absence. Obtained
 * from `IntDomain.spanOrNull`; the domain returns itself, so holding a span allocates nothing.
 */
interface IntSpan {
    /** Number of values, exact. */
    val size: Int

    /** The `i`-th value, 0-indexed ascending. */
    fun valueAt(i: Int): Long

    /** Invoke [action] for each value, ascending. */
    fun forEach(action: IntConsumer)
}

internal fun IntDomain.withStorage(settings: DomainStorageSettings): IntDomain =
    (this as? AbstractIntDomain)?.withStorage(settings) ?: this
