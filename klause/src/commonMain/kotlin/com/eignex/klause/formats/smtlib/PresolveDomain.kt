package com.eignex.klause.formats.smtlib

import com.eignex.klause.ir.IntDomain

// An open integer is not representable as a searchable `IntDomain`, so the type system keeps infinity out of
// finite CP search. Infinity is a `null` bound, never a sentinel.
internal sealed interface PresolveDomain {
    class Finite(val domain: IntDomain) : PresolveDomain

    class Open(val lo: Long?, val hi: Long?) : PresolveDomain {
        init {
            require(lo == null || hi == null) { "Open needs an open side; use Finite" }
        }
    }
}

// Crossed bounds close to `[lo, lo]`; the constraints that proved them still make the model unsat.
internal fun openOrFinite(lo: Long?, hi: Long?): PresolveDomain = if (lo != null && hi != null) {
    PresolveDomain.Finite(if (lo <= hi) IntDomain(lo, hi) else IntDomain(lo, lo))
} else {
    PresolveDomain.Open(lo, hi)
}
