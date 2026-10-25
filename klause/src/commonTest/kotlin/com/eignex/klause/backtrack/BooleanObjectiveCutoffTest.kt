package com.eignex.klause.backtrack

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BooleanObjectiveCutoffTest {
    @Test
    fun `Boolean cutoffs should admit integer coordinates and accept exactly improving costs`() {
        val problem = Problem(2, 1, arrayOf(IntDomain(0, 2)), emptyList())
        val objective = LinearObjective(longArrayOf(-3, 5), constant = 7)

        for (incumbent in listOf(4L, 7L, 9L, 12L)) {
            val cutoff = checkNotNull(booleanObjectiveCutoff(problem, objective, BigFraction.ofLong(incumbent)))
            for (mask in 0..3) {
                val sample = Sample(BooleanArray(2) { mask and (1 shl it) != 0 }, longArrayOf(2))
                var activity = 0L
                for (index in cutoff.literals.indices) {
                    val literal = cutoff.literals[index]
                    if (Lit.evaluate(literal, sample.boolValue(Lit.variable(literal)))) activity += cutoff.weights[index]
                }
                assertEquals(objective.evaluateLong(sample) < incumbent, activity >= cutoff.degree)
            }
        }
    }

    @Test
    fun `a remaining integer cost should withhold a Boolean cutoff`() {
        val problem = Problem(1, 1, arrayOf(IntDomain(0, 2)), emptyList())
        val objective = LinearObjective(longArrayOf(1), longArrayOf(1))

        val cutoff = booleanObjectiveCutoff(problem, objective, BigFraction.ofLong(2))

        assertNull(cutoff)
    }

    @Test
    fun `cutoffs outside learned word arithmetic should decline exactly`() {
        val problem = Problem(2, 0, emptyArray(), emptyList())
        val objectives = listOf(
            LinearObjective(longArrayOf(Long.MIN_VALUE)),
            LinearObjective(longArrayOf(Long.MAX_VALUE, 1)),
            LinearObjective(longArrayOf(1), constant = Long.MAX_VALUE),
        )

        for (objective in objectives) {
            assertNull(booleanObjectiveCutoff(problem, objective, BigFraction.ofLong(Long.MIN_VALUE)))
        }
    }
}
