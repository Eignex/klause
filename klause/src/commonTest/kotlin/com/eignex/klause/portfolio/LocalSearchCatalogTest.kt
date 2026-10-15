package com.eignex.klause.portfolio

import com.eignex.klause.solver.ProblemClass
import kotlin.test.Test
import kotlin.test.assertEquals

class LocalSearchCatalogTest {

    @Test
    fun `clausal satisfaction should start with the fixed break walk`() {
        val recipes = LocalSearchCatalog.diverse(Kind.CSP, 1, ProblemClass.Sat)

        assertEquals("probsat/plain", recipes.single().label)
    }

    @Test
    fun `clausal optimization should retain its general local search leader`() {
        val recipes = LocalSearchCatalog.diverse(Kind.COP, 1, ProblemClass.Sat)

        assertEquals("cbls/fixed", recipes.single().label)
    }
}
