package com.eignex.klause.ir

import com.eignex.klause.util.Bits

/**
 * The integer values a source model declares each of its columns may take.
 *
 * [bounds] states the range, either side of which may be open. Two endpoints cannot say which interior
 * values a declaration excludes, so a column that admits less than its whole range — `{1, 3, 5}`, or a set
 * a front-end carved before the model was assembled — states its value set through [declaredOrNull]. A
 * rebuild that reads [bounds] alone widens such a column into its hull, which is a different model.
 *
 * A column with an open side declares no value set at all. The finite machinery still needs something to
 * branch on there, so a lane may materialize a fallback box for it, and [finiteDomain] hands that box
 * back — but its endpoints were invented rather than declared, and treating them as a restriction caps a
 * model that is genuinely unbounded. That is why the two are separate readings of this table and why
 * [declaredOrNull] answers `null` for every open-marked column: no consumer can mistake a box for a
 * declaration.
 *
 * Nothing here says which engine owns a column. That is
 * [com.eignex.klause.solver.pipeline.ComponentPlan.intOwner], selected once per solve, and the
 * root-propagated domains a finite engine branches on live on
 * [com.eignex.klause.propagation.BakedProblem].
 */
class SourceIntDomains internal constructor(
    /** Declared bounds of every column; either side may be open. */
    val bounds: IntBounds,
    private val stated: Array<IntDomain>?,
) {
    /** Number of source integer columns. */
    val size: Int get() = bounds.size

    /** Whether column [v] is closed on both sides, so a value set stated for it is a declaration. */
    private fun isClosed(v: Int): Boolean = bounds.hasLower(v) && bounds.hasUpper(v)

    /**
     * Value set column [v] declares, or `null` when the model admits its whole [bounds] range — including
     * every column with an open side, whose stated domain is an invented box rather than a declaration.
     */
    fun declaredOrNull(v: Int): IntDomain? = if (isClosed(v)) stated?.get(v) else null

    /**
     * Finite domain of column [v] for a lane that must branch on it: the box a caller supplied, else the
     * closed [bounds] range.
     *
     * This is the finite reading, not the logical one — the box it returns for an open-marked column has an
     * invented endpoint. A consumer reasoning about the model reads [declaredOrNull] and [bounds].
     */
    fun finiteDomain(v: Int): IntDomain = stated?.get(v) ?: run {
        require(isClosed(v)) {
            "integer column $v has an open side and cannot enter finite preparation"
        }
        IntDomain(bounds.lower(v), bounds.upper(v))
    }

    /** Every column's finite domain, materialized where no box was supplied. */
    fun finiteDomains(): Array<IntDomain> = stated?.copyOf() ?: Array(size, ::finiteDomain)

    /** The supplied boxes, handed back without copying; the bridge a finite consumer still reads. */
    internal fun statedOrNull(): Array<IntDomain>? = stated

    /**
     * These declarations over [newBounds], or `null` when the narrower range leaves a declared value set
     * empty.
     *
     * A declared value set survives a bound rewrite: the two intersect rather than replace one another, so
     * a non-contiguous column does not widen back to its hull. A column the source left open declares
     * nothing, so a bound proved for it replaces its box outright — intersecting there would let an
     * invented endpoint refute a range the model never excluded.
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

    /**
     * [box] replaced by the proved range, keeping it only while the column stays open.
     *
     * A box kept over a column that stayed open can be wider than one side [newBounds] proved. That
     * weakens the box, never the model — the bound came from the rows, which still enforce it — and
     * narrowing it here has no representation to land in, since a half-open range is not an [IntDomain].
     */
    private fun reboxed(v: Int, box: IntDomain, newBounds: IntBounds): IntDomain =
        if (newBounds.hasLower(v) && newBounds.hasUpper(v)) {
            IntDomain(newBounds.lower(v), newBounds.upper(v))
        } else {
            box
        }

    /** [declaration] intersected with column [v]'s range in [newBounds], or `null` when nothing remains. */
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

        /**
         * Columns whose finite domains are [domains].
         *
         * [modelBounds] states the model-level range when the caller retains one across a rebuild;
         * otherwise the range is read off [domains] and the sides [openLo] / [openHi] mark — or their
         * packed forms — are recorded as open. An open-marked column's entry is a fallback box, so it
         * declares nothing; see [declaredOrNull]. [shared] hands [domains] itself to the result instead of
         * a copy, for the projection that owns the array it passes in.
         */
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
            return SourceIntDomains(bounds, if (shared) domains else domains.copyOf())
        }
    }
}
