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
    fun `parser accepts attached short values, bundled shorts, long=value, and -- terminator`() {
        val opts = CommonOptions()
        val positionals = mutableListOf<String>()
        parseArgs(
            // -as          → bundled booleans (all-solutions + statistics)
            // -t5000       → attached short value
            // --random-seed=7 → long with =value
            // -p 2         → space-separated short value
            // --           → end of options
            // -notaflag    → positional after the terminator, not an ignored flag
            arrayOf("-as", "-t5000", "--random-seed=7", "-p", "2", "--", "-notaflag"),
            commonFlagSpecs(opts),
        ) { positionals.add(it) }

        assertTrue(opts.allSolutions, "expected -a")
        assertTrue(opts.statistics, "expected -s")
        assertTrue(opts.timeLimitMs == 5000L, "timeLimitMs=${opts.timeLimitMs}")
        assertTrue(opts.randomSeed == 7L, "randomSeed=${opts.randomSeed}")
        assertTrue(opts.parallel == 2, "parallel=${opts.parallel}")
        assertTrue(positionals == listOf("-notaflag"), positionals.toString())
    }

    @Test
    fun `-p2 attached form drives the parallel portfolio (matches the documented spelling)`() {
        val fzn = File.createTempFile("cli", ".fzn").apply {
            writeText("var 1..3: x;\nconstraint int_lt(x, 3);\nsolve satisfy;\n")
            deleteOnExit()
        }
        val out = capture { main(arrayOf("-f", "-p2", "-t", "10000", fzn.absolutePath)) }
        assertTrue("x = " in out, out)
    }

    @Test
    fun `--help prints usage with standard flags and known formats and needs no input file`() {
        for (flag in listOf("--help", "-h")) {
            val out = capture { main(arrayOf(flag)) }
            assertTrue("usage: klause-cli" in out, out)
            // The standard MiniZinc fzn-spec flags are all documented.
            for (token in listOf("-a", "-i", "-n", "-f", "-s", "-v", "-t", "-r", "-p", "--engine")) {
                assertTrue(token in out, "help missing $token:\n$out")
            }
            // Formats come from the live mode registry.
            assertTrue("minizinc" in out, out)
            assertTrue("xcsp3" in out, out)
        }
    }

    @Test
    fun `--version prints name and version on a single line`() {
        val out = capture { main(arrayOf("--version")) }.trim()
        assertTrue(out == "Klause 0.1.0", out)
    }

    @Test
    fun `engine params reach the single-solver engines`() {
        val fzn = File.createTempFile("cli", ".fzn").apply {
            writeText("var 1..3: x;\nconstraint int_lt(x, 3);\nsolve satisfy;\n")
            deleteOnExit()
        }
        for (engineArgs in listOf(
            // cp-single is the only engine that takes the per-solver var-/val-selector knobs.
            arrayOf("-e", "cp-single", "--param", "seed=7", "--param", "val-selector=max", "--param", "luby=50"),
            // ls-single takes the ls strategy knobs (tabu-tenure / noise); ls is the portfolio.
            arrayOf("-e", "ls-single", "--param", "tabu-tenure=5", "--param", "noise=0.1", "-t", "5000"),
            arrayOf("-e", "ls", "--param", "seed=7", "--param", "lambda=2.0", "-t", "5000"),
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
    fun `minizinc standard -f -p routes to a parallel portfolio`() {
        val fzn = File.createTempFile("cli", ".fzn").apply {
            writeText("var 1..3: x;\nconstraint int_lt(x, 3);\nsolve satisfy;\n")
            deleteOnExit()
        }
        // `-f -p2` ⇒ free (cp alias) parallel pool sized to 2. `-e ls -p2` ⇒ pure-LS pool. (Bare
        // `-p2` would be the single-core `fixed` engine and is rejected — that's by design.)
        for (engineArgs in listOf(arrayOf("-f", "-p", "2"), arrayOf("-e", "ls", "-p", "2"))) {
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
}
