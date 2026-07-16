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
        // at least 2 of 3 vars true; the model line must assign every variable as xi or -xi.
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
        // `x1 x2` mints a Tseitin AND-indicator (a third bool var); it must not leak into the `v` line,
        // which reports only the declared x1..xN.
        val out = capture { main(arrayOf(opb("+1 x1 x2 >= 1 ;\n"))) }
        assertTrue("s SATISFIABLE" in out, out)
        val model = out.lines().first { it.startsWith("v ") }.removePrefix("v ").trim().split(" ")
        assertEquals(setOf("x1", "x2"), model.map { it.removePrefix("-") }.toSet(), "only declared vars: $out")
    }

    @Test
    fun `an optimisation instance proves the objective optimum`() {
        // min 1x1+2x2+3x3+4x4 under a set cover whose optimum is x1=x3=1 -> cost 4.
        val out = capture {
            main(
                arrayOf(
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
        // x1 >= 1 and x1 <= 0 cannot both hold.
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
