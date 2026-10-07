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

    @Test
    fun `highs reads a MIP summary, an optimum proven and a time-limited bound a witness`() {
        val optimal = "  Status            Optimal\n  Primal bound      3089\n  Dual bound        3089\n"
        val limited = "  Status            Time limit reached\n  Primal bound      428\n  Dual bound        409\n"
        val none = "  Status            Time limit reached\n  Primal bound      inf\n"

        val results = listOf(
            optimal,
            limited,
            none,
        ).map { HighsReference.parse(it, 100, emptyList(), maximize = false) }

        assertEquals(listOf(3089.0, 428.0, null), results.map { it.objective })
        assertEquals(listOf(true, false, false), results.map { it.proven })
        assertEquals(listOf(true, true, null), results.map { it.feasible })
    }

    @Test
    fun `highs reads an LP summary and an infeasible model`() {
        val lp = HighsReference.parse(
            "Model status        : Optimal\nObjective value     :  -4.6475314286e+02\n",
            10,
            emptyList(),
            false,
        )
        val infeasible = HighsReference.parse(
            "  Status            Infeasible\n  Primal bound      inf\n",
            10,
            emptyList(),
            false,
        )

        assertEquals(listOf(-464.75314286, true), listOf(lp.objective, lp.proven))
        assertEquals(listOf(false, true), listOf(infeasible.feasible, infeasible.proven))
    }
}
