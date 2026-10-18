package com.eignex.klause.bench.profile

import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.source.CorpusFetcher
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class CliStreamCaptureTest {
    @Test
    fun `catalog capture should preserve the original bytes format and CLI arguments`() {
        val output = Files.createTempDirectory("cli-catalog-capture").toFile()
        try {
            val original = File(CorpusFetcher.workspaceRoot(),
                "klause-bench/smoke-corpus/mps-linear-source-v1/bounded-decimal-constant.mps").readBytes()
            val arguments = listOf("-t", "10000", "-p", "1", "-r", "3", "-v")

            val settings = CliStreamCapture.prepare((listOf("suite=mps-linear-source-v1",
                "problem=bounded-decimal-constant", "sha256=${sha256(original)}") + "--" + arguments).toTypedArray(),
                output)

            assertContentEquals(original, settings.source.readBytes())
            assertEquals("input.mps", settings.source.name)
            assertEquals(sha256(original), settings.sourceSha256)
            assertEquals(arguments + settings.source.path, settings.command.drop(1))
            assertNull(settings.childJvmOptions)
            assertNull(settings.recording)
        } finally {
            output.deleteRecursively()
        }
    }

    @Test
    fun `a different catalog source hash should fail before preserving or launching it`() {
        val output = Files.createTempDirectory("cli-source-mismatch").toFile()
        try {
            assertFailsWith<IllegalArgumentException> {
                CliStreamCapture.prepare(arrayOf("suite=mps-linear-source-v1", "problem=bounded-decimal-constant",
                    "sha256=${"0".repeat(64)}", "--"), output)
            }

            assertFalse(File(output, "input.mps").exists())
        } finally {
            output.deleteRecursively()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["suite=miplib2017", "problem=app2-1", "suite="])
    fun `incomplete catalog identities should fail before fetching a source`(selector: String) {
        val output = Files.createTempDirectory("cli-incomplete-source").toFile()
        try {
            assertFailsWith<IllegalArgumentException> {
                CliStreamCapture.prepare(arrayOf(selector, "--"), output)
            }

            assertTrue(output.listFiles().orEmpty().isEmpty())
        } finally {
            output.deleteRecursively()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["input=unused.mps", "text64=eA=="])
    fun `catalog identities should reject a second input mechanism`(selector: String) {
        val output = Files.createTempDirectory("cli-ambiguous-source").toFile()
        try {
            assertFailsWith<IllegalArgumentException> {
                CliStreamCapture.prepare(arrayOf(selector, "suite=mps-linear-source-v1",
                    "problem=bounded-decimal-constant", "sha256=${"0".repeat(64)}", "--"), output)
            }

            assertTrue(output.listFiles().orEmpty().isEmpty())
        } finally {
            output.deleteRecursively()
        }
    }

    @Test
    fun `child recording options should retain inherited memory and processor restrictions`() {
        val inherited = "-Xmx3g -XX:+UseSerialGC -XX:ActiveProcessorCount=1"
        val recording = File("cli-profile", "cli.jfr")

        val options = CliStreamCapture.profileOptions(inherited, recording)

        assertTrue(options.startsWith("$inherited "))
        assertTrue("filename=${recording.path},settings=profile,dumponexit=true" in options)
        assertEquals(1, Regex("StartFlightRecording").findAll(options).count())
    }

    @Test
    fun `child profiling should not replace an existing recording`() {
        assertFailsWith<IllegalArgumentException> {
            CliStreamCapture.profileOptions("-XX:StartFlightRecording=filename=existing.jfr", File("cli.jfr"))
        }
    }

    @Test
    fun `child profiling should attach to the selected CLI invocation`() {
        val output = Files.createTempDirectory("cli-profile-capture").toFile()
        try {
            val settings = CliStreamCapture.prepare(arrayOf("text64=eA==", "profileJvm=true", "--", "-t", "10000"),
                output)

            assertEquals(File(output, "cli.jfr").canonicalFile, settings.recording)
            assertTrue("filename=${settings.recording?.path},settings=profile,dumponexit=true" in
                checkNotNull(settings.childJvmOptions))
            assertEquals(listOf("-t", "10000", settings.source.path), settings.command.drop(1))
            assertEquals("x", settings.source.readText())
        } finally {
            output.deleteRecursively()
        }
    }
}
