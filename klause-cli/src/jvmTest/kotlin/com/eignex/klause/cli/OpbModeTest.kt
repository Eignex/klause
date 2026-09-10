package com.eignex.klause.cli

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpbModeTest {

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

    private fun opb(text: String): String = File.createTempFile("opb", ".opb").apply {
        writeText(text)
        deleteOnExit()
    }.absolutePath

    @Test
    fun `a satisfiable pseudo-boolean instance prints s SATISFIABLE with a full literal model line`() {
        val out = capture { main(arrayOf(opb("+1 x1 +1 x2 +1 x3 >= 2 ;\n"))) }
        assertTrue("s SATISFIABLE" in out, out)
        val model = out.lines().first { it.startsWith("v ") }.removePrefix("v ").trim().split(" ")
        assertTrue(
            model.map { it.removePrefix("-") }.toSet() == setOf("x1", "x2", "x3"),
            "model must cover all vars: $out",
        )
        assertTrue(model.count { !it.startsWith("-") } >= 2, "at least two vars must be true: $out")
    }

    @Test
    fun `the model line lists only declared variables not reified product indicators`() {
        val out = capture { main(arrayOf(opb("+1 x1 x2 >= 1 ;\n"))) }
        assertTrue("s SATISFIABLE" in out, out)
        val model = out.lines().first { it.startsWith("v ") }.removePrefix("v ").trim().split(" ")
        assertEquals(setOf("x1", "x2"), model.map { it.removePrefix("-") }.toSet(), "only declared vars: $out")
    }

    @Test
    fun `an optimisation instance proves the objective optimum`() {
        val out = capture {
            main(
                arrayOf(
                    "-e",
                    "bt",
                    opb(
                        "min: 1 x1 +2 x2 +3 x3 +4 x4 ;\n" +
                            "+1 x1 +1 x2 >= 1 ;\n" +
                            "+1 x2 +1 x3 +1 x4 >= 1 ;\n" +
                            "+1 x1 +1 x3 +1 x4 >= 2 ;\n",
                    ),
                ),
            )
        }
        assertTrue("s OPTIMUM FOUND" in out, out)
        assertTrue("o 4" in out.lines().map { it.trim() }, "expected optimum cost 4: $out")
    }

    @Test
    fun `an infeasible instance prints s UNSATISFIABLE`() {
        val out = capture { main(arrayOf(opb("+1 x1 >= 1 ;\n+1 x1 <= 0 ;\n"))) }
        assertTrue("s UNSATISFIABLE" in out, out)
    }

    @Test
    fun `the opb extension and the explicit format both route to the OPB front-end`() {
        val path = opb("+1 x1 >= 1 ;\n")
        val byExt = capture { main(arrayOf(path)) }
        val byFormat = capture { main(arrayOf("--format", "opb", path)) }
        assertTrue("s SATISFIABLE" in byExt && "s SATISFIABLE" in byFormat, "$byExt\n---\n$byFormat")
    }
}
