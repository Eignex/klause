package com.eignex.klause.cli

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class SmtLibSessionCliTest {
    @Test
    fun `one script can query checked models across assertion stack changes`() {
        val source = File.createTempFile("smt-session", ".smt2")
        source.writeText("""
            (set-option :produce-models true)
            (declare-const p Bool)
            (assert p)
            (check-sat)
            (get-value ((not p)))
            (push 1)
            (assert (not p))
            (check-sat)
            (pop 1)
            (check-sat)
            (get-value (p))
        """.trimIndent())

        val output = try {
            captureStdout { assertEquals(0, runCli(arrayOf("-e", "cp", source.absolutePath))) }
        } finally { source.delete() }

        assertEquals(listOf("sat", "(((not p) false))", "unsat", "sat", "((p true))"), output.trim().lines())
    }

    @Test
    fun `regular output destinations append and reset restores stdout`() {
        val source = File.createTempFile("smt-channels", ".smt2")
        val destination = File.createTempFile("smt-replies", ".txt")
        destination.writeText("existing\n")
        source.writeText("""
            (set-option :regular-output-channel "${destination.absolutePath}")
            (echo "first")
            (reset)
            (echo "last")
        """.trimIndent())

        try {
            val output = captureStdout { assertEquals(0, runCli(arrayOf(source.absolutePath))) }

            assertEquals("\"last\"", output.trim())
            assertEquals("existing\n\"first\"\n", destination.readText())
        } finally {
            source.delete()
            destination.delete()
        }
    }

    @Test
    fun `a script without a query produces no solver result`() {
        val source = File.createTempFile("smt-declarations", ".smt2")
        source.writeText("(declare-const p Bool) (assert p)")

        val output = try {
            captureStdout { assertEquals(0, runCli(arrayOf(source.absolutePath))) }
        } finally { source.delete() }

        assertEquals("", output)
    }
}
