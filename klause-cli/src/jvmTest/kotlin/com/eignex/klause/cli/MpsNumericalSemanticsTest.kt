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
                "c numericalSemantics=${mpsNumericalSemantics(checkNotNull(options.sourceSemantics))}\n" +
                    "c optimalityTolerance=${mpsOptimalityTolerance(checkNotNull(options.sourceSemantics))}\n",
                bytes.toString().replace("\r\n", "\n"),
            )
        } finally {
            System.setOut(previous)
            source.delete()
        }
    }

    @Test
    fun `default MPS policy should export exactly the versioned policy keys`() {
        val policy = "{\"numericAuthority\":\"DECIMAL_LITERALS\"," +
            "\"feasibilityTolerance\":{\"absolute\":\"1/10000000\",\"relative\":\"0/1\"}," +
            "\"undeclaredIntegers\":{\"lower\":\"0\",\"upper\":\"1\"}," +
            "\"undeclaredReals\":{\"lower\":\"0/1\",\"upper\":null}," +
            "\"integrality\":\"exact\",\"objective\":\"exact\"}"

        val exported = mpsNumericalSemantics(mpsSemantics.resolve())

        assertEquals("{\"schema\":\"mps-decimal-semantics-v1\",\"declared\":$policy,\"effective\":$policy}", exported)
    }

    @Test
    fun `zero tolerance override should preserve declared defaults separately`() {
        val policy = mpsSemantics.resolve(SemanticOverrides(feasibilityTolerance = SourceTolerance()))

        val exported = mpsNumericalSemantics(policy)

        assertContains(exported.substringBefore("\"effective\":"),
            "\"feasibilityTolerance\":{\"absolute\":\"1/10000000\",\"relative\":\"0/1\"}")
        assertContains(exported.substringAfter("\"effective\":"),
            "\"feasibilityTolerance\":{\"absolute\":\"0/1\",\"relative\":\"0/1\"}")
    }

    @Test
    fun `default MPS policy should declare a relative optimality gap with the feasibility floor`() {
        val exported = mpsOptimalityTolerance(mpsSemantics.resolve())

        val gap = "{\"absolute\":\"1/10000000\",\"relative\":\"1/10000\"}"
        assertEquals(2, Regex.fromLiteral(gap).findAll(exported).count())
    }

    @Test
    fun `exact MPS loading should print a zero optimality gap`() {
        val options = CommonOptions().apply { exact = true }

        options.sourceOverrides(mpsSemantics)

        val exported = mpsOptimalityTolerance(checkNotNull(options.sourceSemantics))
        assertContains(exported.substringAfter("\"effective\":"),
            "{\"absolute\":\"0/1\",\"relative\":\"0/1\"}")
    }
}
