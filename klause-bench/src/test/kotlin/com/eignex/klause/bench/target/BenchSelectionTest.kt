package com.eignex.klause.bench.target

import com.eignex.klause.bench.catalog.Category
import com.eignex.klause.bench.catalog.Expected
import com.eignex.klause.bench.catalog.Format
import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.catalog.corpus
import com.eignex.klause.bench.source.CorpusSelection
import com.eignex.klause.bench.source.CorpusSelection.Selection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BenchSelectionTest {
    private fun problem(suite: String, name: String, family: String? = null, default: Int? = null) = SelectedProblem(
        suite,
        ProblemRef(name, Format.XCSP3, corpus(name), Category.OPTIMIZATION, Expected.Unknown, family = family),
        default,
    )

    private fun names(selected: List<SelectedProblem>) = selected.map { it.ref.name }

    @Test
    fun `a per-family cap counts the provider's family rather than the name prefix`() {
        val candidates = listOf(
            problem("xcsp3-cop", "Rack-1", family = "Rack"),
            problem("xcsp3-cop", "Rack-2", family = "Rack"),
            problem("xcsp3-cop", "Mario-1", family = "Mario"),
        )

        val selected = BenchSelection.capFamilies(candidates, perFamily = 1, seed = null)

        assertEquals(listOf("Rack-1", "Mario-1"), names(selected))
    }

    @Test
    fun `a suite default caps its families only when no per-family cap is given`() {
        val candidates = listOf(problem("hakank", "a/1", default = 1), problem("hakank", "a/2", default = 1))

        assertEquals(
            listOf(listOf("a/1"), listOf("a/1", "a/2")),
            listOf(null, 2).map { names(BenchSelection.capFamilies(candidates, perFamily = it, seed = null)) },
        )
    }

    @Test
    fun `families of the same name in two suites are capped apart`() {
        val candidates = listOf(problem("one", "a/1"), problem("one", "a/2"), problem("two", "a/1"))

        val selected = BenchSelection.capFamilies(candidates, perFamily = 1, seed = null)

        assertEquals(listOf("one", "two"), selected.map { it.suite })
    }

    @Test
    fun `a filter fills the cap and reads no instance past it`() {
        val candidates = listOf(
            problem("s", "f/csp"),
            problem("s", "f/cop1"),
            problem("s", "f/cop2"),
            problem("s", "f/cop3"),
        )
        val read = mutableListOf<String>()

        val selected = BenchSelection.capFamilies(candidates, perFamily = 2, seed = null) {
            read += it.name
            "cop" in it.name
        }

        assertEquals(
            listOf(listOf("f/cop1", "f/cop2"), listOf("f/csp", "f/cop1", "f/cop2")),
            listOf(names(selected), read),
        )
    }

    @Test
    fun `compiler exclusions leave room for valid siblings before family and overall caps`() {
        val candidates = listOf(
            problem("s", "f/broken", family = "f"),
            problem("s", "f/valid", family = "f"),
            problem("s", "g/valid", family = "g"),
        )
        val reached = mutableListOf<String>()

        val capped = BenchSelection.capFamilies(candidates, perFamily = 1, seed = null, compatible = {
            reached += it.ref.name
            it.ref.name != "f/broken"
        })
        val selected = CorpusSelection.applySelectionBy(
            capped,
            Selection(maxInstances = 2),
        ) { it.family }

        assertEquals(listOf("f/valid", "g/valid"), names(selected))
        assertEquals(listOf("f/broken", "f/valid", "g/valid"), reached)
    }

    @Test
    fun `compiler infrastructure failures stop selection instead of consuming a cap`() {
        val candidates = listOf(problem("s", "f/one"), problem("s", "f/two"))

        assertFailsWith<IllegalStateException> {
            BenchSelection.capFamilies(candidates, perFamily = 1, seed = 7, compatible = {
                error("compiler unavailable")
            })
        }
    }

    @Test
    fun `seeded compatibility selection fills caps deterministically`() {
        val candidates = (1..8).map { problem("s", "f/$it") }
        val selections = List(2) { _ ->
            BenchSelection.capFamilies(candidates, perFamily = 3, seed = 7, compatible = { candidate ->
                candidate.ref.name.substringAfter('/').toInt() % 2 == 0
            })
        }

        assertEquals(names(selections[0]), names(selections[1]))
        assertEquals(3, selections[0].size)
        assertEquals(true, selections[0].all { it.ref.name.substringAfter('/').toInt() % 2 == 0 })
    }
}
