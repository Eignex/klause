package com.eignex.klause.bench.metric

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class SolverInvocationTest {

    @Test
    fun `a subprocess killed by the hard timeout is recorded as an undecided run`() {
        val r = SolverInvocation.invoke(listOf("sleep", "5"), SolverInvocation.Dialect.MINIZINC, hardTimeoutMs = 100)
        assertNull(r.feasible)
        assertFalse(r.proven)
        assertEquals("hard-timeout", r.stats["killed"])
    }

    @Test
    fun `a subprocess that fails without being killed still raises`() {
        assertFailsWith<IllegalStateException> {
            SolverInvocation.invoke(listOf("false"), SolverInvocation.Dialect.MINIZINC)
        }
    }
}
