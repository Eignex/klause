package com.eignex.klause.bench.runner

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class MiniZincCompatibilityTest {
    @ParameterizedTest
    @ValueSource(
        strings = [
            "Error: type error: undefined identifier `is_output'",
            "Error: type error: no function or predicate with this signature found: int_search",
        ],
    )
    fun `legacy annotation failures are source type incompatibilities`(diagnostic: String) {
        assertEquals("type-error", MiniZincCompileFailure.classify(diagnostic))
    }

    @Test
    fun `array index mismatch is source instance incompatibility`() {
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
    fun `infrastructure and unclassified failures cannot quarantine a source`(diagnostic: String) {
        assertNull(MiniZincCompileFailure.classify(diagnostic))
    }

    @Test
    fun `library identity changes when an included source changes`() {
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
    fun `abnormal compiler exits cannot classify stale type diagnostics as source incompatibility`() {
        val identity = MiniZincCompilerIdentity(
            version = "test",
            executable = "test",
            executableSha256 = "test",
            standardLibrary = "test",
            standardLibrarySha256 = "test",
            solverLibrarySha256 = "test",
            configurationSha256 = "test",
        )
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
