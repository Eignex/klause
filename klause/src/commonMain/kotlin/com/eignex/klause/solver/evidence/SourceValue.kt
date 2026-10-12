package com.eignex.klause.solver.evidence

import com.eignex.klause.arithmetic.polynomial.AlgebraicReal
import com.eignex.klause.simplex.exact.BigFraction

/** Exact source scalar retained independently of floating telemetry. */
sealed interface SourceValue {
    /** Exact reduced rational value. */
    data class Rational(val value: BigFraction) : SourceValue
    /** Exact algebraic coordinate, including its defining polynomial and root identity. */
    data class Algebraic(val value: AlgebraicReal) : SourceValue
    /** Source-specific exact symbolic value interpreted only by its declared semantics checker. */
    interface Extension : SourceValue
}
