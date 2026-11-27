package com.eignex.gradle

import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(Parameterized::class)
class RunGradleWithMetadataRecoveryTest(private val scenario: Scenario) {
    @Test
    fun `gradle retries only confirmed buildsrc metadata misses`() {
        val directory = Files.createTempDirectory("gradle-metadata-test")
        try {
            val script = Path.of("../klause-bench/scripts/run-gradle-with-metadata-recovery.sh").toAbsolutePath()
            Files.writeString(directory.resolve("gradle.log"), scenario.log)
            Files.writeString(directory.resolve("gradle"), """
                printf '%s\n' "${'$'}*" >> calls
                cat gradle.log
                if [[ ! -f attempted ]]; then
                    touch attempted
                    exit ${scenario.firstExit}
                fi
                exit ${scenario.retryExit}
            """.trimIndent())
            val bin = Files.createDirectory(directory.resolve("bin"))
            Files.writeString(bin.resolve("curl"), """
                #!/usr/bin/env bash
                url="${'$'}{!#}"
                printf '%s\n' "${'$'}url" >> probes
                if [[ "${'$'}url" == '${scenario.unavailablePom}' ]]; then
                    printf 404
                else
                    printf 200
                fi
                exit ${scenario.probeExit}
            """.trimIndent()).toFile().setExecutable(true)
            val args = listOf("check", "koverXmlReport") + if (scenario.refresh) listOf("--refresh-dependencies") else emptyList()
            val process = ProcessBuilder(listOf("bash", script.toString(), "bash", "gradle") + args)
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .apply { environment()["PATH"] = "$bin:${System.getenv("PATH")}" }
                .start()

            val output = process.inputStream.bufferedReader().use { it.readText() }

            assertEquals(scenario.exit, process.waitFor(), output)
            val calls = Files.readAllLines(directory.resolve("calls"))
            val firstCall = (args + "--console=plain").joinToString(" ")
            val expectedCalls = when {
                !scenario.retry -> listOf(firstCall)
                scenario.refresh -> listOf(firstCall, firstCall)
                else -> listOf(firstCall, "$firstCall --refresh-dependencies")
            }
            assertEquals(expectedCalls, calls, output)
            val probes = directory.resolve("probes")
            val probedUrls = if (Files.exists(probes)) Files.readAllLines(probes) else emptyList()
            assertEquals(scenario.probes, probedUrls, output)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    data class Scenario(
        val name: String,
        val log: String = MISSING_METADATA,
        val firstExit: Int = 42,
        val retryExit: Int = 0,
        val probeExit: Int = 0,
        val unavailablePom: String = "",
        val refresh: Boolean = false,
        val retry: Boolean = false,
        val probes: List<String> = emptyList(),
        val exit: Int = 42,
    ) {
        override fun toString() = name
    }

    companion object {
        private const val REPOSITORY = "https://plugins.gradle.org/m2/org/jetbrains/kotlin"
        private fun pom(artifact: String) = "$REPOSITORY/$artifact/2.4.0/$artifact-2.4.0.pom"
        private val stdlib = pom("kotlin-stdlib")
        private val bom = pom("kotlin-gradle-plugins-bom")
        private val api = pom("kotlin-gradle-plugin-api")
        private val poms = listOf(stdlib, bom, api)
        private val MISSING_BOM = """
            FAILURE: Build failed with an exception.

            * What went wrong:
            A problem occurred configuring project ':buildSrc'.
            > Could not resolve all artifacts for configuration 'classpath'.
               > Could not find org.jetbrains.kotlin:kotlin-gradle-plugins-bom:2.4.0.
                 Searched in the following locations:
                   - $bom
            * Try:
        """.trimIndent()
        private val MISSING_METADATA = """
            FAILURE: Build failed with an exception.

            * What went wrong:
            A problem occurred configuring project ':buildSrc'.
            > Could not resolve all artifacts for configuration 'classpath'.
               > Could not find org.jetbrains.kotlin:kotlin-stdlib:2.4.0.
                 Searched in the following locations:
                   - $stdlib
                 Required by:
                     buildscript of project ':buildSrc'
               > Could not resolve org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.0.
                  > Could not resolve org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.0.
                     > Could not parse POM ${pom("kotlin-gradle-plugin")}
                        > Could not find org.jetbrains.kotlin:kotlin-gradle-plugins-bom:2.4.0.
                          Searched in the following locations:
                            - $bom
               > Could not find org.jetbrains.kotlin:kotlin-gradle-plugin-api:2.4.0.
                 Searched in the following locations:
                   - $api
            > There are 2 more failures with identical causes.

            * Try:
        """.trimIndent()

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun scenarios(): List<Array<Scenario>> = listOf(
            Scenario("successful build", log = "BUILD SUCCESSFUL", firstExit = 0, exit = 0),
            Scenario("all plugin portal metadata recovered", retry = true, probes = poms, exit = 0),
            Scenario("reruns an already refreshed invocation", refresh = true, retry = true, probes = poms, exit = 0),
            Scenario("one pom remains missing", unavailablePom = bom, probes = listOf(stdlib, bom)),
            Scenario("failed recovery probe", log = MISSING_BOM, probeExit = 7, probes = listOf(bom)),
            Scenario("retry failure is final", retry = true, retryExit = 43, probes = poms, exit = 43),
            Scenario("compilation tasks already started", log = "> Task :buildSrc:compileKotlin FAILED\n$MISSING_METADATA"),
            Scenario("jvm test tasks already started", log = "> Task :klause:jvmTest FAILED\n$MISSING_METADATA"),
            Scenario("different project", log = MISSING_METADATA.replace(":buildSrc", ":klause")),
            Scenario("unrelated miss in the same classpath", log = MISSING_METADATA.replace(
                "org.jetbrains.kotlin:kotlin-stdlib", "org.example:runtime",
            )),
            Scenario("different repository", log = MISSING_METADATA.replace(api, "https://example.com/module.pom")),
            Scenario("missing repository evidence", log = MISSING_METADATA.replace("- $bom", "")),
        ).map { arrayOf(it) }
    }
}
