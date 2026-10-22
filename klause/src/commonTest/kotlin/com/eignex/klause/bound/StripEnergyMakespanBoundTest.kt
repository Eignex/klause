package com.eignex.klause.bound

import com.eignex.klause.factor.arithmetic.ArrayMinMax
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.factor.scheduling.Diffn
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.PropagationSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StripEnergyMakespanBoundTest {

    private fun objectiveOf(n: Int): Int = 2 * n + 2

    /**
     * [n] additions of [duration] on a diffn whose lanes are `1..lanes`: starts `0 until n`, lanes
     * `n until 2n`, the two last additions' ends `2n` and `2n + 1`, and the objective over those ends.
     */
    @Suppress("LongParameterList")
    private fun additions(
        n: Int,
        lanes: Long,
        duration: Long = 1L,
        precedences: Boolean = true,
        endMax: Boolean = true,
        openLanes: Boolean = false,
    ): Problem {
        val vars = 2 * n + 3
        val factors = ArrayList<Factor>()
        factors.add(Diffn(IntArray(n) { it }, IntArray(n) { n + it }, LongArray(n) { duration }, LongArray(n) { 1L }))
        if (precedences) {
            for (i in 0 until n - 2) {
                factors.add(Linear(longArrayOf(1, -1), intArrayOf(n - 1 - i % 2, i), LinearOp.GE, duration))
            }
        }
        factors.add(Linear(longArrayOf(1, -1), intArrayOf(2 * n, n - 2), LinearOp.EQ, duration))
        factors.add(Linear(longArrayOf(1, -1), intArrayOf(2 * n + 1, n - 1), LinearOp.EQ, duration))
        factors.add(ArrayMinMax(result = objectiveOf(n), xs = intArrayOf(2 * n, 2 * n + 1), max = endMax))
        return Problem(
            numBoolVars = 0,
            numIntVars = vars,
            intDomains = Array(vars) { if (it in n until 2 * n) IntDomain(1L, lanes) else IntDomain(0L, 1000L) },
            factors = factors.toTypedArray(),
            openIntHi = if (openLanes) BooleanArray(vars) { it in n until 2 * n } else null,
        )
    }

    /** Unit tasks `0 until n` on a cumulative, each with `objective − start ≥ 1`; the objective is `n`. */
    private fun cumulative(n: Int, capacity: Long, openCapacityAbove: Boolean = false): Problem {
        val factors = ArrayList<Factor>()
        val unit = LongArray(n) { 1L }
        factors.add(Cumulative(IntArray(n) { it }, unit, unit.copyOf(), capacity, capacityVar = n + 1))
        for (i in 0 until n) factors.add(Linear(longArrayOf(1, -1), intArrayOf(n, i), LinearOp.GE, 1L))
        return Problem(
            numBoolVars = 0,
            numIntVars = n + 2,
            intDomains = Array(n + 2) { if (it == n + 1) IntDomain(capacity, capacity) else IntDomain(0L, 100L) },
            factors = factors.toTypedArray(),
            openIntHi = BooleanArray(n + 2) { it == n + 1 && openCapacityAbove },
        )
    }

    private fun floorValue(p: Problem, objective: Int): Long? =
        StripEnergyMakespanBound(p, objective).floor(PropagationSession(p))?.value

    @Test
    fun `floor should fill the lanes with additions linked through precedences`() {
        for ((lanes, expected) in listOf(1L to 32L, 2L to 16L, 3L to 11L)) {
            assertEquals(expected, floorValue(additions(32, lanes), objectiveOf(32)), "lanes=$lanes")
        }
    }

    @Test
    fun `floor should weigh additions by their duration`() {
        assertEquals(16L, floorValue(additions(8, lanes = 1L, duration = 2L), objectiveOf(8)))
    }

    @Test
    fun `floor should count only additions with a verified link to the objective`() {
        assertEquals(2L, floorValue(additions(32, lanes = 1L, precedences = false), objectiveOf(32)))
    }

    @Test
    fun `bound should not apply when the objective is a minimum of the ends`() {
        assertFalse(StripEnergyMakespanBound(additions(32, lanes = 1L, endMax = false), objectiveOf(32)).applicable)
    }

    @Test
    fun `bound should not apply when the lanes are open above`() {
        assertFalse(StripEnergyMakespanBound(additions(32, lanes = 1L, openLanes = true), objectiveOf(32)).applicable)
    }

    @Test
    fun `floor should not count a task whose link is shorter than its duration`() {
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 5,
            intDomains = arrayOf(
                IntDomain(0L, 100L), IntDomain(0L, 100L), IntDomain(0L, 0L), IntDomain(0L, 0L), IntDomain(0L, 100L),
            ),
            factors = arrayOf(
                Diffn(intArrayOf(0, 1), intArrayOf(2, 3), longArrayOf(2, 2), longArrayOf(1, 1)),
                Linear(longArrayOf(1, -1), intArrayOf(4, 0), LinearOp.GE, 2L),
                Linear(longArrayOf(1, -1), intArrayOf(4, 1), LinearOp.GE, 1L),
            ),
        )
        assertEquals(2L, floorValue(p, objective = 4))
    }

    @Test
    fun `floor should divide cumulative energy by the declared capacity`() {
        assertEquals(3L, floorValue(cumulative(6, capacity = 2L), objective = 6))
    }

    @Test
    fun `bound should not apply when the cumulative capacity is open above`() {
        assertFalse(StripEnergyMakespanBound(cumulative(6, capacity = 2L, openCapacityAbove = true), 6).applicable)
    }

    @Test
    fun `floor reason should negate the start and lane bounds it reads`() {
        val p = additions(4, lanes = 2L)
        val session = PropagationSession(p)
        val expected = buildSet {
            for (t in 0 until 4) add(session.boundGeLit(t, session.intDomain(t).min, positive = false))
            for (y in 4 until 8) {
                add(session.boundGeLit(y, 1L, positive = false))
                add(session.boundLeLit(y, 2L, positive = false))
            }
        }

        val floor = assertNotNull(StripEnergyMakespanBound(p, objectiveOf(4)).floor(session))

        assertEquals(expected, floor.reason.toSet())
    }

    @Test
    fun `floor reason literals should all be false under the bounds they negate`() {
        val p = additions(4, lanes = 2L)
        val session = PropagationSession(p)

        val floor = assertNotNull(StripEnergyMakespanBound(p, objectiveOf(4)).floor(session))

        assertTrue(floor.reason.all { session.litTruth(it) == false })
    }
}
