package com.eignex.klause.solver

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.arithmetic.polynomial.PolynomialResourceLimit
import com.eignex.klause.arithmetic.real.ExactRealArithmetic
import com.eignex.klause.arithmetic.real.exactRealValue
import com.eignex.klause.arithmetic.real.toExactRealValue
import com.eignex.klause.arithmetic.transcendental.TranscendentalArithmetic
import com.eignex.klause.arithmetic.transcendental.TranscendentalInterrupted
import com.eignex.klause.arithmetic.transcendental.TranscendentalResourceLimit
import com.eignex.klause.ir.Problem
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.pipeline.exactRealSource
import com.eignex.klause.theory.transcendental.SourceTruth
import com.eignex.klause.theory.transcendental.TranscendentalSourceCheck
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation

/**
 * Check an exact sample against every native factor and declaration using complete polynomial encodings.
 * Null withholds a verdict when an encoding, analytic identity or resource allowance is unavailable.
 */
fun Problem.checkExactRealSample(sample: Sample, cancellation: Cancellation = Cancellation.Never): Boolean? {
    if (sample.numBoolVars != numBoolVars || sample.numIntVars != numIntVars || sample.numRealVars != numRealVars) {
        return false
    }
    if (numRealVars > 0 && sample.exactReals == null && sample.algebraicReals == null && sample.symbolicReals == null) {
        return null
    }
    return try {
        val source = exactRealSource(FactorProjectionProviders.Default, cancellation) ?: return null
        val arithmetic = ExactRealArithmetic(
            TranscendentalArithmetic(cancellation, maximumWork = 100000),
            AlgebraicArithmetic(PolynomialArithmetic(cancellation)),
        )
        val values = List(numIntVars) { BigFraction.of(sample.exactIntValue(it), BIG_ONE).toExactRealValue() } +
            List(numRealVars) { sample.exactRealValue(it) }
        when (TranscendentalSourceCheck(arithmetic, values, sample.bools).check(source.formula)) {
            SourceTruth.TRUE -> true
            SourceTruth.FALSE, SourceTruth.DOMAIN_INVALID -> false
            SourceTruth.UNKNOWN, SourceTruth.DOMAIN_UNKNOWN -> null
        }
    } catch (_: PolynomialInterrupted) {
        null
    } catch (_: PolynomialResourceLimit) {
        null
    } catch (_: TranscendentalInterrupted) {
        null
    } catch (_: TranscendentalResourceLimit) {
        null
    }
}
