package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.AlgebraicReal
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic

internal class PolynomialRootMerge(families: List<List<AlgebraicReal>>, private val algebraic: AlgebraicArithmetic) {
    private val arithmetic = algebraic.arithmetic
    private val roots = families.map { family ->
        arithmetic.step()
        family.map { arithmetic.step(); ComparisonRoot(it, arithmetic) }
    }
    private val positions = IntArray(roots.size)
    private val heap = ArrayList<Int>()
    private var ordered = false
    private var advancePending = false
    private var previous: ComparisonRoot? = null

    init {
        for (family in roots.indices) {
            arithmetic.step()
            if (roots[family].isNotEmpty()) heap.add(family)
        }
    }

    fun next(): AlgebraicReal? {
        arithmetic.step()
        if (!ordered) {
            for (index in heap.size / 2 - 1 downTo 0) siftDown(index)
            ordered = true
        }
        if (advancePending) {
            advanceHead()
            advancePending = false
        }
        while (heap.isNotEmpty()) {
            arithmetic.step()
            val family = heap[0]
            val candidate = roots[family][positions[family]]
            val last = previous
            if (last == null || compareRoots(last, candidate) != 0) {
                arithmetic.step()
                previous = candidate
                advancePending = true
                return candidate.original
            }
            advanceHead()
        }
        arithmetic.step()
        return null
    }

    private fun advanceHead() {
        arithmetic.step()
        val family = heap[0]
        if (++positions[family] == roots[family].size) {
            val last = heap.removeAt(heap.lastIndex)
            if (heap.isEmpty()) return
            heap[0] = last
        }
        siftDown(0)
    }

    private fun siftDown(start: Int) {
        var index = start
        while (index < heap.size / 2) {
            arithmetic.step()
            val left = 2 * index + 1
            val right = left + 1
            val child = if (right < heap.size && compare(heap[right], heap[left]) < 0) right else left
            if (compare(heap[index], heap[child]) <= 0) return
            val family = heap[index]
            heap[index] = heap[child]
            heap[child] = family
            index = child
        }
    }

    private fun compare(left: Int, right: Int): Int {
        arithmetic.step()
        val comparison = compareRoots(roots[left][positions[left]], roots[right][positions[right]])
        // Equal roots retain the first projection polynomial's scalar and isolating bounds.
        return if (comparison != 0) comparison else left.compareTo(right)
    }

    private fun compareRoots(left: ComparisonRoot, right: ComparisonRoot): Int {
        arithmetic.step()
        val a = left.value
        val b = right.value
        return when {
            a === b -> 0
            a.rational != null && b.rational != null -> checkNotNull(a.rational).compareTo(checkNotNull(b.rational))
            a.upperBound <= b.lowerBound -> -1
            b.upperBound <= a.lowerBound -> 1
            else -> algebraic.compareWithRefinements(a, b, left.retain, right.retain)
        }
    }
}

private class ComparisonRoot(val original: AlgebraicReal, arithmetic: PolynomialArithmetic) {
    var value: AlgebraicReal = original
        private set
    val retain: (AlgebraicReal) -> Unit = { refined ->
        arithmetic.step()
        check(refined.definingPolynomial === original.definingPolynomial && refined.rootIndex == original.rootIndex)
        check(refined.lowerBound >= value.lowerBound && refined.upperBound <= value.upperBound)
        value = refined
    }
}
