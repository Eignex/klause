package com.eignex.klause.bench.source

import com.eignex.klause.ir.ObjectiveSense
import com.github.luben.zstd.ZstdOutputStreamNoFinalizer
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream
import java.io.InterruptedIOException
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class MpsObjectiveTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun initializeCodec() {
            ZstdOutputStreamNoFinalizer(ByteArrayOutputStream(), 1).use { it.write(ByteArray(0)) }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `objective metadata should support plain and compressed sources`(compressed: Boolean, @TempDir root: Path) {
        val source = root.resolve("source.mps").toFile().apply {
            writeText("NAME example\nOBJSENSE MAX\nROWS\n N cost\nENDATA\n")
        }
        val input = if (compressed) CorpusFiles.compress(source) else source

        assertEquals(ObjectiveSense.MAXIMIZE, MpsObjective.read(input))
    }

    @ParameterizedTest
    @ValueSource(strings = ["OBJSENSE MAX", "OBJSENSE\n MAXIMIZE", "objsense max"])
    fun `maximization metadata should not parse columns`(directive: String, @TempDir root: Path) {
        val source = root.resolve("source.mps").toFile().apply {
            writeText("NAME example\n$directive\nROWS\n N cost\nCOLUMNS\n invalid columns\nENDATA\n")
        }

        assertEquals(ObjectiveSense.MAXIMIZE, MpsObjective.read(source))
    }

    @ParameterizedTest
    @ValueSource(strings = ["ROWS\n N cost", "LAZYCONS\n N cost", "ROWS\n N cost $ comment\n N ignored"])
    fun `a declared constant objective should select minimization`(rows: String, @TempDir root: Path) {
        val source = root.resolve("source.mps").toFile().apply { writeText("NAME example\n$rows\nENDATA\n") }

        assertEquals(ObjectiveSense.MINIMIZE, MpsObjective.read(source))
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "* N comment\nROWS\n L row\nCOLUMNS\n x row 1",
        "ROWS\n $ N comment\n L row\nENDATA\nROWS\n N ignored",
        "ROWS\n L row\nUSERCUTS\n N ignored",
    ])
    fun `non objective rows should retain satisfaction`(body: String, @TempDir root: Path) {
        val source = root.resolve("source.mps").toFile().apply { writeText("NAME example\n$body\nENDATA\n") }

        assertNull(MpsObjective.read(source))
    }

    @Test
    fun `interrupted metadata scanning should stop before opening the source`(@TempDir root: Path) {
        try {
            Thread.currentThread().interrupt()

            assertFailsWith<InterruptedIOException> { MpsObjective.read(root.resolve("missing.mps").toFile()) }
        } finally {
            Thread.interrupted()
        }
    }
}
