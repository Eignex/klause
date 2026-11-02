package com.eignex.klause.cli

import com.eignex.klause.formats.mps.mpsSemantics
import com.eignex.klause.ir.SemanticOverrides
import com.eignex.klause.ir.SourceTolerance
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class MpsNumericalSemanticsTest {
    @Test
    fun `loading MPS without statistics should emit the captured invocation policy`() {
        val source = File.createTempFile("mps-policy", ".mps").apply {
            writeText("NAME T\nROWS\n N COST\nCOLUMNS\n X COST 1\nENDATA\n")
        }
        val bytes = ByteArrayOutputStream()
        val previous = System.out
        val options = CommonOptions().apply { exact = true }
        try {
            System.setOut(PrintStream(bytes))

            MpsMode.newSession().load(source.absolutePath, options)

            assertEquals(
                "c numericalSemantics=${mpsNumericalSemantics(checkNotNull(options.sourceSemantics))}\n",
                bytes.toString().replace("\r\n", "\n"),
            )
        } finally {
            System.setOut(previous)
            source.delete()
        }
    }

    @Test
    fun `default MPS policy should declare exact rational tolerance components`() {
        val exported = mpsNumericalSemantics(mpsSemantics.resolve())

        assertContains(exported, "\"schema\":\"mps-decimal-semantics-v1\"")
        assertContains(exported, "\"numericAuthority\":\"DECIMAL_LITERALS\"")
        assertEquals(2, Regex("\"absolute\":\"1/10000000\"").findAll(exported).count())
        assertEquals(2, Regex("\"relative\":\"1/10000000\"").findAll(exported).count())
        assertContains(exported, "\"undeclaredIntegers\":{\"lower\":\"0\",\"upper\":\"1\"}")
        assertContains(exported, "\"undeclaredReals\":{\"lower\":\"0/1\",\"upper\":null}")
        assertContains(exported, "\"integrality\":\"exact\",\"objective\":\"exact\"")
    }

    @Test
    fun `zero tolerance override should preserve declared defaults separately`() {
        val policy = mpsSemantics.resolve(SemanticOverrides(feasibilityTolerance = SourceTolerance()))

        val exported = mpsNumericalSemantics(policy)

        assertEquals(1, Regex("\"absolute\":\"1/10000000\"").findAll(exported).count())
        assertEquals(1, Regex("\"absolute\":\"0/1\"").findAll(exported).count())
        assertContains(exported.substringAfter("\"effective\":"), "\"relative\":\"0/1\"")
    }
}
