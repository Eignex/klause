package com.eignex.klause.bench.target

import com.eignex.klause.bench.catalog.Category
import com.eignex.klause.bench.catalog.Expected
import com.eignex.klause.bench.catalog.Format
import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.catalog.corpus
import com.eignex.klause.bench.source.CorpusSelection
import com.eignex.klause.bench.source.CorpusSelection.Selection
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
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
    fun `a per-family cap should count the provider family rather than the name prefix`() {
        val candidates = listOf(
            problem("xcsp3-cop", "Rack-1", family = "Rack"),
            problem("xcsp3-cop", "Rack-2", family = "Rack"),
            problem("xcsp3-cop", "Mario-1", family = "Mario"),
        )

        val selected = BenchSelection.capFamilies(candidates, perFamily = 1, seed = null)

        assertEquals(listOf("Rack-1", "Mario-1"), names(selected))
    }

    @Test
    fun `a suite default should cap its families only when no per-family cap is given`() {
        val candidates = listOf(problem("hakank", "a/1", default = 1), problem("hakank", "a/2", default = 1))

        assertEquals(
            listOf(listOf("a/1"), listOf("a/1", "a/2")),
            listOf(null, 2).map { names(BenchSelection.capFamilies(candidates, perFamily = it, seed = null)) },
        )
    }

    @Test
    fun `families of the same name in two suites should be capped apart`() {
        val candidates = listOf(problem("one", "a/1"), problem("one", "a/2"), problem("two", "a/1"))

        val selected = BenchSelection.capFamilies(candidates, perFamily = 1, seed = null)

        assertEquals(listOf("one", "two"), selected.map { it.suite })
    }

    @Test
    fun `a filter should fill the cap and read no instance past it`() {
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
    fun `compiler exclusions should leave room for valid siblings before family and overall caps`() {
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
    fun `compiler infrastructure failures should stop selection instead of consuming a cap`() {
        val candidates = listOf(problem("s", "f/one"), problem("s", "f/two"))

        assertFailsWith<IllegalStateException> {
            BenchSelection.capFamilies(candidates, perFamily = 1, seed = 7, compatible = {
                error("compiler unavailable")
            })
        }
    }

    @Test
    fun `seeded compatibility selection should fill caps deterministically`() {
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

    @ParameterizedTest
    @CsvSource(
        nullValues = ["null"],
        value = [
            "null,null,1,false", "null,7,5,false", "null,7,50,false", "null,7,null,false",
            "1,7,4,false", "2,7,5,false", "3,null,5,false", "0,7,5,false", "2,7,0,false",
            "null,null,5,true", "2,7,5,true", "2,7,null,true", "2,7,50,true",
        ],
    )
    fun `capped admission preserves the ranked cohort of eager family selection`(
        perFamily: Int?,
        seed: Long?,
        maxInstances: Int?,
        balanceFormats: Boolean,
    ) {
        val candidates = listOf(
            problem("one", "f/1", default = 2),
            problem("one", "g/1", default = 2),
            problem("one", "f/2", default = 2),
            problem("one", "f/3", default = 2),
            problem("one", "g/2", default = 2),
            problem("one", "g/3", default = 2),
            problem("two", "f/1"),
            problem("two", "f/2"),
            problem("two", "f/3"),
            problem("two", "flat-1", family = "flat"),
            problem("two", "flat-2", family = "flat"),
            problem("two", "empty/1", default = 0),
        ).map { candidate ->
            if (candidate.suite == "one") candidate else candidate.copy(ref = candidate.ref.copy(format = Format.MINIZINC))
        }
        val compatible: (SelectedProblem) -> Boolean = { it.ref.name != "f/2" }
        val accept: (ProblemRef) -> Boolean = { it.name != "g/1" }
        val capped = BenchSelection.capFamilies(candidates, perFamily, seed, compatible, accept)
        val selection = Selection(maxInstances = maxInstances)
        val expected = if (balanceFormats) {
            CorpusSelection.applyBalancedBy(capped, selection, { it.family }) { it.ref.format }
        } else {
            CorpusSelection.applySelectionBy(capped, selection) { it.family }
        }

        val selected = BenchSelection.select(candidates, perFamily, seed, maxInstances, balanceFormats, compatible, accept)

        assertEquals(expected, selected)
    }

    @Test
    fun `overall cap stops admission within a family round without checking later families`() {
        val candidates = listOf(
            problem("s", "a/1"), problem("s", "a/2"), problem("s", "a/3"),
            problem("s", "b/1"), problem("s", "b/2"), problem("s", "b/3"),
            problem("s", "c/1"), problem("s", "c/2"),
        )
        val reached = mutableListOf<String>()

        val selected = BenchSelection.select(candidates, perFamily = 3, seed = null, maxInstances = 4, compatible = {
            reached += it.ref.name
            true
        })

        assertEquals(listOf("a/1", "b/1", "c/1", "a/2"), names(selected))
        assertEquals(names(selected), reached)
    }

    @Test
    fun `empty and rejected families do not consume rounds or compatible admission slots`() {
        val candidates = listOf(
            problem("s", "empty/no"), problem("s", "a/no"), problem("s", "a/broken"),
            problem("s", "a/valid"), problem("s", "a/unused"), problem("s", "b/valid"),
        )
        val read = mutableListOf<String>()
        val compiled = mutableListOf<String>()

        val selected = BenchSelection.select(candidates, perFamily = 1, seed = null, maxInstances = 2, compatible = {
            compiled += it.ref.name
            it.ref.name != "a/broken"
        }, accept = {
            read += it.name
            !it.name.endsWith("/no")
        })

        assertEquals(listOf("a/valid", "b/valid"), names(selected))
        assertEquals(listOf("empty/no", "a/no", "a/broken", "a/valid", "b/valid"), read)
        assertEquals(listOf("a/broken", "a/valid", "b/valid"), compiled)
    }

    @ParameterizedTest
    @CsvSource("1,false", "2,true")
    fun `infrastructure failure propagates exactly when its candidate is reached`(maxInstances: Int, fails: Boolean) {
        val candidates = listOf(problem("s", "a/valid"), problem("s", "a/unavailable"))
        val reached = mutableListOf<String>()
        val select = {
            BenchSelection.select(candidates, perFamily = 3, seed = null, maxInstances = maxInstances, compatible = {
                reached += it.ref.name
                check(it.ref.name != "a/unavailable") { "compiler unavailable" }
                true
            })
        }

        if (fails) {
            assertEquals("compiler unavailable", assertFailsWith<IllegalStateException> { select() }.message)
            assertEquals(listOf("a/valid", "a/unavailable"), reached)
        } else {
            assertEquals(listOf("a/valid"), names(select()))
            assertEquals(listOf("a/valid"), reached)
        }
    }

    @ParameterizedTest
    @CsvSource(nullValues = ["null"], value = ["null,false", "0,false", "-1,false", "1,true"])
    fun `unbounded nonpositive and format balanced selection retain eager admission`(maxInstances: Int?, balance: Boolean) {
        val candidates = listOf(problem("s", "a/1"), problem("s", "a/2"), problem("s", "b/1"))
        val reached = mutableListOf<String>()
        val select = {
            BenchSelection.select(candidates, perFamily = 2, seed = null, maxInstances = maxInstances,
                balanceFormats = balance, compatible = {
                    reached += it.ref.name
                    true
                })
        }

        if (maxInstances == -1) assertFailsWith<IllegalArgumentException> { select() } else select()

        assertEquals(listOf("a/1", "a/2", "b/1"), reached)
    }
}
