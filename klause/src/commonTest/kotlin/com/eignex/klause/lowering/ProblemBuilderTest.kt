package com.eignex.klause.lowering

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.schema.plus
import com.eignex.klause.schema.times
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProblemBuilderTest {
    @Test
    fun `the builder should build a problem with allocated columns factors and names`() {
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
        assertEquals(IntDomain(2, 5), problem.finiteIntDomains().single())
        assertEquals(1.0, problem.realUpper.single())
    }

    @Test
    fun `shared builder products should use the real namespace despite overlapping finite ids`() {
        val builder = ProblemBuilder()
        builder.newBool("b")
        builder.newInt(IntDomain(0, 1), "i")
        val x = builder.newReal(-1.0, 1.0, "x")
        val result = builder.newReal(0.0, 1.0, "result")

        builder.continuousProduct(x, x, result)
        val problem = builder.build()

        assertTrue(problem.factors.single().boolVars.isEmpty())
        assertTrue(problem.factors.single().intVars.isEmpty())
        assertContentEquals(intArrayOf(x, result), problem.factors.single().variables.reals)
    }

    @Test
    fun `unallocated real columns should decline at shared lowering`() {
        val builder = ProblemBuilder()
        builder.newInt(IntDomain(0, 1))

        assertFailsWith<IllegalArgumentException> { builder.continuousProduct(0, 0, 0) }
    }
}
