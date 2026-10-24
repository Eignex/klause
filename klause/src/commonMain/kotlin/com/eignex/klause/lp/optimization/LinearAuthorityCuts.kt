package com.eignex.klause.lp.optimization

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceDerivation
import com.eignex.klause.solver.evidence.SourceInequality
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.abs
import com.eignex.klause.util.div
import com.eignex.klause.util.gcd
import com.eignex.klause.util.isZero
import com.eignex.klause.util.magnitudeBitLength
import com.eignex.klause.util.times

internal class LinearAuthorityCut(
    val owner: Int,
    val inequality: SourceInequality,
    val derivation: SourceDerivation,
)

internal class LinearAuthorityCuts private constructor(
    val rows: List<LinearAuthorityCut>,
    val work: Long,
) {
    companion object {
        fun prepare(source: SourceProofContext, cancellation: Cancellation): LinearAuthorityCuts? {
            if (cancellation()) return null
            val cuts = ArrayList<LinearAuthorityCut>()
            var work = 0L
            for ((owner, entry) in source.premises.entries.withIndex()) {
                if (cancellation()) return null
                val row = entry.value.inequality
                val coefficients = row.coefficients
                val cost = 1L + coefficients.size * 4L
                if (cost > 65_536L - work) break
                work += cost
                if (coefficients.isEmpty() || coefficients.keys.any { it.kind != SourceCoordinate.Kind.Integer }) {
                    continue
                }
                val values = coefficients.values
                if ((values + row.rhs).any { it.num.magnitudeBitLength() > 4096 ||
                        it.den.magnitudeBitLength() > 4096 }) continue
                var scale = BIG_ONE
                for (value in values) {
                    if (cancellation()) return null
                    scale = scale / scale.gcd(value.den) * value.den
                    if (scale.magnitudeBitLength() > 4096) break
                }
                if (scale.magnitudeBitLength() > 4096) continue
                var divisor = BIG_ZERO
                for (value in values) {
                    if (cancellation()) return null
                    divisor = divisor.gcd((value.num * (scale / value.den)).abs())
                }
                if (divisor.isZero()) continue
                val multiplier = BigFraction.of(scale, divisor)
                val scaled = row.rhs * multiplier
                val rhs = if (row.strict) BigFraction.of(scaled.ceilInteger(), BIG_ONE) - BigFraction.ONE
                    else BigFraction.of(scaled.negated().ceilInteger(), BIG_ONE).negated()
                if (rhs == scaled && !row.strict) continue
                val conclusion = SourceInequality(coefficients.mapValues { (_, value) -> value * multiplier }, rhs)
                val proof = SourceDerivation.IntegerRounding(SourceDerivation.Combination(listOf(
                    SourceDerivation.Combination.Term(multiplier, SourceDerivation.Premise(entry.key)))))
                cuts += LinearAuthorityCut(owner, conclusion, proof)
            }
            return LinearAuthorityCuts(cuts, work)
        }
    }
}
