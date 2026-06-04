package com.eignex.klause.cli

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertTrue

class CliTest {

    private fun capture(block: () -> Unit): String {
        val buf = ByteArrayOutputStream()
        val old = System.out
        System.setOut(PrintStream(buf))
        try { block() } finally { System.setOut(old) }
        return buf.toString()
    }

    @Test
    fun `engine params reach the backtrack and ls engines`() {
        val fzn = File.createTempFile("cli", ".fzn").apply {
            writeText("var 1..3: x;\nconstraint int_lt(x, 3);\nsolve satisfy;\n")
            deleteOnExit()
        }
        for (engineArgs in listOf(
            arrayOf("-e", "backtrack", "-p", "seed=7", "-p", "val-heuristic=max", "-p", "luby=50"),
            arrayOf("-e", "ls", "-p", "seed=7", "-p", "tabu-tenure=5", "-p", "lambda=2.0", "-t", "5000"),
        )) {
            val out = capture { main(engineArgs + fzn.absolutePath) }
            assertTrue("x = " in out, out)
            assertTrue("----------" in out, out)
        }
    }

    @Test
    fun `portfolio engine solves with explicit worker counts`() {
        val fzn = File.createTempFile("cli", ".fzn").apply {
            writeText("var 1..3: x;\nconstraint int_lt(x, 3);\nsolve satisfy;\n")
            deleteOnExit()
        }
        val out = capture {
            main(arrayOf("-e", "portfolio", "-p", "ls=1", "-p", "bt=1", "-t", "10000", fzn.absolutePath))
        }
        assertTrue("x = " in out, out)
    }

    @Test
    fun `coverage report counts parsed, solved and unsupported`() {
        val dir = File.createTempFile("xcsp-cov", "").let { it.delete(); it.mkdirs(); it }
        try {
            File(dir, "ok.smt2").writeText(
                "(declare-const x Int)(declare-const y Int)" +
                    "(assert (>= x 0))(assert (>= y 0))(assert (<= (+ x y) 5))(check-sat)"
            )
            File(dir, "sat.xml").writeText(
                """<instance type="CSP"><variables><var id="a"> 1..3 </var><var id="b"> 1..3 </var></variables>
                   <constraints><allDifferent> a b </allDifferent></constraints></instance>"""
            )
            // `pow` is outside the linear intension subset → an unsupported construct.
            File(dir, "bad.xml").writeText(
                """<instance type="CSP"><variables><var id="a"> 1..3 </var></variables>
                   <constraints><intension> eq(pow(a,2),4) </intension></constraints></instance>"""
            )

            val out = capture { main(arrayOf("--coverage", "-t", "5000", dir.absolutePath)) }
            assertTrue("total instances : 3" in out, out)
            assertTrue("parsed          : 2" in out, out)
            assertTrue("unsupported     : 1" in out, out)
            assertTrue("UNSUPPORTED" in out, out)
        } finally {
            dir.deleteRecursively()
        }
    }
}
