package com.eignex.klause.cli

import com.eignex.klause.config.KlauseConfig
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.lp.bounding.LpEmphasis
import com.eignex.klause.lp.bounding.LpTechnique
import com.eignex.klause.lp.engine.LpZeroObjectivePricing
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.ineffectiveNumerics
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CliModeOptionsTest {
    @Test
    fun `incremental linear controls should leave finite SMT CLI solving available`() {
        val source = File.createTempFile("incremental-finite", ".smt2").apply {
            writeText("(declare-const b Bool) (assert b) (check-sat)")
            deleteOnExit()
        }
        for (engine in listOf("fixed", "backtrack", "mixed")) {
            for (enabled in listOf(false, true)) {
                val output = capture {
                    assertEquals(0, runCli(arrayOf("-e", engine, "--param",
                        "open-linear-incremental=$enabled", source.absolutePath)))
                }

                assertTrue("sat" in output.lines(), output)
            }
        }
    }

    @Test
    fun `finite SMT CLI should reject malformed incremental linear controls`() {
        val source = File.createTempFile("incremental-malformed", ".smt2").apply {
            writeText("(declare-const b Bool) (assert b) (check-sat)")
            deleteOnExit()
        }
        var code = -1

        val error = captureErr {
            code = runCli(arrayOf("--param", "open-linear-incremental=invalid", source.absolutePath))
        }

        assertEquals(2, code, error)
        assertTrue("open-linear-incremental" in error, error)
    }

    @Test
    fun `explicit default LP pricing should retain its flag provenance`() {
        val common = CommonOptions()
        assertEquals(false, common.lpPricingExplicit)

        parseArgs(arrayOf("--lp-pricing", "min-bound-support"), commonFlagSpecs(common)) {}

        assertEquals(LpZeroObjectivePricing.MIN_BOUND_SUPPORT, common.lpPricing)
        assertEquals(true, common.lpPricingExplicit)
    }

    @Test
    fun `unsupported caller bound precision should return a clean cli usage diagnostic`() {
        val input = File.createTempFile("callerprecision", ".fzn").apply {
            writeText("var float: x; solve satisfy;")
            deleteOnExit()
        }
        var code = -1

        val error = captureErr {
            code = runCli(arrayOf("--unbounded-real-lo", "1/3", input.absolutePath))
        }

        assertEquals(2, code, error)
        assertTrue("undeclared real bound cannot be represented exactly in binary64" in error, error)
    }

    @Test
    fun `unbounded integer flags should retain caller endpoints beyond FlatZinc defaults`() {
        val source = File.createTempFile("clibounds", ".fzn").apply {
            writeText("var int: x; solve satisfy;")
            deleteOnExit()
        }
        for ((lower, upper) in listOf(-2_000_000L to 3_000_000L, Long.MIN_VALUE to Long.MAX_VALUE)) {
            val common = CommonOptions().apply { config = KlauseConfig.DEFAULT }
            val session = MiniZincMode.newSession()
            parseArgs(
                arrayOf("--unbounded-int-lo", lower.toString(), "--unbounded-int-hi", upper.toString()),
                commonFlagSpecs(common) + session.flags(),
            ) {}

            val problem = checkNotNull(session.load(source.absolutePath, common).problem)

            assertEquals(lower, problem.intBounds.lower(0))
            assertEquals(upper, problem.intBounds.upper(0))
        }
    }

    @Test
    fun `numeric flags should reject a non-numeric value with a usage error`() {
        val specs = commonFlagSpecs(CommonOptions())
        for (flag in listOf("-t", "-r", "-n")) {
            val e = assertFailsWith<CliUsageException> { parseArgs(arrayOf(flag, "abc"), specs) {} }
            assertTrue("$flag expects an integer" in e.message.orEmpty(), "$flag: ${e.message}")
        }
    }

    @Test
    fun `non-positive luby units should exit with a configuration error`() {
        val fzn = File.createTempFile("cliluby", ".fzn").apply {
            writeText("var 1..3: x; solve satisfy;")
            deleteOnExit()
        }
        for (base in listOf("0", "-1", Long.MIN_VALUE.toString())) {
            var code = -1
            val error = captureErr {
                code = runCli(arrayOf("-e", "cp", "--param", "luby=$base", fzn.absolutePath))
            }

            assertEquals(2, code, error)
            assertTrue("luby" in error && "positive integer" in error, error)
            assertTrue("ArithmeticException" !in error, error)
        }
    }

    @Test
    fun `a malformed instance should surface a clean format diagnostic and the cli error code`() {
        val fzn = File.createTempFile("clibad", ".fzn").apply {
            writeText("var 1..3: x;\nconstraint not_a_real_builtin(;\n")
            deleteOnExit()
        }
        var code = -1
        val err = captureErr { code = runCli(arrayOf(fzn.absolutePath)) }
        assertEquals(2, code, err)
        assertTrue(err.trimStart().startsWith("klause FlatZinc:"), err)
    }

    @Test
    fun `ls optimize should stream improving incumbents live rather than only the final best`() {
        // Presolve is off so its clock limit cannot change the model the seeded walk sees.
        val n = 4
        val fzn = buildString {
            // FlatZinc requires every var declaration before the constraints.
            for (i in 1..n) appendLine("var 1..$n: x$i;")
            appendLine("var 0..100000: obj;")
            for (i in 1..n) for (j in i + 1..n) appendLine("constraint int_ne(x$i, x$j);")
            val coeffs = (1..n).joinToString(",") { (n - it + 1).toString() } + ",-1"
            val vars = (1..n).joinToString(",") { "x$it" } + ",obj"
            appendLine("constraint int_lin_eq([$coeffs], [$vars], 0);")
            appendLine("solve minimize obj;")
        }
        val file = File.createTempFile("cliopt", ".fzn").apply {
            writeText(fzn)
            deleteOnExit()
        }
        val out = capture {
            val flags = arrayOf("-e", "ls", "-p", "1", "-r", "17", "-a", "-s", "--presolve", "off")
            val allowance = arrayOf("--param", "node-limit=1000", "-t", "10000")
            main(flags + allowance + file.absolutePath)
        }
        val separators = out.lines().count { it == "----------" }
        val solutionsStat = Regex("solutions=(\\d+)").find(out)?.groupValues?.get(1)?.toInt()
        assertTrue(separators >= 2, out)
        assertEquals(separators, solutionsStat)
        assertTrue("==========" !in out, out)
    }

    @Test
    fun `parser should accept attached short values bundled shorts long=value and the -- terminator`() {
        val opts = CommonOptions()
        val positionals = mutableListOf<String>()
        parseArgs(
            arrayOf("-as", "-t5000", "--random-seed=7", "-p", "2", "--", "-notaflag"),
            commonFlagSpecs(opts),
        ) { positionals.add(it) }

        assertTrue(opts.allSolutions)
        assertTrue(opts.statistics)
        assertEquals(5000L, opts.timeLimitMs)
        assertEquals(7L, opts.randomSeed)
        assertEquals(2, opts.parallel)
        assertEquals(listOf("-notaflag"), positionals)
    }

    @Test
    fun `--lp should parse an emphasis plus technique deltas into an LpConfig ceiling`() {
        val opts = CommonOptions()
        parseArgs(arrayOf("--lp", "aggressive,-cuts"), commonFlagSpecs(opts)) { }
        assertEquals("aggressive,-cuts", opts.lp)
        val cfg = LpConfig.parse(requireNotNull(opts.lp))
        assertEquals(LpEmphasis.AGGRESSIVE, cfg.emphasis)
        assertEquals(false, cfg.resolved(LpTechnique.CUTS))
        assertEquals(true, cfg.resolved(LpTechnique.BOUNDING))
    }

    @Test
    fun `--lp-pricing should default to minimum bound support and accept largest pivot`() {
        val defaults = CommonOptions()
        assertEquals(LpZeroObjectivePricing.MIN_BOUND_SUPPORT, defaults.lpPricing)

        val overridden = CommonOptions()
        parseArgs(arrayOf("--lp-pricing", "largest-pivot"), commonFlagSpecs(overridden)) { }

        assertEquals(LpZeroObjectivePricing.LARGEST_PIVOT, overridden.lpPricing)
        val error = assertFailsWith<CliUsageException> {
            parseArgs(arrayOf("--lp-pricing", "unknown"), commonFlagSpecs(CommonOptions())) { }
        }
        assertTrue("expects min-bound-support | largest-pivot" in error.message.orEmpty(), error.message.orEmpty())
    }

    @Test
    fun `default engine should be mixed and overridable via the KLAUSE_ENGINE property`() {
        assertTrue(defaultEngine() == FiniteEngine.MIXED, defaultEngine().id)
        // cliProp reads the system property first on the JVM, so it stands in for the env var.
        System.setProperty("klause.engine", "fixed")
        try {
            assertTrue(defaultEngine() == FiniteEngine.FIXED, defaultEngine().id)
            val out = capture { main(arrayOf("--help")) }
            assertTrue("default: fixed" in out, out)
        } finally {
            System.clearProperty("klause.engine")
        }
    }

    @Test
    fun `engine tokens should map to their finite solve routes`() {
        assertEquals(FiniteEngine.FIXED, parseEngine("fixed"))
        assertEquals(FiniteEngine.BACKTRACK, parseEngine("cp"))
        assertEquals(FiniteEngine.LOCAL_SEARCH, parseEngine("local-search"))
        assertEquals(FiniteEngine.MIXED, parseEngine("portfolio"))
        assertEquals(FiniteEngine.ALNS, parseEngine("lns"))
    }

    @Test
    fun `the LP ceiling default should be overridable via the KLAUSE_LP property`() {
        assertEquals(null, defaultLp())
        // cliProp reads the system property first on the JVM, so it stands in for the env var.
        System.setProperty("klause.lp", "conservative")
        try {
            assertEquals("conservative", defaultLp())
            val out = capture { main(arrayOf("--help")) }
            assertTrue("default: conservative" in out, out)
        } finally {
            System.clearProperty("klause.lp")
        }
    }

    @Test
    fun `core config env overrides should install for non-MiniZinc front-ends too`() {
        val saved = KlauseConfig.current
        val xml = File.createTempFile("cli", ".xml").apply {
            writeText(
                """<instance type="CSP"><variables><var id="a"> 1..3 </var></variables><constraints/></instance>""",
            )
            deleteOnExit()
        }
        System.setProperty("klause.bitset.threshold", "777")
        try {
            capture { main(arrayOf("-t", "5000", xml.absolutePath)) }
            assertEquals(777, KlauseConfig.current.bitsetThreshold)
        } finally {
            System.clearProperty("klause.bitset.threshold")
            KlauseConfig.current = saved
        }
    }

    @Test
    fun `--help should print usage with standard flags and known formats without an input file`() {
        for (flag in listOf("--help", "-h")) {
            val out = capture { main(arrayOf(flag)) }
            assertTrue("usage: klause-cli" in out, out)
            for (token in listOf("-a", "-i", "-n", "-f", "-s", "-v", "-t", "-r", "-p", "--engine")) {
                assertTrue(token in out, "help missing $token:\n$out")
            }
            assertTrue("minizinc" in out, out)
            assertTrue("xcsp3" in out, out)
            assertTrue("(default: mixed)" in out, out)
            assertTrue("(default: default)" in out, out)
            assertTrue("(default: 1)" in out, out)
        }
    }

    @Test
    fun `--version should print name and version on a single line`() {
        val out = capture { main(arrayOf("--version")) }.trim()
        assertTrue(out == "Klause 0.0.1", out)
    }

    @Test
    fun `engine params should reach the single-solver engines`() {
        val fzn = File.createTempFile("cli", ".fzn").apply {
            writeText("var 1..3: x;\nconstraint int_lt(x, 3);\nsolve satisfy;\n")
            deleteOnExit()
        }
        for (engineArgs in listOf(
            arrayOf("-e", "cp", "--param", "seed=7", "--param", "val-selector=max", "--param", "luby=50"),
            arrayOf("-e", "ls", "--param", "strategy=cbls", "--param", "tabu-tenure=5", "-t", "5000"),
            arrayOf("-e", "ls", "--param", "seed=7", "--param", "lambda=2.0", "-t", "5000"),
        )) {
            val out = capture { main(engineArgs + fzn.absolutePath) }
            assertTrue("x = " in out, out)
            assertTrue("----------" in out, out)
        }
    }

    @Test
    fun `ls bare recipe axes and strategy-base overrides should all be valid`() {
        val fzn = File.createTempFile("cli", ".fzn").apply {
            writeText("var 1..3: x;\nconstraint int_lt(x, 3);\nsolve satisfy;\n")
            deleteOnExit()
        }
        for (args in listOf(
            arrayOf("--param", "sources=violated,argmin", "--param", "acceptance=walksat", "--param", "noise=0.2"),
            arrayOf("--param", "strategy=feasibilityjump"),
            arrayOf("--param", "strategy=cbls", "--param", "acceptance=sa"),
        )) {
            val out = capture { main(arrayOf("-e", "ls", "-t", "5000") + args + fzn.absolutePath) }
            assertTrue("x = " in out, "$args -> $out")
            assertTrue("----------" in out, "$args -> $out")
        }
    }

    @Test
    fun `ls dry-run should list the resolved arm pool with axis edits applied`() {
        val fzn = File.createTempFile("cli", ".fzn").apply {
            writeText("var 1..3: x;\nconstraint int_lt(x, 3);\nsolve satisfy;\n")
            deleteOnExit()
        }
        val default = captureErr { main(arrayOf("-e", "ls", "--param", "dry-run-solver=on", fzn.absolutePath)) }
        assertTrue("ls dry-run:" in default, default)
        assertTrue("cbls/fixed" in default, default)

        val removed = captureErr {
            main(arrayOf("-e", "ls", "--param", "sources=-violated", "--param", "dry-run-solver=on", fzn.absolutePath))
        }
        assertTrue("violated-repairs" !in removed, removed)

        val scoped = captureErr {
            main(arrayOf("-e", "ls", "--param", "scoring=cbls.break", "--param", "dry-run-solver=on", fzn.absolutePath))
        }
        assertTrue("scoring=Break" in scoped, scoped)

        val annealed = captureErr {
            main(arrayOf("-e", "ls", "--param", "acceptance=sa", "--param", "dry-run-solver=on", fzn.absolutePath))
        }
        assertTrue("acceptance=Metropolis" in annealed, annealed)
        assertTrue("temperature=Geometric" in annealed, annealed)
    }

    @Test
    fun `dry-run-solver should describe a backtrack engine`() {
        val fzn = File.createTempFile("cli", ".fzn").apply {
            writeText("var 1..3: x;\nconstraint int_lt(x, 3);\nsolve satisfy;\n")
            deleteOnExit()
        }
        val out = captureErr { main(arrayOf("-e", "cp", "--param", "dry-run-solver=on", fzn.absolutePath)) }
        assertTrue("solver dry-run:" in out, out)
        assertTrue("backtrack" in out && "var-select:" in out, out)
        assertTrue("lp-pricing:  min-bound-support" in out, out)

        val largestPivot = captureErr {
            main(
                arrayOf(
                    "-e",
                    "cp",
                    "--lp-pricing",
                    "largest-pivot",
                    "--param",
                    "dry-run-solver=on",
                    fzn.absolutePath,
                ),
            )
        }
        assertTrue("lp-pricing:  largest-pivot" in largestPivot, largestPivot)
        assertTrue("lp-pricing:  min-bound-support" !in largestPivot, largestPivot)
    }

    @Test
    fun `consumed allowances and duplicate overrides should retain the dry run output`() {
        val fzn = File.createTempFile("cli", ".fzn").apply {
            writeText("var 1..3: x;\nconstraint int_lt(x, 3);\nsolve satisfy;\n")
            deleteOnExit()
        }
        val controls = listOf(
            arrayOf("--param", "seed=7", "--param", "node-limit=9"),
            arrayOf(
                "--param",
                "seed=invalid",
                "--param",
                "node-limit=9",
                "--param",
                "seed=7",
                "--param",
                "node-limit=9",
            ),
        )

        val outputs = controls.map { parameters ->
            captureErr {
                main(arrayOf("-e", "fixed", "--param", "dry-run-solver=on") + parameters + fzn.absolutePath)
            }
        }

        assertEquals(outputs.first(), outputs.last())
    }

    @Test
    fun `a cp override should edit every arm of the pool rather than collapsing to one`() {
        val fzn = File.createTempFile("cli", ".fzn").apply {
            writeText("var 1..3: x;\nconstraint int_lt(x, 3);\nsolve satisfy;\n")
            deleteOnExit()
        }
        val out = captureErr {
            main(
                arrayOf(
                    "-e", "cp", "-p", "8", "--param", "var-selector=smallest-domain",
                    "--param", "dry-run-solver=on", fzn.absolutePath,
                ),
            )
        }
        val armLines = out.lines().count {
            it.trimStart().startsWith("conflictDriven:") || it.trimStart().startsWith("free:")
        }
        assertEquals(2, armLines, out)
        assertTrue("var-select:  SmallestDomain" in out, out)
        assertTrue("var-select:  Vsids" !in out, out)
    }

    @Test
    fun `ls arm should select exactly one curated arm in isolation`() {
        val fzn = File.createTempFile("cli", ".fzn").apply {
            writeText("var 1..3: x;\nconstraint int_lt(x, 3);\nsolve satisfy;\n")
            deleteOnExit()
        }
        val dry = captureErr {
            main(
                arrayOf(
                    "-e",
                    "ls",
                    "--param",
                    "arm=cbls-plateau/ils-basin",
                    "--param",
                    "dry-run-solver=on",
                    fzn.absolutePath,
                ),
            )
        }
        assertTrue("ls dry-run: 1 arm(s)" in dry, dry)
        assertTrue("cbls-plateau/ils-basin" in dry, dry)
        val out = capture { main(arrayOf("-e", "ls", "--param", "arm=cbls/fixed", "-t", "5000", fzn.absolutePath)) }
        assertTrue("x = " in out, out)
        assertTrue("----------" in out, out)
    }

    @Test
    fun `ls numeric knobs no axis consumes should be reported as ineffective`() {
        assertEquals(listOf("noise"), ineffectiveNumerics("auto", emptySet(), listOf("noise")))
        assertTrue(ineffectiveNumerics("cbls", emptySet(), listOf("noise", "smooth-prob", "tabu-tenure")).isEmpty())
        assertTrue(ineffectiveNumerics("auto", setOf("walksat"), listOf("noise")).isEmpty())
        assertTrue(ineffectiveNumerics("probsat", emptySet(), listOf("cb")).isEmpty())
        assertTrue(ineffectiveNumerics("sa", emptySet(), listOf("initial-temp", "cooling-rate", "min-temp")).isEmpty())
        assertEquals(listOf("tabu-tenure"), ineffectiveNumerics("fjump", emptySet(), listOf("tabu-tenure")))
    }

    @Test
    fun `minizinc standard -f -p should route to a parallel portfolio in both -p spellings`() {
        val fzn = File.createTempFile("cli", ".fzn").apply {
            writeText("var 1..3: x;\nconstraint int_lt(x, 3);\nsolve satisfy;\n")
            deleteOnExit()
        }
        for (engineArgs in listOf(
            arrayOf("-f", "-p2"),
            arrayOf("-f", "-p", "2"),
            arrayOf("-e", "ls", "-p", "2"),
        )) {
            val out = capture { main(engineArgs + arrayOf("-t", "10000", fzn.absolutePath)) }
            assertTrue("x = " in out, out)
        }
    }

    @Test
    fun `fixed should accept selector overrides without a source search annotation`() {
        val fzn = File.createTempFile("cliunannotated", ".fzn").apply {
            writeText("var 1..3: x;\nconstraint int_lt(x, 3);\nsolve satisfy;\n")
            deleteOnExit()
        }

        val out = capture {
            main(arrayOf("-e", "fixed", "--param", "var-selector=input-order", fzn.absolutePath))
        }

        assertTrue("x = " in out, out)
    }

    @Test
    fun `fixed should reject selector overrides when a source search annotation exists`() {
        val fzn = File.createTempFile("cliannotated", ".fzn").apply {
            writeText(
                "var 1..3: x;\nconstraint int_lt(x, 3);\n" +
                    "solve :: int_search([x], input_order, indomain_min, complete) satisfy;\n",
            )
            deleteOnExit()
        }

        var code = -1
        val err = captureErr {
            code = runCli(arrayOf("-e", "fixed", "--param", "var-selector=input-order", fzn.absolutePath))
        }

        assertEquals(2, code, err)
        assertTrue("var-selector" in err, err)
    }

    @Test
    fun `-s should print mzn-stat lines for satisfy and optimize verdicts`() {
        val sat = File.createTempFile("cli", ".fzn").apply {
            writeText("var 1..3: x;\nconstraint int_lt(x, 3);\nsolve satisfy;\n")
            deleteOnExit()
        }
        val opt = File.createTempFile("cli", ".fzn").apply {
            writeText("var 1..9: x;\nsolve maximize x;\n")
            deleteOnExit()
        }
        val cpOut = capture { main(arrayOf("-s", "-e", "cp", sat.absolutePath)) }
        assertTrue("%%%mzn-stat: solveTime=" in cpOut, cpOut)
        assertTrue("%%%mzn-stat: solutions=1" in cpOut, cpOut)
        assertTrue("%%%mzn-stat: nodes=" in cpOut, cpOut)
        assertTrue("%%%mzn-stat-end" in cpOut, cpOut)
        assertTrue(cpOut.indexOf("==========") < cpOut.indexOf("%%%mzn-stat:"), cpOut)

        val lsOut = capture { main(arrayOf("-s", "-e", "ls", sat.absolutePath)) }
        assertTrue("%%%mzn-stat: solutions=1" in lsOut, lsOut)
        assertTrue("%%%mzn-stat: lsMoves=" in lsOut, lsOut)
        assertTrue("%%%mzn-stat: nodes=" !in lsOut, lsOut)
        assertTrue("%%%mzn-stat-end" in lsOut, lsOut)

        val maxOut = capture {
            main(arrayOf("-s", "-e", "ls", "--param", "node-limit=100", "-t", "10000", opt.absolutePath))
        }
        assertTrue("%%%mzn-stat: lsIncumbentObjective=" in maxOut, maxOut)
        assertTrue("%%%mzn-stat: lsIncumbentObjective=-" !in maxOut, maxOut)
    }

    @Test
    fun `--output-objective should emit the _objective line per solution and be off by default`() {
        // The objective var is not an output var, so only the _objective line exposes its value.
        val fzn = File.createTempFile("cli", ".fzn").apply {
            writeText(
                "var 0..9: x:: output_var;\n" +
                    "var 0..9: X_INTRODUCED_0_;\n" +
                    "constraint int_lin_eq([1,-1],[x,X_INTRODUCED_0_],0);\n" +
                    "solve maximize X_INTRODUCED_0_;\n",
            )
            deleteOnExit()
        }
        val withFlag = capture { main(arrayOf("-e", "bt", "--output-objective", "-t", "5000", fzn.absolutePath)) }
        assertTrue("_objective = 9;" in withFlag, withFlag)

        val without = capture { main(arrayOf("-e", "bt", "-t", "5000", fzn.absolutePath)) }
        assertTrue("_objective" !in without, without)
    }

    @Test
    fun `xcsp3 csp should emit the competition SATISFIABLE protocol with named instantiation`() {
        val xml = File.createTempFile("cli", ".xml").apply {
            writeText(
                """<instance type="CSP"><variables><var id="a"> 1..3 </var><var id="b"> 1..3 </var></variables>
                   <constraints><allDifferent> a b </allDifferent></constraints></instance>""",
            )
            deleteOnExit()
        }
        val out = capture { main(arrayOf("-e", "bt", "-t", "5000", xml.absolutePath)) }
        assertTrue("s SATISFIABLE" in out, out)
        assertTrue("v <instantiation>" in out, out)
        assertTrue("<list> a b </list>" in out, out)
        assertTrue("----------" !in out, out)
        assertTrue("%%%mzn-stat" !in out, out)
    }

    @Test
    fun `xcsp3 cop should emit o lines and OPTIMUM FOUND with the true objective`() {
        val xml = File.createTempFile("cli", ".xml").apply {
            writeText(
                """<instance type="COP"><variables><var id="a"> 1..3 </var></variables>
                   <objectives><maximize> a </maximize></objectives></instance>""",
            )
            deleteOnExit()
        }
        val out = capture { main(arrayOf("-e", "bt", "-t", "5000", "-s", xml.absolutePath)) }
        assertTrue("s OPTIMUM FOUND" in out, out)
        assertTrue("o 3" in out, out)
        assertTrue("<values> 3 </values>" in out, out)
        assertTrue("c solveTime=" in out, out)
    }

    @Test
    fun `smtlib should emit sat and a get-model block`() {
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
    fun `format override should force a mode regardless of extension`() {
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

    private fun captureErr(block: () -> Unit): String {
        val buf = ByteArrayOutputStream()
        val old = System.err
        System.setErr(PrintStream(buf))
        try {
            block()
        } finally {
            System.setErr(old)
        }
        return buf.toString()
    }
}
