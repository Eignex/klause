package com.eignex.klause.lowering

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.IntDomain
import kotlin.test.Test
import kotlin.test.assertEquals

class ProblemBuilderTest {

    @Test
    fun `builds a problem with allocated columns factors and names`() {
        val builder = ProblemBuilder()
        val enabled = builder.newBool("enabled")
        val required = builder.newBool("required")
        val limit = builder.newInt(IntDomain(2, 5), "limit")
        val rate = builder.newReal(0.0, 1.0, "rate")
        builder.factors += Clause(intArrayOf(Lit.make(required, true)))

        val problem = builder.build()

        assertEquals(0, enabled)
        assertEquals(1, required)
        assertEquals(0, limit)
        assertEquals(0, rate)
        assertEquals(mapOf("enabled" to enabled, "required" to required), builder.boolVarIdByName)
        assertEquals(mapOf("limit" to limit), builder.intVarIdByName)
        assertEquals(mapOf("rate" to rate), builder.realVarIdByName)
        assertEquals(1, problem.factors.size)
        assertEquals(IntDomain(2, 5), problem.requireFiniteIntDomains().single())
        assertEquals(1.0, problem.realUpper.single())
    }
}
