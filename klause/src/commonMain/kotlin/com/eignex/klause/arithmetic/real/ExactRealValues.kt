package com.eignex.klause.arithmetic.real

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.AlgebraicReal
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.arithmetic.polynomial.PolynomialResourceLimit
import com.eignex.klause.arithmetic.transcendental.TranscendentalArithmetic
import com.eignex.klause.arithmetic.transcendental.TranscendentalInterrupted
import com.eignex.klause.arithmetic.transcendental.TranscendentalResourceLimit
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.util.Cancellation

/** Capture an algebraic scalar as an exact real coordinate. */
fun AlgebraicReal.toExactRealValue(): ExactRealValue = ExactRealValue(ExactRealExpression.Algebraic(this))

/** Capture a rational scalar without a floating conversion. */
fun BigFraction.toExactRealValue(): ExactRealValue = AlgebraicArithmetic().rational(this).toExactRealValue()

/** Authoritative real coordinate, widening a finite binary64 value only when no exact witness exists. */
fun Sample.exactRealValue(id: Int): ExactRealValue = symbolicReals?.get(id)
    ?: algebraicReals?.get(id)?.toExactRealValue()
    ?: (exactReals?.get(id) ?: requireNotNull(BigFraction.ofDouble(approximateRealValue(id))) {
        "real coordinate $id has no exact value"
    }).toExactRealValue()

/** Numerical view for display or learning features; NaN means an enclosure was unavailable. */
fun ExactRealValue.approximate(): Double {
    rational?.let { return it.toDouble() }
    var polls = 0
    val stop = Cancellation { ++polls > 100000 }
    return try {
        val arithmetic = ExactRealArithmetic(TranscendentalArithmetic(stop, maximumWork = 100000),
            AlgebraicArithmetic(PolynomialArithmetic(stop)))
        val enclosure = arithmetic.enclose(this, 16)
        enclosure.lower.toDouble() / 2 + enclosure.upper.toDouble() / 2
    } catch (_: PolynomialInterrupted) {
        Double.NaN
    } catch (_: PolynomialResourceLimit) {
        Double.NaN
    } catch (_: TranscendentalInterrupted) {
        Double.NaN
    } catch (_: TranscendentalResourceLimit) {
        Double.NaN
    } catch (_: RealCertificationUnavailable) {
        Double.NaN
    }
}

/** Certified comparison; null means the available exact identities and enclosures did not decide it. */
fun ExactRealValue.compareExactly(other: ExactRealValue): Int? {
    var polls = 0
    val stop = Cancellation { ++polls > 100000 }
    return try {
        ExactRealArithmetic(TranscendentalArithmetic(stop, maximumWork = 100000),
            AlgebraicArithmetic(PolynomialArithmetic(stop))).compare(this, other)
    } catch (_: PolynomialInterrupted) {
        null
    } catch (_: PolynomialResourceLimit) {
        null
    } catch (_: TranscendentalInterrupted) {
        null
    } catch (_: TranscendentalResourceLimit) {
        null
    } catch (_: RealCertificationUnavailable) {
        null
    }
}
