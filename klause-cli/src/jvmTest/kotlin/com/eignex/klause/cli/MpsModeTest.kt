package com.eignex.klause.cli

import com.eignex.klause.formats.mps.Mps
import com.eignex.klause.formats.mps.toExactSourceProblem
import com.eignex.klause.formats.mps.toProblem
import com.eignex.klause.ir.FloatSemantics
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SemanticOverrides
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.solver.pipeline.OpenTheoryAssignment
import com.eignex.klause.solver.result.LpStats
import com.eignex.klause.solver.result.RunStats
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.bigIntOf
import com.eignex.kumulant.stat.summary.SumResult
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MpsModeTest {
    @Test
    fun `inexact working optima should retain scoped metadata beside accepted source points`() {
        for (claimsCoverTolerance in listOf(false, true)) {
            val output = MpsOutput(
                sourceExact = false,
                exactSourceWitness = { true },
                claimsCoverTolerance = claimsCoverTolerance,
            )

            val text = capture {
                output.onSolution("v X=1", objective = 1)
                output.onComplete(Verdict.OPTIMAL)
            }

            assertTrue("s SATISFIABLE" in text.lines(), text)
            assertTrue("c workingModelOptimizationStatus=tolerance-optimum" in text.lines(), text)
            assertTrue("v X=1" in text.lines(), text)
            assertFalse(text.lines().any { it.startsWith("c optimizationStatus=") }, text)
        }
    }

    @Test
    fun `a checked accepted objective bound should retain attained optimum authority`() {
        val output = MpsOutput(
            sourceExact = false,
            sourceObjective = { BigFraction.ONE },
            exactSourceWitness = { false },
            claimsCoverTolerance = false,
            acceptedObjectiveBound = BigFraction.ONE,
        )

        val text = capture {
            output.onExactSolution("v X=1", BigFraction.ONE)
            output.onComplete(Verdict.OPTIMAL)
        }

        assertTrue("s OPTIMUM FOUND" in text.lines(), text)
        assertTrue("c optimizationStatus=attained-optimum" in text.lines(), text)
        assertTrue("c MPS source tolerance witness" in text.lines(), text)
        assertFalse("tolerance-accepted optimality unproved" in text, text)
    }

    @Test
    fun `a nominal root bound should retain its value and source units under tolerant publication`() {
        for ((proofExact, key) in listOf(true to "nominalSourceLpRootBound", false to "workingModelLpRootBound")) {
            val output = MpsOutput(objectiveScale = 3L, proofExact = proofExact, claimsCoverTolerance = false)
            val stats = SolveStats(
                run = RunStats(backend = "backtrack"),
                lp = LpStats(solves = SumResult(1.0), rootBound = -1.23456789),
            )

            val text = capture { output.onStatistics(stats, 1L, 0L) }

            assertTrue("c $key=-1.23456789" in text.lines(), text)
            assertTrue("c ${key}Scale=3" in text.lines(), text)
            assertFalse(text.lines().any { it.startsWith("c lpRootBound=") }, text)
        }
    }

    @Test
    fun `an exact nominal unbounded ray should remain valid in the tolerance accepted superset`() {
        val output = MpsOutput(exactSourceWitness = { true }, claimsCoverTolerance = false)

        val text = capture {
            output.onExactSolution("v X=1", BigFraction.ONE)
            output.onComplete(Verdict.UNBOUNDED)
        }

        assertTrue("s UNBOUNDED" in text.lines(), text)
        assertTrue("v X=1" in text.lines(), text)
    }

    @Test
    fun `a tolerance accepted constant row must not inherit a nominal refutation`() {
        val source = File.createTempFile("mps-tolerated-empty-row", ".mps").apply {
            writeText("ROWS\n N COST\n G R\nCOLUMNS\n X COST 0\nRHS\n RHS R 0.00000005\nENDATA\n")
            deleteOnExit()
        }
        for (exact in listOf(false, true)) {
            val flags = if (exact) arrayOf("--exact") else emptyArray()
            val text = capture {
                assertEquals(0, runCli(arrayOf("-e", "cp", "-p", "1", *flags, source.absolutePath)))
            }

            assertTrue((if (exact) "s UNSATISFIABLE" else "s UNKNOWN") in text.lines(), text)
            if (!exact) assertTrue("nominal refutation; tolerance-accepted feasibility unproved" in text, text)
        }
    }

    @Test
    fun `nominal continuous optimization should retain its point without claiming the tolerant optimum`() {
        val source = File.createTempFile("mps-tolerant-objective", ".mps").apply {
            writeText("ROWS\n N COST\nCOLUMNS\n X COST 1\nBOUNDS\n LO B X 1\nENDATA\n")
            deleteOnExit()
        }
        val text = capture {
            assertEquals(0, runCli(arrayOf("-e", "cp", "-p", "1", "--source-absolute-tolerance", "1",
                "--source-relative-tolerance", "0", source.absolutePath)))
        }

        assertTrue("s SATISFIABLE" in text.lines(), text)
        assertTrue("o 1" in text.lines(), text)
        assertEquals(1.0, text.lines().single { it.startsWith("v ") }.substringAfter("X=").toDouble())
        assertTrue("nominalSourceOptimizationStatus=" in text, text)
        assertTrue("tolerance-accepted optimality unproved" in text, text)
    }

    @Test
    fun `zero tolerance checking should use exact rational source coordinates when supplied`() {
        val input = File.createTempFile("mps-zero-rational", ".mps").apply {
            writeText("ROWS\n N COST\n E R\nCOLUMNS\n X COST 1 R 1\nRHS\n RHS R 0.1\nENDATA\n")
            deleteOnExit()
        }
        val common = CommonOptions().apply {
            semanticOverrides = SemanticOverrides(feasibilityTolerance = SourceTolerance())
        }
        val check = assertNotNull(MpsMode.newSession().load(input.absolutePath, common).toleranceCheck)
        val tenth = BigFraction.ofLong(10).reciprocal()
        val exact = Sample(BooleanArray(0), LongArray(0), doubleArrayOf(0.1), exactReals = listOf(tenth))
        val approximate = Sample(BooleanArray(0), LongArray(0), doubleArrayOf(0.1))

        assertTrue(check(exact))
        assertFalse(check(approximate))
    }

    @Test
    fun `MPS caller declaration defaults should qualify optimum output to the effective source`() {
        val input = File.createTempFile("mps-effective-defaults", ".mps").apply {
            writeText("ROWS\n N COST\nCOLUMNS\n M0 'MARKER' 'INTORG'\n I COST 1\n M1 'MARKER' 'INTEND'\nENDATA")
            deleteOnExit()
        }
        val common = CommonOptions()
        parseArgs(arrayOf("--exact", "--unbounded-int-lo", "2", "--unbounded-int-hi", "3"),
            commonFlagSpecs(common)) {}
        val session = MpsMode.newSession()
        val loaded = session.load(input.absolutePath, common)
        val output = session.output(common)

        val text = capture {
            output.onSolution(loaded.render(Sample(BooleanArray(0), longArrayOf(2L))), 2L, null)
            output.onComplete(Verdict.OPTIMAL)
        }

        assertTrue("s OPTIMUM FOUND" in text.lines(), text)
        assertTrue("o 2" in text.lines(), text)
        assertTrue("exact effective MPS source witness" in text, text)
        assertTrue("MPS source scope=effective declaration defaults" in text, text)
        assertFalse("exact original MPS source witness" in text, text)
    }

    @Test
    fun `MPS caller declaration defaults should qualify source refutations`() {
        val input = File.createTempFile("mps-effective-refutation", ".mps").apply {
            writeText("ROWS\n L R\nCOLUMNS\n M0 'MARKER' 'INTORG'\n I R 1\n" +
                " M1 'MARKER' 'INTEND'\nRHS\n RHS R 1\nENDATA")
            deleteOnExit()
        }
        val common = CommonOptions()
        parseArgs(arrayOf("--exact", "--unbounded-int-lo", "2", "--unbounded-int-hi", "open"),
            commonFlagSpecs(common)) {}
        val session = MpsMode.newSession()
        val loaded = session.load(input.absolutePath, common)

        val text = capture { session.output(common).onComplete(Verdict.UNSATISFIABLE) }

        assertIs<SolvablePipeline.Refuted>(loaded.pipeline)
        assertTrue("s UNSATISFIABLE" in text.lines(), text)
        assertTrue("MPS source scope=effective declaration defaults" in text, text)
    }

    @Test
    fun `MPS real declaration flags should retain exact rational endpoints`() {
        val input = File.createTempFile("mps-rational-defaults", ".mps").apply {
            writeText("ROWS\n N COST\nCOLUMNS\n X COST 1\nENDATA")
            deleteOnExit()
        }
        val common = CommonOptions()
        parseArgs(arrayOf("--exact", "--unbounded-real-lo", "1/3", "--unbounded-real-hi", "1/3"),
            commonFlagSpecs(common)) {}
        val session = MpsMode.newSession()
        val loaded = session.load(input.absolutePath, common)
        val value = BigFraction.ofLong(3).reciprocal()
        val output = session.output(common)

        val text = capture {
            val point = Sample(BooleanArray(0), LongArray(0), doubleArrayOf(1.0 / 3), listOf(value))
            output.onExactSolution(loaded.render(point), value)
            output.onComplete(Verdict.SATISFIABLE)
        }

        assertTrue("o 1/3" in text.lines(), text)
        assertTrue("s SATISFIABLE" in text.lines(), text)
        assertTrue("exact effective MPS source witness" in text, text)
        assertFailsWith<IllegalArgumentException> {
            loaded.render(Sample(BooleanArray(0), LongArray(0), doubleArrayOf(0.0), listOf(BigFraction.ZERO)))
        }
    }

    @Test
    fun `MPS loading should apply shared absolute relative and zero tolerance overrides`() {
        val source = File.createTempFile("mps-shared-tolerance", ".mps").apply {
            writeText("ROWS\n N COST\n E R\nCOLUMNS\n X COST 1 R 1\nRHS\n RHS R 100\nENDATA\n")
            deleteOnExit()
        }
        val policies = listOf(
            SourceTolerance(absolute = SemanticRational.ONE) to true,
            SourceTolerance(relative = SemanticRational.of(BIG_ONE, bigIntOf(100))) to true,
            SourceTolerance() to false,
        )

        for ((policy, accepted) in policies) {
            val common = CommonOptions().apply { semanticOverrides = SemanticOverrides(feasibilityTolerance = policy) }
            val loaded = MpsMode.newSession().load(source.absolutePath, common)
            val original = assertIs<Problem>(loaded.finiteShape.preparedPlan.originalSource)
            val point = Sample(BooleanArray(0), LongArray(0), doubleArrayOf(99.5))

            assertEquals(policy, original.semantics.effective.feasibilityTolerance)
            assertEquals(accepted, assertNotNull(loaded.toleranceCheck)(point))
        }
    }

    @Test
    fun `MPS caller tolerance and objective publication should retain original source units`() {
        val source = File.createTempFile("mps-shared-source-units", ".mps").apply {
            writeText(
                "ROWS\n N COST\n G R\nCOLUMNS\n X COST 0.1 R 0.1\n" +
                    "RHS\n RHS R 0.3 COST -0.05\nENDATA\n",
            )
            deleteOnExit()
        }
        val common = CommonOptions().apply {
            semanticOverrides = SemanticOverrides(
                feasibilityTolerance = SourceTolerance(absolute = SemanticRational.of(BIG_ONE, bigIntOf(10))),
            )
        }
        val session = MpsMode.newSession()
        val loaded = session.load(source.absolutePath, common)
        val point = Sample(BooleanArray(0), LongArray(0), doubleArrayOf(2.5))
        val check = assertNotNull(loaded.toleranceCheck)
        val output = session.output(common)

        val text = capture {
            output.onSolution(loaded.render(point), null, assertNotNull(loaded.continuousObjectiveValue)(point))
            output.onComplete(Verdict.BEST_FOUND)
        }

        assertTrue(check(point))
        assertFalse(check(Sample(BooleanArray(0), LongArray(0), doubleArrayOf(1.5))))
        assertTrue("o 0.3" in text.lines(), text)
        assertTrue("s SATISFIABLE" in text.lines(), text)
        assertTrue("MPS source tolerance witness" in text, text)
    }

    @Test
    fun `MPS exact loading should replace the shared tolerance without mutating caller overrides`() {
        val source = File.createTempFile("mps-shared-exact", ".mps").apply {
            writeText("ROWS\n N COST\nCOLUMNS\n X COST 1\nENDATA\n")
            deleteOnExit()
        }
        val policy = SourceTolerance(absolute = SemanticRational.ONE)
        val common = CommonOptions().apply {
            exact = true
            semanticOverrides = SemanticOverrides(feasibilityTolerance = policy)
        }

        val loaded = MpsMode.newSession().load(source.absolutePath, common)
        val original = assertIs<Problem>(loaded.finiteShape.preparedPlan.originalSource)

        assertEquals(SourceTolerance(), original.semantics.effective.feasibilityTolerance)
        assertEquals(policy, common.semanticOverrides.feasibilityTolerance)
        assertNull(loaded.toleranceCheck)
    }

    @Test
    fun `MPS exact loading should reject inapplicable shared overrides`() {
        val source = File.createTempFile("mps-shared-inapplicable", ".mps").apply {
            writeText("ROWS\n N COST\nCOLUMNS\n X COST 1\nENDATA\n")
            deleteOnExit()
        }
        val common = CommonOptions().apply {
            exact = true
            semanticOverrides = SemanticOverrides(floats = FloatSemantics.Continuous)
        }

        assertFailsWith<IllegalArgumentException> { MpsMode.newSession().load(source.absolutePath, common) }
    }

    @Test
    fun `loaded MPS tolerance checks should retain the captured caller policy`() {
        val source = File.createTempFile("mps-shared-capture", ".mps").apply {
            writeText("ROWS\n N COST\n E R\nCOLUMNS\n X COST 1 R 1\nRHS\n RHS R 1\nENDATA\n")
            deleteOnExit()
        }
        val common = CommonOptions().apply {
            semanticOverrides = SemanticOverrides(
                feasibilityTolerance = SourceTolerance(absolute = SemanticRational.ONE),
            )
        }
        val loaded = MpsMode.newSession().load(source.absolutePath, common)
        common.semanticOverrides = SemanticOverrides(feasibilityTolerance = SourceTolerance())

        val accepted = assertNotNull(loaded.toleranceCheck)(Sample(BooleanArray(0), LongArray(0), doubleArrayOf(0.5)))

        assertTrue(accepted)
    }

    @Test
    fun `numerical MPS publication should apply the captured shared tolerance`() {
        val source = File.createTempFile("mps-shared-open-tolerance", ".mps").apply {
            writeText(
                "ROWS\n N COST\n G R\nCOLUMNS\n M0 'MARKER' 'INTORG'\n I R -1\n" +
                    " M1 'MARKER' 'INTEND'\n X COST 1 R 1\nBOUNDS\n LI B I 3\nENDATA\n",
            )
            deleteOnExit()
        }
        val policies = listOf(SourceTolerance(absolute = SemanticRational.ONE) to true, SourceTolerance() to false)
        val value = BigFraction.ofLong(5L) * BigFraction.ofLong(2L).reciprocal()
        for ((policy, accepted) in policies) {
            val common = CommonOptions().apply { semanticOverrides = SemanticOverrides(feasibilityTolerance = policy) }
            val session = MpsMode.newSession()
            val loaded = session.load(source.absolutePath, common)
            val pipeline = assertIs<SolvablePipeline.NumericalLinear>(loaded.pipeline)
            val assignment = OpenTheoryAssignment.Sampled(
                Sample(BooleanArray(0), longArrayOf(3L), doubleArrayOf(2.5), listOf(value)),
            )
            val rendered = pipeline.render(assignment)
            val output = session.output(common)

            val text = capture {
                ArithmeticPublication(output).publish(rendered, SourceValue.Rational(value))
                output.onComplete(Verdict.BEST_FOUND)
            }

            assertEquals(accepted, "s SATISFIABLE" in text.lines(), text)
            assertEquals(accepted, text.lines().any { it.startsWith("v ") }, text)
            if (accepted) {
                assertTrue("o 2.5" in text.lines(), text)
                assertTrue("MPS source tolerance witness" in text, text)
            } else {
                assertTrue("s UNKNOWN" in text.lines(), text)
            }
            assertFalse("s OPTIMUM FOUND" in text.lines(), text)
        }
    }

    @Test
    fun `finite MPS execution should retain original open declarations after bound closure`() {
        val source = File.createTempFile("mps-original-declarations", ".mps").apply {
            writeText(
                "ROWS\n N COST\n L R\nCOLUMNS\n M0 'MARKER' 'INTORG'\n" +
                    " I COST 0\n X COST 1 R 1\n M1 'MARKER' 'INTEND'\nRHS\n RHS R 2\n" +
                    "BOUNDS\n FX B I 1\n LI B X 0\nINDICATORS\n IF R I 1\nENDATA\n",
            )
            deleteOnExit()
        }
        for (exact in listOf(false, true)) {
            val loaded = MpsMode.newSession().load(source.absolutePath, CommonOptions().apply { this.exact = exact })

            val original = assertIs<Problem>(loaded.finiteShape.preparedPlan.originalSource)
            assertFalse(original.intBounds.hasUpper(1))
            assertEquals(2L, loaded.finiteProblem.intBounds.upper(1))
        }
    }

    @Test
    fun `finite MPS maximization should retain its original objective beside the minimized execution goal`() {
        val source = File.createTempFile("mps-original-objective", ".mps").apply {
            writeText(
                "OBJSENSE\n MAX\nROWS\n N COST\nCOLUMNS\n M0 'MARKER' 'INTORG'\n" +
                    " I COST 1\n M1 'MARKER' 'INTEND'\nBOUNDS\n LI B I 0\n UI B I 2\nENDATA\n",
            )
            deleteOnExit()
        }
        for (exact in listOf(false, true)) {
            val loaded = MpsMode.newSession().load(source.absolutePath, CommonOptions().apply { this.exact = exact })

            assertTrue(loaded.finiteShape.preparedPlan.maximize)
            assertEquals(1L, assertNotNull(loaded.finiteShape.preparedPlan.objective).intCoefficients[0])
            assertEquals(-1L, assertNotNull(loaded.linearObjective).intCoefficients[0])
        }
    }

    @Test
    fun `open MPS indicator regions should retain numerical optima under either certification policy`() {
        val source = File.createTempFile("mps-open-indicator", ".mps").apply {
            writeText(
                "ROWS\n N COST\n G R\nCOLUMNS\n M0 'MARKER' 'INTORG'\n" +
                    " I COST 0\n M1 'MARKER' 'INTEND'\n X COST 1 R 1\nRHS\n RHS R 2\n" +
                    "BOUNDS\n LI B I 0\n LO B X 0\nINDICATORS\n IF R I 0\nENDATA\n",
            )
            deleteOnExit()
        }
        for (flags in listOf(emptyArray(), arrayOf("--exact"))) {
            val text = capture {
                assertEquals(0, runCli(arrayOf("-s", "-p", "1", *flags, source.absolutePath)))
            }

            assertTrue((if (flags.isNotEmpty()) "s OPTIMUM FOUND" else "s SATISFIABLE") in text.lines(), text)
            if (flags.isEmpty()) assertTrue("nominalSourceOptimizationStatus=" in text, text)
            assertTrue("o 0" in text.lines(), text)
            assertTrue("solvePipeline=numerical-mip" in text, text)
            assertFalse("declaredTheoryRoute=" in text, text)
        }
    }

    @Test
    fun `nonzero underflow should decline numerical MPS under either certification policy`() {
        val source = File.createTempFile("mps-underflow-numerical", ".mps").apply {
            writeText(
                "ROWS\n N COST\n G R\nCOLUMNS\n X COST 1 R 1e-400\n" +
                    "RHS\n RHS R 0.1\nBOUNDS\n UP B X 1\nENDATA\n",
            )
            deleteOnExit()
        }
        for (flags in listOf(emptyArray(), arrayOf("--exact"))) {
            val text = capture {
                assertEquals(0, runCli(arrayOf("-s", "-p", "1", *flags, source.absolutePath)))
            }

            assertTrue("s UNKNOWN" in text.lines(), text)
            assertTrue("solvePipeline=numerical-mip" in text, text)
            assertFalse("declaredTheoryRoute=" in text, text)
            assertFalse(text.lines().any { it.startsWith("v ") }, text)
        }
    }

    @Test
    fun `an infeasible constant MPS row should retain numerical refutation under either policy`() {
        val source = File.createTempFile("mps-constant-infeasible", ".mps").apply {
            writeText(
                "ROWS\n N COST\n G R\nCOLUMNS\n X COST 0\nRHS\n RHS R 1\n" +
                    "BOUNDS\n FR B X\nENDATA\n",
            )
            deleteOnExit()
        }
        for (flags in listOf(emptyArray(), arrayOf("--exact"))) {
            val text = capture {
                assertEquals(0, runCli(arrayOf("-e", "cp", "-p", "1", *flags, source.absolutePath)))
            }

            assertTrue("s UNSATISFIABLE" in text.lines(), text)
            assertFalse(text.lines().any { it.startsWith("v ") }, text)
        }
    }

    @Test
    fun `fixed MPS indicators should retain numerical bound closure under either certification policy`() {
        val source = File.createTempFile("mps-fixed-indicator", ".mps").apply {
            writeText(
                "ROWS\n N COST\n L R\nCOLUMNS\n M0 'MARKER' 'INTORG'\n" +
                    " I COST 0\n X COST 1 R 1\n M1 'MARKER' 'INTEND'\nRHS\n RHS R 2\n" +
                    "BOUNDS\n FX B I 1\n LI B X 0\nINDICATORS\n IF R I 1\nENDATA\n",
            )
            deleteOnExit()
        }
        for (exact in listOf(false, true)) {
            val loaded = MpsMode.newSession().load(source.absolutePath, CommonOptions().apply { this.exact = exact })
            val flags = if (exact) arrayOf("--exact") else emptyArray()
            val text = capture {
                assertEquals(0, runCli(arrayOf("-e", "cp", "-p", "1", *flags, source.absolutePath)))
            }

            assertIs<SolvablePipeline.FiniteCp>(loaded.pipeline)
            assertTrue("s OPTIMUM FOUND" in text.lines(), text)
            assertTrue("o 0" in text.lines(), text)
        }
    }

    @Test
    fun `continuous MPS heuristic workers should retain numerical completion under either policy`() {
        val source = File.createTempFile("mps-finite-numerical", ".mps").apply {
            writeText("ROWS\n N COST\nCOLUMNS\n X COST 1\nBOUNDS\n UP B X 1\nENDATA\n")
            deleteOnExit()
        }
        for ((engine, flags) in listOf("ls" to emptyArray(), "alns" to arrayOf("--exact"))) {
            val loaded = MpsMode.newSession().load(
                source.absolutePath,
                CommonOptions().apply { exact = flags.isNotEmpty() },
            )
            val text = capture {
                assertEquals(
                    0,
                    runCli(
                        arrayOf(
                            "-s", "-e", engine, "-p", "1", "--param",
                            "node-limit=100", *flags, source.absolutePath,
                        ),
                    ),
                )
            }

            assertTrue(loaded.numericalCompletion)
            assertTrue("s SATISFIABLE" in text.lines(), text)
            assertEquals(0.0, text.lines().single { it.startsWith("v ") }.substringAfter("X=").toDouble())
            assertFalse("exact-neighborhood" in text, text)
            assertFalse("declaredTheoryRoute=" in text, text)
        }
    }

    @Test
    fun `open MPS optimization should execute numerical relaxations under default and exact policy`() {
        val source = File.createTempFile("mps-open-numerical", ".mps").apply {
            writeText(
                "ROWS\n N COST\n G R\nCOLUMNS\n M0 'MARKER' 'INTORG'\n" +
                    " I COST 1 R 1\n M1 'MARKER' 'INTEND'\n X R 1\n" +
                    "BOUNDS\n LI B I 0\n FR B X\nENDATA\n",
            )
            deleteOnExit()
        }
        for (flags in listOf(emptyArray(), arrayOf("--exact"))) {
            val text = capture {
                assertEquals(0, runCli(arrayOf("-s", "-e", "cp", "-p", "1", *flags, source.absolutePath)))
            }

            assertTrue("s OPTIMUM FOUND" in text.lines(), text)
            assertTrue("o 0" in text.lines(), text)
            assertTrue("v I=0 X=0" in text.lines(), text)
            assertTrue("solvePipeline=numerical-mip" in text, text)
            assertTrue("reportedBackend=numerical-mip" in text, text)
            assertFalse("declaredTheoryRoute=" in text, text)
        }
    }

    @Test
    fun `exact continuous decimals should retain numerical execution and source certificates`() {
        val source = File.createTempFile("mps-exact-continuous", ".mps").apply {
            writeText(
                "ROWS\n N COST\n E R\nCOLUMNS\n X COST 0.1 R 3\nRHS\n RHS R 1\n" +
                    "BOUNDS\n LO B X 0.1\n UP B X 1\nENDATA\n",
            )
            deleteOnExit()
        }
        assertFalse(Mps.parse(source.readText()).toProblem().sourceExact)
        val loaded = MpsMode.newSession().load(source.absolutePath, CommonOptions().apply { exact = true })

        val text = capture { assertEquals(0, runCli(arrayOf("-e", "cp", "-p", "1", "--exact", source.absolutePath))) }

        assertIs<SolvablePipeline.FiniteCp>(loaded.pipeline)
        assertNull(loaded.toleranceCheck)
        assertTrue("s OPTIMUM FOUND" in text.lines(), text)
        assertTrue("o 1/30" in text.lines(), text)
        assertTrue("exact original MPS source witness" in text, text)
    }

    @Test
    fun `default bounded decimal optimization should separate exact feasibility from tolerance optimality`() {
        val source = File.createTempFile("mps-finite-tolerance", ".mps").apply {
            writeText(
                "ROWS\n N COST\n G R\nCOLUMNS\n M0 'MARKER' 'INTORG'\n I R -0.1\n" +
                    " M1 'MARKER' 'INTEND'\n X COST 0.1 R 1\nRHS\n RHS R 0.2\n" +
                    "BOUNDS\n LI B I 0\n UI B I 1\n LO B X 0.1\nENDATA\n",
            )
            deleteOnExit()
        }

        val text = capture { assertEquals(0, runCli(arrayOf("-e", "cp", "-p", "1", source.absolutePath))) }

        assertTrue("s SATISFIABLE" in text.lines(), text)
        assertTrue("nominalSourceOptimizationStatus=tolerance-optimum" in text, text)
        assertTrue("exact original MPS source witness" in text, text)
    }

    @Test
    fun `numerical CP and LS completion should retain the configured MPS source guarantee`() {
        val source = File.createTempFile("mps-completion-source-guarantee", ".mps").apply {
            writeText(
                "ROWS\n N COST\n G R\nCOLUMNS\n X COST 1 R 3\nRHS\n RHS R 1\n" +
                    "BOUNDS\n UP B X 1\nENDATA\n",
            )
            deleteOnExit()
        }
        for (exact in listOf(false, true)) {
            for (engine in listOf("cp", "ls")) {
                val flags = if (exact) arrayOf("--exact") else emptyArray()
                val text = capture {
                    assertEquals(
                        0,
                        runCli(
                            arrayOf(
                                "-s", "-e", engine, "-p", "1", "--param",
                                "node-limit=100", *flags, source.absolutePath,
                            ),
                        ),
                    )
                }

                assertTrue(
                    if (engine == "cp" && exact) {
                        "s OPTIMUM FOUND" in text.lines()
                    } else {
                        "s SATISFIABLE" in text.lines()
                    },
                    "exact=$exact engine=$engine\n$text",
                )
                assertTrue(
                    if (exact) {
                        "exact original MPS source witness" in text
                    } else {
                        "MPS source tolerance witness" in text
                    },
                    "exact=$exact engine=$engine\n$text",
                )
                if (exact) assertTrue("v X=1/3" in text.lines(), text)
                if (engine == "cp" && !exact) {
                    assertTrue("nominalSourceOptimizationStatus=" in text, text)
                }
                if (engine == "ls") assertFalse("optimizationStatus=" in text, text)
                assertFalse("exact-neighborhood" in text, text)
                assertFalse("declaredTheoryRoute=" in text, text)
            }
        }
    }

    @Test
    fun `bounded mixed decimal loading should retain finite execution under default and exact settings`() {
        val source = File.createTempFile("mps-finite-decimal", ".mps").apply {
            writeText(
                "ROWS\n N COST\n G R\nCOLUMNS\n M0 'MARKER' 'INTORG'\n I R -0.1\n" +
                    " M1 'MARKER' 'INTEND'\n X COST 0.1 R 1\nRHS\n RHS R 0.2\n" +
                    "BOUNDS\n LI B I 0\n UI B I 1\n LO B X 0.1\nENDATA\n",
            )
            deleteOnExit()
        }

        for (exact in listOf(false, true)) {
            val solvable = MpsMode.newSession().load(source.absolutePath, CommonOptions().apply { this.exact = exact })

            assertIs<SolvablePipeline.FiniteCp>(solvable.pipeline)
            assertEquals(1, assertNotNull(solvable.problem).numIntVars)
            assertEquals(!exact, solvable.toleranceCheck != null)
        }
    }

    @Test
    fun `joint decimal indicators should refute their enabled source`() {
        val source = File.createTempFile("mps-joint-indicators", ".mps").apply {
            writeText(
                "ROWS\n N COST\n G A\n L B\nCOLUMNS\n M0 'MARKER' 'INTORG'\n I COST 0\n" +
                    " M1 'MARKER' 'INTEND'\n X A 1 B 1\nRHS\n RHS A 0.2 B 0.1\n" +
                    "BOUNDS\n FX BN I 1\n LO BN X 0\n UP BN X 1\n" +
                    "INDICATORS\n IF A I 1\n IF B I 1\nENDATA\n",
            )
            deleteOnExit()
        }

        for (flags in listOf(emptyArray(), arrayOf("--exact"))) {
            val text = capture { assertEquals(0, runCli(arrayOf("-e", "cp", "-p", "1") + flags + source.absolutePath)) }

            assertTrue("s UNSATISFIABLE" in text.lines(), text)
        }
    }

    @Test
    fun `float rendering should check zero reconstruction against source rows`() {
        val compiled = Mps.parse(
            "ROWS\n N COST\n E R\nCOLUMNS\n M0 'MARKER' 'INTORG'\n I COST 1 R -1\n" +
                " M1 'MARKER' 'INTEND'\n X R 1\nRHS\n RHS R 0\nBOUNDS\n BV B I\n LO B X 0\nENDATA",
        ).toExactSourceProblem()
        for (residual in listOf(-5e-14, 5e-14)) {
            val sample = Sample(BooleanArray(0), longArrayOf(0L), doubleArrayOf(residual))

            val rendered = renderMpsFloat(compiled, sample)

            assertEquals("v I=0 X=0", rendered)
        }
    }

    @Test
    fun `zero reconstruction should preserve a nonzero source equality`() {
        val compiled = Mps.parse(
            "ROWS\n N COST\n E R\nCOLUMNS\n X COST 0 R 1\nRHS\n RHS R 0.00000000000001\n" +
                "BOUNDS\n LO B X 0\nENDATA",
        ).toExactSourceProblem()
        val sample = Sample(BooleanArray(0), LongArray(0), doubleArrayOf(1e-14))

        val rendered = renderMpsFloat(compiled, sample)

        assertEquals("v X=${1e-14}", rendered)
    }

    @Test
    fun `zero reconstruction should preserve the source objective at the float point`() {
        val compiled = Mps.parse("ROWS\n N COST\nCOLUMNS\n X COST 1\nBOUNDS\n LO B X 0\nENDATA")
            .toExactSourceProblem()
        val sample = Sample(BooleanArray(0), LongArray(0), doubleArrayOf(-5e-14))

        val rendered = renderMpsFloat(compiled, sample)

        assertEquals("v X=${-5e-14}", rendered)
    }

    @Test
    fun `source inexact models should retain their original float witness`() {
        val compiled = Mps.parse(
            "ROWS\n N COST\n E R\nCOLUMNS\n X COST 1 R 3.0000000000000001\nRHS\n RHS R 1\nENDATA",
        ).toProblem()
        val sample = Sample(BooleanArray(0), LongArray(0), doubleArrayOf(-5e-14))
        assertFalse(compiled.sourceExact)

        val rendered = renderMpsFloat(compiled, sample)

        assertEquals("v X=${-5e-14}", rendered)
    }

    @Test
    fun `MPS publication should use the checked current source value before duplicate handling`() {
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
    fun `accepted MPS objectives should retain original units and decimal formatting`() {
        val output = MpsOutput(objectiveScale = 3L, sourceObjective = { BigFraction.ofLong(99) })

        val text = capture {
            output.onArithmeticIncumbent(
                "v X=1/2",
                SourceValue.Rational(
                    BigFraction.ONE *
                        BigFraction.ofLong(2).reciprocal(),
                ),
            )
        }

        assertEquals("o 0.5\nv X=1/2\n", text)
    }

    @Test
    fun `exact open objectives should undo the MPS scale without rounding`() {
        for (sign in listOf(-1L, 1L)) {
            val output = MpsOutput(objectiveScale = 3L)

            val text = capture { output.onExactSolution("v X=$sign", BigFraction.ofLong(sign)) }

            assertTrue("o ${if (sign < 0) "-" else ""}1/3" in text, text)
        }
    }

    @Test
    fun `retained integer objectives should undo arbitrary positive multipliers`() {
        for ((scale, expected) in listOf(2L to "0.5", 3L to "1/3", 5L to "0.2", 100L to "0.01")) {
            val output = MpsOutput(objectiveScale = scale)

            val text = capture { output.onSolution("v I=1", objective = 1L) }

            assertTrue("o $expected" in text.lines(), text)
        }
    }

    @Test
    fun `MPS root statistics should retain their source objective scale`() {
        for (scale in listOf(1L, 3L, 100L)) {
            val output = MpsOutput(objectiveScale = scale)
            val stats = SolveStats(
                run = RunStats(backend = "backtrack"),
                lp = LpStats(solves = SumResult(1.0), rootBound = -1.23456789),
            )

            val text = capture { output.onStatistics(stats, 1L, 0L) }

            assertTrue("c lpRootBound=-1.23456789\n" in text, text)
            assertTrue("c lpRootBoundScale=$scale\n" in text, text)
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
    fun `pure continuous objectives should attain fractional optima in either direction`() {
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
    fun `open continuous objectives should retain source sense in either direction`() {
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

            val pipeline = solvable.pipeline as SolvablePipeline.NumericalLinear
            assertNotNull(pipeline.request.objective)
            assertEquals(sense == "MAX", pipeline.request.maximize)
        }
    }

    @Test
    fun `open continuous incumbents should report the source objective without an optimum claim`() {
        val mps = File.createTempFile("mpsopenincumbent", ".mps").apply {
            writeText(
                "NAME T\nROWS\n N COST\n G R\nCOLUMNS\n" +
                    " M0 'MARKER' 'INTORG'\n I R -1\n M1 'MARKER' 'INTEND'\n" +
                    " X COST 0.5 R 1\nBOUNDS\n LI B I 3\n LO B X 3\nENDATA\n",
            )
            deleteOnExit()
        }

        val text = capture {
            assertEquals(
                0,
                runCli(
                    arrayOf(
                        "-e",
                        "ls",
                        "--param",
                        "node-limit=100",
                        "--param",
                        "max-flips=1",
                        mps.absolutePath,
                    ),
                ),
            )
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
    fun `pure continuous decimal lowerings should admit only source tolerance witnesses`() {
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
    fun `exact open decimal sources should admit complete search`() {
        val mps = File.createTempFile("mpsopenrounded", ".mps").apply {
            writeText(
                "NAME T\nROWS\n N COST\n G R\nCOLUMNS\n M0 'MARKER' 'INTORG'\n" +
                    " I COST 1 R -1\n M1 'MARKER' 'INTEND'\n X R 1\n" +
                    "BOUNDS\n LI B I 0\n LO B X 0.1\nENDATA\n",
            )
            deleteOnExit()
        }

        val solvable = MpsMode.newSession().load(mps.absolutePath, CommonOptions().apply { exact = true })

        assertTrue(solvable.pipeline is SolvablePipeline.NumericalLinear)
        assertNull(solvable.toleranceCheck)
    }

    @Test
    fun `default open decimal incumbents should report their original objective`() {
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
        val pipeline = solvable.pipeline as SolvablePipeline.NumericalLinear
        val assignment = OpenTheoryAssignment.Sampled(
            Sample(BooleanArray(0), longArrayOf(0L), doubleArrayOf(1.0), listOf(BigFraction.ONE)),
        )
        val rendered = pipeline.render(assignment)
        val output = session.output(CommonOptions())

        val text = capture {
            ArithmeticPublication(output).publish(rendered, SourceValue.Rational(BigFraction.ONE))
            output.onComplete(Verdict.BEST_FOUND)
        }

        assertTrue("o 1.0000000000000000001" in text.lines(), text)
        assertTrue("s SATISFIABLE" in text.lines(), text)
        assertFalse("s OPTIMUM FOUND" in text.lines(), text)
        assertEquals(1, text.lines().count { it.startsWith("v ") })
    }

    @Test
    fun `default open decimal witnesses violating the source should be withheld`() {
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
        val pipeline = solvable.pipeline as SolvablePipeline.NumericalLinear
        val assignment = OpenTheoryAssignment.Sampled(
            Sample(BooleanArray(0), longArrayOf(1L), doubleArrayOf(0.0), listOf(BigFraction.ZERO)),
        )
        val rendered = pipeline.render(assignment)
        val output = session.output(CommonOptions())

        val text = capture {
            ArithmeticPublication(output).publish(rendered, SourceValue.Rational(BigFraction.ONE))
            output.onComplete(Verdict.BEST_FOUND)
        }

        assertTrue("s UNKNOWN" in text.lines(), text)
        assertFalse(text.lines().any { it.startsWith("v ") || it.startsWith("o ") }, text)
    }

    @Test
    fun `a source objective with no finite decimal should print as a fraction`() {
        val third = BigFraction.ofLong(3L).reciprocal()
        val output = MpsOutput(sourceObjective = { third })

        val text = capture { output.onSolution("v X=1/3", objective = 0L, continuousObjective = 1.0 / 3.0) }

        assertTrue("o 1/3" in text, text)
    }

    @Test
    fun `a source objective with a finite decimal should stay decimal`() {
        val half = BigFraction.ofLong(2L).reciprocal()
        val output = MpsOutput(sourceObjective = { half })

        val text = capture { output.onSolution("v X=0.5", objective = 0L, continuousObjective = 0.5) }

        assertTrue("o 0.5" in text, text)
    }

    @Test
    fun `a source difference should qualify the lowered optimum`() {
        val output = MpsOutput(sourceExact = false, sourceDifference = "objective coefficient 'X'")

        val text = capture {
            output.onSolution("v X=1", objective = 1L)
            output.onComplete(Verdict.OPTIMAL)
        }

        assertTrue("s SATISFIABLE" in text, text)
        assertTrue("lowered model differs from MPS source at objective coefficient 'X'" in text, text)
    }

    @Test
    fun `a feasible verdict without a verified source witness should be unknown`() {
        val output = MpsOutput(sourceExact = false, sourceDifference = "row 'R' coefficient")

        val text = capture { output.onComplete(Verdict.OPTIMAL) }

        assertTrue("s UNKNOWN" in text, text)
        assertTrue("s SATISFIABLE" !in text, text)
    }

    @Test
    fun `a source difference should decline negative terminal claims`() {
        listOf(Verdict.UNSATISFIABLE, Verdict.UNBOUNDED).forEach { verdict ->
            val output = MpsOutput(sourceExact = false, sourceDifference = "row 'R' coefficient")

            val text = capture { output.onComplete(verdict) }

            assertTrue("s UNKNOWN" in text, text)
        }
    }

    @Test
    fun `a source within tolerance should still decline negative terminal claims`() {
        listOf(Verdict.UNSATISFIABLE, Verdict.UNBOUNDED).forEach { verdict ->
            val output = MpsOutput(sourceExact = true, proofExact = false)

            val text = capture { output.onComplete(verdict) }

            assertTrue("s UNKNOWN" in text, text)
        }
    }

    @Test
    fun `complete search should retain nominal decimal objective cutoff evidence`() {
        val mps = File.createTempFile("mpssourceobjective", ".mps").apply {
            writeText(
                "ROWS\n N COST\n G R\nCOLUMNS\n M0 'MARKER' 'INTORG'\n I COST 1 R -1\n" +
                    " M1 'MARKER' 'INTEND'\n X COST 1.0000000000000000001 R 1\n" +
                    "BOUNDS\n LI B I 0\n LO B X 0.1\nENDATA\n",
            )
            deleteOnExit()
        }
        val text = capture {
            assertEquals(0, runCli(arrayOf("-e", "cp", "-p", "1", "--param", "node-limit=10000", mps.absolutePath)))
        }

        assertTrue("s SATISFIABLE" in text.lines(), text)
        assertTrue("nominalSourceOptimizationStatus=" in text, text)
        assertTrue("o 0.10000000000000000001" in text.lines(), text)
        assertTrue("v I=0 X=1/10" in text.lines(), text)
    }

    @Test
    fun `decimal cancellation should supersede a rounded refutation`() {
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
    fun `exact source refutations should not follow rounded decimal feasibility`() {
        val mps = File.createTempFile("mpssourceconflict", ".mps").apply {
            writeText(
                "ROWS\n E R\nCOLUMNS\n M0 'MARKER' 'INTORG'\n I R 0\n" +
                    " M1 'MARKER' 'INTEND'\n X R 0.1\n X R 0.2\n X R -0.3\n" +
                    "RHS\n RHS R 0.00000000000000000001\nBOUNDS\n FR B I\n FR B X\nENDATA\n",
            )
            deleteOnExit()
        }

        for (exact in listOf(false, true)) {
            val flags = if (exact) arrayOf("--exact") else emptyArray()
            val text = capture {
                assertEquals(0, runCli(arrayOf("-e", "cp", "-p", "1", *flags, mps.absolutePath)))
            }

            assertTrue((if (exact) "s UNSATISFIABLE" else "s UNKNOWN") in text.lines(), text)
            if (!exact) assertTrue("nominal refutation; tolerance-accepted feasibility unproved" in text, text)
            assertFalse(text.lines().any { it.startsWith("v ") }, text)
        }
    }

    @Test
    fun `heuristic engines should retain decimal incumbent strength`() {
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
                assertEquals(
                    0,
                    runCli(
                        arrayOf(
                            "-e", engine, "-p", "1", "--param",
                            "node-limit=10000", "--param", "max-flips=4", mps.absolutePath,
                        ),
                    ),
                )
            }

            assertTrue("s SATISFIABLE" in text.lines(), text)
            assertTrue("o 0.1" in text.lines(), text)
            assertFalse("s OPTIMUM FOUND" in text.lines(), text)
        }
    }

    @Test
    fun `bounded mixed exact decimal sources should retain optimum authority`() {
        val mps = File.createTempFile("mpsfiniteoriginalsource", ".mps").apply {
            writeText(
                "ROWS\n N COST\n G R\nCOLUMNS\n M0 'MARKER' 'INTORG'\n I R -0.1\n" +
                    " M1 'MARKER' 'INTEND'\n X COST 0.1 R 1\nRHS\n RHS R 0.2\n" +
                    "BOUNDS\n LI B I 0\n UI B I 1\n LO B X 0.1\nENDATA\n",
            )
            deleteOnExit()
        }

        val text = capture {
            assertEquals(0, runCli(arrayOf("--exact", "-e", "cp", "-p", "1", mps.absolutePath)))
        }

        assertTrue("s OPTIMUM FOUND" in text.lines(), text)
        assertTrue("o 0.02" in text.lines(), text)
        assertTrue("v I=0 X=0.2" in text.lines(), text)
    }

    @Test
    fun `rejected terminal witnesses should not promote an earlier incumbent`() {
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
    fun `constant source objectives should retain attained optimum semantics`() {
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
    fun `retained MPS outputs should keep their source objective scope`() {
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
        val original = session.load(files[0].absolutePath, CommonOptions()).pipeline as SolvablePipeline.NumericalLinear
        val output = session.output(CommonOptions())
        val assignment = OpenTheoryAssignment.Sampled(
            Sample(BooleanArray(0), longArrayOf(0), doubleArrayOf(1.0), listOf(BigFraction.ONE)),
        )
        val rendered = original.render(assignment)
        val next = session.load(files[1].absolutePath, CommonOptions()).pipeline as SolvablePipeline.NumericalLinear
        next.render(assignment)

        val text = capture {
            output.onExactSolution(rendered, BigFraction.ONE)
            output.onComplete(Verdict.BEST_FOUND)
        }

        assertTrue("o 1.0000000000000000001" in text.lines(), text)
        assertFalse("o 2" in text.lines(), text)
    }

    @Test
    fun `source witness guarantees should distinguish exact and tolerance acceptance`() {
        val mps = File.createTempFile("mpswitnessguarantee", ".mps").apply {
            writeText("OBJSENSE\n MAX\nROWS\n N COST\nCOLUMNS\n X COST 1\nBOUNDS\n UP B X 1\nENDATA\n")
            deleteOnExit()
        }
        for (exact in listOf(false, true)) {
            val common = CommonOptions().apply { this.exact = exact }
            val session = MpsMode.newSession()
            val solvable = session.load(mps.absolutePath, common)
            val sample = Sample(
                BooleanArray(0),
                LongArray(0),
                doubleArrayOf(if (exact) 1.0 else 1.00000001),
                if (exact) listOf(BigFraction.ONE) else null,
            )
            if (!exact) assertTrue(assertNotNull(solvable.toleranceCheck)(sample))
            val output = session.output(common)

            val text = capture {
                output.onSolution(solvable.render(sample), null, sample.approximateRealValue(0))
                output.onComplete(Verdict.OPTIMAL)
            }

            val qualification = if (exact) "exact original MPS source witness" else "MPS source tolerance witness"
            assertTrue(qualification in text, text)
            val status = if (exact) "attained-optimum" else "tolerance-optimum"
            val scope = if (exact) "optimizationStatus" else "nominalSourceOptimizationStatus"
            assertTrue("c $scope=$status" in text.lines(), text)
            assertTrue((if (exact) "s OPTIMUM FOUND" else "s SATISFIABLE") in text.lines(), text)
        }
    }

    @Test
    fun `exact feasibility alone should not establish attained optimality`() {
        for ((proofExact, exactOptimum) in listOf(false to true, true to false)) {
            val output = MpsOutput(
                proofExact = proofExact,
                exactSourceWitness = { true },
                exactSourceOptimum = { exactOptimum },
            )

            val text = capture {
                output.onSolution("v X=1", objective = 1)
                output.onComplete(Verdict.OPTIMAL)
            }

            assertTrue("c optimizationStatus=tolerance-optimum" in text.lines(), text)
            assertTrue("exact original MPS source witness" in text, text)
        }
    }

    @Test
    fun `a tolerance witness should not support source unboundedness`() {
        val output = MpsOutput(exactSourceWitness = { false })

        val text = capture {
            output.onSolution("v X=0", objective = 0)
            output.onComplete(Verdict.UNBOUNDED)
        }

        assertTrue("s SATISFIABLE" in text.lines(), text)
        assertFalse("s UNBOUNDED" in text.lines(), text)
    }
}
