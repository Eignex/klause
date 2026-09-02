package com.eignex.klause.ir

import com.eignex.klause.util.Bits

/**
 * The integer values a source model declares each of its columns may take.
 *
 * [bounds] states the range, either side of which may be open. A declaration that admits less than its
 * whole range — `{1, 3, 5}`, or a set a front-end carved before the model was assembled — keeps its value
 * set here as well, because two endpoints do not say which interior values the model excluded. A rebuild
 * that reads [bounds] alone widens such a declaration into its hull; reading this table instead is what
 * keeps a source rewrite equisatisfiable.
 *
 * Nothing here says which engine owns a column or holds mutable search state for it. That is
 * [com.eignex.klause.solver.pipeline.ComponentPlan.intOwner], selected once per solve, and the
 * root-propagated finite domains a finite engine branches on live on
 * [com.eignex.klause.propagation.BakedProblem].
 */
class SourceIntDomains internal constructor(
    /** Declared bounds of every column; either side may be open. */
    val bounds: IntBounds,
    private val declared: Array<IntDomain>?,
) {
    /** Number of source integer columns. */
    val size: Int get() = bounds.size

    /** Whether the model states an explicit value set per column rather than bounds alone. */
    val hasDeclaredDomains: Boolean get() = declared != null

    /** Declared value set of column [v], or `null` when the model admits its whole [bounds] range. */
    fun declaredOrNull(v: Int): IntDomain? = declared?.get(v)

    /**
     * Every declared value set, or `null` when the model states bounds alone.
     *
     * Handed back without copying, so a consumer that folds root deductions into it mutates the
     * declaration it was built from — which is exactly what the propagation projection owns its own array
     * for.
     */
    internal fun allDeclaredOrNull(): Array<IntDomain>? = declared

    /**
     * Finite value set of column [v], materialized from [bounds] when the model admits its whole range.
     *
     * Rejects a column with an open side: no finite set is declared for it, and inventing an endpoint is a
     * decision for the lane that needs one.
     */
    fun finiteDomain(v: Int): IntDomain = declared?.get(v) ?: run {
        require(bounds.hasLower(v) && bounds.hasUpper(v)) {
            "integer column $v has an open side and cannot enter finite preparation"
        }
        IntDomain(bounds.lower(v), bounds.upper(v))
    }

    /** Every column's finite value set, materialized where the model admits its whole range. */
    fun finiteDomains(): Array<IntDomain> = declared?.copyOf() ?: Array(size, ::finiteDomain)

    /**
     * These declarations over [newBounds], or `null` when the narrower range leaves a declared value set
     * empty.
     *
     * A bound rewrite proves a column cannot leave [newBounds]; a value set it already declared still
     * holds, so the two intersect rather than replace one another. Losing the intersection would widen the
     * column, and taking [newBounds] as the whole declaration is that loss.
     */
    fun rebounded(newBounds: IntBounds): SourceIntDomains? {
        require(newBounds.size == size) { "rebounding ${newBounds.size} columns over a model of $size" }
        val current = declared ?: return SourceIntDomains(newBounds, null)
        val next = Array(size) { v ->
            var domain = current[v]
            if (newBounds.hasLower(v) && newBounds.lower(v) > domain.min) {
                if (newBounds.lower(v) > domain.max) return null
                domain = domain.withMinAtLeast(newBounds.lower(v))
            }
            if (newBounds.hasUpper(v) && newBounds.upper(v) < domain.max) {
                if (newBounds.upper(v) < domain.min) return null
                domain = domain.withMaxAtMost(newBounds.upper(v))
            }
            domain
        }
        return SourceIntDomains(newBounds, next)
    }

    /** The two shapes a source declaration comes in: a bound range, or explicit value sets. */
    companion object {
        /** Columns declared by [bounds] alone, admitting every value in range. */
        fun ofBounds(bounds: IntBounds): SourceIntDomains = SourceIntDomains(bounds, null)

        /**
         * Columns declared by the explicit value sets [domains].
         *
         * [modelBounds] states the model-level range when the caller retains one across a rebuild;
         * otherwise the range is read off [domains] and the sides [openLo] / [openHi] mark — or their
         * packed forms — are recorded as open, because a finite endpoint a lane invented to close an open
         * side is an artefact rather than a declaration. [shared] hands [domains] itself to the result
         * instead of a copy, for the projection that owns the array it passes in.
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
