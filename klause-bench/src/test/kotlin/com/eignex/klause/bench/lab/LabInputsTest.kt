package com.eignex.klause.bench.lab

import com.eignex.klause.bench.catalog.Category
import com.eignex.klause.bench.catalog.Expected
import com.eignex.klause.bench.catalog.Format
import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.catalog.ProblemSource
import com.eignex.lab.bench.BenchInputRoot
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class LabInputsTest {
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
