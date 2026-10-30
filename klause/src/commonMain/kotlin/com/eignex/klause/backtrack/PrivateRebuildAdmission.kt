package com.eignex.klause.backtrack

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Problem
import com.eignex.klause.util.Cancellation

internal object PrivateRebuildAdmission {
    fun permits(problem: Problem, cancellation: Cancellation): Boolean? {
        val columns = problem.numBoolVars
        cancellation.charge(columns * 12L)
        if (cancellation()) return null
        val occurrences = IntArray(columns * 2)
        val widths = IntArray(columns * 2)
        val volumes = IntArray(columns * 2)
        for (factor in problem.factors) {
            val clause = factor as Clause
            cancellation.charge(clause.literals.size * 3L + 1L)
            if (cancellation()) return null
            if (clause.tautological) continue
            for (literal in clause.literals) {
                occurrences[literal] = minOf(4, occurrences[literal] + 1)
                widths[literal] = maxOf(widths[literal], clause.literals.size)
                volumes[literal] = minOf(9, volumes[literal] + minOf(9, clause.literals.size))
            }
        }
        for (column in 0 until columns) {
            cancellation.charge(6L)
            if (cancellation()) return null
            if (nonGrowing(occurrences, widths, volumes, 2 * column)) return true
        }
        return false
    }

    private fun nonGrowing(occurrences: IntArray, widths: IntArray, volumes: IntArray, positive: Int): Boolean {
        val negative = positive + 1
        val p = occurrences[positive]
        val n = occurrences[negative]
        if (p == 0 || n == 0) return p + n > 0
        // One positive parent of width w has non-growing raw volume iff n * (w - 2) <= w.
        // With both counts at least two, raw clause count admits only 2x2, with total width at most eight.
        return when {
            p == 1 -> singleParent(widths[positive], n)
            n == 1 -> singleParent(widths[negative], p)
            p == 2 && n == 2 -> volumes[positive] + volumes[negative] <= 8
            else -> false
        }
    }

    private fun singleParent(width: Int, otherParents: Int): Boolean =
        width <= 2 || otherParents <= width / (width - 2)
}
