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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NativeLpAdapterTest {
    @Test
    fun `native plans should preserve original MPS inputs and label their runtime`() {
        val request = BenchRequest(parameters = mapOf("suite" to "mps-core", "name" to "blend-tiny"))

        val case = NativeLpSuite.plan(request).single()

        assertEquals("MPS", case.attributes["format"])
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

    @Test
    fun `native commands should use the selected executable and retain exact single processor controls`() {
        val root = Files.createTempDirectory("native-cli-command").toFile()
        try {
            val executable = root.resolve("klause-cli.kexe").apply { writeText("fixture"); setExecutable(true) }
            val source = root.resolve("source.mps")
            val ref = ProblemRef("source", Format.MPS, ProblemSource.Prepared(source), Category.OPTIMIZATION,
                Expected.Unknown)
            val entry = ResolvedProblem(ref, lazy { error("subprocess commands must not materialize a model") })

            val command = SolverInvocation.klauseCommand(entry,
                SolverInvocation.Settings(processors = 1, exact = true), Budget(1000), true, source, executable)

            assertEquals(executable.absolutePath, command.first())
            assertTrue(command.windowed(2).contains(listOf("--format", "mps")))
            assertTrue(command.windowed(2).contains(listOf("-p", "1")))
            assertTrue("--exact" in command)
        } finally {
            root.deleteRecursively()
        }
    }
}
