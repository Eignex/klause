package com.eignex.klause.cli

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CliModeMpsTest {

    @Test
    fun `an MPS objective should be reported in file units`() {
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
                BOUNDS
                 PL BND       X
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
    fun `a rounded MPS source row should be solved within tolerance`() {
        val mps = File.createTempFile("clisourcereject", ".mps").apply {
            writeText(
                """
                NAME ROUNDEDSOURCE
                ROWS
                 N COST
                 E R
                COLUMNS
                 X COST 1 R 3.0000000000000001
                RHS
                 RHS R 1
                ENDATA
                """.trimIndent(),
            )
            deleteOnExit()
        }
        val out = capture {
            assertEquals(
                0,
                runCli(arrayOf("-e", "cp", "-p", "1", "--param", "arms=1", mps.absolutePath)),
            )
        }

        assertTrue("s SATISFIABLE" in out.lines(), out)
        assertTrue("v X=0.3333333333333333" in out.lines(), out)
        assertTrue("c workingModelOptimizationStatus=tolerance-optimum" in out.lines(), out)
        assertTrue("tolerance-accepted optimality unproved" in out, out)
    }

    @Test
    fun `an MPS maximize objective should report its maximum`() {
        val model = """
            NAME          MAXINT
            OBJSENSE
                MAX
            ROWS
             N  COST
             L  R
            COLUMNS
                M1        'MARKER'                 'INTORG'
                I         COST           1.0   R           1.0
                J         COST           2.0   R           1.0
                M2        'MARKER'                 'INTEND'
            RHS
                RHS       R              4
            BOUNDS
             UP BND       I              10
             UP BND       J              3
            ENDATA
        """.trimIndent()
        val mps = File.createTempFile("climaximize", ".mps").apply {
            writeText(model)
            deleteOnExit()
        }

        val out = capture { main(arrayOf(mps.absolutePath)) }

        assertTrue("s OPTIMUM FOUND" in out, out)
        assertTrue("o 7" in out.lines(), out)
        assertTrue("v I=1 J=3" in out.lines(), out)
    }

    @Test
    fun `an MPS objective with a continuous contribution should be reported whole`() {
        val output = MpsOutput()

        val out = capture { output.onSolution("v x=60.0", objective = 0L, continuousObjective = 60.0) }

        assertTrue("o 60" in out, out)
        assertTrue("o 0" !in out, "the discrete part alone must not be reported: $out")
    }

    @Test
    fun `a scaled MPS objective should unscale before printing`() {
        val output = MpsOutput(objectiveScale = 10L)

        val out = capture { output.onSolution("v x=6.05", objective = 0L, continuousObjective = 60.5) }

        assertTrue("o 6.05" in out, out)
    }

    @Test
    fun `portfolio telemetry should carry exact discrete and whole continuous objectives`() {
        val output = MpsOutput()

        val out = capture {
            output.onImprovement("integer", 9_007_199_254_740_993L, elapsedMs = 10)
            output.onImprovement("continuous", 0L, continuousObjective = 60.0, elapsedMs = 20)
            output.onImprovement("mixed-min", -3L, continuousObjective = -63.5, elapsedMs = 30)
            output.onImprovement("mixed-max", 3L, continuousObjective = 63.5, elapsedMs = 40)
        }

        assertTrue("label=integer objective=9007199254740993 time=10" in out, out)
        assertTrue("label=continuous objective=0 continuousObjective=60.0 time=20" in out, out)
        assertTrue("label=mixed-min objective=-3 continuousObjective=-63.5 time=30" in out, out)
        assertTrue("label=mixed-max objective=3 continuousObjective=63.5 time=40" in out, out)
    }

    @Test
    fun `declining MPS bound closure should retain numerical solving under both certification policies`() {
        val mps = File.createTempFile("cli", ".mps").apply {
            writeText(
                "NAME          T\nROWS\n N  COST\n L  R1\nCOLUMNS\n" +
                    "    MARKER                 'MARKER'                 'INTORG'\n" +
                    "    x  COST  -1.0   R1  1.0\n" +
                    "    MARKER                 'MARKER'                 'INTEND'\n" +
                    "RHS\n    RHS  R1  5.0\nBOUNDS\n LI BND  x  0\nENDATA\n",
            )
            deleteOnExit()
        }

        for (exact in listOf(false, true)) {
            val policy = if (exact) arrayOf("--exact") else emptyArray()
            val kept = capture {
                assertEquals(
                    0,
                    runCli(
                        arrayOf("-s", "-t", "10000", "-e", "cp", "-p", "1", "--param", "arms=1") +
                            policy + mps.absolutePath,
                    ),
                )
            }
            val declined = capture {
                assertEquals(
                    0,
                    runCli(
                        arrayOf("-s", "-t", "10000", "--param", "open-bound-proof=false") +
                            policy + mps.absolutePath,
                    ),
                )
            }

            assertTrue("solvePipeline=finite-cp" in kept, kept)
            assertTrue("solvePipeline=numerical-mip" in declined, declined)
            assertTrue("s OPTIMUM FOUND" in declined, declined)
            assertTrue("v x=5" in declined, declined)
            assertFalse("cancelled" in declined, declined)
        }
    }

    @Test
    fun `bound-proof params should be consumed rather than left for an engine to reject`() {
        val mps = File.createTempFile("cli", ".mps").apply {
            writeText(
                "NAME          T\nROWS\n N  COST\n G  R1\nCOLUMNS\n" +
                    "    MARKER                 'MARKER'                 'INTORG'\n" +
                    "    x  COST  1.0   R1  1.0\n" +
                    "    MARKER                 'MARKER'                 'INTEND'\n" +
                    "RHS\n    RHS  R1  1.0\nBOUNDS\n UI BND  x  4\nENDATA\n",
            )
            deleteOnExit()
        }

        var code = -1
        val err = captureErr {
            capture {
                code = runCli(arrayOf("-t", "10000", "--param", "open-bound-proof=false", mps.absolutePath))
            }
        }

        assertEquals(0, code, err)
        assertFalse("open-bound-proof" in err, err)
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
