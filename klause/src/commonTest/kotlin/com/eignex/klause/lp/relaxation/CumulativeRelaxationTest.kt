package com.eignex.klause.lp.relaxation

import com.eignex.klause.factor.arithmetic.ArrayMinMax
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.engine.FloatLpStatus
import com.eignex.klause.lp.engine.solveLp
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CumulativeRelaxationTest {

    private val eps = 1e-6

    private fun makespanProblem(
        starts: Array<IntDomain>,
        durations: LongArray,
        resources: LongArray,
        capacity: Long,
        horizon: Int,
        disjunctive: Boolean = false,
    ): Problem {
        val n = starts.size
        val makespan = n
        val domains = Array(n + 1) { if (it < n) starts[it] else IntDomain(0, horizon.toLong()) }
        val factors = ArrayList<Factor>()
        for (i in 0 until n) {
            factors.add(Linear(longArrayOf(1, -1), intArrayOf(makespan, i), LinearOp.GE, durations[i]))
        }
        factors.add(
            if (disjunctive) {
                Cumulative.unary(IntArray(n) { it }, durations)
            } else {
                Cumulative(IntArray(n) { it }, durations, resources, capacity)
            },
        )
        return Problem(0, n + 1, domains, factors.toTypedArray())
    }

    private fun makespanBound(problem: Problem, makespanVar: Int, cumulative: Boolean): Double {
        val obj = LinearObjective(intCoefficients = LongArray(problem.numIntVars) { if (it == makespanVar) 1L else 0L })
        val relaxation = CpToLpRelaxation(problem, obj, cumulative = cumulative).build(PropagationSession(problem))
        val sol = solveLp(relaxation.model)
        assertEquals(FloatLpStatus.OPTIMAL, sol.status)
        return sol.objectiveValue
    }

    @Test
    fun `area bound should prove the disjunctive makespan`() {
        val p = makespanProblem(
            starts = Array(3) { IntDomain(0, 20) },
            durations = longArrayOf(3, 3, 3),
            resources = longArrayOf(1, 1, 1),
            capacity = 1,
            horizon = 20,
        )
        assertEquals(3.0, makespanBound(p, makespanVar = 3, cumulative = false), eps)
        assertEquals(9.0, makespanBound(p, makespanVar = 3, cumulative = true), eps)
    }

    @Test
    fun `energetic windowing should beat the whole-horizon area`() {
        val p = makespanProblem(
            starts = arrayOf(IntDomain(0, 20), IntDomain(5, 20)),
            durations = longArrayOf(2, 2),
            resources = longArrayOf(1, 1),
            capacity = 1,
            horizon = 20,
        )
        assertEquals(7.0, makespanBound(p, makespanVar = 2, cumulative = true), eps)
    }

    @Test
    fun `relaxation should recognise a makespan defined as array max of end variables`() {
        val domains = arrayOf(
            IntDomain(0, 20),
            IntDomain(0, 20),
            IntDomain(0, 20),
            IntDomain(0, 20),
            IntDomain(0, 20),
        )
        val factors = arrayOf<Factor>(
            Linear(intArrayOf(1, -1), intArrayOf(2, 0), LinearOp.EQ, 2),
            Linear(intArrayOf(1, -1), intArrayOf(3, 1), LinearOp.EQ, 2),
            ArrayMinMax(result = 4, xs = intArrayOf(2, 3), max = true),
            Cumulative(intArrayOf(0, 1), longArrayOf(2, 2), longArrayOf(1, 1), capacity = 1),
        )
        val p = Problem(0, 5, domains, factors)
        assertEquals(4.0, makespanBound(p, makespanVar = 4, cumulative = true), eps)
    }

    @Test
    fun `relaxation should not apply without a makespan link`() {
        val p = Problem(
            0,
            3,
            Array(3) { IntDomain(0, 20) },
            arrayOf<Factor>(Cumulative(intArrayOf(0, 1, 2), longArrayOf(3, 3, 3), longArrayOf(1, 1, 1), 1)),
        )
        assertTrue(!CumulativeRelaxation(p).applicable)
    }

    private fun variableCapacityProblem(openCapacityAbove: Boolean): Problem = Problem(
        numBoolVars = 0,
        numIntVars = 4,
        intDomains = arrayOf(IntDomain(0, 20), IntDomain(0, 20), IntDomain(0, 20), IntDomain(1, 1)),
        factors = arrayOf<Factor>(
            Linear(intArrayOf(1, -1), intArrayOf(2, 0), LinearOp.GE, 2),
            Linear(intArrayOf(1, -1), intArrayOf(2, 1), LinearOp.GE, 2),
            Cumulative(
                starts = intArrayOf(0, 1),
                durations = longArrayOf(2, 2),
                resources = longArrayOf(1, 1),
                capacity = 1,
                capacityVar = 3,
            ),
        ),
        openIntHi = booleanArrayOf(false, false, false, openCapacityAbove),
    )

    @Test
    fun `relaxation should not apply when the capacity variable is open above`() {
        assertTrue(CumulativeRelaxation(variableCapacityProblem(openCapacityAbove = false)).applicable)
        assertTrue(!CumulativeRelaxation(variableCapacityProblem(openCapacityAbove = true)).applicable)
    }

    @Test
    fun `relaxation should not apply with variable durations`() {
        val p = Problem(
            0,
            5,
            Array(5) { IntDomain(0, 20) },
            arrayOf<Factor>(
                Linear(intArrayOf(1, -1), intArrayOf(4, 0), LinearOp.GE, 0),
                Linear(intArrayOf(1, -1), intArrayOf(4, 1), LinearOp.GE, 0),
                Cumulative(
                    starts = intArrayOf(0, 1),
                    durations = longArrayOf(2, 2),
                    resources = longArrayOf(1, 1),
                    capacity = 1,
                    durationVars = intArrayOf(2, 3),
                ),
            ),
        )
        assertTrue(!CumulativeRelaxation(p).applicable)
    }
}
