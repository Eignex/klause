package com.eignex.klause.bench.source

import com.eignex.klause.bench.source.CorpusSelection.Discovered
import com.eignex.klause.bench.source.CorpusSelection.Layout
import com.eignex.klause.bench.source.CorpusSelection.Selection
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CorpusSelectionTest {

    @Test
    fun `interleave spreads across families`() {
        val a = listOf("a1", "a2", "a3")
        val b = listOf("b1", "b2")
        assertEquals(listOf("a1", "b1", "a2", "b2", "a3"), CorpusSelection.interleave(listOf(a, b)))
    }

    @Test
    fun `pickPrimaryMzn prefers exact then model then non-mznc prefix`() {
        fun f(n: String) = File("$n.mzn")
        assertEquals(
            "queens.mzn",
            CorpusSelection.pickPrimaryMzn(
                "queens",
                listOf(f("mznc2009_queens"), f("queens"), f("queens_alt")),
            ).name,
        )
        assertEquals(
            "rost_model.mzn",
            CorpusSelection.pickPrimaryMzn(
                "rost",
                listOf(f("mznc2009_rost"), f("rost_model")),
            ).name,
        )
        // Falls back to a family-prefixed non-mznc name over the year-prefixed variant.
        assertEquals(
            "amaze3.mzn",
            CorpusSelection.pickPrimaryMzn(
                "amaze",
                listOf(f("mznc2012_amaze"), f("amaze3")),
            ).name,
        )
    }

    @Test
    fun `per-family cap then interleave then overall cap`() {
        val all = (1..4).map { Discovered("famA/$it", "famA/m.mzn", "famA/$it.dzn") } +
            (1..4).map { Discovered("famB/$it", "famB/m.mzn", "famB/$it.dzn") }
        val sel = CorpusSelection.applySelection(all, Selection(perFamily = 2, maxInstances = 3))
        assertEquals(3, sel.size)
        // 2 per family, interleaved -> A1,B1,A2 ; capped at 3.
        assertEquals(listOf("famA/1", "famB/1", "famA/2"), sel.map { it.name })
    }

    @Test
    fun `seeded sampling is deterministic`() {
        val all = (1..10).map { Discovered("fam/$it", "fam/m.mzn") }
        val a = CorpusSelection.applySelection(all, Selection(perFamily = 3, sampleSeed = 42))
        val b = CorpusSelection.applySelection(all, Selection(perFamily = 3, sampleSeed = 42))
        assertEquals(a.map { it.name }, b.map { it.name })
        assertEquals(3, a.size)
    }

    @Test
    fun `MznChallenge layout pairs dzn with primary model across nested dirs`() {
        val root = Files.createTempDirectory("mznsel").toFile()
        try {
            File(root, "alpha").mkdirs()
            File(root, "alpha/alpha.mzn").writeText("% model")
            File(root, "alpha/mznc2009_alpha.mzn").writeText("% stale variant")
            File(root, "alpha/data").mkdirs()
            File(root, "alpha/data/i1.dzn").writeText("n=1;")
            File(root, "alpha/data/i2.dzn").writeText("n=2;")
            val found = Layout.MznChallenge().discover(root).sortedBy { it.name }
            assertEquals(2, found.size)
            assertTrue(found.all { it.mznRelPath == "alpha/alpha.mzn" }, "should pick the canonical model")
            assertEquals(listOf("alpha/data/i1", "alpha/data/i2"), found.map { it.name })
            assertTrue(found[0].dznRelPath!!.endsWith("i1.dzn"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `Flat layout groups by familyOf so per-family samples across series`() {
        val root = Files.createTempDirectory("xcspsel").toFile()
        try {
            File(root, "COP").mkdirs()
            listOf(
                "AircraftAssemblyLine-1-178-00-0_c23",
                "AircraftAssemblyLine-2-178-20-0_c23",
                "BinPacking-n1c1w1a_c24",
                "CVRP-A-n32-k5_c22",
            ).forEach { File(root, "COP/$it.xml").writeText("<instance/>") }
            val found = Layout.Flat("COP", "xml", familyOf = { it.substringBefore('-') }).discover(root)
            // Every parameterization keeps its full name, but families collapse to the series prefix.
            assertEquals("AircraftAssemblyLine", found.first { it.name.startsWith("Aircraft") }.family)
            val sampled = CorpusSelection.applySelection(found, Selection(perFamily = 1))
            assertEquals(listOf("AircraftAssemblyLine", "BinPacking", "CVRP"), sampled.map { it.family })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `FlatMzn layout discovers self-contained models with family from first path component`() {
        val root = Files.createTempDirectory("flatsel").toFile()
        try {
            File(root, "unit/globals").mkdirs()
            File(root, "unit/int").mkdirs()
            File(root, "unit/globals/alldiff.mzn").writeText("% t")
            File(root, "unit/int/lin.mzn").writeText("% t")
            val found = Layout.FlatMzn("unit").discover(root).sortedBy { it.name }
            assertEquals(listOf("globals/alldiff", "int/lin"), found.map { it.name })
            assertEquals("globals", found[0].family)
        } finally {
            root.deleteRecursively()
        }
    }
}
