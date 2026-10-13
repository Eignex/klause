package com.eignex.klause.cli

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.solver.pipeline.OpenTheoryAssignment
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MpsModeTest {
    @Test
    fun `MPS publication uses the checked current source value before duplicate handling`() {
        var checked: BigFraction? = BigFraction.ONE * BigFraction.ofLong(2).reciprocal()
        val output = MpsOutput(objectiveScale = 3L, sourceArithmeticValue = { checked?.let(SourceValue::Rational) })
        val publication = ArithmeticPublication(output)

        val text = capture {
            publication.publish("v X=1/2", SourceValue.Rational(BigFraction.ofLong(3)))
            publication.publish("v X=1/2", SourceValue.Rational(BigFraction.ofLong(99)))
            checked = null
            publication.publish("v X=1/2", SourceValue.Rational(BigFraction.ofLong(3)))
        }

        assertEquals("o 0.5\nv X=1/2\n", text)
        assertEquals(1L, publication.solutions)
    }

    @Test
    fun `accepted MPS objectives retain original units and decimal formatting`() {
        val output = MpsOutput(objectiveScale = 3L, sourceObjective = { BigFraction.ofLong(99) })

        val text = capture {
            output.onArithmeticIncumbent("v X=1/2", SourceValue.Rational(BigFraction.ONE *
                BigFraction.ofLong(2).reciprocal()))
        }

        assertEquals("o 0.5\nv X=1/2\n", text)
    }

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
    fun `open continuous objectives retain source sense in either direction`() {
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

            val pipeline = solvable.pipeline as SolvablePipeline.OpenTheory
            assertNotNull(pipeline.request.objective)
            assertEquals(sense == "MAX", pipeline.request.maximize)
        }
    }

    @Test
    fun `open continuous incumbents report the source objective without an optimum claim`() {
        val mps = File.createTempFile("mpsopenincumbent", ".mps").apply {
            writeText(
                "NAME T\nROWS\n N COST\n G R\nCOLUMNS\n" +
                    " M0 'MARKER' 'INTORG'\n I R -1\n M1 'MARKER' 'INTEND'\n" +
                    " X COST 0.5 R 1\nBOUNDS\n LI B I 3\n LO B X 3\nENDATA\n",
            )
            deleteOnExit()
        }

        val text = capture {
            assertEquals(0, runCli(arrayOf("-e", "ls", "--param", "node-limit=100",
                "--param", "max-flips=1", mps.absolutePath)))
        }

        val values = text.lines().single { it.startsWith("v ") }.removePrefix("v ").split(' ')
            .associate { coordinate -> coordinate.split('=').let { it[0] to it[1].toDouble() } }
        val integer = values.getValue("I")
        val real = values.getValue("X")
        val objective = text.lines().last { it.startsWith("o ") }.removePrefix("o ").toDouble()
        assertTrue(integer >= 3.0 && integer % 1.0 == 0.0 && real >= integer, text)
        assertEquals(real * 0.5, objective)
        assertTrue("s SATISFIABLE" in text.lines(), text)
        assertFalse("s OPTIMUM FOUND" in text.lines(), text)
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
        assertFalse(check(Sample(BooleanArray(0), LongArray(0), doubleArrayOf(0.2))))
        assertEquals(SolvablePipeline.FiniteCp, solvable.pipeline)
    }

    @Test
    fun `exact open decimal sources admit complete search`() {
        val mps = File.createTempFile("mpsopenrounded", ".mps").apply {
            writeText(
                "NAME T\nROWS\n N COST\n G R\nCOLUMNS\n M0 'MARKER' 'INTORG'\n" +
                    " I COST 1 R -1\n M1 'MARKER' 'INTEND'\n X R 1\n" +
                    "BOUNDS\n LI B I 0\n LO B X 0.1\nENDATA\n",
            )
            deleteOnExit()
        }

        val solvable = MpsMode.newSession().load(mps.absolutePath, CommonOptions().apply { exact = true })

        assertTrue(solvable.pipeline is SolvablePipeline.OpenTheory)
        assertNull(solvable.toleranceCheck)
    }

    @Test
    fun `default open decimal incumbents report their original objective`() {
        val mps = File.createTempFile("mpsopentolerance", ".mps").apply {
            writeText(
                "NAME T\nROWS\n N COST\n G R\nCOLUMNS\n M0 'MARKER' 'INTORG'\n" +
                    " I COST 1 R -1\n M1 'MARKER' 'INTEND'\n X COST 1.0000000000000000001 R 1\n" +
                    "BOUNDS\n LI B I 0\n LO B X 0.1\nENDATA\n",
            )
            deleteOnExit()
        }
        val session = MpsMode.newSession()
        val solvable = session.load(mps.absolutePath, CommonOptions())
        val pipeline = solvable.pipeline as SolvablePipeline.OpenTheory
        val assignment = OpenTheoryAssignment.Sampled(
            Sample(BooleanArray(0), longArrayOf(0L), doubleArrayOf(1.0), listOf(BigFraction.ONE)),
        )
        val rendered = pipeline.render(assignment)
        val output = session.output(CommonOptions())

        val text = capture {
            output.onExactSolution(rendered, BigFraction.ONE)
            output.onComplete(Verdict.BEST_FOUND)
        }

        assertTrue("o 1.0000000000000000001" in text.lines(), text)
        assertTrue("s SATISFIABLE" in text.lines(), text)
        assertFalse("s OPTIMUM FOUND" in text.lines(), text)
    }

    @Test
    fun `default open decimal witnesses violating the source are withheld`() {
        val mps = File.createTempFile("mpsopenreject", ".mps").apply {
            writeText(
                "NAME T\nROWS\n N COST\n G R\nCOLUMNS\n M0 'MARKER' 'INTORG'\n" +
                    " I COST 1 R -1\n M1 'MARKER' 'INTEND'\n X R 1\n" +
                    "BOUNDS\n LI B I 0\n LO B X 0.1\nENDATA\n",
            )
            deleteOnExit()
        }
        val session = MpsMode.newSession()
        val solvable = session.load(mps.absolutePath, CommonOptions())
        val pipeline = solvable.pipeline as SolvablePipeline.OpenTheory
        val assignment = OpenTheoryAssignment.Sampled(
            Sample(BooleanArray(0), longArrayOf(1L), doubleArrayOf(0.0), listOf(BigFraction.ZERO)),
        )
        val rendered = pipeline.render(assignment)
        val output = session.output(CommonOptions())

        val text = capture {
            output.onExactSolution(rendered, BigFraction.ONE)
            output.onComplete(Verdict.BEST_FOUND)
        }

        assertTrue("s UNKNOWN" in text.lines(), text)
        assertFalse(text.lines().any { it.startsWith("v ") || it.startsWith("o ") }, text)
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

    @Test
    fun `complete engines prove original decimal objective cutoffs`() {
        val mps = File.createTempFile("mpssourceobjective", ".mps").apply {
            writeText(
                "ROWS\n N COST\n G R\nCOLUMNS\n M0 'MARKER' 'INTORG'\n I COST 1 R -1\n" +
                    " M1 'MARKER' 'INTEND'\n X COST 1.0000000000000000001 R 1\n" +
                    "BOUNDS\n LI B I 0\n LO B X 0.1\nENDATA\n",
            )
            deleteOnExit()
        }
        for (engine in listOf("cp", "fixed", "mixed")) {
            val text = capture {
                assertEquals(0, runCli(arrayOf("-e", engine, "-p", "1", "--param",
                    "node-limit=10000", mps.absolutePath)))
            }

            assertTrue("s OPTIMUM FOUND" in text.lines(), text)
            assertTrue("o 0.10000000000000000001" in text.lines(), text)
            assertTrue("v I=0 X=1/10" in text.lines(), text)
        }
    }

    @Test
    fun `original decimal cancellation supersedes a rounded refutation`() {
        val mps = File.createTempFile("mpssourcecancellation", ".mps").apply {
            writeText(
                "ROWS\n E R\nCOLUMNS\n M0 'MARKER' 'INTORG'\n I R 0\n" +
                    " M1 'MARKER' 'INTEND'\n X R 10000000000000000\n X R 1\n X R -10000000000000000\n" +
                    "RHS\n RHS R 1\nBOUNDS\n FR B I\n FR B X\nENDATA\n",
            )
            deleteOnExit()
        }

        val text = capture {
            assertEquals(0, runCli(arrayOf("-e", "fixed", "-p", "1", mps.absolutePath)))
        }

        assertTrue("s SATISFIABLE" in text.lines(), text)
        assertTrue("v I=0 X=1" in text.lines(), text)
    }

    @Test
    fun `exact source refutations do not follow rounded decimal feasibility`() {
        val mps = File.createTempFile("mpssourceconflict", ".mps").apply {
            writeText(
                "ROWS\n E R\nCOLUMNS\n M0 'MARKER' 'INTORG'\n I R 0\n" +
                    " M1 'MARKER' 'INTEND'\n X R 0.1\n X R 0.2\n X R -0.3\n" +
                    "RHS\n RHS R 0.00000000000000000001\nBOUNDS\n FR B I\n FR B X\nENDATA\n",
            )
            deleteOnExit()
        }

        val text = capture {
            assertEquals(0, runCli(arrayOf("-e", "cp", "-p", "1", mps.absolutePath)))
        }

        assertTrue("s UNSATISFIABLE" in text.lines(), text)
    }

    @Test
    fun `heuristic engines retain original decimal incumbent strength`() {
        val mps = File.createTempFile("mpssourceheuristic", ".mps").apply {
            writeText(
                "ROWS\n N COST\n E R\nCOLUMNS\n M0 'MARKER' 'INTORG'\n I R 0\n" +
                    " M1 'MARKER' 'INTEND'\n X COST 0.1 R 0.2\nRHS\n RHS R 0.2\n" +
                    "BOUNDS\n FR B I\n FR B X\nENDATA\n",
            )
            deleteOnExit()
        }
        for (engine in listOf("ls", "alns")) {
            val text = capture {
                assertEquals(0, runCli(arrayOf("-e", engine, "-p", "1", "--param",
                    "node-limit=10000", "--param", "max-flips=4", mps.absolutePath)))
            }

            assertTrue("s SATISFIABLE" in text.lines(), text)
            assertTrue("o 0.1" in text.lines(), text)
            assertFalse("s OPTIMUM FOUND" in text.lines(), text)
        }
    }

    @Test
    fun `explicit exact continuous decimal sources have original optimum authority`() {
        val mps = File.createTempFile("mpsexactsourcecontinuous", ".mps").apply {
            writeText("ROWS\n N COST\nCOLUMNS\n X COST 1\nBOUNDS\n LO B X 0.1\nENDATA\n")
            deleteOnExit()
        }

        val text = capture {
            assertEquals(0, runCli(arrayOf("--exact", "-e", "cp", "-p", "1", mps.absolutePath)))
        }

        assertTrue("s OPTIMUM FOUND" in text.lines(), text)
        assertTrue("o 0.1" in text.lines(), text)
        assertTrue("v X=1/10" in text.lines(), text)
    }

    @Test
    fun `bounded mixed decimal sources retain original optimum authority`() {
        val mps = File.createTempFile("mpsfiniteoriginalsource", ".mps").apply {
            writeText(
                "ROWS\n N COST\n G R\nCOLUMNS\n M0 'MARKER' 'INTORG'\n I R -0.1\n" +
                    " M1 'MARKER' 'INTEND'\n X COST 0.1 R 1\nRHS\n RHS R 0.2\n" +
                    "BOUNDS\n LI B I 0\n UI B I 1\n LO B X 0.1\nENDATA\n",
            )
            deleteOnExit()
        }

        val text = capture {
            assertEquals(0, runCli(arrayOf("-e", "cp", "-p", "1", mps.absolutePath)))
        }

        assertTrue("s OPTIMUM FOUND" in text.lines(), text)
        assertTrue("o 0.02" in text.lines(), text)
        assertTrue("v I=0 X=1/5" in text.lines(), text)
    }

    @Test
    fun `rejected terminal witnesses cannot promote an earlier incumbent`() {
        for (verdict in listOf(Verdict.OPTIMAL, Verdict.UNBOUNDED)) {
            var accepted = true
            val output = MpsOutput(sourceWitnessAccepted = { accepted })

            val text = capture {
                output.onSolution("v X=1", objective = 1)
                accepted = false
                output.onSolution("v X=0", objective = 0)
                output.onComplete(verdict)
            }

            assertTrue("s SATISFIABLE" in text.lines(), text)
            assertFalse("s OPTIMUM FOUND" in text.lines(), text)
            assertFalse("s UNBOUNDED" in text.lines(), text)
        }
    }

    @Test
    fun `constant source objectives retain attained optimum semantics`() {
        val mps = File.createTempFile("mpsconstantsource", ".mps").apply {
            writeText("ROWS\n N COST\n E R\nRHS\n RHS R 0 COST 0.3\nENDATA\n")
            deleteOnExit()
        }

        val text = capture {
            assertEquals(0, runCli(arrayOf("-e", "cp", "-p", "1", mps.absolutePath)))
        }

        assertTrue("s OPTIMUM FOUND" in text.lines(), text)
        assertTrue("o -0.3" in text.lines(), text)
    }

    @Test
    fun `retained MPS outputs keep their original source objective scope`() {
        val files = listOf("1.0000000000000000001", "2").map { coefficient ->
            File.createTempFile("mpsobjectivescope", ".mps").apply {
                writeText(
                    "ROWS\n N COST\n G R\nCOLUMNS\n M0 'MARKER' 'INTORG'\n I R -1\n" +
                        " M1 'MARKER' 'INTEND'\n X COST $coefficient R 1\n" +
                        "BOUNDS\n LI B I 0\n LO B X 0.1\nENDATA\n",
                )
                deleteOnExit()
            }
        }
        val session = MpsMode.newSession()
        val original = session.load(files[0].absolutePath, CommonOptions()).pipeline as SolvablePipeline.OpenTheory
        val output = session.output(CommonOptions())
        val assignment = OpenTheoryAssignment.Sampled(
            Sample(BooleanArray(0), longArrayOf(0), doubleArrayOf(1.0), listOf(BigFraction.ONE)),
        )
        val rendered = original.render(assignment)
        val next = session.load(files[1].absolutePath, CommonOptions()).pipeline as SolvablePipeline.OpenTheory
        next.render(assignment)

        val text = capture {
            output.onExactSolution(rendered, BigFraction.ONE)
            output.onComplete(Verdict.BEST_FOUND)
        }

        assertTrue("o 1.0000000000000000001" in text.lines(), text)
        assertFalse("o 2" in text.lines(), text)
    }

    @Test
    fun `source witness guarantees distinguish exact and tolerance acceptance`() {
        val mps = File.createTempFile("mpswitnessguarantee", ".mps").apply {
            writeText("OBJSENSE\n MAX\nROWS\n N COST\nCOLUMNS\n X COST 1\nBOUNDS\n UP B X 1\nENDATA\n")
            deleteOnExit()
        }
        for (exact in listOf(false, true)) {
            val common = CommonOptions().apply { this.exact = exact }
            val session = MpsMode.newSession()
            val solvable = session.load(mps.absolutePath, common)
            val sample = Sample(BooleanArray(0), LongArray(0), doubleArrayOf(if (exact) 1.0 else 1.00000001),
                if (exact) listOf(BigFraction.ONE) else null)
            if (!exact) assertTrue(assertNotNull(solvable.toleranceCheck)(sample))
            val output = session.output(common)

            val text = capture {
                output.onSolution(solvable.render(sample), null, sample.approximateRealValue(0))
                output.onComplete(Verdict.OPTIMAL)
            }

            val qualification = if (exact) "exact original MPS source witness" else "MPS source tolerance witness"
            assertTrue(qualification in text, text)
            val status = if (exact) "attained-optimum" else "tolerance-optimum"
            assertTrue("c optimizationStatus=$status" in text.lines(), text)
        }
    }

    @Test
    fun `a tolerance witness cannot support original source unboundedness`() {
        val output = MpsOutput(exactSourceWitness = { false })

        val text = capture {
            output.onSolution("v X=0", objective = 0)
            output.onComplete(Verdict.UNBOUNDED)
        }

        assertTrue("s SATISFIABLE" in text.lines(), text)
        assertFalse("s UNBOUNDED" in text.lines(), text)
    }
}
