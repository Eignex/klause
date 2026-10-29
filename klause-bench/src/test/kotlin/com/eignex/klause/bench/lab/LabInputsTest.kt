package com.eignex.klause.bench.lab

import com.eignex.klause.bench.catalog.Category
import com.eignex.klause.bench.catalog.Expected
import com.eignex.klause.bench.catalog.Format
import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.catalog.ProblemSource
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.lab.bench.BenchRequest
import com.eignex.lab.bench.BenchInputRoot
import com.eignex.lab.bench.BenchContext
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertContentEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class LabInputsTest {
    @ParameterizedTest
    @CsvSource(
        "argmax,62060d189d8d76561ad44f9ad64e7ab5275ab78b85da1d25e33bb1edc959a573",
        "bin_packing,5c53902b47e6b31ced58beae5fa38a64364ccd900db35b3acc6d95f185604314",
        "connected,8a80fa9b17d409949df5b0a407f283257b60de06c7cdeede0671f7f2884d1653",
        "diffn_nonstrict_k,4d25d2f98703c1370a405b86182ca6b9b49804aca38683386b5ab5ba5f04dc7b",
        "graph_coloring,c893e0eee1a53155b59ab069198704b10dffa7175e3c8bc43d36a52de5917bd2",
        "latin_square,c90897e5b3076dba985a73abfa82c1350add7feddd6c8c1be3bd7b4e527ece37",
        "magic_square,0aedd7ac5742b6f650244b5157c96978034b390f4572614854d957fa1cde919d",
        "queens,661c79062ccf03dd4cd5ec29c94e2a8360f2bb702f748f5f9cc5a7fe6383193f",
        "send_more_money,1e9c0721684a133195f76333842f23850e5ba456b779c79e019eba87a3ace763",
        "sudoku,214886a9a6f69373af468185afb92f94b933387213196ef564ab5317e5413290",
        "zero_one_knapsack,d763322febf3d75574c902318c58b6436f24403c900bca90c0d4888145ec42d3",
    )
    fun `smoke selection retains original bytes under isolated filenames`(name: String, sha256: String) {
        val request = BenchRequest(parameters = mapOf(
            "suite" to "mzn-smoke", "name" to name, "input-only" to "true",
        ))

        val case = KlauseLabSuite.plan(request).single()
        val model = case.inputs.single { it.role == "model" }

        assertEquals("mzn-smoke", case.attributes["suite"])
        assertEquals(name, case.attributes["problem"])
        assertEquals("klause-mzn-lib/test-models/klause-test-$name.mzn", model.path)
        assertEquals(BenchInputRoot.SOURCE, model.root)
        assertEquals(sha256, model.sha256)
    }

    @Test
    fun `smoke include directories contain only namespaced diagnostic models`() {
        val request = BenchRequest(parameters = mapOf(
            "suite" to "mzn-smoke", "name" to "argmax", "input-only" to "true",
        ))

        val case = KlauseLabSuite.plan(request).single()
        val include = case.inputs.single { it.role == "include" }
        val directory = CorpusFetcher.workspaceRoot().resolve(include.path)
        val models = directory.listFiles().orEmpty().filter { it.extension == "mzn" }

        assertTrue(models.isNotEmpty())
        assertTrue(models.all { it.name.startsWith("klause-test-") })
        assertEquals(listOf("globals.mzn"), LabInputs.includeNames(
            CorpusFetcher.workspaceRoot().resolve(case.inputs.single { it.role == "model" }.path).readText(),
        ))
    }

    @ParameterizedTest
    @CsvSource(
        "model with spaces.cnf,CORPUS",
        "normalized-ppp:1-12.cnf.zst,SOURCE",
        "directory:one/model.cnf,SOURCE",
    )
    fun `corpus symlink resolves to a portable input`(name: String, expectedRoot: BenchInputRoot) {
        val root = Files.createTempDirectory("lab-inputs-")
        val workspaceProperty = System.getProperty("klause.workspace.root")
        val corpusProperty = System.getProperty("klause.bench.corpusCache")
        try {
            val source = Files.createDirectory(root.resolve("source"))
            val corpus = Files.createDirectory(root.resolve("corpus"))
            val linked = Files.createSymbolicLink(root.resolve("linked corpus"), corpus)
            val original = corpus.resolve(name)
            Files.createDirectories(original.parent)
            Files.writeString(original, "p cnf 1 1\n1 0\n")
            System.setProperty("klause.workspace.root", source.toString())
            System.setProperty("klause.bench.corpusCache", linked.toString())
            val ref = ProblemRef(
                "model", Format.DIMACS,
                ProblemSource.Prepared(linked.resolve(name).toFile()),
                Category.SAT, Expected.Unknown,
            )

            val inputs = LabInputs.resolve(ref)

            val input = inputs.single()
            val context = BenchContext(root, worktree = source, corpusDirectory = corpus)
            assertEquals(expectedRoot, input.root)
            assertTrue(':' !in input.path)
            assertTrue(input.path.endsWith(original.fileName.toString().replace(':', '_')))
            assertContentEquals(Files.readAllBytes(original), Files.readAllBytes(LabInputs.path(input, context)))
            assertEquals(inputs, LabInputs.resolve(ref))
            if (input.root == BenchInputRoot.SOURCE) {
                Files.writeString(LabInputs.path(input, context), "corrupt")
                assertEquals(inputs, LabInputs.resolve(ref))
                assertContentEquals(Files.readAllBytes(original), Files.readAllBytes(LabInputs.path(input, context)))
            }
        } finally {
            if (workspaceProperty == null) {
                System.clearProperty("klause.workspace.root")
            } else {
                System.setProperty("klause.workspace.root", workspaceProperty)
            }
            if (corpusProperty == null) {
                System.clearProperty("klause.bench.corpusCache")
            } else {
                System.setProperty("klause.bench.corpusCache", corpusProperty)
            }
            root.toFile().deleteRecursively()
        }
    }
}
