package com.eignex.klause.bench.metric

import kotlin.test.Test
import kotlin.test.assertEquals

class SecondReferencesTest {
    @Test
    fun `kissat's status line is its verdict, a decision being a proof`() {
        fun verdict(out: String) = KissatReference.parse(out, 100, emptyList()).let { it.feasible to it.proven }

        assertEquals(
            listOf(true to true, false to true, null to false),
            listOf(verdict("s SATISFIABLE\n"), verdict("c x\ns UNSATISFIABLE\n"), verdict("s UNKNOWN\n")),
        )
    }
}
