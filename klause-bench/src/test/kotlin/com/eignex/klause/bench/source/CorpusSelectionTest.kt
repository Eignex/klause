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
    fun `interleave should spread across families`() {
        val a = listOf("a1", "a2", "a3")
        val b = listOf("b1", "b2")
        assertEquals(listOf("a1", "b1", "a2", "b2", "a3"), CorpusSelection.interleave(listOf(a, b)))
    }

    @Test
    fun `pickPrimaryMzn should prefer exact then model then non-mznc prefix`() {
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
    fun `equal-priority primary models should be independent of directory enumeration`() {
        val candidates = listOf(File("bacp-9.mzn"), File("bacp-19.mzn"))

        val forward = CorpusSelection.pickPrimaryMzn("bacp", candidates)
        val reversed = CorpusSelection.pickPrimaryMzn("bacp", candidates.reversed())

        assertEquals("bacp-19.mzn", forward.name)
        assertEquals(forward, reversed)
    }

    @Test
    fun `data-free challenge keys should distinguish the selected model within a family`() {
        val root = Files.createTempDirectory("mznmodels").toFile()
        try {
            File(root, "2011/bacp").mkdirs()
            File(root, "2011/bacp/bacp-9.mzn").writeText("int: n = 9;")
            val original = Layout.MznChallengeAllYears().discover(root).single()

            File(root, "2011/bacp/bacp-19.mzn").writeText("int: n = 19;")
            val selected = Layout.MznChallengeAllYears().discover(root).single()

            assertEquals("2011/bacp/bacp-9", original.name)
            assertEquals("2011/bacp/bacp-19", selected.name)
            assertEquals("2011/bacp/bacp-19.mzn", selected.mznRelPath)
            assertEquals(original.family, selected.family)
            assertEquals("2011/bacp", selected.family)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `selection should apply per-family cap then interleave then overall cap`() {
        val all = (1..4).map { Discovered("famA/$it", "famA/m.mzn", "famA/$it.dzn") } +
            (1..4).map { Discovered("famB/$it", "famB/m.mzn", "famB/$it.dzn") }
        val sel = CorpusSelection.applySelection(all, Selection(perFamily = 2, maxInstances = 3))
        assertEquals(3, sel.size)
        // 2 per family, interleaved -> A1,B1,A2 ; capped at 3.
        assertEquals(listOf("famA/1", "famB/1", "famA/2"), sel.map { it.name })
    }

    @Test
    fun `seeded sampling should be deterministic`() {
        val all = (1..10).map { Discovered("fam/$it", "fam/m.mzn") }
        val a = CorpusSelection.applySelection(all, Selection(perFamily = 3, sampleSeed = 42))
        val b = CorpusSelection.applySelection(all, Selection(perFamily = 3, sampleSeed = 42))
        assertEquals(a.map { it.name }, b.map { it.name })
        assertEquals(3, a.size)
    }

    @Test
    fun `MznChallenge layout should pair dzn with the primary model across nested dirs`() {
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
            assertTrue(found.all { it.mznRelPath == "alpha/alpha.mzn" })
            assertEquals(listOf("alpha/data/i1", "alpha/data/i2"), found.map { it.name })
            assertTrue(found[0].dznRelPath!!.endsWith("i1.dzn"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `MznChallenge layout should pair json data like dzn`() {
        val root = Files.createTempDirectory("mznjson").toFile()
        try {
            File(root, "accap").mkdirs()
            File(root, "accap/accap.mzn").writeText("% model")
            File(root, "accap/a4.json").writeText("{}")
            File(root, "accap/a5.dzn").writeText("n=5;")
            val found = Layout.MznChallenge().discover(root).sortedBy { it.name }
            assertEquals(
                listOf("accap/a4" to "accap/a4.json", "accap/a5" to "accap/a5.dzn"),
                found.map { it.name to it.dznRelPath },
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `MznChallenge layout should discover models inside family subdirectories`() {
        val root = Files.createTempDirectory("mznnested").toFile()
        try {
            File(root, "alpha/models").mkdirs()
            File(root, "alpha/models/alpha.mzn").writeText("% model")
            File(root, "alpha/i1.dzn").writeText("n=1;")

            val found = Layout.MznChallenge().discover(root)

            assertEquals(
                listOf(Discovered("alpha/i1", "alpha/models/alpha.mzn", "alpha/i1.dzn", familyKey = "alpha")),
                found,
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `MznChallengeAllYears layout should year-prefix families so a repeated name does not collide`() {
        val root = Files.createTempDirectory("mznyears").toFile()
        try {
            File(root, "2013/black-hole").mkdirs()
            File(root, "2013/black-hole/black-hole.mzn").writeText("% 2013 model")
            File(root, "2025/black-hole").mkdirs()
            File(root, "2025/black-hole/black-hole.mzn").writeText("% 2025 model")
            File(root, "not-a-year").mkdirs()
            val found = Layout.MznChallengeAllYears().discover(root).sortedBy { it.name }
            assertEquals(listOf("2013/black-hole/black-hole", "2025/black-hole/black-hole"), found.map { it.name })
            assertEquals(listOf("2013/black-hole", "2025/black-hole"), found.map { it.family })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `MznChallengeAllYears should exclude model-only 2026 families before capping`() {
        val root = Files.createTempDirectory("mznyears").toFile()
        try {
            File(root, "2026/atp-stage2").mkdirs()
            File(root, "2026/atp-stage2/stage2_disjunctive_search.mzn").writeText("int: n;")
            File(root, "2020/bnn-planner").mkdirs()
            File(root, "2020/bnn-planner/model.mzn").writeText("int: n = 1;")

            val found = Layout.MznChallengeAllYears().discover(root)
            val selected = CorpusSelection.applySelection(found, Selection(maxInstances = 1))

            assertEquals(listOf("2020/bnn-planner/model"), selected.map { it.name })
            assertEquals(listOf("2020/bnn-planner/model"), found.map { it.name })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `MznChallenge layout should include 2026 families with instance data`() {
        val root = Files.createTempDirectory("mznyears").toFile()
        try {
            File(root, "2026/atp-stage2").mkdirs()
            File(root, "2026/atp-stage2/stage2_disjunctive_search.mzn").writeText("int: n;")
            File(root, "2026/atp-stage2/i1.dzn").writeText("n=1;")
            File(root, "2026/atp-stage2/i2.json").writeText("{\"n\":2}")

            val found = Layout.MznChallenge("2026").discover(root)

            assertEquals(listOf("atp-stage2/i1", "atp-stage2/i2"), found.map { it.name })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `MznChallenge layout should exclude the incompatible 2010 depot placement family`() {
        val root = Files.createTempDirectory("mznyears").toFile()
        try {
            File(root, "2010/depot_placement").mkdirs()
            File(root, "2010/depot_placement/depot_placement.mzn").writeText("% model")
            File(root, "2010/depot_placement/st70_5.dzn").writeText("n=5;")
            File(root, "2010/alpha").mkdirs()
            File(root, "2010/alpha/alpha.mzn").writeText("% model")
            File(root, "2010/alpha/i1.dzn").writeText("n=1;")

            val found = Layout.MznChallenge("2010").discover(root)

            assertEquals(listOf("alpha/i1"), found.map { it.name })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `compatibility discovery should retain depot identities for compiler classification`() {
        val root = Files.createTempDirectory("mzncompat").toFile()
        try {
            File(root, "2010/depot_placement").mkdirs()
            File(root, "2010/depot_placement/depot_placement.mzn").writeText("% model")
            File(root, "2010/depot_placement/st70_5.dzn").writeText("n=5;")

            val found = Layout.MznChallengeAllYears(legacyExclusions = false).discover(root)

            assertEquals(listOf("2010/depot_placement/st70_5"), found.map { it.name })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `Flat layout should group by familyOf so per-family samples across series`() {
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
            assertEquals("AircraftAssemblyLine", found.first { it.name.startsWith("Aircraft") }.family)
            val sampled = CorpusSelection.applySelection(found, Selection(perFamily = 1))
            assertEquals(listOf("AircraftAssemblyLine", "BinPacking", "CVRP"), sampled.map { it.family })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `Flat layout should find a compressed instance and name it without the suffix`() {
        val root = Files.createTempDirectory("zstsel").toFile()
        try {
            File(root, "PB24/fam").mkdirs()
            File(root, "PB24/fam/inst.opb.zst").writeBytes(byteArrayOf())

            val found = Layout.Flat("PB24", "opb").discover(root)

            assertEquals(listOf("fam/inst"), found.map { it.name })
            assertEquals(listOf("PB24/fam/inst.opb.zst"), found.map { it.mznRelPath })
        } finally {
            root.deleteRecursively()
        }
    }

    private fun items(format: String, count: Int) = (1..count).map { format to "$format$it/x" }
    private val family = { item: Pair<String, String> -> item.second.substringBefore('/') }
    private val byFormat = { item: Pair<String, String> -> item.first as Any }

    @Test
    fun `balance should split the cap evenly across the groups present`() {
        val all = items("mzn", 10) + items("xcsp", 3) + items("sat", 3)
        val out = CorpusSelection.applyBalancedBy(all, Selection(maxInstances = 6), family, byFormat)
        assertEquals(mapOf("mzn" to 2, "xcsp" to 2, "sat" to 2), out.groupingBy { it.first }.eachCount())
    }

    @Test
    fun `balance should water-fill a short group surplus into groups with room`() {
        val all = items("big", 10) + items("small", 1)
        val out = CorpusSelection.applyBalancedBy(all, Selection(maxInstances = 6), family, byFormat)
        // Even share is 3/3, but 'small' can only supply 1, so 'big' absorbs the surplus to 5.
        assertEquals(6, out.size)
        assertEquals(mapOf("big" to 5, "small" to 1), out.groupingBy { it.first }.eachCount())
    }

    @Test
    fun `balance without a cap should keep every group in full`() {
        val all = items("big", 10) + items("small", 1)
        val out = CorpusSelection.applyBalancedBy(all, Selection(), family, byFormat)
        assertEquals(11, out.size)
    }

    @Test
    fun `FlatMzn layout should discover self-contained models with family from the first path component`() {
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
