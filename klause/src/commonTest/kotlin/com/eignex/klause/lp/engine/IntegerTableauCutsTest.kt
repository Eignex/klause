package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IntegerTableauCutsTest {
    @Test
    fun `retained tableau cuts match fresh cuts through nested lower bounds and rollback`() {
        for (ieee in listOf(false, true)) {
            val source = assertNotNull(LpBuilder().apply {
                val x = addVar(3L, 9L, cost = -1L)
                if (ieee) addRealRow(intArrayOf(x), doubleArrayOf(2.0), Relation.LE, 11.0)
                else addRow(intArrayOf(x), longArrayOf(2L), Relation.LE, 11L)
            }.build(Sense.MINIMIZE).authoritativeModel())
            val trail = LpBoundTrail(source)
            assertTrue(trail.push())
            assertTrue(trail.assertBound(0, false, ExactLpSide(ExactLpNumber.of(1L)), 7L))
            assertTrue(trail.push())
            assertTrue(trail.assertBound(0, false, ExactLpSide(ExactLpNumber.of(2L)), 8L))
            for ((depth, lower) in listOf(2 to 5L, 1 to 4L, 0 to 3L)) {
                assertTrue(trail.pop(depth))
                val model = assertNotNull(trail.state.toWorkingModel())
                val fresh = LpBuilder().apply {
                    val x = addVar(lower, 9L, cost = -1L)
                    addRow(intArrayOf(x), longArrayOf(2L), Relation.LE, 11L)
                }.build(Sense.MINIMIZE)
                val basis = Basis(intArrayOf(0), arrayOf(VarStatus.BASIC, VarStatus.AT_LOWER))
                for (mir in listOf(false, true)) {

                    val cuts = integerTableauCuts(model, basis, doubleArrayOf(5.5), 8, mir)
                    val expected = integerTableauCuts(fresh, basis, doubleArrayOf(5.5), 8, mir)

                    assertTrue(cuts.isNotEmpty())
                    assertEquals(expected.map { it.key() }, cuts.map { it.key() })
                    assertEquals(BigFraction.ofLong(3L), model.exactShift(0))
                    for (cut in cuts) {
                        val proof = assertNotNull(cut.tableau)
                        assertEquals(lower, proof.columns.single().lower)
                        assertEquals(BigFraction.ofLong(11L), proof.rows.single().rhs)
                        for (x in lower..5L) assertTrue(cut.coeffs.single() * x >= cut.rhs)
                        assertTrue(cut.coeffs.single() * 5.5 < cut.rhs)
                    }
                }
            }
        }
    }

    @Test
    fun `retained tableau cuts exclude inactive rows from their rounding proof across pop`() {
        val source = assertNotNull(LpBuilder().apply {
            val x = addVar(0L, 5L)
            addRow(intArrayOf(x), longArrayOf(2L), Relation.LE, 3L)
        }.build(Sense.MINIMIZE).authoritativeModel())
        val trail = LpBoundTrail(source)
        assertTrue(trail.push())
        assertTrue(trail.suspend(setOf(0L)))
        assertTrue(trail.append(
            LpScopedRow(
                1L, listOf(0 to ExactLpNumber.of(2L)), ExactLpNumber.of(5L),
                ExactLpColumn(ExactLpBounds(ExactLpSide(ExactLpNumber.of(0L)))),
            ),
            true,
        ))
        for ((depth, row) in listOf(1 to 1, 0 to 0)) {
            assertTrue(trail.pop(depth))
            val model = assertNotNull(trail.state.toWorkingModel())
            val basis = Basis(
                if (row == 1) intArrayOf(model.slackCol(0), 0) else intArrayOf(0, model.slackCol(1)),
                Array(model.numVars) { VarStatus.AT_LOWER },
            )
            val primal = if (row == 1) 2.5 else 1.5

            val cuts = integerTableauCuts(model, basis, doubleArrayOf(primal), 8, mir = false)

            assertTrue(cuts.isNotEmpty())
            for (cut in cuts) {
                assertEquals(listOf(row), assertNotNull(cut.tableau).rows.map { it.index })
                for (x in 0L..primal.toLong()) assertTrue(cut.coeffs.single() * x >= cut.rhs)
                assertTrue(cut.coeffs.single() * primal < cut.rhs)
            }
        }
    }

    @Test
    fun `tableau candidates decline malformed basis and nonfinite primal values`() {
        val model = LpBuilder().apply {
            val x = addVar(0L, 5L)
            addRow(intArrayOf(x), longArrayOf(2L), Relation.LE, 3L)
        }.build(Sense.MINIMIZE)
        for ((headings, primal) in listOf(
            intArrayOf() to 1.5, intArrayOf(-1) to 1.5, intArrayOf(2) to 1.5,
            intArrayOf(0) to Double.NaN, intArrayOf(0) to Double.POSITIVE_INFINITY,
        )) {
            val basis = Basis(headings, Array(model.numVars) { VarStatus.AT_LOWER })

            assertTrue(integerTableauCuts(model, basis, doubleArrayOf(primal), 8, mir = false).isEmpty())
        }
    }

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
    fun `continuous and synthetic lower bounds decline at the tableau entry point`() {
        for (continuous in listOf(false, true)) {
            val builder = LpBuilder()
            if (continuous) builder.addRealVar(0.0, 3.0) else builder.addFreeVar(null, null)
            builder.addRow(mapOf(0 to 2L), Relation.LE, 3)
            val model = builder.build(Sense.MINIMIZE)

            val cuts = integerTableauCuts(
                model,
                Basis(intArrayOf(0), Array(2) { VarStatus.AT_LOWER }),
                doubleArrayOf(1.5),
                1,
                mir = false,
            )

            assertTrue(cuts.isEmpty())
        }
    }
}
