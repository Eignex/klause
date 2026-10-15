package com.eignex.klause.bench.metric

import kotlin.test.Test
import kotlin.test.assertEquals

class KissatReferenceTest {
    @Test
    fun `kissat status line should be its verdict with a decision as proof`() {
        fun verdict(out: String) = KissatReference.parse(out, 100, emptyList()).let { it.feasible to it.proven }

        assertEquals(
            listOf(true to true, false to true, null to false),
            listOf(verdict("s SATISFIABLE\n"), verdict("c x\ns UNSATISFIABLE\n"), verdict("s UNKNOWN\n")),
        )
    }
}
