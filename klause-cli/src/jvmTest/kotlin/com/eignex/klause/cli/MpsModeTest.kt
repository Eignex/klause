package com.eignex.klause.cli

import com.eignex.klause.formats.mps.MpsLoweringException
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MpsModeTest {
    @Test
    fun `exact open objectives undo the MPS scale without rounding`() {
        for (sign in listOf(-1L, 1L)) {
            val output = MpsOutput(objectiveScale = 3L)

            val text = capture { output.onExactSolution("v X=$sign", BigFraction.ofLong(sign)) }

            assertTrue("o ${if (sign < 0) "-" else ""}1/3" in text, text)
        }
    }

    private fun capture(block: () -> Unit): String {
        val output = ByteArrayOutputStream()
        val previous = System.out
        System.setOut(PrintStream(output))
        try {
            block()
        } finally {
            System.setOut(previous)
        }
        return output.toString()
    }

    @Test
    fun `exact MPS loading requires certificates instead of tolerance acceptance`() {
        val mps = File.createTempFile("mpsfloat", ".mps").apply {
            writeText(
                """
                NAME T
                ROWS
                 N COST
                 L LIMIT
                COLUMNS
                    X COST 1 LIMIT 1
                RHS
                    RHS LIMIT 1
                BOUNDS
                 UP BND X 1
                ENDATA
                """.trimIndent(),
            )
            deleteOnExit()
        }

        val tolerant = MpsMode.newSession().load(mps.absolutePath, CommonOptions())
        val exact = MpsMode.newSession().load(mps.absolutePath, CommonOptions().apply { this.exact = true })

        assertNotNull(tolerant.toleranceCheck)
        assertNull(exact.toleranceCheck)
    }

    @Test
    fun `pure continuous objectives attain fractional optima in either direction`() {
        for ((sense, expected) in listOf("MIN" to "1/3", "MAX" to "2/3")) {
            val mps = File.createTempFile("mpscontinuous", ".mps").apply {
                writeText(
                    "NAME T\nOBJSENSE\n $sense\nROWS\n N COST\n G LOWER\n L UPPER\n" +
                        "COLUMNS\n X COST 1 LOWER 3\n X UPPER 3\nRHS\n RHS LOWER 1 UPPER 2\n" +
                        "BOUNDS\n FR B X\nENDATA\n",
                )
                deleteOnExit()
            }

            val text = capture {
                assertEquals(0, runCli(arrayOf("--exact", "-e", "cp", "-p", "1", mps.absolutePath)))
            }

            assertTrue("o $expected" in text.lines(), text)
            assertTrue("v X=$expected" in text.lines(), text)
            assertTrue("s OPTIMUM FOUND" in text.lines(), text)
        }
    }

    @Test
    fun `open continuous objectives use witness search in either direction`() {
        for (sense in listOf("MIN", "MAX")) {
            val mps = File.createTempFile("mpsopenobjective", ".mps").apply {
                writeText(
                    "NAME T\nOBJSENSE\n $sense\nROWS\n N COST\n G R\nCOLUMNS\n" +
                        " M0 'MARKER' 'INTORG'\n I R -1\n M1 'MARKER' 'INTEND'\n" +
                        " X COST 0.5 R 1\nBOUNDS\n LI B I 3\nENDATA\n",
                )
                deleteOnExit()
            }

            val solvable = MpsMode.newSession().load(mps.absolutePath, CommonOptions())

            val pipeline = solvable.pipeline as SolvablePipeline.OpenLocalSearch
            assertNotNull(pipeline.objective)
            assertEquals(sense == "MAX", pipeline.maximize)
        }
    }

    @Test
    fun `pure continuous decimal lowerings admit only source tolerance witnesses`() {
        val mps = File.createTempFile("mpscontinuoustolerance", ".mps").apply {
            writeText(
                "NAME T\nROWS\n N COST\nCOLUMNS\n X COST -1\n" +
                    "BOUNDS\n UP B X 0.1\nENDATA\n",
            )
            deleteOnExit()
        }
        val solvable = MpsMode.newSession().load(mps.absolutePath, CommonOptions())
        val check = assertNotNull(solvable.toleranceCheck)

        assertTrue(check(Sample(BooleanArray(0), LongArray(0), doubleArrayOf(0.1))))
        assertEquals(false, check(Sample(BooleanArray(0), LongArray(0), doubleArrayOf(0.2))))
        assertEquals(SolvablePipeline.FiniteCp, solvable.pipeline)
    }

    @Test
    fun `open decimal lowerings retain a source correspondence diagnostic`() {
        val mps = File.createTempFile("mpsopenrounded", ".mps").apply {
            writeText(
                "NAME T\nROWS\n N COST\n G R\nCOLUMNS\n M0 'MARKER' 'INTORG'\n" +
                    " I COST 1 R -1\n M1 'MARKER' 'INTEND'\n X R 1\n" +
                    "BOUNDS\n LI B I 0\n LO B X 0.1\nENDATA\n",
            )
            deleteOnExit()
        }

        for (exact in listOf(false, true)) {
            val failure = assertFailsWith<MpsLoweringException> {
                MpsMode.newSession().load(mps.absolutePath, CommonOptions().apply { this.exact = exact })
            }

            val route = if (exact) "exact MPS lowering" else "open MPS source"
            assertTrue("$route differs" in failure.message.orEmpty(), failure.message)
            assertTrue("column bound 'X'" in failure.message.orEmpty(), failure.message)
        }
    }

    @Test
    fun `a source objective with no finite decimal prints as a fraction`() {
        val third = BigFraction.ofLong(3L).reciprocal()
        val output = MpsOutput(sourceObjective = { third })

        val text = capture { output.onSolution("v X=1/3", objective = 0L, continuousObjective = 1.0 / 3.0) }

        assertTrue("o 1/3" in text, text)
    }

    @Test
    fun `a source objective with a finite decimal stays decimal`() {
        val half = BigFraction.ofLong(2L).reciprocal()
        val output = MpsOutput(sourceObjective = { half })

        val text = capture { output.onSolution("v X=0.5", objective = 0L, continuousObjective = 0.5) }

        assertTrue("o 0.5" in text, text)
    }

    @Test
    fun `a source difference qualifies the lowered optimum`() {
        val output = MpsOutput(sourceExact = false, sourceDifference = "objective coefficient 'X'")

        val text = capture {
            output.onSolution("v X=1", objective = 1L)
            output.onComplete(Verdict.OPTIMAL)
        }

        assertTrue("s SATISFIABLE" in text, text)
        assertTrue("lowered model differs from MPS source at objective coefficient 'X'" in text, text)
    }

    @Test
    fun `a feasible verdict without a verified source witness is unknown`() {
        val output = MpsOutput(sourceExact = false, sourceDifference = "row 'R' coefficient")

        val text = capture { output.onComplete(Verdict.OPTIMAL) }

        assertTrue("s UNKNOWN" in text, text)
        assertTrue("s SATISFIABLE" !in text, text)
    }

    @Test
    fun `a source difference declines negative terminal claims`() {
        listOf(Verdict.UNSATISFIABLE, Verdict.UNBOUNDED).forEach { verdict ->
            val output = MpsOutput(sourceExact = false, sourceDifference = "row 'R' coefficient")

            val text = capture { output.onComplete(verdict) }

            assertTrue("s UNKNOWN" in text, text)
        }
    }

    @Test
    fun `a source within tolerance still declines negative terminal claims`() {
        listOf(Verdict.UNSATISFIABLE, Verdict.UNBOUNDED).forEach { verdict ->
            val output = MpsOutput(sourceExact = true, proofExact = false)

            val text = capture { output.onComplete(verdict) }

            assertTrue("s UNKNOWN" in text, text)
        }
    }
}
