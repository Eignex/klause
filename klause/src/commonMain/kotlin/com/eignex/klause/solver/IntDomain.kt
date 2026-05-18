package com.eignex.klause.solver

/**
 * Integer-variable domain. Conceptually a finite set of integers; physically a
 * `[min..max]` interval with an optional sorted list of interior holes for sparse
 * cases.
 *
 * **Invariants**
 *  - `min <= max` (no empty domains; the propagation engine treats domain-empty as
 *    conflict).
 *  - `holes` (when present) is sorted ascending with strictly-distinct values.
 *  - Every hole `h` satisfies `min < h < max` — endpoints are never holes, so
 *    `contains(min)` and `contains(max)` are always true. Pruning operations re-pin
 *    the endpoints if they coincide with a removed value.
 *
 * **Representation cost**
 *  - Contiguous domain (the typical case): `holes == null`, 16 bytes — same as the
 *    previous `data class IntDomain(min, max)` shape.
 *  - Sparse domain: `holes` is a sorted `IntArray` of interior excluded values. Hot
 *    paths ([contains], [min], [max], [size]) check `holes == null` first and fall
 *    through to the same fast integer arithmetic as before; only operations that
 *    actually need to walk the holes pay any cost.
 *
 * **Why a class with an optional `holes` IntArray rather than a sealed Range/Sparse?**
 *  Lots of callers construct `IntDomain(min, max)` directly and read `.min / .max`
 *  arithmetic-style. A sealed hierarchy would force sealed-when destructuring at
 *  every call site even for the trivial contiguous use; the optional-holes design
 *  keeps the call-site API source-compatible with the old data class.
 */
class IntDomain private constructor(
    val min: Int,
    val max: Int,
    /** Sorted ascending, contains values strictly between [min] and [max] that are
     *  excluded from the domain. `null` means "domain is the contiguous interval
     *  `[min..max]`" — the hot-path representation. Empty arrays are never stored;
     *  excluding the last interior value transitions back to `null` representation.
     *  Public for `inline fun forEach` access; treat as internal API. */
    @PublishedApi internal val holes: IntArray?,
) {
    init {
        require(min <= max) { "Empty domain: $min..$max" }
    }

    /** Source-compatible constructor for the contiguous case (`null` holes). */
    constructor(min: Int, max: Int) : this(min, max, null)

    /** Number of values in the domain — `(max - min + 1)` minus any interior holes. */
    val size: Int get() = max - min + 1 - (holes?.size ?: 0)

    operator fun contains(value: Int): Boolean {
        if (value < min || value > max) return false
        if (holes == null) return true
        return holes.binarySearch(value) < 0
    }

    fun clamp(value: Int): Int = if (value < min) min else if (value > max) max else value

    /**
     * Return a new domain with [value] excluded, or `this` if [value] is not currently
     * present (idempotent on absent values). Throws [IllegalStateException] if removing
     * [value] would empty the domain.
     *
     * Three cases:
     *  - `value == min`: advance `min` forward past any consecutive holes that follow.
     *    Discard holes that are now below the new `min`.
     *  - `value == max`: pull `max` back past any consecutive holes that precede it.
     *    Discard holes above the new `max`.
     *  - interior: insert `value` into [holes] in sorted position. If `holes` was
     *    `null`, allocate a single-entry array.
     */
    fun excludeValue(value: Int): IntDomain {
        if (!contains(value)) return this
        return when {
            value == min -> {
                var newMin = min + 1
                if (holes != null) {
                    while (newMin <= max && holes.binarySearch(newMin) >= 0) newMin++
                }
                check(newMin <= max) { "Empty domain after excludeValue($value)" }
                // Trim holes now <= newMin (they were either between old min and new min, or are now the endpoint).
                val newHoles = trimHolesBelow(holes, newMin + 1)
                IntDomain(newMin, max, newHoles)
            }
            value == max -> {
                var newMax = max - 1
                if (holes != null) {
                    while (newMax >= min && holes.binarySearch(newMax) >= 0) newMax--
                }
                check(newMax >= min) { "Empty domain after excludeValue($value)" }
                val newHoles = trimHolesAbove(holes, newMax - 1)
                IntDomain(min, newMax, newHoles)
            }
            else -> {
                val newHoles = if (holes == null) intArrayOf(value)
                else insertSorted(holes, value)
                IntDomain(min, max, newHoles)
            }
        }
    }

    /**
     * Return a domain with min raised to at least [newMin]. Returns `this` when
     * [newMin] is already covered (no-op). Throws [IllegalStateException] on empty.
     * Advances past any holes co-occurring with the new lower bound.
     */
    fun withMinAtLeast(newMin: Int): IntDomain {
        if (newMin <= min) return this
        check(newMin <= max) { "withMinAtLeast($newMin) empties domain [$min..$max]" }
        var m = newMin
        if (holes != null) {
            while (m <= max && holes.binarySearch(m) >= 0) m++
        }
        check(m <= max) { "withMinAtLeast($newMin): only holes remained above $newMin" }
        val newHoles = trimHolesBelow(holes, m + 1)
        return IntDomain(m, max, newHoles)
    }

    fun withMaxAtMost(newMax: Int): IntDomain {
        if (newMax >= max) return this
        check(newMax >= min) { "withMaxAtMost($newMax) empties domain [$min..$max]" }
        var m = newMax
        if (holes != null) {
            while (m >= min && holes.binarySearch(m) >= 0) m--
        }
        check(m >= min) { "withMaxAtMost($newMax): only holes remained below $newMax" }
        val newHoles = trimHolesAbove(holes, m - 1)
        return IntDomain(min, m, newHoles)
    }

    /**
     * Iterate every value present in the domain in ascending order. Contiguous fast
     * path is a plain `min..max` walk; sparse path filters via a single linear walk
     * of [holes] (no per-element binarySearch). Inlined so callers get the hot path
     * without indirection.
     */
    inline fun forEach(action: (Int) -> Unit) {
        val h = holes
        if (h == null) {
            for (v in min..max) action(v)
        } else {
            var holeIdx = 0
            for (v in min..max) {
                if (holeIdx < h.size && h[holeIdx] == v) {
                    holeIdx++
                    continue
                }
                action(v)
            }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (other !is IntDomain) return false
        if (min != other.min || max != other.max) return false
        val a = holes
        val b = other.holes
        return when {
            a == null && b == null -> true
            a == null || b == null -> false
            else -> a.contentEquals(b)
        }
    }

    override fun hashCode(): Int {
        var h = min * 31 + max
        if (holes != null) h = h * 31 + holes.contentHashCode()
        return h
    }

    override fun toString(): String =
        if (holes == null) "IntDomain($min..$max)"
        else "IntDomain($min..$max - ${holes.toList()})"

    companion object {
        /** Drop trimmed entries that fell below or equal to [upper] - 1; i.e., keep
         *  holes that are `>= upper`. */
        private fun trimHolesBelow(holes: IntArray?, upper: Int): IntArray? {
            if (holes == null) return null
            var start = 0
            while (start < holes.size && holes[start] < upper) start++
            return when {
                start == holes.size -> null
                start == 0 -> holes
                else -> holes.copyOfRange(start, holes.size)
            }
        }

        /** Keep holes that are `<= upper`. */
        private fun trimHolesAbove(holes: IntArray?, upper: Int): IntArray? {
            if (holes == null) return null
            var end = holes.size
            while (end > 0 && holes[end - 1] > upper) end--
            return when {
                end == 0 -> null
                end == holes.size -> holes
                else -> holes.copyOfRange(0, end)
            }
        }

        private fun insertSorted(holes: IntArray, value: Int): IntArray {
            val idx = holes.binarySearch(value)
            if (idx >= 0) return holes  // already excluded; idempotent
            val insertAt = -(idx + 1)
            val out = IntArray(holes.size + 1)
            for (i in 0 until insertAt) out[i] = holes[i]
            out[insertAt] = value
            for (i in insertAt until holes.size) out[i + 1] = holes[i]
            return out
        }
    }
}
