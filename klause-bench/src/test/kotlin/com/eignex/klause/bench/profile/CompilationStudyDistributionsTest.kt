package com.eignex.klause.bench.profile

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CompilationStudyDistributionsTest {
    @Test
    fun `retained nested distribution bytes survive removal of the build tree`() {
        val directory = Files.createTempDirectory("distribution-retention-").toFile()
        try {
            val source = File(directory, "source")
            val executable = File(source, "bin/cli").apply { parentFile.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
            check(executable.setExecutable(true))
            File(source, "lib/library.jar").apply { parentFile.mkdirs(); writeBytes(byteArrayOf(4, 5)) }
            val destination = File(directory, "retained")

            val inventory = CompilationStudyDistributions.retain(source, destination)
            check(source.deleteRecursively())

            assertEquals(setOf("bin/cli", "lib/library.jar"), inventory.keys)
            assertContentEquals(byteArrayOf(1, 2, 3), File(destination, "bin/cli").readBytes())
            assertContentEquals(byteArrayOf(4, 5), File(destination, "lib/library.jar").readBytes())
            assertTrue(File(destination, "bin/cli").canExecute())
            assertEquals(3L, inventory.getValue("bin/cli").bytes)
            assertEquals("039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81",
                inventory.getValue("bin/cli").sha256)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `retention cannot overwrite an earlier installed distribution`() {
        val directory = Files.createTempDirectory("distribution-retention-").toFile()
        try {
            val source = File(directory, "source").apply { mkdir() }
            val destination = File(directory, "retained").apply { mkdir() }

            assertFailsWith<IllegalArgumentException> { CompilationStudyDistributions.retain(source, destination) }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `a linked installed file is rejected before copying`() {
        val directory = Files.createTempDirectory("distribution-retention-").toFile()
        try {
            val source = File(directory, "source").apply { mkdir() }
            val target = File(directory, "target").apply { writeText("private") }
            Files.createSymbolicLink(File(source, "linked").toPath(), target.toPath())

            assertFailsWith<IllegalArgumentException> {
                CompilationStudyDistributions.retain(source, File(directory, "retained"))
            }
        } finally {
            directory.deleteRecursively()
        }
    }
}
