package com.eignex.klause.bench.lab

import com.eignex.klause.bench.catalog.Category
import com.eignex.klause.bench.catalog.Expected
import com.eignex.klause.bench.catalog.Format
import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.catalog.ProblemSource
import com.eignex.klause.bench.source.CorpusFiles
import com.eignex.lab.bench.BenchInputRoot
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LabInputsTest {
    @Test
    fun `plain DIMACS input carriers retain original bytes across packed delivery`() {
        val root = Files.createTempDirectory("lab-plain-input-")
        val previous = System.getProperty("klause.workspace.root")
        try {
            System.setProperty("klause.workspace.root", root.toString())
            val source = root.resolve("model.cnf").toFile().apply { writeText("p cnf 2 1\n1 -2 0\n") }
            val packed = CorpusFiles.compress(source)
            val ref = ProblemRef("model", Format.DIMACS, ProblemSource.Prepared(packed), Category.SAT, Expected.Unknown)

            val input = LabInputs.resolve(ref, inputOnly = true, plainDimacs = true).single()

            assertEquals("model.cnf", input.path)
            assertEquals(BenchInputRoot.SOURCE, input.root)
            assertEquals("16c839d02401701c2a72e7c21131959b9c5ca74b8cbd550866d527bd1289c868", input.sha256)
            assertEquals("p cnf 2 1\n1 -2 0\n", source.readText())
            assertTrue(packed.isFile)
        } finally {
            if (previous == null) System.clearProperty("klause.workspace.root") else {
                System.setProperty("klause.workspace.root", previous)
            }
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `corpus symlink resolves to a portable input`() {
        val root = Files.createTempDirectory("lab-inputs-")
        val workspaceProperty = System.getProperty("klause.workspace.root")
        val corpusProperty = System.getProperty("klause.bench.corpusCache")
        try {
            val source = Files.createDirectory(root.resolve("source"))
            val corpus = Files.createDirectory(root.resolve("corpus"))
            val linked = Files.createSymbolicLink(root.resolve("linked corpus"), corpus)
            Files.writeString(corpus.resolve("model with spaces.cnf"), "p cnf 1 1\n1 0\n")
            System.setProperty("klause.workspace.root", source.toString())
            System.setProperty("klause.bench.corpusCache", linked.toString())
            val ref = ProblemRef(
                "model", Format.DIMACS,
                ProblemSource.Prepared(linked.resolve("model with spaces.cnf").toFile()),
                Category.SAT, Expected.Unknown,
            )

            val inputs = LabInputs.resolve(ref)

            assertEquals(BenchInputRoot.CORPUS, inputs.single().root)
            assertEquals("model with spaces.cnf", inputs.single().path)
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
