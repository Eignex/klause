package com.eignex.klause.meta.alns

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals

class DestroyOperatorTest {
    @Test
    fun `overlapping wide factors preserve the seeded connected neighborhood`() {
        val count = 1024
        val problem = Problem(
            count, 0, emptyArray(),
            List(32) { Clause(IntArray(count) { Lit.make(it, true) }) },
        ).bake()
        val incumbent = Sample(BooleanArray(count) { true }, LongArray(0))
        val seed = Random(17).nextInt(count)

        for (fraction in listOf(0.25, 0.5, 1.0)) {
            val freed = DestroyOperator.AdjacencyRelated.destroy(
                Random(17), problem, incumbent, LinearObjective(), fraction,
            )

            val expected = (listOf(seed) + (0 until count).filter { it != seed })
                .take((fraction * count).toInt()).sorted().toIntArray()
            assertContentEquals(expected, freed.bools)
            assertContentEquals(IntArray(0), freed.ints)
        }
    }

    @Test
    fun `disconnected neighborhoods retain both finite coordinate spaces`() {
        val problem = Problem(3, 2, Array(2) { IntDomain(0, 1) }, emptyList()).bake()
        val incumbent = Sample(BooleanArray(3), LongArray(2))

        val freed = DestroyOperator.AdjacencyRelated.destroy(
            Random(17), problem, incumbent, LinearObjective(), 1.0,
        )

        assertContentEquals(intArrayOf(0, 1, 2), freed.bools)
        assertContentEquals(intArrayOf(0, 1), freed.ints)
    }
}
