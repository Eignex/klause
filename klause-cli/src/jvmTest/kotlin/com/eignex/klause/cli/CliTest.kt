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
        try {
            block()
        } finally {
            System.setOut(old)
        }
        return buf.toString()
    }

    @Test
    fun `engine params reach the cp and ls engines`() {
        val fzn = File.createTempFile("cli", ".fzn").apply {
            writeText("var 1..3: x;\nconstraint int_lt(x, 3);\nsolve satisfy;\n")
            deleteOnExit()
        }
        for (engineArgs in listOf(
            arrayOf("-e", "cp", "--param", "seed=7", "--param", "val-heuristic=max", "--param", "luby=50"),
            arrayOf("-e", "ls", "--param", "seed=7", "--param", "tabu-tenure=5", "--param", "lambda=2.0", "-t", "5000"),
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
            main(arrayOf("-e", "portfolio", "--param", "ls=1", "--param", "bt=1", "-t", "10000", fzn.absolutePath))
        }
        assertTrue("x = " in out, out)
    }

    @Test
    fun `minizinc standard -p routes to a parallel portfolio`() {
        val fzn = File.createTempFile("cli", ".fzn").apply {
            writeText("var 1..3: x;\nconstraint int_lt(x, 3);\nsolve satisfy;\n")
            deleteOnExit()
        }
        // No -e: mixed pool sized to 2. With -e ls: pure-LS pool, still solves.
        for (engineArgs in listOf(arrayOf("-p", "2"), arrayOf("-e", "ls", "-p", "2"))) {
            val out = capture { main(engineArgs + arrayOf("-t", "10000", fzn.absolutePath)) }
            assertTrue("x = " in out, out)
        }
    }

    @Test
    fun `-s prints mzn-stat lines for satisfy and optimize verdicts`() {
        val sat = File.createTempFile("cli", ".fzn").apply {
            writeText("var 1..3: x;\nconstraint int_lt(x, 3);\nsolve satisfy;\n")
            deleteOnExit()
        }
        val opt = File.createTempFile("cli", ".fzn").apply {
            writeText("var 1..9: x;\nsolve maximize x;\n")
            deleteOnExit()
        }
        val satOut = capture { main(arrayOf("-s", sat.absolutePath)) }
        assertTrue("%%%mzn-stat: solveTime=" in satOut, satOut)
        assertTrue("%%%mzn-stat: solutions=1" in satOut, satOut)
        assertTrue("%%%mzn-stat: nodes=" in satOut, satOut)
        assertTrue("%%%mzn-stat-end" in satOut, satOut)
        // The stats block must come after the protocol terminator, not between solutions.
        assertTrue(satOut.indexOf("==========") < satOut.indexOf("%%%mzn-stat:"), satOut)

        val optOut = capture { main(arrayOf("-s", opt.absolutePath)) }
        assertTrue("%%%mzn-stat: solutions=" in optOut, optOut)
        assertTrue("%%%mzn-stat-end" in optOut, optOut)
    }

    @Test
    fun `xcsp3 csp emits competition SATISFIABLE protocol with named instantiation`() {
        val xml = File.createTempFile("cli", ".xml").apply {
            writeText(
                """<instance type="CSP"><variables><var id="a"> 1..3 </var><var id="b"> 1..3 </var></variables>
                   <constraints><allDifferent> a b </allDifferent></constraints></instance>""",
            )
            deleteOnExit()
        }
        val out = capture { main(arrayOf("-t", "5000", xml.absolutePath)) }
        assertTrue("s SATISFIABLE" in out, out)
        assertTrue("v <instantiation>" in out, out)
        assertTrue("<list> a b </list>" in out, out)
        // No MiniZinc-flavored tokens must leak into XCSP output.
        assertTrue("----------" !in out, out)
        assertTrue("%%%mzn-stat" !in out, out)
    }

    @Test
    fun `xcsp3 cop emits o lines and OPTIMUM FOUND with the true objective`() {
        val xml = File.createTempFile("cli", ".xml").apply {
            writeText(
                """<instance type="COP"><variables><var id="a"> 1..3 </var></variables>
                   <objectives><maximize> a </maximize></objectives></instance>""",
            )
            deleteOnExit()
        }
        val out = capture { main(arrayOf("-t", "5000", "-s", xml.absolutePath)) }
        assertTrue("s OPTIMUM FOUND" in out, out)
        // maximize a over 1..3 → optimum 3, reported sign-corrected (not the negated internal).
        assertTrue("o 3" in out, out)
        assertTrue("<values> 3 </values>" in out, out)
        // `-s` statistics adapt to XCSP `c` comment lines, not %%%mzn-stat.
        assertTrue("c solveTime=" in out, out)
    }

    @Test
    fun `smtlib emits sat and a get-model block`() {
        val smt = File.createTempFile("cli", ".smt2").apply {
            writeText(
                "(declare-const x Int)(assert (>= x 0))(assert (<= x 5))(check-sat)(get-model)",
            )
            deleteOnExit()
        }
        val out = capture { main(arrayOf("-t", "5000", smt.absolutePath)) }
        assertTrue(out.trim().startsWith("sat"), out)
        assertTrue("(define-fun x () Int" in out, out)
    }

    @Test
    fun `format override forces a mode regardless of extension`() {
        // An XCSP3 instance written to a .txt file is still solved as XCSP3 via --format.
        val txt = File.createTempFile("cli", ".txt").apply {
            writeText(
                """<instance type="CSP"><variables><var id="a"> 1..3 </var></variables>
                   <constraints/></instance>""",
            )
            deleteOnExit()
        }
        val out = capture { main(arrayOf("--format", "xcsp3", "-t", "5000", txt.absolutePath)) }
        assertTrue("s SATISFIABLE" in out, out)
    }

    @Test
    fun `coverage report counts parsed, solved and unsupported`() {
        val dir = File.createTempFile("xcsp-cov", "").let {
            it.delete()
            it.mkdirs()
            it
        }
        try {
            File(dir, "ok.smt2").writeText(
                "(declare-const x Int)(declare-const y Int)" +
                    "(assert (>= x 0))(assert (>= y 0))(assert (<= (+ x y) 5))(check-sat)",
            )
            File(dir, "sat.xml").writeText(
                """<instance type="CSP"><variables><var id="a"> 1..3 </var><var id="b"> 1..3 </var></variables>
                   <constraints><allDifferent> a b </allDifferent></constraints></instance>""",
            )
            // `pow` is outside the linear intension subset → an unsupported construct.
            File(dir, "bad.xml").writeText(
                """<instance type="CSP"><variables><var id="a"> 1..3 </var></variables>
                   <constraints><intension> eq(pow(a,2),4) </intension></constraints></instance>""",
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
