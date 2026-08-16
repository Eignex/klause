package com.eignex.klause.cli

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WcnfModeTest {

    private fun capture(block: () -> Unit): String {
        val buf = ByteArrayOutputStream()
        val old = System.out
        System.setOut(PrintStream(buf))
        try {
            block()
        } finally {
            System.setOut(old)
        }
        return buf.toString()
    }

    private fun wcnf(text: String): String = File.createTempFile("wcnf", ".wcnf").apply {
        writeText(text)
        deleteOnExit()
    }.absolutePath

    @Test
    fun `a new-format weighted instance proves the minimum-cost solution`() {
        // hard x1 or x2; soft ~x1 (cost 3) and ~x2 (cost 1). Optimum sets x2, paying only 1.
        val out = capture { main(arrayOf("-e", "bt", wcnf("h 1 2 0\n3 -1 0\n1 -2 0\n"))) }
        assertTrue("s OPTIMUM FOUND" in out, out)
        assertTrue("o 1" in out.lines().map { it.trim() }, "expected optimum cost 1: $out")
        // The v line reports only the two original variables as a bit-string.
        val model = out.lines().first { it.startsWith("v ") }.removePrefix("v ").trim()
        assertEquals("01", model, out)
    }

    @Test
    fun `an old-format p wcnf instance routes and proves its optimum`() {
        // top 10 makes the first clause hard; cheapest cover sets x1 only, cost 1.
        val out = capture { main(arrayOf("-e", "bt", wcnf("p wcnf 3 4 10\n10 1 2 3 0\n1 -1 0\n2 -2 0\n3 -3 0\n"))) }
        assertTrue("s OPTIMUM FOUND" in out, out)
        assertTrue("o 1" in out.lines().map { it.trim() }, "expected optimum cost 1: $out")
    }

    @Test
    fun `infeasible hard clauses print s UNSATISFIABLE`() {
        val out = capture { main(arrayOf(wcnf("h 1 0\nh -1 0\n2 -2 0\n"))) }
        assertTrue("s UNSATISFIABLE" in out, out)
    }

    @Test
    fun `the wcnf extension and the explicit format both route to the WCNF front-end`() {
        val path = wcnf("h 1 0\n1 -1 0\n")
        val byExt = capture { main(arrayOf("-e", "bt", path)) }
        val byFormat = capture { main(arrayOf("-e", "bt", "--format", "wcnf", path)) }
        assertTrue("s OPTIMUM FOUND" in byExt && "s OPTIMUM FOUND" in byFormat, "$byExt\n---\n$byFormat")
    }
}
