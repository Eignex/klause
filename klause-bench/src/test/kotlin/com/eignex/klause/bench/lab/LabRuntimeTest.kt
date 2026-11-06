package com.eignex.klause.bench.lab

import com.eignex.lab.bench.BenchCase
import com.eignex.lab.bench.BenchContext
import com.eignex.lab.bench.BenchProtocol
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class LabRuntimeTest {
    @ParameterizedTest
    @CsvSource("none,false", "resources,false", "jfr,true", "compilation,true", "compilation+log,true")
    fun `CLI recording depth is retained without changing unrecorded runtimes`(
        measurement: String,
        recording: Boolean,
    ) {
        val root = Files.createTempDirectory("lab recording ")
        try {
            val settings = LabSettings(mapOf("measurement" to measurement))
            val case = BenchCase("case", settings.caseParameters("cp", 7), settings.resources())
            val output = root.resolve("out with spaces")

            LabRuntime.environment(case, BenchContext(output, worktree = root))
            val options = BenchProtocol.json.parseToJsonElement(Files.readString(output.resolve("runtime.json")))
                .jsonObject.getValue("javaOptions").jsonArray.map { it.jsonPrimitive.content }
            val arguments = Files.readString(output.resolve("cli.args"))
            val preflight = Files.readString(output.resolve("preflight.args"))

            assertEquals(recording, "-XX:FlightRecorderOptions=stackdepth=256" in options)
            assertEquals(recording, "-XX:FlightRecorderOptions=stackdepth=256" in arguments)
            assertFalse("FlightRecorderOptions" in preflight)
            if (!recording) assertEquals(listOf("-Xmx2560m", "-XX:ActiveProcessorCount=1"), options)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
