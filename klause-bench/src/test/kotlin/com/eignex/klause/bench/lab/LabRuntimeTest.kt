package com.eignex.klause.bench.lab

import com.eignex.lab.bench.BenchCase
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LabRuntimeTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `harness recording is separate from CLI measurement`(recording: Boolean) {
        val settings = LabSettings(mapOf("harness-jfr" to recording.toString(), "measurement" to "none"))
        val case = BenchCase("case", settings.caseParameters("cp", 1), settings.resources())
        val input = Path.of("case", "input.json")
        val report = Path.of("case", "report")

        val command = LabRuntime.harnessCommand(case, input, report)

        assertEquals(recording, "-Dklause.bench.source-events=true" in command)
        assertEquals(recording, command.any { it.startsWith("-XX:StartFlightRecording=") })
        if (recording) assertTrue(command.any { "filename=$report/harness.jfr," in it })
        assertTrue("-Xmx512m" in command)
        assertEquals(listOf("solve-record", input.toString(), report.toString()), command.takeLast(3))
        assertEquals("none", case.parameters["measurement"])
    }
}
