package com.eignex.klause.bench.catalog

import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class ProblemSetsTest {
    @ParameterizedTest
    @ValueSource(strings = ["bench.json", "bench-linux.json"])
    fun `declared private workspaces should retain every frozen set`(manifest: String) {
        val inputs = Json.parseToJsonElement(File("lab/$manifest").readText()).jsonObject
            .getValue("workspaceInputs").jsonArray.map { it.jsonPrimitive.content.trimEnd('/') }
        val source = File("sets")
        val expected = ProblemSets.names(source).associateWith { ProblemSets.load(listOf(it), source) }
        val workspace = Files.createTempDirectory("sets-workspace").toFile()
        try {
            for (file in source.listFiles().orEmpty()) {
                val relative = "klause-bench/sets/${file.name}"
                if (inputs.any { relative == it || relative.startsWith("$it/") }) {
                    val target = workspace.resolve(relative)
                    target.parentFile.mkdirs()
                    file.copyTo(target)
                }
            }

            val sets = workspace.resolve("klause-bench/sets")
            assertTrue(expected.getValue("sweep").isNotEmpty())
            assertEquals(expected.keys.toList(), ProblemSets.names(sets))
            assertEquals(expected, expected.keys.associateWith { ProblemSets.load(listOf(it), sets) })
        } finally {
            workspace.deleteRecursively()
        }
    }

    @Test
    fun `a set should read its problems and includes in order each once`() {
        val root = Files.createTempDirectory("sets").toFile()
        try {
            root.resolve("a.txt").writeText("# a\nhakank/x/x\nsatlib/uf20-01  # one\n")
            root.resolve("b.txt").writeText("@a\nhakank/x/x\nmiplib2017/air04\n")

            assertEquals(
                listOf(SetEntry("hakank", "x/x"), SetEntry("satlib", "uf20-01"), SetEntry("miplib2017", "air04")),
                ProblemSets.load(listOf("b"), root),
            )
            assertEquals(listOf("a", "b"), ProblemSets.names(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a set that includes itself should be refused`() {
        val root = Files.createTempDirectory("sets").toFile()
        try {
            root.resolve("a.txt").writeText("@b\n")
            root.resolve("b.txt").writeText("@a\n")

            assertFailsWith<IllegalArgumentException> { ProblemSets.load(listOf("a"), root) }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `every committed set should load`() {
        val root = File("sets")
        for (name in ProblemSets.names(root)) {
            val entries = ProblemSets.load(listOf(name), root)
            assertTrue(entries.isNotEmpty(), "set '$name' is empty")
        }
    }

    @Test
    fun `sweep should hold every thematic problem once`() {
        val root = File("sets")
        val sweep = ProblemSets.load(listOf("sweep"), root)
        val focused = listOf(
            "open-int", "linear-real", "nonlinear-real", "mip", "scheduling", "routing", "packing",
            "globals", "sat", "maxsat", "pb",
        )
        val expected = focused.flatMap { ProblemSets.load(listOf(it), root) }
        assertEquals(
            expected.size,
            sweep.size,
            "a problem is in two focused sets",
        )
        assertEquals(expected.toSet(), sweep.toSet())
    }
}
