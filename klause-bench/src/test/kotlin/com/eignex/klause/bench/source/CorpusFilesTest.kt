package com.eignex.klause.bench.source

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CorpusFilesTest {

    @Test
    fun `readText returns the plain text of a compressed instance`() {
        val root = Files.createTempDirectory("corpusfiles").toFile()
        try {
            val body = "p cnf 2 1\n1 -2 0\n"
            val packed = CorpusFiles.compress(File(root, "inst.cnf").apply { writeText(body) })

            assertEquals(body, CorpusFiles.readText(packed))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `compressTree compresses instances in place and leaves MiniZinc sources plain`() {
        val root = Files.createTempDirectory("corpusfiles").toFile()
        try {
            File(root, "fam").mkdirs()
            File(root, "fam/inst.mps").writeText("NAME inst\nROWS\n N obj\nCOLUMNS\nRHS\nENDATA\n")
            File(root, "fam/model.mzn").writeText("var 1..3: x;\nsolve satisfy;\n")
            File(root, "fam/data.dzn").writeText("n = 3;\n")

            val done = CorpusFiles.compressTree(root)

            assertEquals(1, done.files)
            assertFalse(File(root, "fam/inst.mps").exists())
            assertTrue(File(root, "fam/inst.mps.zst").isFile)
            assertTrue(File(root, "fam/model.mzn").isFile)
            assertTrue(File(root, "fam/data.dzn").isFile)
        } finally {
            root.deleteRecursively()
        }
    }
}
