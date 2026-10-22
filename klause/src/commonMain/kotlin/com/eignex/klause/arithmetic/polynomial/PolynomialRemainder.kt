package com.eignex.klause.arithmetic.polynomial

internal fun preparePolynomialRemainder(
    polynomial: Polynomial,
    divisors: List<Polynomial>,
    arithmetic: PolynomialArithmetic,
): Iterator<Polynomial?> = PolynomialRemainder(divisors, arithmetic).prepare(polynomial)

internal class PolynomialRemainder(divisors: List<Polynomial>, private val arithmetic: PolynomialArithmetic) {
    private val generators = HashMap<Int?, MutableList<RemainderDivisor>>()

    init {
        for ((index, divisor) in divisors.withIndex()) {
            arithmetic.step()
            if (divisor.isZero) continue
            val leading = divisor.terms.last()
            val variable = leading.monomial.variables.lastOrNull()
            generators.getOrPut(variable) { ArrayList() }.add(RemainderDivisor(index, divisor, leading))
        }
    }

    fun prepare(polynomial: Polynomial): Iterator<Polynomial?> = sequence {
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
            for (divisor in candidates(term.monomial)) {
                arithmetic.step()
                val leading = divisor.leading
                val quotient = term.monomial.quotient(leading.monomial) ?: continue
                val factor = Polynomial.of(listOf(PolynomialTerm(quotient,
                    term.coefficient * leading.coefficient.reciprocal())))
                val multiplying = arithmetic.prepareMultiply(factor, divisor.polynomial)
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

    private fun candidates(monomial: Monomial): Sequence<RemainderDivisor> = sequence {
        val buckets = ArrayList<List<RemainderDivisor>>()
        for (variable in monomial.variables) {
            arithmetic.step()
            generators[variable]?.let { buckets.add(it) }
        }
        arithmetic.step()
        generators[null]?.let { buckets.add(it) }
        val positions = IntArray(buckets.size)
        while (true) {
            var selected = -1
            var earliest = Int.MAX_VALUE
            for (index in buckets.indices) {
                arithmetic.step()
                val position = positions[index]
                if (position == buckets[index].size) continue
                val order = buckets[index][position].index
                if (order < earliest) {
                    selected = index
                    earliest = order
                }
            }
            if (selected < 0) return@sequence
            yield(buckets[selected][positions[selected]++])
        }
    }
}

private class RemainderDivisor(val index: Int, val polynomial: Polynomial, val leading: PolynomialTerm)
