package com.eignex.klause.cli

import com.eignex.klause.config.KlauseConfig
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.lp.bounding.LpEmphasis
import com.eignex.klause.lp.bounding.LpTechnique
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.ValSelectorKind
import com.eignex.klause.solver.pipeline.VarSelectorKind
import com.eignex.klause.solver.pipeline.ineffectiveNumerics
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CliModeTest {

    @Test
    fun `numeric flags reject a non-numeric value with a usage error`() {
        val specs = commonFlagSpecs(CommonOptions())
        for (flag in listOf("-t", "-r", "-n")) {
            val e = assertFailsWith<CliUsageException> { parseArgs(arrayOf(flag, "abc"), specs) {} }
            assertTrue("$flag expects an integer" in e.message.orEmpty(), "$flag: ${e.message}")
        }
    }

    @Test
    fun `a malformed instance surfaces a clean format diagnostic and the cli error code`() {
        // A syntactically broken FlatZinc file: the front-end parser throws a FormatException from
        // load. main must catch it at the boundary — a formatted stderr line and exit code 2 — rather
        // than let it escape as an uncaught stack trace.
        val fzn = File.createTempFile("clibad", ".fzn").apply {
            writeText("var 1..3: x;\nconstraint not_a_real_builtin(;\n")
            deleteOnExit()
        }
        var code = -1
        val err = captureErr { code = runCli(arrayOf(fzn.absolutePath)) }
        assertEquals(2, code, "malformed input must exit with the CLI error code:\n$err")
        assertTrue(err.trimStart().startsWith("klause FlatZinc:"), "expected a formatted format diagnostic:\n$err")
    }

    @Test
    fun `an open SMT difference row is solved without a finite search box`() {
        val smt = File.createTempFile("clidiff", ".smt2").apply {
            writeText(
                """
                (declare-const x Int)
                (assert (>= x 5))
                (check-sat)
                """.trimIndent(),
            )
            deleteOnExit()
        }

        var code = -1
        val out = capture { code = runCli(arrayOf(smt.absolutePath)) }

        assertEquals(0, code, out)
        assertTrue(out.lines().firstOrNull() == "sat", out)
        assertTrue("(define-fun x () Int 5)" in out, out)
    }

    @Test
    fun `an open theory run that spends its budget names the cause`() {
        val smt = File.createTempFile("clibudget", ".smt2").apply {
            writeText(
                """
                (declare-const x Int)
                (assert (>= x 5))
                (check-sat)
                """.trimIndent(),
            )
            deleteOnExit()
        }

        var code = -1
        // A zero budget is already spent at the first poll, so the cause is decided rather than raced.
        val out = capture { code = runCli(arrayOf("-s", "-t", "0", smt.absolutePath)) }

        assertEquals(0, code, out)
        assertTrue(out.lines().firstOrNull() == "unknown", out)
        assertTrue("; budget exhausted" in out, out)
    }

    @Test
    fun `an open SMT general LIA model is solved without finite lowering`() {
        val smt = File.createTempFile("cliopen", ".smt2").apply {
            writeText(
                """
                (declare-const x Int)
                (declare-const y Int)
                (assert (<= (+ (* 2 x) y) 3))
                (check-sat)
                """.trimIndent(),
            )
            deleteOnExit()
        }
        var code = -1
        val out = capture { code = runCli(arrayOf(smt.absolutePath)) }

        assertEquals(0, code, out)
        assertTrue(out.lines().firstOrNull() == "sat", out)
    }

    @Test
    fun `an open SMT LRA model renders an exact rational witness`() {
        val smt = File.createTempFile("clilra", ".smt2").apply {
            writeText(
                """
                (set-logic QF_LRA)
                (declare-const x Real)
                (assert (= x (/ 1.0 3.0)))
                (check-sat)
                """.trimIndent(),
            )
            deleteOnExit()
        }

        var code = -1
        val out = capture { code = runCli(arrayOf(smt.absolutePath)) }

        assertEquals(0, code, out)
        assertTrue(out.lines().firstOrNull() == "sat", out)
        assertTrue("(define-fun x () Real 1/3)" in out, out)
    }

    @Test
    fun `an open SMT LIRA model renders mixed exact witnesses`() {
        val smt = File.createTempFile("clilira", ".smt2").apply {
            writeText(
                """
                (set-logic QF_LIRA)
                (declare-const x Int) (declare-const y Real)
                (assert (= y (+ (to_real x) (/ 1.0 3.0))))
                (check-sat)
                """.trimIndent(),
            )
            deleteOnExit()
        }

        var code = -1
        val out = capture { code = runCli(arrayOf(smt.absolutePath)) }

        assertEquals(0, code, out)
        assertTrue(out.lines().firstOrNull() == "sat", out)
        assertTrue("(define-fun x () Int " in out, out)
        assertTrue("(define-fun y () Real " in out, out)
    }

    @Test
    fun `an open MPS difference row is solved without a finite search box`() {
        val mps = File.createTempFile("clidiff", ".mps").apply {
            writeText(
                """
                NAME          DIFF
                ROWS
                 N  COST
                 G  LOWER
                COLUMNS
                    MK1       'MARKER'                 'INTORG'
                    X         LOWER          1.0
                    MK2       'MARKER'                 'INTEND'
                RHS
                    RHS       LOWER          5.0
                ENDATA
                """.trimIndent(),
            )
            deleteOnExit()
        }

        val out = capture { main(arrayOf(mps.absolutePath)) }

        assertTrue("s SATISFIABLE" in out, out)
        assertTrue("X=5" in out, out)
    }

    @Test
    fun `an open MPS general LIA row is solved without finite lowering`() {
        val mps = File.createTempFile("clilia", ".mps").apply {
            writeText(
                """
                NAME          LIA
                ROWS
                 N  COST
                 L  ROW
                COLUMNS
                    MK1       'MARKER'                 'INTORG'
                    X         ROW            2.0
                    MK2       'MARKER'                 'INTEND'
                RHS
                    RHS       ROW            3.0
                ENDATA
                """.trimIndent(),
            )
            deleteOnExit()
        }

        val out = capture { main(arrayOf(mps.absolutePath)) }

        assertTrue("s SATISFIABLE" in out, out)
    }

    @Test
    fun `an MPS objective is reported in file units`() {
        val mps = File.createTempFile("cliobjective", ".mps").apply {
            writeText(
                """
                NAME          OBJECTIVE
                ROWS
                 N  COST
                 G  LOWER
                COLUMNS
                    MK1       'MARKER'                 'INTORG'
                    X         COST           0.5
                    X         LOWER          1.0
                    MK2       'MARKER'                 'INTEND'
                RHS
                    RHS       LOWER          3.0
                ENDATA
                """.trimIndent(),
            )
            deleteOnExit()
        }

        var code = -1
        val out = capture { code = runCli(arrayOf(mps.absolutePath)) }

        assertEquals(0, code, out)
        assertTrue("o 1.5" in out, out)
    }

    @Test
    fun `an approximate MPS objective does not claim an exact optimum`() {
        val mps = File.createTempFile("cliapproxobjective", ".mps").apply {
            writeText(
                """
                NAME          APPROXIMATE
                ROWS
                 N  COST
                 G  FIXED
                COLUMNS
                    MK1       'MARKER'                 'INTORG'
                    X         COST           1000000000000000
                    X         FIXED          1
                    Y         COST           0.25
                    MK2       'MARKER'                 'INTEND'
                RHS
                    RHS       FIXED          1
                BOUNDS
                 UP BND       X              1
                 LO BND       Y             -2
                 UP BND       Y              3
                ENDATA
                """.trimIndent(),
            )
            deleteOnExit()
        }

        var code = -1
        val out = capture { code = runCli(arrayOf(mps.absolutePath)) }

        assertEquals(0, code, out)
        assertTrue("s SATISFIABLE" in out, out)
        assertTrue("objective approximation error <= 0.75" in out, out)
    }

    @Test
    fun `an unknown approximate MPS objective does not claim a retained optimum`() {
        val output = MpsOutput(objectiveErrorBound = 0.75)

        val out = capture { output.onComplete(Verdict.UNKNOWN) }

        assertTrue("objective approximation error <= 0.75" in out, out)
        assertTrue("retained objective is optimal" !in out, out)
    }

    @Test
    fun `an MPS objective a continuous column contributes to is reported whole`() {
        val output = MpsOutput()

        val out = capture { output.onSolution("v x=60.0", objective = 0L, continuousObjective = 60.0) }

        assertTrue("o 60" in out, out)
        assertTrue("o 0" !in out, "the discrete part alone must not be reported: $out")
    }

    @Test
    fun `an MPS objective on its scale unscales before printing`() {
        val output = MpsOutput(objectiveScale = 10L)

        val out = capture { output.onSolution("v x=6.05", objective = 0L, continuousObjective = 60.5) }

        assertTrue("o 6.05" in out, out)
    }

    @Test
    fun `an MPS objective over integer columns alone keeps its exact integer form`() {
        val output = MpsOutput()

        val out = capture { output.onSolution("v a=3", objective = 3L) }

        assertTrue("o 3" in out, out)
    }

    @Test
    fun `an exhausted inner MPS constraint approximation is qualified`() {
        val output = MpsOutput(hasInnerConstraintApproximation = true)

        val out = capture { output.onComplete(Verdict.UNSATISFIABLE) }

        assertTrue("s UNKNOWN" in out, out)
        assertTrue("source boundary is unresolved" in out, out)
    }

    @Test
    fun `an open mixed MPS model decides through the exact LIRA core`() {
        val mps = File.createTempFile("clilira", ".mps").apply {
            writeText(
                """
                NAME          LIRA
                ROWS
                 N  COST
                 G  ROW
                COLUMNS
                    MK1       'MARKER'                 'INTORG'
                    X         ROW            1.0
                    MK2       'MARKER'                 'INTEND'
                    Y         ROW            1.0
                RHS
                    RHS       ROW            0.0
                ENDATA
                """.trimIndent(),
            )
            deleteOnExit()
        }

        var code = -1
        val out = capture { code = runCli(arrayOf(mps.absolutePath, "-t", "5000")) }

        assertEquals(0, code, out)
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

    @Test
    fun `ls optimize streams improving incumbents live rather than only the final best`() {
        // A small alldifferent COP with a weighted objective: LS reaches a feasible permutation, then
        // improves it several times. Each strict improvement must hit the stream as its own
        // `----------` block (the MiniZinc time-to-best contract) — not be withheld until termination.
        val n = 8
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
        val out = capture { main(arrayOf("-e", "ls", "-a", "-s", "-t", "1000", file.absolutePath)) }
        val separators = out.lines().count { it == "----------" }
        val solutionsStat = Regex("solutions=(\\d+)").find(out)?.groupValues?.get(1)?.toInt()
        assertTrue(separators >= 2, "expected multiple streamed incumbents, got $separators:\n$out")
        assertEquals(separators, solutionsStat, "the solutions= statistic must match the streamed blocks")
        assertTrue("==========" !in out, "local search must not claim proven optimality:\n$out")
    }

    @Test
    fun `the cp override pool accepts every var- and val-selector value the enums expose`() {
        val fzn = File.createTempFile("cli", ".fzn").apply {
            writeText("var 1..3: x;\nconstraint int_lt(x, 3);\nsolve satisfy;\n")
            deleteOnExit()
        }
        // Every exposed selector must construct and solve, guarding the enum-to-selector mappings.
        for (v in VarSelectorKind.entries) {
            val out = capture {
                main(arrayOf("-e", "cp", "--param", "var-selector=${v.id}", "-t", "5000", fzn.absolutePath))
            }
            assertTrue("x = " in out, "var-selector=${v.id}:\n$out")
        }
        for (v in ValSelectorKind.entries) {
            val out = capture {
                main(arrayOf("-e", "cp", "--param", "val-selector=${v.id}", "-t", "5000", fzn.absolutePath))
            }
            assertTrue("x = " in out, "val-selector=${v.id}:\n$out")
        }
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
    fun `--lp parses an emphasis plus technique deltas into an LpConfig ceiling`() {
        val opts = CommonOptions()
        parseArgs(arrayOf("--lp", "aggressive,-cuts"), commonFlagSpecs(opts)) { }
        assertTrue(opts.lp == "aggressive,-cuts", "lp=${opts.lp}")
        // The token routes through LpConfig.parse (emphasis + per-technique delta).
        val cfg = LpConfig.parse(requireNotNull(opts.lp))
        assertEquals(LpEmphasis.AGGRESSIVE, cfg.emphasis)
        assertEquals(false, cfg.resolved(LpTechnique.CUTS))
        assertEquals(true, cfg.resolved(LpTechnique.BOUNDING))
    }

    @Test
    fun `default engine is mixed and is overridable via the KLAUSE_ENGINE property`() {
        assertTrue(defaultEngine() == FiniteEngine.MIXED, defaultEngine().id)
        // cliProp reads the system property first on the JVM, so it stands in for the env var.
        System.setProperty("klause.engine", "fixed")
        try {
            assertTrue(defaultEngine() == FiniteEngine.FIXED, defaultEngine().id)
            // The override must surface in --help so a packaged image's default is visible.
            val out = capture { main(arrayOf("--help")) }
            assertTrue("default: fixed" in out, out)
        } finally {
            System.clearProperty("klause.engine")
        }
    }

    @Test
    fun `engine tokens map to their finite solve routes`() {
        assertEquals(FiniteEngine.FIXED, parseEngine("fixed"))
        assertEquals(FiniteEngine.BACKTRACK, parseEngine("cp"))
        assertEquals(FiniteEngine.LOCAL_SEARCH, parseEngine("local-search"))
        assertEquals(FiniteEngine.MIXED, parseEngine("portfolio"))
        assertEquals(FiniteEngine.ALNS, parseEngine("lns"))
    }

    @Test
    fun `the LP ceiling default is overridable via the KLAUSE_LP property`() {
        assertEquals(null, defaultLp())
        // cliProp reads the system property first on the JVM, so it stands in for the env var.
        System.setProperty("klause.lp", "conservative")
        try {
            assertEquals("conservative", defaultLp())
            // The override must surface in --help so a packaged image's default is visible.
            val out = capture { main(arrayOf("--help")) }
            assertTrue("default: conservative" in out, out)
        } finally {
            System.clearProperty("klause.lp")
        }
    }

    @Test
    fun `core config env overrides install for non-MiniZinc front-ends too`() {
        // The install happens once in main, before the front-end is picked, so every front-end
        // (XCSP3, SMT-LIB, MiniZinc) sees the KLAUSE_* overrides rather than built-in defaults.
        val saved = KlauseConfig.current
        val xml = File.createTempFile("cli", ".xml").apply {
            writeText(
                """<instance type="CSP"><variables><var id="a"> 1..3 </var></variables><constraints/></instance>""",
            )
            deleteOnExit()
        }
        System.setProperty("klause.float.scale", "777")
        try {
            capture { main(arrayOf("-t", "5000", xml.absolutePath)) }
            assertEquals(777L, KlauseConfig.current.floatScale)
        } finally {
            System.clearProperty("klause.float.scale")
            KlauseConfig.current = saved
        }
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
            // Defaults are shown for the flags that carry one.
            assertTrue("(default: mixed)" in out, out) // engine (env-overridable)
            assertTrue("(default: default)" in out, out) // presolve strength
            assertTrue("(default: 1)" in out, out) // parallel cores
        }
    }

    @Test
    fun `--version prints name and version on a single line`() {
        val out = capture { main(arrayOf("--version")) }.trim()
        assertTrue(out == "Klause 0.0.1", out)
    }

    @Test
    fun `engine params reach the single-solver engines`() {
        val fzn = File.createTempFile("cli", ".fzn").apply {
            writeText("var 1..3: x;\nconstraint int_lt(x, 3);\nsolve satisfy;\n")
            deleteOnExit()
        }
        for (engineArgs in listOf(
            // cp resolves a one-arm override pool from the per-solver var-/val-selector knobs.
            arrayOf("-e", "cp", "--param", "seed=7", "--param", "val-selector=max", "--param", "luby=50"),
            // ls resolves a four-axis arm pool; a named base takes the strategy knobs (tabu / noise).
            arrayOf("-e", "ls", "--param", "strategy=cbls", "--param", "tabu-tenure=5", "-t", "5000"),
            arrayOf("-e", "ls", "--param", "seed=7", "--param", "lambda=2.0", "-t", "5000"),
        )) {
            val out = capture { main(engineArgs + fzn.absolutePath) }
            assertTrue("x = " in out, out)
            assertTrue("----------" in out, out)
        }
    }

    @Test
    fun `ls bare recipe axes select sources scoring and acceptance`() {
        val fzn = File.createTempFile("cli", ".fzn").apply {
            writeText("var 1..3: x;\nconstraint int_lt(x, 3);\nsolve satisfy;\n")
            deleteOnExit()
        }
        // A `sources=` spec with no `strategy=` is a bare four-axis recipe over the driver.
        for (recipe in listOf(
            arrayOf("--param", "sources=violated,argmin", "--param", "acceptance=walksat", "--param", "noise=0.2"),
            arrayOf("--param", "sources=violated,frontier", "--param", "scoring=raw", "--param", "acceptance=greedy"),
            arrayOf("--param", "sources=argmin", "--param", "acceptance=probsat", "--param", "cb=2.0"),
            arrayOf("--param", "sources=violated", "--param", "acceptance=sa", "--param", "cooling-rate=0.99"),
        )) {
            val out = capture { main(arrayOf("-e", "ls", "-t", "5000") + recipe + fzn.absolutePath) }
            assertTrue("x = " in out, out)
            assertTrue("----------" in out, out)
        }
    }

    @Test
    fun `ls strategy base plus per-axis overrides are all valid`() {
        val fzn = File.createTempFile("cli", ".fzn").apply {
            writeText("var 1..3: x;\nconstraint int_lt(x, 3);\nsolve satisfy;\n")
            deleteOnExit()
        }
        // A named base strategy, and overriding a single axis on a base. Every combination is valid.
        for (args in listOf(
            arrayOf("--param", "strategy=cbls"),
            arrayOf("--param", "strategy=feasibilityjump"),
            arrayOf("--param", "strategy=walksat"),
            arrayOf("--param", "strategy=probsat"),
            arrayOf("--param", "strategy=sa"),
            arrayOf("--param", "strategy=cbls", "--param", "acceptance=probsat"),
            arrayOf("--param", "strategy=cbls", "--param", "acceptance=sa"),
            arrayOf("--param", "strategy=walksat", "--param", "scoring=raw", "--param", "noise=0.3"),
        )) {
            val out = capture { main(arrayOf("-e", "ls", "-t", "5000") + args + fzn.absolutePath) }
            assertTrue("x = " in out, "$args -> $out")
            assertTrue("----------" in out, "$args -> $out")
        }
    }

    @Test
    fun `ls dry-run lists the resolved arm pool with axis edits applied`() {
        val fzn = File.createTempFile("cli", ".fzn").apply {
            writeText("var 1..3: x;\nconstraint int_lt(x, 3);\nsolve satisfy;\n")
            deleteOnExit()
        }
        // The default pool lists the curated arms; no solve output on stdout.
        val default = captureErr { main(arrayOf("-e", "ls", "--param", "dry-run-solver=on", fzn.absolutePath)) }
        assertTrue("ls dry-run:" in default, default)
        assertTrue("cbls/fixed" in default, default)

        // A global source removal drops `violated` from every arm.
        val removed = captureErr {
            main(arrayOf("-e", "ls", "--param", "sources=-violated", "--param", "dry-run-solver=on", fzn.absolutePath))
        }
        assertTrue("violated-repairs" !in removed, removed)

        // A scoped scalar edit sets break scoring on the cbls family only.
        val scoped = captureErr {
            main(arrayOf("-e", "ls", "--param", "scoring=cbls.break", "--param", "dry-run-solver=on", fzn.absolutePath))
        }
        assertTrue("scoring=Break" in scoped, scoped)

        // An acceptance edit to sa attaches a cooling schedule to arms that carried none.
        val annealed = captureErr {
            main(arrayOf("-e", "ls", "--param", "acceptance=sa", "--param", "dry-run-solver=on", fzn.absolutePath))
        }
        assertTrue("acceptance=Metropolis" in annealed, annealed)
        assertTrue("temperature=Geometric" in annealed, annealed)
    }

    @Test
    fun `dry-run-solver describes a backtrack engine`() {
        val fzn = File.createTempFile("cli", ".fzn").apply {
            writeText("var 1..3: x;\nconstraint int_lt(x, 3);\nsolve satisfy;\n")
            deleteOnExit()
        }
        val out = captureErr { main(arrayOf("-e", "cp", "--param", "dry-run-solver=on", fzn.absolutePath)) }
        assertTrue("solver dry-run:" in out, out)
        assertTrue("backtrack" in out && "var-select:" in out, out)
    }

    @Test
    fun `a cp override edits every arm of the pool rather than collapsing to one`() {
        val fzn = File.createTempFile("cli", ".fzn").apply {
            writeText("var 1..3: x;\nconstraint int_lt(x, 3);\nsolve satisfy;\n")
            deleteOnExit()
        }
        // -p8 -e cp with a pinned selector must stay a full pool: the override edits each curated arm,
        // so a parallel run keeps its per-arm diversity instead of racing eight identical solvers.
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
        assertEquals(2, armLines, "the curated arms are preserved as distinct pool entries:\n$out")
        assertTrue("var-select:  SmallestDomain" in out, "the override is pinned across the pool:\n$out")
        assertTrue("var-select:  Vsids" !in out, "no arm keeps its original selector once overridden:\n$out")
    }

    @Test
    fun `dry-run-presolve prints the presolved problem`() {
        val fzn = File.createTempFile("cli", ".fzn").apply {
            writeText("var 1..3: x;\nconstraint int_lt(x, 3);\nsolve satisfy;\n")
            deleteOnExit()
        }
        val out = captureErr { main(arrayOf("--param", "dry-run-presolve=on", fzn.absolutePath)) }
        assertTrue("presolve dry-run:" in out, out)
        assertTrue("factors:" in out, out)
        assertTrue("elapsed:" in out, out)
    }

    @Test
    fun `an open wide General LIA witness is rendered without Long narrowing`() {
        val smt = File.createTempFile("cli", ".smt2").apply {
            writeText(
                "(set-logic QF_LIA)\n" +
                    "(declare-const x Int)\n" +
                    "(assert (= x 18446744073709551616))\n" +
                    "(check-sat)\n",
            )
            deleteOnExit()
        }
        var code = -1
        val out = capture { code = runCli(arrayOf(smt.absolutePath)) }

        assertEquals(0, code, out)
        assertTrue(out.lines().firstOrNull() == "sat", out)
        assertTrue("(define-fun x () Int 18446744073709551616)" in out, out)
    }

    @Test
    fun `mixed open General LIA arithmetic is decided without a Long witness box`() {
        val smt = File.createTempFile("cli", ".smt2").apply {
            writeText(
                "(set-logic QF_LIA)\n" +
                    "(declare-const a Int)\n(declare-const b Int)\n" +
                    "(assert (>= b 0))\n(assert (<= b 7))\n" +
                    "(assert (>= (+ a b) 0))\n" +
                    "(check-sat)\n",
            )
            deleteOnExit()
        }
        var code = -1
        val out = capture { code = runCli(arrayOf(smt.absolutePath)) }

        assertEquals(0, code, out)
        assertTrue(out.lines().firstOrNull() == "sat", out)
    }

    @Test
    fun `the lp-harvest presolve pass reports its contribution when enabled`() {
        // x+y<=3, y+z<=3, x+z<=3 imply x+y+z<=5 (LP max 4.5), so the lp-harvest pass drops it. The pass is
        // opt-in, so it fires only with the +lp-harvest delta; the dry-run must then report the removal.
        val fzn = File.createTempFile("cli", ".fzn").apply {
            writeText(
                "array[1..3] of var 0..10: x;\n" +
                    "constraint int_lin_le([1,1],[x[1],x[2]],3);\n" +
                    "constraint int_lin_le([1,1],[x[2],x[3]],3);\n" +
                    "constraint int_lin_le([1,1],[x[1],x[3]],3);\n" +
                    "constraint int_lin_le([1,1,1],[x[1],x[2],x[3]],5);\n" +
                    "solve satisfy;\n",
            )
            deleteOnExit()
        }
        val off = captureErr { main(arrayOf("--param", "dry-run-presolve=on", fzn.absolutePath)) }
        assertTrue("lp-harvest:" !in off, "the opt-in pass must stay off by default: $off")
        val on = captureErr {
            main(arrayOf("--presolve", "default,+lp-harvest", "--param", "dry-run-presolve=on", fzn.absolutePath))
        }
        assertTrue("lp-harvest:" in on, on)
        assertTrue("redundant constraint" in on, on)
    }

    @Test
    fun `ls arm selects exactly one curated arm in isolation`() {
        val fzn = File.createTempFile("cli", ".fzn").apply {
            writeText("var 1..3: x;\nconstraint int_lt(x, 3);\nsolve satisfy;\n")
            deleteOnExit()
        }
        // arm= resolves a one-arm pool of exactly that catalog arm (the fair-tester sweep).
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
        // It also solves as a single isolated arm.
        val out = capture { main(arrayOf("-e", "ls", "--param", "arm=cbls/fixed", "-t", "5000", fzn.absolutePath)) }
        assertTrue("x = " in out, out)
        assertTrue("----------" in out, out)
    }

    @Test
    fun `ls numeric knobs no axis consumes are reported as ineffective`() {
        // noise is meaningless on the curated pool (no walksat acceptance) — rejected, not ignored.
        assertEquals(listOf("noise"), ineffectiveNumerics("auto", emptySet(), listOf("noise")))
        // The cbls base consumes noise/smoothing/tabu; a walksat acceptance edit consumes noise.
        assertTrue(ineffectiveNumerics("cbls", emptySet(), listOf("noise", "smooth-prob", "tabu-tenure")).isEmpty())
        assertTrue(ineffectiveNumerics("auto", setOf("walksat"), listOf("noise")).isEmpty())
        assertTrue(ineffectiveNumerics("probsat", emptySet(), listOf("cb")).isEmpty())
        assertTrue(ineffectiveNumerics("sa", emptySet(), listOf("initial-temp", "cooling-rate", "min-temp")).isEmpty())
        // feasibility-jump ignores tabu, so a tabu-tenure knob is ineffective there.
        assertEquals(listOf("tabu-tenure"), ineffectiveNumerics("fjump", emptySet(), listOf("tabu-tenure")))
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
    fun `alns engine optimizes a small cop`() {
        val fzn = File.createTempFile("cli", ".fzn").apply {
            writeText("var 1..3: x;\nconstraint int_lt(x, 3);\nsolve minimize x;\n")
            deleteOnExit()
        }
        // `-e alns` routes to the hybrid-ALNS portfolio (EngineMix.ALNS). x < 3 over 1..3 ⇒ optimum x = 1.
        val out = capture { main(arrayOf("-e", "alns", "-p", "2", "-t", "10000", fzn.absolutePath)) }
        assertTrue("x = 1" in out, out)
    }

    @Test
    fun `minizinc standard -f -p routes to a parallel portfolio in both -p spellings`() {
        val fzn = File.createTempFile("cli", ".fzn").apply {
            writeText("var 1..3: x;\nconstraint int_lt(x, 3);\nsolve satisfy;\n")
            deleteOnExit()
        }
        // `-f -p2` is the free (cp alias) parallel pool sized to 2, in the attached and the
        // separated spelling; `-e ls -p 2` is the pure-LS pool. Bare `-p2` selects the single-core
        // `fixed` engine and is rejected by design.
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
    fun `-s prints mzn-stat lines for satisfy and optimize verdicts`() {
        val sat = File.createTempFile("cli", ".fzn").apply {
            writeText("var 1..3: x;\nconstraint int_lt(x, 3);\nsolve satisfy;\n")
            deleteOnExit()
        }
        val opt = File.createTempFile("cli", ".fzn").apply {
            writeText("var 1..9: x;\nsolve maximize x;\n")
            deleteOnExit()
        }
        // Complete engine: the CP/CDCL counter schema (nodes/failures/...).
        val cpOut = capture { main(arrayOf("-s", "-e", "cp", sat.absolutePath)) }
        assertTrue("%%%mzn-stat: solveTime=" in cpOut, cpOut)
        assertTrue("%%%mzn-stat: solutions=1" in cpOut, cpOut)
        assertTrue("%%%mzn-stat: nodes=" in cpOut, cpOut)
        assertTrue("%%%mzn-stat-end" in cpOut, cpOut)
        // The stats block must come after the protocol terminator, not between solutions.
        assertTrue(cpOut.indexOf("==========") < cpOut.indexOf("%%%mzn-stat:"), cpOut)

        // Local search: the native LS schema (lsMoves/...), and none of the CP zero counters.
        val lsOut = capture { main(arrayOf("-s", "-e", "ls", sat.absolutePath)) }
        assertTrue("%%%mzn-stat: solutions=1" in lsOut, lsOut)
        assertTrue("%%%mzn-stat: lsMoves=" in lsOut, lsOut)
        assertTrue("%%%mzn-stat: nodes=" !in lsOut, lsOut)
        assertTrue("%%%mzn-stat-end" in lsOut, lsOut)

        val optOut = capture { main(arrayOf("-s", "-e", "bt", opt.absolutePath)) }
        assertTrue("%%%mzn-stat: solutions=" in optOut, optOut)
        assertTrue("%%%mzn-stat-end" in optOut, optOut)

        // The LS incumbent objective is reported in the model's orientation, not the engine's internal
        // minimise frame: a maximize incumbent reads as a positive value, never negated. LS optimize is
        // incomplete (it never proves the optimum), so the run is bounded by a short deadline.
        val maxOut = capture { main(arrayOf("-s", "-e", "ls", "-t", "100", opt.absolutePath)) }
        assertTrue("%%%mzn-stat: lsIncumbentObjective=" in maxOut, maxOut)
        assertTrue("%%%mzn-stat: lsIncumbentObjective=-" !in maxOut, maxOut)
    }

    @Test
    fun `--output-objective emits the _objective line per solution, off by default`() {
        // Objective var X_INTRODUCED_0_ is not in the output section, so the only way a parser
        // scraping the solution stream can read the optimised objective is the _objective line.
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
        val out = capture { main(arrayOf("-e", "bt", "-t", "5000", "-s", xml.absolutePath)) }
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
    fun `the presolve budget floor never takes more than its share of a short run`() {
        // 0.1 of 5s is 500ms, which the 5000ms floor would raise to the whole time limit.
        assertEquals(1250L, SolveCore.derivedPresolveBudgetMs(5_000L, 0.1))
    }

    @Test
    fun `the presolve budget floor still lifts a mid-length run`() {
        // 0.1 of 30s is 3s; the floor lifts it to 5s, which is under the 25% ceiling of 7.5s.
        assertEquals(5_000L, SolveCore.derivedPresolveBudgetMs(30_000L, 0.1))
    }

    @Test
    fun `the presolve budget is a plain share once the run is long enough`() {
        assertEquals(12_000L, SolveCore.derivedPresolveBudgetMs(120_000L, 0.1))
    }

    @Test
    fun `the presolve budget falls back to the flat backstop without a time limit`() {
        assertEquals(CliKnobs.DEFAULT_PRESOLVE_BUDGET_MS, SolveCore.derivedPresolveBudgetMs(null, 0.1))
    }

    @Test
    fun `an unbounded model refuted over its true ranges reports unsat, not unknown`() {
        // x - y <= -1 and y - x <= -1 sum to 0 <= -2, with neither variable bounded anywhere. The
        // refutation owes nothing to the finite search box, so softening it to `unknown` would be
        // throwing away a real answer.
        val smt = File.createTempFile("cli", ".smt2").apply {
            writeText(
                "(set-logic QF_LIA)\n" +
                    "(declare-const x Int)\n(declare-const y Int)\n" +
                    "(assert (< x y))\n(assert (< y x))\n(check-sat)\n",
            )
            deleteOnExit()
        }
        val out = capture { main(arrayOf(smt.absolutePath)) }
        assertTrue("unsat" in out, "expected unsat, got: $out")
    }
}
