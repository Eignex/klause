package com.eignex.klause.lp.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RoundingCutSlackTest {
    @Test
    fun `rounded cuts should cover integer perturbations of equalities rows and shifted lower bounds`() {
        for (origin in listOf(-2L, 0L, 3L)) {
            val base = LpBuilder().apply {
                val x = addVar(origin, origin + 2L)
                val y = addVar(origin, origin + 2L)
                addRow(intArrayOf(x, y), longArrayOf(1L, 1L), Relation.LE, 2L * origin + 1L)
                addRow(intArrayOf(x, y), longArrayOf(1L, -2L), Relation.EQ, -origin)
            }.build(Sense.MINIMIZE)
            val authority = assertNotNull(base.authoritativeModel())
            val model = assertNotNull(LpExactState(authority.copy(rows = listOf(
                authority.row(0).copy(slack = LpRowSlack(doubleArrayOf(2.0), doubleArrayOf(4.0))),
                authority.row(1),
            ))).toWorkingModel())
            val basis = Basis(intArrayOf(0, 1), arrayOf(VarStatus.BASIC, VarStatus.BASIC,
                VarStatus.AT_LOWER, VarStatus.FIXED))
            for (mir in listOf(false, true)) {
                val cuts = integerTableauCuts(model, basis,
                    doubleArrayOf(origin + 2.0 / 3.0, origin + 1.0 / 3.0), 8, mir)
                assertTrue(cuts.isNotEmpty())
                for (policy in listOf(LpBoundSlack(1.0, 0.0), LpBoundSlack(0.0, 0.25))) {
                    val lower = origin - policy.at(origin.toDouble())
                    val upper = origin + 2L + policy.at((origin + 2L).toDouble())
                    val rowMovement = 2.0 * policy.at(4.0)
                    val equalityMovement = max(policy.absolute, policy.relative * 3.0 * abs(origin.toDouble()))
                    for (x in origin - 2L..origin + 4L) {
                        for (y in origin - 2L..origin + 4L) {
                            if (x < lower || y < lower || x > upper || y > upper ||
                                x + y > 2L * origin + 1L + rowMovement ||
                                abs(x - 2L * y + origin) > equalityMovement
                            ) {
                                continue
                            }
                            for (cut in cuts) {
                                val point = longArrayOf(x, y)
                                val activity = cut.cols.indices.sumOf { cut.coeffs[it] * point[cut.cols[it]] }
                                val movement = assertNotNull(cut.slack).at(policy)

                                assertTrue(activity >= cut.rhs - movement,
                                    "mir=$mir origin=$origin point=($x,$y) " +
                                        "activity=$activity rhs=${cut.rhs} slack=$movement")
                            }
                        }
                    }
                }
            }
        }
    }
}
