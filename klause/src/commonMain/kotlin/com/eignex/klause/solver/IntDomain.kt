package com.eignex.klause.solver

import com.eignex.klause.solver.intdomain.ContiguousDomain

/** Primitive-`Int` visitor for the [IntDomain] iteration methods. A `fun interface` (not
 *  `(Int) -> Unit`) so `action(v)` passes a primitive `int` with no `Integer` boxing on the
 *  per-value propagation hot paths; lambdas still convert at the call site via SAM conversion, so
 *  `d.forEach { … }` is unchanged. */
fun interface IntConsumer {
    /** Receive one domain [value] from an [IntDomain] iteration. */
    fun accept(value: Int)
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
 *    (`<=` [BITSET_THRESHOLD]); membership is an O(1) bit test at any density.
 *  - [com.eignex.klause.solver.intdomain.RunsDomain] — a sorted list of disjoint present runs;
 *    membership O(log runs), storage O(runs). The wide-span rep for relatively few runs (few holes,
 *    or clustered survivors).
 *  - [com.eignex.klause.solver.intdomain.SurvivorsDomain] — the sorted present values; membership
 *    O(log survivors), storage O(survivors). The escape for scattered survivor sets and the
 *    alternating "comb", where a run list would cost ~2 ints per survivor.
 *
 * The run and survivor reps together keep both storage and the hot paths **independent of the
 * declared span**: the former "sorted hole list" (complement) rep degenerated to O(span) storage
 * and cache-thrashing binary searches once a wide domain was carved down to a small reachable set —
 * a pathological throughput cliff. Between them every wide shape stays O(min(holes, survivors)).
 *
 * **Representation choice** — construct via the factories (`IntDomain(min, max)` for the contiguous
 * case; the internal `intDomainFrom*` factories pick the wide rep): a single run ⇒ contiguous; span
 * `<=` [BITSET_THRESHOLD] ⇒ bitset; otherwise the run list when it is at least as compact as the
 * survivor list (`2·runs <= survivors`), else the survivor list. Domains are immutable, so every
 * mutation returns a fresh value and re-picks — there is no flip-flop cost.
 *
 * Callers depend only on this interface and `IntDomain(min, max)` / [IntDomain.BITSET_THRESHOLD];
 * the concrete reps are an implementation detail.
 */
interface IntDomain {
    /** Inclusive lower bound; always an in-domain value. */
    val min: Int

    /** Inclusive upper bound; always an in-domain value. */
    val max: Int

    /** Number of values in the domain (O(1)). */
    val size: Int

    /** True iff [value] lies in the domain. */
    operator fun contains(value: Int): Boolean

    /** The `i`-th value present in the domain (0-indexed, ascending). */
    fun valueAt(i: Int): Int

    /** Return a new domain with [value] excluded, or `this` if [value] is absent (idempotent).
     *  Throws if removing [value] would empty the domain. */
    fun excludeValue(value: Int): IntDomain

    /**
     * Return a domain with every value in [values] excluded, `this` if none are present
     * (idempotent), or `null` when excluding them all would empty the domain. [values] must be
     * sorted ascending and distinct; entries outside `min..max` or already absent are ignored.
     * Merges in a single pass keeping survivors — O(present + values), span-independent for the
     * wide reps. Only the value *set* is contractual; the resulting rep is an implementation detail.
     */
    fun excludeValues(values: IntArray): IntDomain?

    /** Copy of the domain with its min raised to at least [newMin]. `this` when already covered;
     *  throws on empty. */
    fun withMinAtLeast(newMin: Int): IntDomain

    /** Copy of the domain with its max tightened to at most [newMax]. `this` when already covered;
     *  throws on empty. */
    fun withMaxAtMost(newMax: Int): IntDomain

    /**
     * Inverse of an interior [excludeValue]: put [value] (strictly inside `min..max` and currently
     * absent) back into the domain. Exists for the undo journal — an interior carve is journaled as
     * the carved value alone instead of a full prior-domain snapshot.
     */
    fun includeInteriorValue(value: Int): IntDomain

    /** Invoke [action] for each value present in the domain, ascending. */
    fun forEach(action: IntConsumer)

    /** Invoke [action] for each value excluded strictly between [min] and [max], ascending.
     *  No-op for a contiguous domain. */
    fun forEachHole(action: IntConsumer)

    /** Invoke [action] for each value in `[lo, hi]` carved *out* of the domain (an interior hole),
     *  ascending, clamped to `[min, max]`. Span-independent for the wide reps — it walks the
     *  runs / survivors crossed, not every integer in the range. */
    fun forEachHoleInRange(lo: Int, hi: Int, action: IntConsumer)

    /** Nearest in-domain value to [value] (clamps to the bounds). */
    fun clamp(value: Int): Int = when {
        value < min -> min
        value > max -> max
        else -> value
    }

    /** Nearest in-`[min, max]` value to a `Long` [value], returned as `Int`. A target beyond `Int`
     *  range necessarily lies past the corresponding bound, so it clamps to [min] / [max]. */
    fun clampLong(value: Long): Int = when {
        value < min -> min
        value > max -> max
        else -> value.toInt()
    }

    /** Factory and shared constants for [IntDomain]. */
    companion object {
        /** Span threshold (inclusive) below which a non-contiguous domain is stored as a bitset
         *  instead of a wide rep. 4096 ⇒ ≤ 64 longs (512 bytes), which keeps membership O(1) for
         *  the whole moderate-span middle ground; only genuinely wide domains fall through to the
         *  run / survivor reps. */
        const val BITSET_THRESHOLD: Int = 4096

        /** Construct the contiguous domain `(min..max)`. Source-compatible with the former class
         *  constructor, so existing `IntDomain(min, max)` call sites are unchanged. */
        operator fun invoke(min: Int, max: Int): IntDomain = ContiguousDomain(min, max)
    }
}
