package com.eignex.klause.bench.profile

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ProfileCaptureArchiveTest {
    @Test
    fun `compressed capture preserves exact raw bytes`() {
        val out = Files.createTempDirectory("profile-capture-test-").toFile()
        try {
            ProfileCaptureArchive.run(out) { out.resolve("record.txt").writeText("raw\r\n") }

            val unpacked = ProcessBuilder("tar", "-xOf", out.resolve("capture.tar.gz").path, "record.txt").start()
            assertEquals("raw\r\n", unpacked.inputStream.bufferedReader().use { it.readText() })
            assertEquals(0, unpacked.waitFor())
        } finally {
            out.deleteRecursively()
        }
    }

    @Test
    fun `live recording remains outside the compressed capture`() {
        val out = Files.createTempDirectory("profile-live-test-").toFile()
        try {
            out.resolve("profile.jfr").writeText("live")

            ProfileCaptureArchive.run(out) { out.resolve("record.txt").writeText("raw") }

            val listing = ProcessBuilder("tar", "-tzf", out.resolve("capture.tar.gz").path).start()
            val members = listing.inputStream.bufferedReader().use { it.readLines() }
            assertEquals(0, listing.waitFor())
            assertFalse("profile.jfr" in members)
            assertEquals("live", out.resolve("profile.jfr").readText())
        } finally {
            out.deleteRecursively()
        }
    }

    @Test
    fun `action failure stays primary after its output is captured`() {
        val out = Files.createTempDirectory("profile-failure-test-").toFile()
        val failure = IllegalArgumentException("original action")
        try {
            val raised = assertFailsWith<IllegalArgumentException> {
                ProfileCaptureArchive.run(out) {
                    out.resolve("partial.txt").writeText("partial")
                    throw failure
                }
            }

            assertSame(failure, raised)
            assertTrue(out.resolve("capture.tar.gz").isFile)
            assertTrue(out.resolve("capture-manifest.json").isFile)
        } finally {
            out.deleteRecursively()
        }
    }

    @Test
    fun `symbolic links are refused without reading or removing external outputs`() {
        val root = Files.createTempDirectory("profile-link-test-").toFile()
        try {
            val external = root.resolve("external").apply { mkdirs() }
            external.resolve("record.txt").writeText("external")
            for (target in listOf(external, external.resolve("record.txt"))) {
                val out = root.resolve("out").apply { mkdirs() }
                Files.createSymbolicLink(out.resolve("link").toPath(), target.toPath())

                assertFailsWith<IllegalStateException> { ProfileCaptureArchive.run(out) {} }

                assertEquals("external", external.resolve("record.txt").readText())
                assertFalse(out.resolve("capture.tar.gz").exists())
                Files.delete(out.resolve("link").toPath())
                out.deleteRecursively()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `action failure retains archive failure as a suppressed exception`() {
        val root = Files.createTempDirectory("profile-errors-test-").toFile()
        val failure = IllegalArgumentException("original action")
        try {
            val out = root.resolve("out").apply { mkdirs() }
            val external = root.resolve("external").apply { mkdirs() }
            Files.createSymbolicLink(out.resolve("link").toPath(), external.toPath())

            val raised = assertFailsWith<IllegalArgumentException> {
                ProfileCaptureArchive.run(out) { throw failure }
            }

            assertSame(failure, raised)
            assertTrue(raised.suppressedExceptions.single() is IllegalStateException)
            assertTrue(external.isDirectory)
            Files.delete(out.resolve("link").toPath())
        } finally {
            root.deleteRecursively()
        }
    }
}
