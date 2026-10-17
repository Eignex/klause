package com.eignex.klause.bench.profile

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CliRecordingCaptureTest {
    @Test
    fun `recording commands should preserve default solver choices and the requested source budget`() {
        val request = CliRecordingCapture.parse(
            arrayOf(
                "suite=smtlib-qflia",
                "problem=original",
                "sha256=${"a".repeat(64)}",
                "timeout=10000",
                "seed=1",
            ),
        )

        val command = CliRecordingCapture.command(request, File("/installed/cli"), File("/captured/input.smt2"))

        assertEquals(listOf("/installed/cli", "/captured/input.smt2", "-t", "10000", "-r", "1", "-s"), command)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "suite=x problem=y sha256=HASH timeout=10000 seed=1 seed=2",
            "suite=x problem=y sha256=HASH timeout=10000 seed=1 engine=cp",
            "suite=x problem=y timeout=10000 seed=1",
            "suite=x problem=y sha256=wrong timeout=10000 seed=1",
            "suite=x problem=y sha256=HASH timeout=60001 seed=1",
            "suite=x problem=y sha256=HASH timeout=0 seed=1",
            "suite=x problem=y sha256=HASH timeout=10000 seed=bad",
        ],
    )
    fun `invalid recording requests should fail before accessing source files`(request: String) {
        val args = request.replace("HASH", "a".repeat(64)).split(' ').toTypedArray()

        assertFailsWith<IllegalArgumentException> { CliRecordingCapture.parse(args) }
    }

    @Test
    fun `child recording should replace inherited profiling options without changing the parent`() {
        val inherited = mapOf(
            "JAVA_OPTS" to "-XX:StartFlightRecording=filename=parent.jfr",
            "JAVA_TOOL_OPTIONS" to "-Xmx1g",
            "KLAUSE_CLI_OPTS" to "-XX:ActiveProcessorCount=8",
        )

        val child = CliRecordingCapture.childEnvironment(
            inherited,
            File("/runtime"),
            File("/out/cli.jfr"),
            File("/out/cli.jfc"),
        )

        assertEquals("-XX:StartFlightRecording=filename=parent.jfr", inherited["JAVA_OPTS"])
        assertEquals("", child["JAVA_OPTS"])
        assertFalse("JAVA_TOOL_OPTIONS" in child)
        assertTrue(child.getValue("KLAUSE_CLI_OPTS").contains("filename=/out/cli.jfr,settings=/out/cli.jfc"))
        assertTrue(child.getValue("KLAUSE_CLI_OPTS").contains("-XX:ActiveProcessorCount=1"))
    }
}
