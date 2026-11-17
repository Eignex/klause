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

class SmtObjectiveTest {
    @Test
    fun `compressed input retains the objective direction`(@TempDir root: Path) {
        val source = root.resolve("source.smt2").toFile().apply { writeText("(maximize 3) (check-sat)") }
        val packed = CorpusFiles.compress(source)

        assertEquals(ObjectiveSense.MAXIMIZE, SmtObjective.read(packed))
    }

    @Test
    fun `interrupted metadata scanning stops before reading the source`(@TempDir root: Path) {
        val source = root.resolve("source.smt2").toFile().apply { writeText("(minimize 3) (check-sat)") }
        try {
            Thread.currentThread().interrupt()

            assertFailsWith<InterruptedIOException> { SmtObjective.read(source) }
        } finally {
            Thread.interrupted()
        }
    }

    @ParameterizedTest
    @EnumSource(ObjectiveSense::class)
    fun `objective direction comes from the command head`(sense: ObjectiveSense, @TempDir root: Path) {
        val source = root.resolve("source.smt2").toFile().apply {
            writeText("(declare-const x Real)\n(; metadata\n ${sense.name.lowercase()} (* x x))\n(check-sat)")
        }

        assertEquals(sense, SmtObjective.read(source))
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "; (maximize x)\n(assert true)\n(check-sat)",
        "(set-info :source \"quoted \"\" (maximize x)\")\n(check-sat)",
        "(declare-const |name (minimize x)| Bool)\n(check-sat)",
        "(declare-fun maximize (Int) Bool)\n(assert (maximize 1))\n(check-sat)",
        "(maximizer x)\n(check-sat)",
        "(exit)\n(maximize x)",
    ])
    fun `non objective text does not select optimization`(text: String, @TempDir root: Path) {
        val source = root.resolve("source.smt2").toFile().apply { writeText(text) }

        assertNull(SmtObjective.read(source))
    }
}
