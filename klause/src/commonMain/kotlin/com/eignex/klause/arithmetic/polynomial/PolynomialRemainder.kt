package com.eignex.klause.arithmetic.polynomial

internal fun preparePolynomialRemainder(
    polynomial: Polynomial,
    divisors: List<Polynomial>,
    arithmetic: PolynomialArithmetic,
): Iterator<Polynomial?> {
    val generators = divisors.mapNotNull {
        arithmetic.step()
        if (it.isZero) null else it to it.terms.last()
    }
    return sequence {
        if (generators.isEmpty()) {
            arithmetic.step()
            yield(polynomial)
            return@sequence
        }
        var pending = polynomial
        val remainder = ArrayList<PolynomialTerm>()
        while (!pending.isZero) {
            arithmetic.step()
            val term = pending.terms.last()
            var cancelled = false
            for ((divisor, leading) in generators) {
                arithmetic.step()
                val quotient = term.monomial.quotient(leading.monomial) ?: continue
                val factor = Polynomial.of(listOf(PolynomialTerm(quotient,
                    term.coefficient * leading.coefficient.reciprocal())))
                val multiplying = arithmetic.prepareMultiply(factor, divisor)
                var product: Polynomial? = null
                while (product == null) {
                    product = multiplying.next()
                    if (product == null) yield(null)
                }
                pending = arithmetic.subtract(pending, product)
                cancelled = true
                break
            }
            if (!cancelled) {
                remainder.add(term)
                pending = arithmetic.subtract(pending, Polynomial.of(listOf(term)))
            }
            yield(null)
        }
        arithmetic.step()
        yield(Polynomial.of(remainder))
    }.iterator()
}
