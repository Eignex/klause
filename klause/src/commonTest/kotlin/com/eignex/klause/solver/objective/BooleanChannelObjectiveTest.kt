package com.eignex.klause.solver.objective

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.bake
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BooleanChannelObjectiveTest {
    @Test
    fun `fixed integer terms contribute to the Boolean objective constant`() {
        val problem = Problem(
            1, 3, arrayOf(IntDomain(0, 1), IntDomain(-2, -2), IntDomain(1, 3)),
            listOf(
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 1),
                Linear(intArrayOf(2, 3, -1), intArrayOf(0, 1, 2), LinearOp.EQ, -7),
            ),
        )
        val objective = LinearObjective(intCoefficients = longArrayOf(0, 0, 1), constant = 4)

        val projected = checkNotNull(objective.throughBooleanChannels(problem))
        val values = BacktrackSolver(problem.bake()).enumerate(BacktrackParams(randomSeed = 0L))
            .map { it.bools.single() to projected.evaluateExact(it).toString() }.toSet()

        assertEquals(setOf(false to "5", true to "7"), values)
    }

    @Test
    fun `a signed affine objective agrees for either channel polarity and objective direction`() {
        for (channelBound in listOf(0, 1)) {
            for (direction in listOf(-1L, 1L)) {
                val problem = Problem(
                    2, 3, arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(-2, 3)),
                    listOf(
                        ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, channelBound),
                        ReifiedLinear(1, intArrayOf(1), intArrayOf(1), LinearOp.EQ, 1),
                        Linear(longArrayOf(2, -3, -1), intArrayOf(0, 1, 2), LinearOp.EQ, -1),
                    ),
                )
                val objective = LinearObjective(intCoefficients = longArrayOf(0, 0, direction), constant = 7)

                val projected = checkNotNull(objective.throughBooleanChannels(problem))
                val assignments = BacktrackSolver(problem.bake()).enumerate(BacktrackParams(randomSeed = 0L)).toList()

                assertEquals(4, assignments.size)
                for (sample in assignments) {
                    assertEquals(objective.evaluateExact(sample), projected.evaluateExact(sample))
                }
            }
        }
    }

    @Test
    fun `a guarded equality cannot define an unconditional objective`() {
        val problem = Problem(
            2, 2, arrayOf(IntDomain(0, 1), IntDomain(0, 1)),
            listOf(
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 1),
                ReifiedLinear(1, intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 0),
            ),
        )

        assertNull(problem.minimizeInt(1).throughBooleanChannels(problem))
    }

    @Test
    fun `an equality indicator over a nonbinary integer cannot carry its value`() {
        val problem = Problem(
            1, 2, arrayOf(IntDomain(0, 2), IntDomain(0, 2)),
            listOf(
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 1),
                Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 0),
            ),
        )

        assertNull(problem.minimizeInt(1).throughBooleanChannels(problem))
    }

    @Test
    fun `a multi variable reified reader keeps the integer objective`() {
        val problem = Problem(
            3, 3, arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 2)),
            listOf(
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 1),
                ReifiedLinear(1, intArrayOf(1), intArrayOf(1), LinearOp.EQ, 1),
                ReifiedLinear(2, intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 1),
                Linear(intArrayOf(1, 1, -1), intArrayOf(0, 1, 2), LinearOp.EQ, 0),
            ),
        )

        assertNull(problem.minimizeInt(2).throughBooleanChannels(problem))
    }

    @Test
    fun `an objective whose Boolean range exceeds signed long stays in its source representation`() {
        val problem = Problem(
            2, 3, arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, Long.MAX_VALUE)),
            listOf(
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 1),
                ReifiedLinear(1, intArrayOf(1), intArrayOf(1), LinearOp.EQ, 1),
                Linear(longArrayOf(Long.MAX_VALUE, 1, -1), intArrayOf(0, 1, 2), LinearOp.EQ, 0),
            ),
        )

        assertNull(problem.minimizeInt(2).throughBooleanChannels(problem))
    }
}
