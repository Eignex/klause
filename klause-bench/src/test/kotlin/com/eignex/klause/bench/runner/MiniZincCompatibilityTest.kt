package com.eignex.klause.bench.runner

import com.eignex.klause.bench.catalog.Category
import com.eignex.klause.bench.catalog.Expected
import com.eignex.klause.bench.catalog.Format
import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.catalog.corpus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class MiniZincCompatibilityTest {
    private val identity = MiniZincCompilerIdentity(
        version = "test",
        executable = "test",
        executableSha256 = "test",
        standardLibrary = "test",
        standardLibrarySha256 = "test",
        solverLibrarySha256 = "test",
        configurationSha256 = "test",
    )
    private val ref = ProblemRef("fam/slow", Format.MINIZINC, corpus("slow.mzn"), Category.CSP, Expected.Unknown)

    private fun failure(exitCode: Int, diagnostic: String, timeoutSec: Int? = null) =
        MiniZincCompileFailure(exitCode, diagnostic, identity, listOf("minizinc"), 7, mapOf("model" to "m"), timeoutSec)

    @Test
    fun `compile timeouts should exclude the source with their own reason`() {
        val timeout = failure(137, "minizinc compile timed out after 60s for slow.mzn", timeoutSec = 60)
        val captured = ByteArrayOutputStream()
        val original = System.err
        val admitted = try {
            System.setErr(PrintStream(captured, true))
            MiniZincCompatibility.admit("mzn-bench-compatible-v1", ref) { throw timeout }
        } finally {
            System.setErr(original)
        }

        assertFalse(admitted)
        val record = Json.parseToJsonElement(captured.toString().trim()).jsonObject
        assertEquals("fam/slow", record.getValue("problem").jsonPrimitive.content)
        assertEquals(MZN_COMPATIBILITY_POLICY, record.getValue("selectionPolicy").jsonPrimitive.content)
        assertEquals("source-compiler-incompatible", record.getValue("status").jsonPrimitive.content)
        assertEquals("compile-timeout", record.getValue("reason").jsonPrimitive.content)
        assertEquals(60, record.getValue("compilerTimeoutSec").jsonPrimitive.int)
        assertEquals(137, record.getValue("compilerExit").jsonPrimitive.int)
    }

    @Test
    fun `generic compile failures should exclude the source`() {
        val rejected = failure(1, "Error: result of evaluation is undefined: maximum of empty set is undefined")

        assertEquals("undefined-evaluation", MiniZincCompatibility.exclusion(ref) { throw rejected }?.incompatibility)
    }

    @Test
    fun `infrastructure failures should stop admission`() {
        val crash = failure(139, "compiler crashed with signal 11")
        val missing = failure(1, "Error: cannot open file 'klause/globals.mzn'")

        assertFailsWith<MiniZincCompileFailure> { MiniZincCompatibility.exclusion(ref) { throw crash } }
        assertFailsWith<MiniZincCompileFailure> { MiniZincCompatibility.exclusion(ref) { throw missing } }
        assertFailsWith<IllegalStateException> { MiniZincCompatibility.exclusion(ref) { error("corpus missing") } }
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "Error: type error: undefined identifier `is_output'",
            "Error: type error: no function or predicate with this signature found: int_search",
        ],
    )
    fun `annotation failures should be source type incompatibilities`(diagnostic: String) {
        assertEquals("type-error", MiniZincCompileFailure.classify(diagnostic))
    }

    @Test
    fun `array index mismatch should be a source instance incompatibility`() {
        assertEquals(
            "index-set-mismatch",
            MiniZincCompileFailure.classify(
            "Error: evaluation error: Index set mismatch. Declared index sets of argument DSR are different",
        )
        )
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "Error: result of evaluation is undefined: maximum of empty set is undefined",
            "Error: evaluation error: Assertion failed: n must be positive",
            "Error: flattening error: set of float is not supported",
        ],
    )
    fun `recognised compile rejections should get their own reason`(diagnostic: String) {
        val reason = MiniZincCompileFailure.classify(diagnostic)
        assertEquals(
            when {
                "undefined" in diagnostic -> "undefined-evaluation"
                "Assertion" in diagnostic -> "assertion"
                else -> "unsupported"
            },
            reason,
        )
    }

    @Test
    fun `unrecognised compile rejections should be generic compile errors`() {
        assertEquals("compile-error", failure(1, "Error: something new went wrong").incompatibility)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "Error: cannot open file 'missing.mzn'",
            "Error: evaluation error: out of memory",
            "Config exception: no solver with tag klause found",
        ],
    )
    fun `infrastructure failures should not quarantine a source`(diagnostic: String) {
        assertNull(failure(1, diagnostic).incompatibility)
    }

    @Test
    fun `library identity should change when an included source changes`() {
        val root = Files.createTempDirectory("mznlibrary").toFile()
        try {
            File(root, "nested").mkdirs()
            val source = File(root, "nested/predicate.mzn").apply { writeText("predicate p() = true;") }
            val original = MiniZincCompatibility.treeHash(root)

            source.writeText("predicate p() = false;")

            assertNotEquals(original, MiniZincCompatibility.treeHash(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `abnormal compiler exits should not classify stale type diagnostics as source incompatibility`() {
        val failure = MiniZincCompileFailure(
            137,
            "Error: type error: undefined identifier",
            identity,
            emptyList(),
            0,
            emptyMap(),
        )

        assertNull(failure.incompatibility)
    }
}
