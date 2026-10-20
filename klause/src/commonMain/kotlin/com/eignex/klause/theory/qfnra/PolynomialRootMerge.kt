package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.AlgebraicReal

internal class PolynomialRootMerge(families: List<List<AlgebraicReal>>, private val algebraic: AlgebraicArithmetic) {
    private val arithmetic = algebraic.arithmetic
    private val roots = families.map { family ->
        arithmetic.step()
        family.map { arithmetic.step(); it }
    }
    private val positions = IntArray(roots.size)
    private val heap = ArrayList<Int>()
    private var ordered = false
    private var advancePending = false
    private var previous: AlgebraicReal? = null

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
            if (last == null || algebraic.compare(last, candidate) != 0) {
                arithmetic.step()
                previous = candidate
                advancePending = true
                return candidate
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
        val comparison = algebraic.compare(roots[left][positions[left]], roots[right][positions[right]])
        // Equal roots retain the first projection polynomial's scalar and isolating bounds.
        return if (comparison != 0) comparison else left.compareTo(right)
    }
}
