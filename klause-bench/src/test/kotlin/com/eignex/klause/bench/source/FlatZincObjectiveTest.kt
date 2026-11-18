package com.eignex.klause.bench.source

import com.eignex.klause.ir.ObjectiveSense
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import java.io.InterruptedIOException
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class FlatZincObjectiveTest {
    @ParameterizedTest
    @EnumSource(ObjectiveSense::class)
    fun `solve annotations should retain objective direction`(sense: ObjectiveSense) {
        val text = "var 0..2: x; solve :: seq_search([int_search([x], input_order, indomain_min, complete)])\n" +
            "${sense.name.lowercase()} x;"

        assertEquals(sense, FlatZincObjective.read(text.reader()))
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "% solve maximize x;\nsolve satisfy;",
        "/* solve maximize x; */ solve satisfy;",
        "output [\"solve minimize x; % comment\"]; solve satisfy;",
        "annotation note(string: x); solve :: note(\"maximize; solve minimize x\") satisfy;",
        "int: solve_count = 1; solve satisfy;",
        "function int: f(int: x) = let { int: y = x; } in y; solve satisfy;",
    ])
    fun `non directive text should retain satisfaction`(text: String) {
        assertNull(FlatZincObjective.read(text.reader()))
    }

    @Test
    fun `interrupted metadata scanning should stop before opening the source`(@TempDir root: Path) {
        try {
            Thread.currentThread().interrupt()

            assertFailsWith<InterruptedIOException> { FlatZincObjective.read(root.resolve("missing.fzn").toFile()) }
        } finally {
            Thread.interrupted()
        }
    }
}
