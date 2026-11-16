package com.eignex.klause.lp

import com.eignex.klause.factor.table.Element
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.engine.FloatLpStatus
import com.eignex.klause.lp.engine.solveLp
import com.eignex.klause.lp.relaxation.CpToLpRelaxation
import com.eignex.klause.lp.relaxation.RootDomains
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ElementLpProjectionTest {
    @Test
    fun `shared choices preserve correlations between constant properties`() {
        val problem = Problem(
            0, 3, arrayOf(IntDomain(0, 2), IntDomain(0, 1), IntDomain(0, 1)),
            arrayOf(
                Element(0, 1, longArrayOf(0, 1, 0), false, 0),
                Element(0, 2, longArrayOf(1, 0, 1), false, 0),
            ),
        )
        val relaxation = CpToLpRelaxation(
            problem, LinearObjective(intCoefficients = longArrayOf(0, 1, 1)), elementHull = true,
        ).build(RootDomains(problem))

        val solution = solveLp(relaxation.model)

        assertEquals(FloatLpStatus.OPTIMAL, solution.status)
        assertEquals(1.0, solution.objectiveValue, 1e-7)
        assertEquals(3, relaxation.colReq.count { it != null })
    }

    @Test
    fun `selector admission counts retained choices in long arrays`() {
        for (offset in listOf(-9, 0, 11, Int.MIN_VALUE, Int.MAX_VALUE)) {
            val domain = IntDomain(offset.toLong(), offset + 4095L)
                .excludeValues(LongArray(4094) { offset.toLong() + it + 1L })!!
            val factor = Element(0, 1, LongArray(4096) { it.toLong() }, false, offset)
            val problem = Problem(0, 2, arrayOf(domain, IntDomain(0, 4095)), arrayOf(factor))

            val estimate = assertNotNull(factor.estimateLpHull(RootBoxes(problem)))
            val relaxation = CpToLpRelaxation(problem, null, elementHull = true).build(RootDomains(problem))

            assertEquals(2L, estimate.cols)
            assertEquals(2, relaxation.colReq.count { it != null })
        }
    }

    @Test
    fun `over budget retained choices decline without selector allocation`() {
        val factor = Element(0, 1, LongArray(1025), false, 0)
        val problem = Problem(0, 2, arrayOf(IntDomain(0, 1024), IntDomain(0, 1)), arrayOf(factor))

        val estimate = factor.estimateLpHull(RootBoxes(problem))
        val relaxation = CpToLpRelaxation(problem, null, elementHull = true).build(RootDomains(problem))

        assertNull(estimate)
        assertEquals(0, relaxation.colReq.count { it != null })
    }

    @Test
    fun `array ranges inside an index hole decline without selector allocation`() {
        val factor = Element(0, 1, longArrayOf(1, 2, 3), false, 10)
        val domain = IntDomain(0, 100).excludeValues(LongArray(99) { it + 1L })!!
        val problem = Problem(0, 2, arrayOf(domain, IntDomain(0, 3)), arrayOf(factor))

        val estimate = factor.estimateLpHull(RootBoxes(problem))
        val relaxation = CpToLpRelaxation(problem, null, elementHull = true).build(RootDomains(problem))

        assertNull(estimate)
        assertEquals(0, relaxation.colReq.count { it != null })
    }

    @Test
    fun `different indexes retain independent choices`() {
        val problem = Problem(
            0, 4, Array(4) { IntDomain(0, 1) },
            arrayOf(
                Element(0, 2, longArrayOf(0, 1), false, 0),
                Element(1, 3, longArrayOf(1, 0), false, 0),
            ),
        )
        val relaxation = CpToLpRelaxation(
            problem, LinearObjective(intCoefficients = longArrayOf(0, 0, 1, 1)), elementHull = true,
        ).build(RootDomains(problem))

        val solution = solveLp(relaxation.model)

        assertEquals(FloatLpStatus.OPTIMAL, solution.status)
        assertEquals(0.0, solution.objectiveValue, 1e-7)
    }
}
