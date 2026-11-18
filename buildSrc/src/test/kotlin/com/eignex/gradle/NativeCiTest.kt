package com.eignex.gradle

import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(Parameterized::class)
class NativeCiTest(private val scenario: Scenario) {
    @Test
    fun `native build retries only recovered configuration metadata`() {
        val directory = Files.createTempDirectory("native-ci-test")
        try {
            val script = Path.of("../klause/scripts/native-ci.sh").toAbsolutePath()
            Files.writeString(directory.resolve("failure.log"), scenario.log)
            Files.writeString(directory.resolve("gradlew"), """
                #!/usr/bin/env bash
                printf '%s\n' "${'$'}*" >> calls
                if [[ ! -f attempted ]]; then
                  touch attempted
                  cat failure.log
                  exit ${scenario.firstExit}
                fi
                exit ${scenario.retryExit}
            """.trimIndent()).toFile().setExecutable(true)
            val bin = Files.createDirectory(directory.resolve("bin"))
            Files.writeString(bin.resolve("curl"), """
                #!/usr/bin/env bash
                printf '%s\n' "${'$'}*" >> probes
                printf '%s' '${scenario.httpStatus}'
                exit ${scenario.probeExit}
            """.trimIndent()).toFile().setExecutable(true)
            val process = ProcessBuilder("bash", script.toString())
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .apply {
                    environment()["PATH"] = "$bin:${System.getenv("PATH")}"
                    environment()["NATIVE_CACHE_OPTION"] = "--build-cache"
                    environment()["NATIVE_TEST_REPEAT"] = scenario.repeat.toString()
                }.start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            assertEquals(scenario.exit, process.waitFor(), output)
            val calls = Files.readAllLines(directory.resolve("calls"))
            assertEquals(scenario.calls, calls.size, output)
            assertFalse(calls.first().contains("--refresh-dependencies"))
            if (scenario.probes > 0) {
                val probes = Files.readAllLines(directory.resolve("probes"))
                assertEquals(scenario.probes, probes.size)
                assertTrue(probes.single().endsWith(METADATA_URL))
            } else {
                assertFalse(Files.exists(directory.resolve("probes")))
            }
            if (scenario.calls > 1 && scenario.firstExit != 0) {
                assertTrue(calls[1].contains("--refresh-dependencies"))
            }
            if (scenario.repeat > 1) {
                assertTrue(calls.last().endsWith(":klause:linuxX64Test --rerun"))
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    data class Scenario(
        val name: String,
        val log: String = MISSING_METADATA,
        val firstExit: Int = 42,
        val httpStatus: String = "200",
        val probeExit: Int = 0,
        val retryExit: Int = 0,
        val repeat: Int = 1,
        val calls: Int = 1,
        val probes: Int = 0,
        val exit: Int = 42,
    ) {
        override fun toString() = name
    }

    companion object {
        private const val METADATA_URL = "https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/" +
            "kotlin-gradle-plugins-bom/2.4.0/kotlin-gradle-plugins-bom-2.4.0.pom"
        private val MISSING_METADATA = """
            Resource missing. [HTTP HEAD: $METADATA_URL]
            A problem occurred configuring project ':buildSrc'.
            > Could not resolve all artifacts for configuration 'classpath'.
            > Could not find org.jetbrains.kotlin:kotlin-gradle-plugins-bom:2.4.0.
        """.trimIndent()

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun scenarios(): List<Array<Scenario>> = listOf(
            Scenario("successful repeated tests", log = "", firstExit = 0, repeat = 3, calls = 3, exit = 0),
            Scenario("recovered metadata", calls = 2, probes = 1, exit = 0),
            Scenario("persistent missing metadata", httpStatus = "404", probes = 1),
            Scenario("failed recovery probe", httpStatus = "000", probeExit = 7, probes = 1),
            Scenario("retry failure is final", retryExit = 43, calls = 2, probes = 1, exit = 43),
            Scenario("compilation failure", log = "$MISSING_METADATA\n> Task :klause:compileKotlinLinuxX64 FAILED"),
            Scenario("test failure", log = "$MISSING_METADATA\n> Task :klause:linuxX64Test FAILED"),
            Scenario("different project", log = MISSING_METADATA.replace(":buildSrc", ":klause")),
            Scenario("different missing artifact", log = MISSING_METADATA.replace("kotlin-gradle-plugins-bom", "other")),
            Scenario("missing artifact without repository evidence", log = MISSING_METADATA.substringAfter('\n')),
        ).map { arrayOf(it) }
    }
}
