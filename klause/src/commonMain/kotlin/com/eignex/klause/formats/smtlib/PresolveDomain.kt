package com.eignex.klause.formats.smtlib

import com.eignex.klause.solver.IntDomain

/**
 * A per-variable domain **during SMT-LIB presolve**, modelled as a sealed union so that an *open*
 * (as-yet unbounded) integer is simply not representable as a searchable [IntDomain]: the type system —
 * not a runtime guard — enforces that infinity never reaches search. An [Open] domain lives only inside
 * [SmtLibQfLia.Builder] between a variable's declaration and [SmtLibQfLia.Builder.boundUnboundedVars];
 * bound inference and OBBT close the sides they can, and the searchable-fallback clamp closes whatever
 * remains, so every entry is [Finite] by the time a [com.eignex.klause.solver.Problem] is built.
 * Infinity is carried structurally (a `null` bound), never by a `Long.MIN/MAX` or `±Long/4` sentinel.
 */
internal sealed interface PresolveDomain {
    /** A fully-known finite domain, ready for search. */
    class Finite(val domain: IntDomain) : PresolveDomain

    /** An integer variable still open on at least one side; `lo`/`hi` null = no bound yet that side.
     *  Not an [IntDomain]: it cannot be handed to a `Problem`/search until closed to a [Finite]. */
    class Open(val lo: Long?, val hi: Long?) : PresolveDomain {
        init {
            require(lo == null || hi == null) { "Open needs an open side; use Finite" }
        }

        /** True when there is no provable lower bound yet. */
        val openBelow: Boolean get() = lo == null

        /** True when there is no provable upper bound yet. */
        val openAbove: Boolean get() = hi == null
    }
}

/** Close a possibly-open `(lo, hi)` pair: both non-null ⇒ [PresolveDomain.Finite] (`lo <= hi ? [lo, hi]`
 *  else `[lo, lo]`, so contradictory provable bounds stay a genuine `unsat`); otherwise [PresolveDomain.Open]. */
internal fun openOrFinite(lo: Long?, hi: Long?): PresolveDomain = if (lo != null && hi != null) {
    PresolveDomain.Finite(if (lo <= hi) IntDomain(lo, hi) else IntDomain(lo, lo))
} else {
    PresolveDomain.Open(lo, hi)
}
