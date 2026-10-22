package com.eignex.klause.arithmetic.polynomial

import com.eignex.klause.simplex.exact.BigFraction

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
        val pending = RemainderTerms(polynomial, arithmetic)
        val remainder = ArrayList<PolynomialTerm>()
        while (!pending.isEmpty) {
            arithmetic.step()
            val term = pending.leading()
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
                for (reduction in product.terms) {
                    pending.subtract(reduction)
                }
                cancelled = true
                break
            }
            if (!cancelled) {
                remainder.add(term)
                pending.removeLeading()
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

private class RemainderTerms(polynomial: Polynomial, private val arithmetic: PolynomialArithmetic) {
    private class Entry(val monomial: Monomial, var coefficient: BigFraction, var position: Int)

    private val entries = HashMap<Monomial, Entry>()
    private val heap = ArrayList<Entry>()
    val isEmpty: Boolean get() = heap.isEmpty()

    init {
        for (term in polynomial.terms.asReversed()) {
            arithmetic.step()
            val entry = Entry(term.monomial, term.coefficient, heap.size)
            entries[term.monomial] = entry
            heap.add(entry)
        }
    }

    fun leading(): PolynomialTerm {
        arithmetic.step()
        val entry = heap.first()
        return PolynomialTerm(entry.monomial, entry.coefficient)
    }

    fun subtract(term: PolynomialTerm) {
        arithmetic.step()
        val entry = entries[term.monomial]
        val coefficient = (entry?.coefficient ?: BigFraction.ZERO) + term.coefficient.negated()
        if (entry != null) {
            if (coefficient.isZero) removeAt(entry.position)
            else entry.coefficient = coefficient
        } else if (!coefficient.isZero) {
            val added = Entry(term.monomial, coefficient, heap.size)
            entries[term.monomial] = added
            heap.add(added)
            siftUp(added.position)
        }
    }

    fun removeLeading() = removeAt(0)

    private fun removeAt(position: Int) {
        arithmetic.step()
        entries.remove(heap[position].monomial)
        val moved = heap.removeAt(heap.lastIndex)
        if (position == heap.size) return
        heap[position] = moved
        moved.position = position
        if (position > 0) {
            arithmetic.step()
            if (moved.monomial > heap[(position - 1) ushr 1].monomial) {
                siftUp(position)
                return
            }
        }
        siftDown(position)
    }

    private fun siftUp(start: Int) {
        var position = start
        val entry = heap[position]
        while (position > 0) {
            arithmetic.step()
            val parent = (position - 1) ushr 1
            if (heap[parent].monomial >= entry.monomial) break
            heap[position] = heap[parent]
            heap[position].position = position
            position = parent
        }
        heap[position] = entry
        entry.position = position
    }

    private fun siftDown(start: Int) {
        var position = start
        val entry = heap[position]
        while (position < heap.size / 2) {
            arithmetic.step()
            val left = position * 2 + 1
            val right = left + 1
            var child = left
            if (right < heap.size) {
                arithmetic.step()
                if (heap[right].monomial > heap[left].monomial) child = right
            }
            arithmetic.step()
            if (heap[child].monomial <= entry.monomial) break
            heap[position] = heap[child]
            heap[position].position = position
            position = child
        }
        heap[position] = entry
        entry.position = position
    }
}
