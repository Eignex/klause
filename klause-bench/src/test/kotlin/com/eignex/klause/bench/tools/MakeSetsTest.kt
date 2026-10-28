package com.eignex.klause.bench.tools

import java.nio.file.Files
import java.time.LocalDate
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MakeSetsTest {
    @Test
    fun `the generator draws what Python's random Random(1) draws`() {
        // Values from CPython 3.14: Random(1).getrandbits(32) three times, then getrandbits(5) and (1).
        val bits = PythonRandom(1)
        assertEquals(listOf(577090037L, 2444712010L, 3639700191L, 25L, 1L), listOf(32, 32, 32, 5, 1).map(bits::bits))
        // Random(1).shuffle(range(20)), twice on the same list.
        val shuffled = PythonRandom(1)
        val items = (0 until 20).toMutableList()
        shuffled.shuffle(items)
        assertEquals(listOf(11, 5, 17, 19, 9, 0, 16, 1, 15, 6, 10, 13, 14, 12, 7, 3, 8, 2, 18, 4), items)
        shuffled.shuffle(items)
        assertEquals(listOf(9, 17, 13, 5, 6, 0, 2, 8, 16, 14, 18, 3, 12, 4, 11, 10, 19, 1, 15, 7), items)
    }

    @Test
    fun `references read as csv DictReader reads them`() {
        val input = "collection,problem,feasible,proven,elapsed_ms\r\n" +
            "c,\"a,\"\"b\"\"\",1,1,5\r\n\r\nc,p,,0,7\n"
        val rows = MakeSets.csvRows(input)
        assertEquals(
            listOf(
            mapOf("collection" to "c", "problem" to "a,\"b\"", "feasible" to "1", "proven" to "1", "elapsed_ms" to "5"),
            mapOf("collection" to "c", "problem" to "p", "feasible" to "", "proven" to "0", "elapsed_ms" to "7"),
        ),
            rows
        )
    }

    @Test
    fun `a set takes proven problems of its theme by tier, each problem once, and the unions name the sets`() {
        val dir = Files.createTempDirectory("sets")
        val features = Files.createDirectories(dir.resolve("features"))
        val problems = (0 until 40).map { "f${it % 4}/p$it" }
        features.resolve("suite.jsonl").writeText(
            "not a problem\n" + problems.joinToString("\n") { p ->
            """{"collection":"c","problem":"$p","suite":"suite","family":"${p.substringBefore('/')}",""" +
                """"format":"MZN","themes":["sat","pb"]}"""
        } + "\n"
        )
        // Ten easy, twenty medium, five hard and five unproven.
        val references = dir.resolve("references.csv")
        references.writeText(
            "collection,problem,feasible,proven,elapsed_ms\n" + problems.mapIndexed { i, p ->
            when {
                i < 10 -> "c,$p,1,1,${i * 10}"
                i < 30 -> "c,$p,1,1,${1000 + i}"
                i < 35 -> "c,$p,0,1,${20_000 + i}"
                else -> "c,$p,,0,5"
            }
        }.joinToString("\n") + "\n"
        )
        val sets = dir.resolve("sets")

        MakeSets.draw(features, references, sets, setOf("sat", "pb"), LocalDate.of(2026, 1, 2))

        val sat = sets.resolve("sat.txt").readLines()
        val heading = "# sat: problems with the sat theme, drawn 2026-01-02 by klause-bench/scripts/draw-sets.sh"
        assertEquals(heading, sat[0])
        val tiers = sat.filter { it.startsWith("# ") && ": " in it && "theme" !in it && "tiers" !in it }
        assertEquals(listOf("# easy: 8", "# medium: 11", "# hard: 1"), tiers)
        val pb = sets.resolve("pb.txt").readLines().filter { !it.startsWith("#") }
        val drawn = sat.filter { !it.startsWith("#") }
        // The rest: one hard of four, the nine medium, and the two easy that take the medium shortfall.
        assertEquals(12, pb.size)
        assertTrue(drawn.intersect(pb.toSet()).isEmpty())
        assertTrue((drawn + pb).none { it.substringAfter("suite/").substringAfter("/p").toInt() >= 35 })
        val linear = "# linear: open-int, linear-real\n@open-int\n@linear-real\n"
        assertEquals(linear, sets.resolve("linear.txt").readText())
        assertTrue(Files.notExists(sets.resolve("mip.txt")))
        dir.toFile().deleteRecursively()
    }
}
