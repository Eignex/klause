package com.eignex.klause.lp.engine

import kotlin.test.Test
import kotlin.test.assertTrue

class IntegerTableauCutsTest {
    @Test
    fun `permuted structural and slack slots produce source valid separating cuts`() {
        val builder = LpBuilder()
        val x = builder.addVar(0, 5)
        val y = builder.addVar(0, 2)
        builder.addRow(mapOf(x to 2L), Relation.LE, 3)
        builder.addRow(mapOf(y to 1L), Relation.LE, 2)
        val model = builder.build(Sense.MINIMIZE)
        val primal = doubleArrayOf(1.5, 0.0)
        for (headings in listOf(intArrayOf(x, model.slackCol(1)), intArrayOf(model.slackCol(1), x))) {
            val basis = Basis(headings, Array(model.n + model.m) { VarStatus.AT_LOWER })
            for (mir in listOf(false, true)) {
                val cuts = integerTableauCuts(model, basis, primal, 8, mir)

                assertTrue(cuts.isNotEmpty())
                assertTrue(
                    cuts.any { cut -> cut.cols.indices.sumOf { cut.coeffs[it] * primal[cut.cols[it]] } < cut.rhs },
                )
                for (xi in 0L..1L) {
                    for (yi in 0L..2L) {
                        val point = longArrayOf(xi, yi)
                        for (cut in cuts) {
                            val lhs = cut.cols.indices.sumOf { cut.coeffs[it] * point[cut.cols[it]] }
                            assertTrue(lhs >= cut.rhs)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `dependent candidate basis declines without producing cuts`() {
        val builder = LpBuilder()
        val x = builder.addVar(0, 5)
        builder.addRow(mapOf(x to 2L), Relation.LE, 3)
        builder.addRow(mapOf(x to 1L), Relation.LE, 2)
        val model = builder.build(Sense.MINIMIZE)
        val basis = Basis(intArrayOf(x, x), Array(3) { VarStatus.AT_LOWER })

        val cuts = integerTableauCuts(model, basis, doubleArrayOf(1.5), 8, mir = false)

        assertTrue(cuts.isEmpty())
    }

    @Test
    fun `unrepresentable candidate transpose solve declines without producing cuts`() {
        val builder = LpBuilder()
        val n = 22
        repeat(n) { builder.addVar(0, 1) }
        for (i in 0 until n) {
            val row = mutableMapOf(i to 1L)
            if (i + 1 < n) row[i + 1] = Long.MAX_VALUE / 8
            builder.addRow(row, Relation.LE, 1)
        }
        val model = builder.build(Sense.MINIMIZE)
        val basis = Basis(IntArray(n) { it }, Array(2 * n) { VarStatus.AT_LOWER })

        val cuts = integerTableauCuts(model, basis, DoubleArray(n) { 0.5 }, 8, mir = false)

        assertTrue(cuts.isEmpty())
    }
}
