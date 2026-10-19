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
    fun `unclassified compile failures should still stop admission`() {
        val crash = failure(139, "compiler crashed with signal 11")

        assertFailsWith<MiniZincCompileFailure> { MiniZincCompatibility.exclusion(ref) { throw crash } }
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
            "Error: cannot open file 'missing.mzn'",
            "Error: evaluation error: out of memory",
            "compiler crashed with signal 11",
            "compiler timed out",
        ],
    )
    fun `infrastructure and unclassified failures should not quarantine a source`(diagnostic: String) {
        assertNull(MiniZincCompileFailure.classify(diagnostic))
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
