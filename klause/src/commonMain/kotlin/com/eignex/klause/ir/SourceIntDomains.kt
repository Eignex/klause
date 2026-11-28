package com.eignex.klause.ir

import com.eignex.util.collections.Bits

/**
 * The integer values a source model declares each of its columns may take.
 *
 * [bounds] states the range, either side of which may be open. A column that admits less than its whole
 * range states its value set through [declaredOrNull]; a rebuild that reads [bounds] alone widens such a
 * column into its hull, which is a different model.
 *
 * A column with an open side declares no value set. A lane may still materialize a fallback box for it,
 * which [finiteDomain] hands back, but its endpoints were invented, and treating them as a restriction
 * caps a genuinely unbounded model. So [declaredOrNull] answers `null` for every open-marked column.
 */
class SourceIntDomains internal constructor(
    /** Declared bounds of every column; either side may be open. */
    val bounds: IntBounds,
    private val stated: Array<IntDomain>?,
    internal val stableEndpoints: Boolean = true,
) {
    /** Number of source integer columns. */
    val size: Int get() = bounds.size

    private fun isClosed(v: Int): Boolean = bounds.hasLower(v) && bounds.hasUpper(v)

    /**
     * Value set column [v] declares, or `null` when the model admits its whole [bounds] range — including
     * every column with an open side, whose stated domain is an invented box rather than a declaration.
     */
    fun declaredOrNull(v: Int): IntDomain? = if (isClosed(v)) stated?.get(v) else null

    internal fun statedOrNull(v: Int): IntDomain? = stated?.get(v)

    /**
     * Finite domain of column [v] for a lane that must branch on it: the box a caller supplied, else the
     * closed [bounds] range. For an open-marked column the box has an invented endpoint; a consumer
     * reasoning about the model reads [declaredOrNull] and [bounds].
     */
    fun finiteDomain(v: Int): IntDomain = stated?.get(v) ?: run {
        require(isClosed(v)) {
            "integer column $v has an open side and cannot enter finite preparation"
        }
        IntDomain(bounds.lower(v), bounds.upper(v))
    }

    /** Every column's finite domain, materialized where no box was supplied. */
    fun finiteDomains(): Array<IntDomain> = stated?.copyOf() ?: Array(size, ::finiteDomain)

    /**
     * These declarations over [newBounds], or `null` when the narrower range leaves a declared value set
     * empty.
     *
     * A declared value set is intersected with the new range, so it does not widen back to its hull. A
     * column the source left open declares nothing, so a proved bound replaces its box outright:
     * intersecting there would let an invented endpoint refute a range the model never excluded.
     */
    fun rebounded(newBounds: IntBounds): SourceIntDomains? {
        require(newBounds.size == size) { "rebounding ${newBounds.size} columns over a model of $size" }
        val current = stated ?: return SourceIntDomains(newBounds, null)
        val next = Array(size) { v ->
            val declaration = declaredOrNull(v)
            if (declaration == null) {
                reboxed(v, current[v], newBounds)
            } else {
                narrowed(declaration, v, newBounds) ?: return null
            }
        }
        return SourceIntDomains(newBounds, next)
    }

    // A box kept over a column that stayed open can be wider than one proved side. That weakens the box,
    // never the model: the rows still enforce the bound, and a half-open range is not an IntDomain.
    private fun reboxed(v: Int, box: IntDomain, newBounds: IntBounds): IntDomain =
        if (newBounds.hasLower(v) && newBounds.hasUpper(v)) {
            IntDomain(newBounds.lower(v), newBounds.upper(v))
        } else {
            box
        }

    private fun narrowed(declaration: IntDomain, v: Int, newBounds: IntBounds): IntDomain? {
        var domain = declaration
        if (newBounds.hasLower(v) && newBounds.lower(v) > domain.min) {
            if (newBounds.lower(v) > domain.max) return null
            domain = domain.withMinAtLeast(newBounds.lower(v))
        }
        if (newBounds.hasUpper(v) && newBounds.upper(v) < domain.max) {
            if (newBounds.upper(v) < domain.min) return null
            domain = domain.withMaxAtMost(newBounds.upper(v))
        }
        return domain
    }

    /** The two shapes a source declaration comes in: a bound range, or explicit value sets. */
    companion object {
        /** Columns declared by [bounds] alone, admitting every value in range. */
        fun ofBounds(bounds: IntBounds): SourceIntDomains = SourceIntDomains(bounds, null)

        // Without modelBounds the range is read off the domains, with the marked sides recorded as open.
        // `shared` adopts the domains array instead of copying it.
        internal fun ofDomains(
            domains: Array<IntDomain>,
            shared: Boolean = false,
            openLo: BooleanArray? = null,
            openHi: BooleanArray? = null,
            packedOpenLo: Bits? = null,
            packedOpenHi: Bits? = null,
            modelBounds: IntBounds? = null,
        ): SourceIntDomains {
            require(openLo == null || openLo.size == domains.size) {
                "openLo size ${openLo?.size} != column count ${domains.size}"
            }
            require(openHi == null || openHi.size == domains.size) {
                "openHi size ${openHi?.size} != column count ${domains.size}"
            }
            // A packed mark set narrower than the columns leaves the tail addressable but unreadable:
            // [IntBounds] would read a column past it as closed, silently turning an invented endpoint
            // back into a declared bound.
            require(packedOpenLo == null || packedOpenLo.size >= domains.size) {
                "packedOpenLo size ${packedOpenLo?.size} < column count ${domains.size}"
            }
            require(packedOpenHi == null || packedOpenHi.size >= domains.size) {
                "packedOpenHi size ${packedOpenHi?.size} < column count ${domains.size}"
            }
            // [size] reads the bounds, so a retained range of a different length would leave the value
            // sets addressable past it with nothing left to catch the mismatch.
            require(modelBounds == null || modelBounds.size == domains.size) {
                "modelBounds size ${modelBounds?.size} != column count ${domains.size}"
            }
            val bounds = modelBounds ?: IntBounds.fromFiniteBounds(
                lowerBounds = LongArray(domains.size) { domains[it].min },
                upperBounds = LongArray(domains.size) { domains[it].max },
                openLo = openLo,
                openHi = openHi,
                packedOpenLo = packedOpenLo,
                packedOpenHi = packedOpenHi,
            )
            return SourceIntDomains(bounds, if (shared) domains else domains.copyOf(),
                stableEndpoints = !shared)
        }
    }
}
