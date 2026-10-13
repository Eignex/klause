package com.eignex.klause.bench.runner

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MiniZincCompatibilityTest {
    @ParameterizedTest
    @ValueSource(strings = [
        "Error: type error: undefined identifier `is_output'",
        "Error: type error: no function or predicate with this signature found: int_search",
    ])
    fun `legacy annotation failures are source type incompatibilities`(diagnostic: String) {
        assertEquals("type-error", MiniZincCompileFailure.classify(diagnostic))
    }

    @Test
    fun `array index mismatch is source instance incompatibility`() {
        assertEquals("index-set-mismatch", MiniZincCompileFailure.classify(
            "Error: evaluation error: Index set mismatch. Declared index sets of argument DSR are different",
        ))
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "Error: cannot open file 'missing.mzn'",
        "Error: evaluation error: out of memory",
        "compiler crashed with signal 11",
        "compiler timed out",
    ])
    fun `infrastructure and unclassified failures cannot quarantine a source`(diagnostic: String) {
        assertNull(MiniZincCompileFailure.classify(diagnostic))
    }
}
