package com.eignex.klause.bench.lab

import com.eignex.klause.bench.catalog.Category
import com.eignex.klause.bench.catalog.Expected
import com.eignex.klause.bench.catalog.Format
import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.catalog.ProblemSource
import com.eignex.klause.bench.metric.SolveMetric
import com.eignex.klause.bench.metric.SolverInvocation
import com.eignex.klause.bench.runner.Budget
import com.eignex.lab.bench.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LabAdapterTest {
    @Test
    fun `paired propagation controls retain source identity and distinct case identities`() {
        val cases = KlauseLabSuite.plan(BenchRequest(parameters = mapOf(
            "suite" to "dimacs-core", "name" to "php4", "engines" to "cp", "propagation-controls" to "true",
        )))

        assertEquals(3, cases.map { it.id }.distinct().size)
        assertEquals(1, cases.map { it.inputs }.distinct().size)
        assertEquals(1, cases.map { it.attributes.getValue("pairKey") }.distinct().size)
        assertEquals(listOf("false", "true", "true"), cases.map { it.parameters["param.propagation-attribution"] })
        assertEquals(listOf("false", "false", "true"), cases.map { it.parameters["param.historical-table-reasons"] })
    }

    @Test
    fun `paired propagation controls reject conflicting switch overrides`() {
        assertFailsWith<IllegalArgumentException> {
            LabSettings(mapOf("engines" to "cp", "propagation-controls" to "true",
                "param.historical-table-reasons" to "false"))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["param.source-validation", "param.source-validation-gzip"])
    fun `source checks are requested from the lab instead of the solver`(key: String) {
        val failure = assertFailsWith<IllegalArgumentException> { LabSettings(mapOf(key to "true")) }

        assertContains(failure.message.orEmpty(), "bench.sourceValidation")
    }

    @ParameterizedTest
    @CsvSource(
        "typo,true", "cpus,0", "memory-mib,2047", "repeats,0", "timeout-ms,0", "engines,no-such-engine",
        "debug,yes", "exact,yes", "measurement,arbitrary", "heap-mib,8192", "gc,arbitrary", "compile-timeout-sec,0",
        "max,no", "lp,invalid", "presolve,invalid", "param.,value", "complete-assignment,yes", "compiler-seed,3",
    )
    fun `invalid controls fail validation`(key: String, value: String) {
        assertFailsWith<IllegalArgumentException> { LabSettings(mapOf(key to value)) }
    }

    @Test
    fun `compiler seed remains explicit in each planned seed and repeat block`() {
        val cases = KlauseLabSuite.plan(BenchRequest(parameters = mapOf(
            "suite" to "dimacs-core", "name" to "php4", "compiler-seed" to "1",
            "solver-seeds" to "1,3", "repeats" to "2",
        )))

        assertEquals(4, cases.size)
        assertEquals(setOf("1"), cases.map { it.parameters["compiler-seed"] }.toSet())
        assertEquals(setOf("1", "3"), cases.map { it.parameters["seed"] }.toSet())
        assertEquals(4, cases.map { it.attributes["comparisonKey"] }.toSet().size)
    }

    @Test
    fun `comparison controls distinguish resource and deadline policies`() {
        fun controls(parameters: Map<String, String>): Map<String, String> {
            val settings = LabSettings(parameters)
            return KlauseLabSuite.comparisonControls(BenchCase("case", settings.caseParameters("cp", 3), settings.resources()))
        }
        val baseline = controls(emptyMap())

        assertEquals("matched-source-v1", baseline["policy"])
        assertEquals("3", baseline["solverSeed"])
        assertEquals("1", baseline["compilerSeed"])
        assertEquals("cli-inclusive-v1", baseline["deadlinePolicy"])
        assertEquals("2", controls(mapOf("cpus" to "2"))["cpus"])
        assertEquals("5000", controls(mapOf("timeout-ms" to "5000"))["solverBudgetMs"])
        assertEquals("true", controls(mapOf("exact" to "true"))["exact"])
    }

    @ParameterizedTest
    @ValueSource(strings = ["default,+table-substitution", "off,+affine", "aggressive,-structural"])
    fun `presolve pass controls should reach the subprocess unchanged`(spec: String) {
        val settings = LabSettings(mapOf("presolve" to spec))

        assertEquals(spec, settings.caseParameters("cp", 3)["presolve"])
    }

    @ParameterizedTest
    @ValueSource(strings = ["default,+missing", "default,affine", "off,-missing"])
    fun `unknown presolve pass controls should fail before planning`(spec: String) {
        assertFailsWith<IllegalArgumentException> { LabSettings(mapOf("presolve" to spec)) }
    }

    @Test
    fun `case resource ceilings include compilation and subprocess shutdown`() {
        val settings = LabSettings(mapOf("timeout-ms" to "1000", "compile-timeout-sec" to "30", "cpus" to "2"))
        val resources = settings.resources()
        assertEquals(202L, resources.timeoutSeconds)
        assertEquals(2, resources.cpus)
        assertEquals(LabSettings.runtimeTags(), resources.requiredTags)
    }

    @ParameterizedTest
    @CsvSource(
        "Mac OS X,aarch64,mac,arm64", "Mac OS X,arm64,mac,arm64",
        "Mac OS X,x86_64,mac,x86_64", "Linux,amd64,linux,x86_64",
    )
    fun `prepared solver cases retain their build platform`(
        os: String, arch: String, platform: String, architecture: String,
    ) {
        assertEquals(setOf("platform:$platform", "arch:$architecture"), LabSettings.runtimeTags(os, arch))
    }

    @Test
    fun `input checks reject changed bytes after relocation`() {
        val root = Files.createTempDirectory("lab-input-")
        try {
            Files.writeString(root.resolve("model with spaces.cnf"), "changed")
            val case = BenchCase("case", inputs = listOf(BenchInput("model with spaces.cnf", sha256 = "0".repeat(64))))
            assertFailsWith<IllegalArgumentException> {
                LabInputs.verify(
                    case,
                    BenchContext(root.resolve("out"), worktree = root),
                )
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `runtime artifacts retain exact options for paths containing spaces`() {
        val root = Files.createTempDirectory("lab runtime ")
        try {
            val settings = LabSettings(mapOf("measurement" to "compilation+log", "debug" to "false", "gc" to "serial"))
            val case = BenchCase("case", settings.caseParameters("cp", 7), settings.resources())
            val environment = LabRuntime.environment(
                case,
                BenchContext(root.resolve("out with spaces"), worktree = root),
            )
            val args = Files.readString(root.resolve("out with spaces/cli.args"))
            val preflight = Files.readString(root.resolve("out with spaces/preflight.args"))
            assertTrue(args.contains("-XX:StartFlightRecording=filename="))
            assertTrue(args.contains("-XX:+LogCompilation"))
            assertFalse(preflight.contains("StartFlightRecording"))
            assertEquals("false", environment["KLAUSE_DEBUG"])
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @ParameterizedTest
    @CsvSource("unsupported,false", "killed,false", "loadError,true", "errorMessage,true")
    fun `domain refusals and timeouts remain distinct from failures`(stat: String, failed: Boolean) {
        val ref = ProblemRef("case", Format.DIMACS, ProblemSource.Vendored("absent"), Category.CSP, Expected.Unknown)
        val record = SolveMetric.loadFailureRecord(
            ref,
            SolverInvocation.Settings(),
            Budget(1000),
            "timestamp",
            null,
            IllegalArgumentException("unavailable"),
        ).copy(
            command = "solve",
            stats = mapOf(
                stat to
                    if (stat == "killed") "hard-timeout" else "reason",
            ),
        )
        assertEquals(failed, KlauseLabSuite.failure(record) != null)
    }

    @Test
    fun `failure after a verdict keeps the domain result and fails the case`() {
        val ref = ProblemRef("case", Format.DIMACS, ProblemSource.Vendored("absent"), Category.CSP, Expected.Unknown)
        val record = SolveMetric.loadFailureRecord(
            ref,
            SolverInvocation.Settings(),
            Budget(1000),
            "timestamp",
            null,
            IllegalArgumentException("unavailable"),
        ).copy(command = "solve", feasible = true, stats = mapOf("exit" to "7"))
        assertTrue(KlauseLabSuite.failure(record) != null)
        assertEquals(true, record.feasible)
        assertNull(record.objective)
    }

    @Test
    fun `include discovery ignores comments and output strings`() {
        val source = """
            % include "ignored.mzn";
            /* include "also ignored.mzn"; */
            output ["include \"text.mzn\";"];
            include /* comment */ "../space and % sign/companion.mzn";
        """.trimIndent()
        assertEquals(listOf("../space and % sign/companion.mzn"), LabInputs.includeNames(source))
    }
}
