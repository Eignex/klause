package com.eignex.klause.factor

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import kotlin.test.Test
import kotlin.test.assertEquals

class FactorPropagationOracleTest {

    @Test
    fun `source assignments should carry across integer coordinates in declaration order`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(1, 2), IntDomain(4, 5)),
            factors = emptyList(),
        )

        val witnesses = FactorPropagationOracle.sourceAssignments(problem).toList()

        assertEquals(
            listOf(1L to 4L, 2L to 4L, 1L to 5L, 2L to 5L),
            witnesses.map { it.intValue(0) to it.intValue(1) },
        )
    }

    @Test
    fun `relevant witnesses should retain independent source assignments`() {
        val problem = Problem(
            numBoolVars = 1,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 2)),
            factors = listOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 0)),
        )

        val witnesses = FactorPropagationOracle.enumerateSat(problem, "witness ownership", null) { true }

        assertEquals(
            listOf(false to 0L, true to 0L, false to 1L, true to 1L, false to 2L, true to 2L),
            witnesses.map { it.bools[0] to it.ints[0] },
        )
    }
}
