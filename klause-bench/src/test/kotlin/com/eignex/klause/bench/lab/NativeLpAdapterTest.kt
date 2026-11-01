package com.eignex.klause.bench.lab

import com.eignex.klause.bench.catalog.Category
import com.eignex.klause.bench.catalog.Expected
import com.eignex.klause.bench.catalog.Format
import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.catalog.ProblemSource
import com.eignex.klause.bench.metric.SolverInvocation
import com.eignex.klause.bench.runner.Budget
import com.eignex.klause.bench.runner.ResolvedProblem
import com.eignex.lab.bench.BenchRequest
import java.nio.file.Files
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NativeLpAdapterTest {
    @ParameterizedTest
    @CsvSource("mps-core,blend-tiny,MPS", "smtlib-qfnra-core-v1,exact/wide-exact-rational,SMTLIB")
    fun `native plans should preserve original inputs and label their runtime`(
        suite: String,
        name: String,
        format: String,
    ) {
        val request = BenchRequest(parameters = mapOf("suite" to suite, "name" to name))

        val case = NativeLpSuite.plan(request).single()

        assertEquals(format, case.attributes["format"])
        assertEquals("kotlin-native-linux-x86_64", case.attributes["cliRuntime"])
        assertEquals(1, case.resources.cpus)
        assertEquals("model", case.inputs.single().role)
    }

    @Test
    fun `native LP plans should reject other source formats`() {
        val request = BenchRequest(parameters = mapOf("suite" to "dimacs-core", "name" to "php4"))

        assertFailsWith<IllegalArgumentException> { NativeLpSuite.plan(request) }
    }

    @Test
    fun `native LP plans should reject multiple processors`() {
        val request = BenchRequest(parameters = mapOf("suite" to "mps-core", "name" to "blend-tiny", "cpus" to "2"))

        assertFailsWith<IllegalArgumentException> { NativeLpSuite.plan(request) }
    }

    @ParameterizedTest
    @CsvSource("MPS,source.mps,PB_COMPETITION", "SMTLIB,source.smt2,SMT_LIB")
    fun `native commands should use the selected executable and retain source controls`(
        format: Format,
        filename: String,
        dialect: String,
    ) {
        val root = Files.createTempDirectory("native-cli-command").toFile()
        try {
            val executable = root.resolve("klause-cli.kexe").apply { writeText("fixture"); setExecutable(true) }
            val source = root.resolve(filename)
            val ref = ProblemRef("source", format, ProblemSource.Prepared(source), Category.OPTIMIZATION,
                Expected.Unknown)
            val entry = ResolvedProblem(ref, lazy { error("subprocess commands must not materialize a model") })

            val command = SolverInvocation.klauseCommand(entry,
                SolverInvocation.Settings(processors = 1, exact = true), Budget(1000), true, source, executable)

            assertEquals(executable.absolutePath, command.first())
            assertEquals(dialect, SolverInvocation.dialectFor(entry.ref.format).name)
            if (format == Format.MPS) assertTrue(command.windowed(2).contains(listOf("--format", "mps")))
            assertTrue(command.windowed(2).contains(listOf("-p", "1")))
            assertTrue("--exact" in command)
        } finally {
            root.deleteRecursively()
        }
    }
}
