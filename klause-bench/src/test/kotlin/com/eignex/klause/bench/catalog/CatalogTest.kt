package com.eignex.klause.bench.catalog

import com.eignex.klause.bench.runner.InProcessRunner
import com.eignex.klause.bench.source.CorpusSelection
import com.eignex.klause.bench.source.CorpusSelection.Selection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CatalogTest {

    private fun ref(suite: String, name: String): ProblemRef = Catalog.suite(suite).problems.first { it.name == name }

    @Test
    fun `xcsp3-core maximization should resolve its sense with a minimise-canonical objective`() {
        val resolved = InProcessRunner.resolve(ref("xcsp3-core", "sum-opt-tiny"))

        assertTrue(resolved.maximize)
        assertEquals(listOf(-3L, -2L, -1L), resolved.objective?.intCoefficients?.toList())
    }

    @Test
    fun `handwritten-core problems should all build in code`() {
        for (ref in Catalog.suite("handwritten-core").problems) {
            assertEquals(Format.IN_CODE, ref.format)
            assertTrue(InProcessRunner.supports(ref))
            InProcessRunner.resolve(ref)
        }
    }

    @Test
    fun `catalogue admission should fill caps from valid identities before provider sampling`() {
        val sources = listOf(ref("dimacs-core", "php4"), ref("dimacs-core", "php3"))
            .map { it.copy(family = "pigeonhole") }
        val dynamic = DynamicSuite("admission", "admission", admit = { it.name != "php4" }) { selection ->
            CorpusSelection.applySelectionBy(sources, selection) { it.family.orEmpty() }
        }

        val selected = Catalog.selected(dynamic, Selection(perFamily = 1, maxInstances = 1))

        assertEquals(listOf("php3"), selected.problems.map { it.name })
    }

    @Test
    fun `raw identity discovery should bypass admission and provider caps`() {
        val sources = listOf(ref("dimacs-core", "php4"), ref("dimacs-core", "php3"))
            .map { it.copy(family = "pigeonhole") }
        val dynamic = DynamicSuite("admission", "admission", defaultPerFamily = 1, admit = { false }) { selection ->
            CorpusSelection.applySelectionBy(sources, selection) { it.family.orEmpty() }
        }

        val discovered = Catalog.discovered(dynamic)

        assertEquals(listOf("php4", "php3"), discovered.map { it.name })
    }
}
