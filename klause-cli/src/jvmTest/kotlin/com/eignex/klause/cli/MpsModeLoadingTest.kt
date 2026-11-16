package com.eignex.klause.cli

import com.eignex.klause.formats.mps.Mps
import com.eignex.klause.formats.mps.checkMpsWitness
import com.eignex.klause.formats.mps.toLinearSourceProblem
import com.eignex.klause.ir.FloatSemantics
import com.eignex.klause.ir.LinearSourceProblem
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.SemanticOverrides
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.solver.pipeline.SourceExecutionKind
import com.eignex.klause.solver.pipeline.SourceSetup
import com.eignex.klause.solver.pipeline.executionKind
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.bigIntOf
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MpsModeLoadingTest {
    @Test
    fun `zero tolerance checking should use exact rational source coordinates when supplied`() {
        val input = File.createTempFile("mps-zero-rational", ".mps").apply {
            writeText("ROWS\n N COST\n E R\nCOLUMNS\n X COST 1 R 1\nRHS\n RHS R 0.1\nENDATA\n")
            deleteOnExit()
        }
        val common = CommonOptions().apply {
            semanticOverrides = SemanticOverrides(feasibilityTolerance = SourceTolerance())
        }
        val loaded = MpsMode.newSession().load(input.absolutePath, common)
        val invocation = assertIs<SourceRendering.LinearSource>(loaded.rendering).invocation
        val source = assertIs<LinearSourceProblem>(invocation.source.originalSource)
        val tenth = BigFraction.ofLong(10).reciprocal()

        assertNotNull(source.checkMpsWitness(listOf(tenth)))
        assertNull(source.checkMpsWitness(listOf(assertNotNull(BigFraction.ofDouble(0.1)))))
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

        val pipeline = assertIs<SourceRendering.LinearSource>(loaded.rendering)
        val source = assertIs<LinearSourceProblem>(pipeline.invocation.source.originalSource)
        val point = assertNotNull(source.checkMpsWitness(listOf(BigFraction.ofLong(2))))

        val text = captureStdout {
            ArithmeticPublication(output).publish(
                pipeline.render(point), SourceValue.Rational(checkNotNull(point.objective)),
            )
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

        val text = captureStdout { session.output(common).onComplete(Verdict.UNSATISFIABLE) }

        val invocation = assertIs<SourceRendering.LinearSource>(loaded.rendering).invocation
        assertEquals(SourceExecutionKind.REFUTED, SourceSetup.executionKind(invocation))
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

        val pipeline = assertIs<SourceRendering.LinearSource>(loaded.rendering)
        val source = assertIs<LinearSourceProblem>(pipeline.invocation.source.originalSource)
        val point = assertNotNull(source.checkMpsWitness(listOf(value)))

        val text = captureStdout {
            ArithmeticPublication(output).publish(
                pipeline.render(point), SourceValue.Rational(checkNotNull(point.objective)),
            )
            output.onComplete(Verdict.SATISFIABLE)
        }

        assertTrue("o 1/3" in text.lines(), text)
        assertTrue("s SATISFIABLE" in text.lines(), text)
        assertTrue("exact effective MPS source witness" in text, text)
        assertNull(source.checkMpsWitness(listOf(BigFraction.ZERO)))
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
            val invocation = assertIs<SourceRendering.LinearSource>(loaded.rendering).invocation
            val original = assertIs<LinearSourceProblem>(invocation.source.originalSource)
            val point = BigFraction.ofLong(199) * BigFraction.ofLong(2).reciprocal()

            assertEquals(policy, original.semantics.effective.feasibilityTolerance)
            assertEquals(accepted, original.checkMpsWitness(listOf(point)) != null)
        }
    }

    @Test
    fun `relative tolerance flag should accept a cancelling row the default refuses`() {
        val source = File.createTempFile("mps-cancelling-row", ".mps").apply {
            writeText("ROWS\n N COST\n E R\nCOLUMNS\n X R 1\n Y R -1\nRHS\n RHS R 0\nENDATA\n")
            deleteOnExit()
        }
        val point = listOf(BigFraction.ofLong(1_000_000_000L), BigFraction.ofLong(999_999_850L))

        for ((args, accepted) in listOf(emptyArray<String>() to false,
            arrayOf("--source-relative-tolerance", "1e-7") to true)) {
            val common = CommonOptions()
            parseArgs(args, commonFlagSpecs(common)) {}
            val loaded = MpsMode.newSession().load(source.absolutePath, common)
            val invocation = assertIs<SourceRendering.LinearSource>(loaded.rendering).invocation
            val original = assertIs<LinearSourceProblem>(invocation.source.originalSource)

            assertEquals(accepted, original.checkMpsWitness(point) != null, args.joinToString(" "))
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
        val pipeline = assertIs<SourceRendering.LinearSource>(loaded.rendering)
        val original = assertIs<LinearSourceProblem>(pipeline.invocation.source.originalSource)
        val value = BigFraction.ofLong(5) * BigFraction.ofLong(2).reciprocal()
        val point = assertNotNull(original.checkMpsWitness(listOf(value)))
        val output = session.output(common)

        val text = captureStdout {
            ArithmeticPublication(output).publish(
                pipeline.render(point), SourceValue.Rational(checkNotNull(point.objective)),
            )
            output.onComplete(Verdict.BEST_FOUND)
        }

        assertNull(original.checkMpsWitness(listOf(BigFraction.ofLong(3) * BigFraction.ofLong(2).reciprocal())))
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
        val invocation = assertIs<SourceRendering.LinearSource>(loaded.rendering).invocation
        val original = assertIs<LinearSourceProblem>(invocation.source.originalSource)

        assertEquals(SourceTolerance(), original.semantics.effective.feasibilityTolerance)
        assertEquals(policy, common.semanticOverrides.feasibilityTolerance)
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

        val invocation = assertIs<SourceRendering.LinearSource>(loaded.rendering).invocation
        val original = assertIs<LinearSourceProblem>(invocation.source.originalSource)

        assertNotNull(original.checkMpsWitness(listOf(BigFraction.ofLong(2).reciprocal())))
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
            val pipeline = assertIs<SourceRendering.LinearSource>(loaded.rendering)
            val original = assertIs<LinearSourceProblem>(pipeline.invocation.source.originalSource)
            val point = original.checkMpsWitness(listOf(BigFraction.ofLong(3), value))
            val output = session.output(common)

            val text = captureStdout {
                point?.let {
                    ArithmeticPublication(output).publish(
                        pipeline.render(it), SourceValue.Rational(checkNotNull(it.objective)),
                    )
                }
                output.onComplete(Verdict.BEST_FOUND)
            }
            assertEquals(accepted, point != null)

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

            val invocation = assertIs<SourceRendering.LinearSource>(loaded.rendering).invocation
            val original = assertIs<LinearSourceProblem>(invocation.source.originalSource)
            assertNull(original.variables[1].upper)
            assertEquals(2L, invocation.finiteShape().problem.intBounds.upper(1))
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

            val invocation = assertIs<SourceRendering.LinearSource>(loaded.rendering).invocation
            val original = assertNotNull(assertIs<LinearSourceProblem>(invocation.source.originalSource).objective)
            assertEquals(ObjectiveSense.MAXIMIZE, original.sense)
            assertEquals(SemanticRational.ONE, original.terms.single().coefficient)
            assertEquals(-1L, assertNotNull(invocation.finiteShape().linearObjective).intCoefficients[0])
        }
    }

    @Test
    fun `integral real definitions should retain original coordinates beside finite integer backing`() {
        for ((realFirst, exact) in listOf(false to false, false to true, true to false, true to true)) {
            val integer = " M0 'MARKER' 'INTORG'\n X DEF -3\n M1 'MARKER' 'INTEND'\n"
            val real = " Z COST 1 DEF 1\n"
            val source = File.createTempFile("mps-integral-real-definition", ".mps").apply {
                writeText(
                    "ROWS\n N COST\n E DEF\nCOLUMNS\n" +
                        (if (realFirst) real + integer else integer + real) +
                        "RHS\n RHS DEF 2\nBOUNDS\n BV B X\n FR B Z\nENDATA\n",
                )
                deleteOnExit()
            }

            val loaded = MpsMode.newSession().load(source.absolutePath, CommonOptions().apply { this.exact = exact })
            val pipeline = assertIs<SourceRendering.LinearSource>(loaded.rendering)
            val original = assertIs<LinearSourceProblem>(pipeline.invocation.source.originalSource)
            val backing = pipeline.invocation.finiteShape().problem
            val point = assertNotNull(original.checkMpsWitness(
                original.variables.map { if (it.integer) BigFraction.ONE else BigFraction.ofLong(5) },
            ))

            assertEquals(listOf(realFirst, !realFirst), original.variables.map { !it.integer })
            assertEquals(2, backing.numIntVars)
            assertEquals(0, backing.numRealVars)
            assertEquals(setOf(0L to 1L, 2L to 5L), (0 until backing.numIntVars).map {
                backing.intBounds.lower(it) to backing.intBounds.upper(it)
            }.toSet())
            assertEquals(BigFraction.ofLong(5), point.objective)
            assertEquals(if (realFirst) "v Z=5 X=1" else "v X=1 Z=5", pipeline.render(point))
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

            val invocation = assertIs<SourceRendering.LinearSource>(solvable.rendering).invocation
            assertEquals(SourceExecutionKind.FINITE, SourceSetup.executionKind(invocation))
            assertEquals(1, invocation.finiteShape().problem.numIntVars)
            assertEquals(exact, checkNotNull(invocation.source.semantics.effective.feasibilityTolerance).isExact)
        }
    }

    @Test
    fun `source-invalid display points should be rejected before rendering`() {
        val source = assertNotNull(Mps.parse(
            "ROWS\n N COST\n E R\nCOLUMNS\n X COST 1 R 3.0000000000000001\nRHS\n RHS R 1\nENDATA",
        ).toLinearSourceProblem())
        val value = assertNotNull(BigFraction.ofDouble(-5e-14))

        val point = source.checkMpsWitness(listOf(value))

        assertEquals(SemanticRational.of(bigIntOf(30000000000000001L), bigIntOf(10000000000000000L)),
            source.rows.single().terms.single().coefficient)
        assertNull(point)
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

            val invocation = assertIs<SourceRendering.LinearSource>(solvable.rendering).invocation
            val original = assertIs<LinearSourceProblem>(invocation.source.originalSource)
            assertEquals(SourceExecutionKind.LINEAR, SourceSetup.executionKind(invocation))
            assertEquals(sense == "MAX", assertNotNull(original.objective).sense == ObjectiveSense.MAXIMIZE)
        }
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
        val invocation = assertIs<SourceRendering.LinearSource>(solvable.rendering).invocation
        val source = assertIs<LinearSourceProblem>(invocation.source.originalSource)

        assertNotNull(source.checkMpsWitness(listOf(assertNotNull(BigFraction.ofDouble(0.1)))))
        assertNull(source.checkMpsWitness(listOf(assertNotNull(BigFraction.ofDouble(0.2)))))
        assertEquals(SourceExecutionKind.FINITE, SourceSetup.executionKind(invocation))
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

        val invocation = assertIs<SourceRendering.LinearSource>(solvable.rendering).invocation
        assertEquals(SourceExecutionKind.LINEAR, SourceSetup.executionKind(invocation))
        assertEquals(SourceTolerance(), invocation.source.semantics.effective.feasibilityTolerance)
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
        val pipeline = assertIs<SourceRendering.LinearSource>(solvable.rendering)
        val source = assertIs<LinearSourceProblem>(pipeline.invocation.source.originalSource)
        val point = assertNotNull(source.checkMpsWitness(listOf(BigFraction.ZERO, BigFraction.ONE)))
        val rendered = pipeline.render(point)
        val output = session.output(CommonOptions())

        val text = captureStdout {
            ArithmeticPublication(output).publish(rendered, SourceValue.Rational(checkNotNull(point.objective)))
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
        val pipeline = assertIs<SourceRendering.LinearSource>(solvable.rendering)
        val source = assertIs<LinearSourceProblem>(pipeline.invocation.source.originalSource)
        val point = source.checkMpsWitness(listOf(BigFraction.ONE, BigFraction.ZERO))
        val output = session.output(CommonOptions())

        val text = captureStdout {
            point?.let {
                ArithmeticPublication(output).publish(
                    pipeline.render(it), SourceValue.Rational(checkNotNull(it.objective)),
                )
            }
            output.onComplete(Verdict.BEST_FOUND)
        }
        assertNull(point)

        assertTrue("s UNKNOWN" in text.lines(), text)
        assertFalse(text.lines().any { it.startsWith("v ") || it.startsWith("o ") }, text)
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
        val original = assertIs<SourceRendering.LinearSource>(
            session.load(files[0].absolutePath, CommonOptions()).rendering,
        )
        val output = session.output(CommonOptions())
        val source = assertIs<LinearSourceProblem>(original.invocation.source.originalSource)
        val point = assertNotNull(source.checkMpsWitness(listOf(BigFraction.ZERO, BigFraction.ONE)))
        val rendered = original.render(point)
        val next = assertIs<SourceRendering.LinearSource>(
            session.load(files[1].absolutePath, CommonOptions()).rendering,
        )
        val later = assertIs<LinearSourceProblem>(next.invocation.source.originalSource)
        next.render(assertNotNull(later.checkMpsWitness(point.values)))

        val text = captureStdout {
            output.onExactSolution(rendered, checkNotNull(point.objective))
            output.onComplete(Verdict.BEST_FOUND)
        }

        assertTrue("o 1.0000000000000000001" in text.lines(), text)
        assertFalse("o 2" in text.lines(), text)
    }
}
