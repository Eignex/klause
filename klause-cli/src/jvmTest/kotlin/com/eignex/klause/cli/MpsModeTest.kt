package com.eignex.klause.cli

import com.eignex.klause.formats.mps.Mps
import com.eignex.klause.formats.mps.toProblem
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.solver.pipeline.SourceExecutionKind
import com.eignex.klause.solver.pipeline.SourceSetup
import com.eignex.klause.solver.pipeline.executionKind
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MpsModeTest {
    @Test
    fun `disabled source templates should preserve prepared MPS search results`() {
        val relative = "klause-bench/smoke-corpus/lp-component-split/cover-2x3.mps"
        val source = listOf(File(relative), File("../$relative")).first { it.isFile }

        val text = captureStdout {
            assertEquals(0, runCli(arrayOf("-e", "cp", "-p", "1", "-s", "--exact",
                "--presolve", "off,+lp-harvest", "--lp", "aggressive,-cuts,preparation=disabled",
                "--param", "lp-component-ownership=true", "--param", "bt-arm=lp-aggressive",
                "--param", "arms=1", "-r", "3", "--param", "max-decisions=1000", source.absolutePath)))
        }

        assertTrue("s OPTIMUM FOUND" in text.lines(), text)
        assertTrue("o 4" in text.lines(), text)
        assertFalse("lpPhase_TEMPLATE_PREPARATION_" in text, text)
        val uses = Regex("lpPhase_TEMPLATE_ASSEMBLY_[A-Z]+_[A-Z_]+=(\\d+)").findAll(text)
            .sumOf { it.groupValues[1].toLong() }
        assertEquals(0L, uses, text)
    }

    @Test
    fun `prepared MPS search should consume templates retained from LP harvest`() {
        val relative = "klause-bench/smoke-corpus/lp-component-split/cover-2x3.mps"
        val source = listOf(File(relative), File("../$relative")).first { it.isFile }

        val text = captureStdout {
            assertEquals(0, runCli(arrayOf("-e", "cp", "-p", "1", "-s", "--exact",
                "--presolve", "off,+lp-harvest", "--lp", "aggressive,-cuts",
                "--param", "lp-component-ownership=true", "--param", "bt-arm=lp-aggressive",
                "--param", "arms=1", "-r", "3", "--param", "max-decisions=1000", source.absolutePath)))
        }

        assertTrue("s OPTIMUM FOUND" in text.lines(), text)
        assertTrue("o 4" in text.lines(), text)
        val reused = Regex("lpPhase_TEMPLATE_PREPARATION_[A-Z]+_FACTORS_REUSED=(\\d+)").findAll(text)
            .sumOf { it.groupValues[1].toLong() }
        val exactBounds = Regex("lpPhase_TEMPLATE_ASSEMBLY_[A-Z]+_EXACT_BOUND_OBJECTS=(\\d+)").findAll(text)
            .sumOf { it.groupValues[1].toLong() }
        val rows = Regex("lpPhase_TEMPLATE_ASSEMBLY_[A-Z]+_ROWS=(\\d+)").findAll(text)
            .sumOf { it.groupValues[1].toLong() }
        val rebound = Regex("lpPhase_TEMPLATE_PREPARATION_[A-Z]+_SOURCE_REBOUND=(\\d+)").findAll(text)
            .sumOf { it.groupValues[1].toLong() }
        val pruned = Regex("lpBoundPruned=(\\d+)").findAll(text).sumOf { it.groupValues[1].toLong() }
        assertTrue(reused > 0L, text)
        assertTrue(exactBounds > 0L, text)
        assertTrue(rows > 0L, text)
        assertTrue(rebound > 0L, text)
        assertTrue(pruned > 0L, text)
    }

    @Test
    fun `prepared separable MPS search should consume compatible child results`() {
        val relative = "klause-bench/smoke-corpus/lp-component-split/cover-2x3.mps"
        val source = listOf(File(relative), File("../$relative")).first { it.isFile }

        val text = captureStdout {
            assertEquals(0, runCli(arrayOf("-e", "cp", "-p", "1", "-s", "--exact", "--presolve", "off",
                "--lp", "aggressive,-cuts", "--param", "lp-component-ownership=true",
                "--param", "bt-arm=lp-aggressive", "--param", "arms=1", "-r", "3",
                "--param", "max-decisions=1000", source.absolutePath)))
        }

        assertTrue("s OPTIMUM FOUND" in text.lines(), text)
        assertTrue("o 4" in text.lines(), text)
        val reuses = Regex("lpPhase_COMPONENT_LIFECYCLE_[A-Z]+_REUSED=(\\d+)").findAll(text)
            .sumOf { it.groupValues[1].toLong() }
        assertTrue(reuses > 0L, text)
    }

    @Test
    fun `a finite decimal optimum should not be reported unbounded under either certification policy`() {
        val source = File.createTempFile("mps-finite-decimal", ".mps").apply {
            writeText("ROWS\n N COST\n L R\nCOLUMNS\n X COST -1 R 0.1\n Y COST 0 R 1\nRHS\n RHS R 0.3\nENDATA\n")
            deleteOnExit()
        }
        for (exact in listOf(false, true)) {
            val flags = if (exact) arrayOf("--exact") else emptyArray()
            val text = captureStdout {
                val arguments = arrayOf("-e", "cp", "-p", "1", "--param", "node-limit=1000", *flags)
                assertEquals(0, runCli(arrayOf(*arguments, source.absolutePath)))
            }

            assertTrue("s OPTIMUM FOUND" in text.lines(), text)
        }
    }

    @Test
    fun `a ray whose decimal slope violates a row should leave a numerical point without unboundedness`() {
        val source = File.createTempFile("mps-rounding-slope", ".mps").apply {
            writeText(
                "ROWS\n N COST\n G R\n E E1\n E E2\nCOLUMNS\n X R 0.1 E1 1\n Y R 0.2 E2 1\n" +
                    " Z COST -1 R -0.30000000000000001 E1 -1 E2 -1\nENDATA\n",
            )
            deleteOnExit()
        }

        val text = captureStdout {
            val arguments = arrayOf("-e", "cp", "-p", "1", "--param", "node-limit=1000", source.absolutePath)
            assertEquals(0, runCli(arguments))
        }

        assertTrue("s SATISFIABLE" in text.lines(), text)
    }

    @Test
    fun `completed native refutations should report under the selected certification policy`() {
        val source = File.createTempFile("mps-tolerated-empty-row", ".mps").apply {
            writeText("ROWS\n N COST\n G R\nCOLUMNS\n X COST 0\nRHS\n RHS R 0.00000005\nENDATA\n")
            deleteOnExit()
        }
        for (exact in listOf(false, true)) {
            val flags = if (exact) arrayOf("--exact") else emptyArray()
            val text = captureStdout {
                assertEquals(0, runCli(arrayOf("-e", "cp", "-p", "1", *flags, source.absolutePath)))
            }

            assertTrue("s UNSATISFIABLE" in text.lines(), text)
            if (!exact) assertTrue("MPS numerical status under configured tolerances" in text, text)
        }
    }

    @Test
    fun `a large objective optimum should be reported optimal under either certification policy`() {
        val source = File.createTempFile("mps-large-objective", ".mps").apply {
            writeText(
                "ROWS\n N COST\n L R\nCOLUMNS\n X COST -123456789.1 R 0.1\n Y COST 0 R 1\n" +
                    " W COST 123456789.3\nRHS\n RHS R 81.1\nBOUNDS\n FX BND W 803\nENDATA\n",
            )
            deleteOnExit()
        }
        for (exact in listOf(false, true)) {
            val flags = if (exact) arrayOf("--exact") else emptyArray()
            val text = captureStdout {
                assertEquals(0, runCli(arrayOf("-e", "cp", "-p", "1", *flags, source.absolutePath)))
            }

            assertTrue("s OPTIMUM FOUND" in text.lines(), text)
        }
    }

    @Test
    fun `configured tolerance should retain a completed numerical optimum`() {
        val source = File.createTempFile("mps-tolerant-objective", ".mps").apply {
            writeText("ROWS\n N COST\nCOLUMNS\n X COST 1\nBOUNDS\n LO B X 1\nENDATA\n")
            deleteOnExit()
        }
        val text = captureStdout {
            assertEquals(0, runCli(arrayOf("-e", "cp", "-p", "1", "--source-absolute-tolerance", "1",
                "--source-relative-tolerance", "0", source.absolutePath)))
        }

        assertTrue("s OPTIMUM FOUND" in text.lines(), text)
        assertTrue("o 1" in text.lines(), text)
        assertEquals(1.0, text.lines().single { it.startsWith("v ") }.substringAfter("X=").toDouble())
        assertTrue("c optimizationStatus=tolerance-optimum" in text.lines(), text)
        assertFalse("tolerance-accepted optimality unproved" in text, text)
    }

    @Test
    fun `a default tolerance finite optimum should be reported as a tolerance optimum`() {
        val text = finiteOptimum()

        assertTrue("c optimizationStatus=tolerance-optimum" in text.lines(), text)
    }

    @Test
    fun `an exact finite optimum should retain an attained optimum certificate`() {
        val text = finiteOptimum("--exact")

        assertTrue("c optimizationStatus=attained-optimum" in text.lines(), text)
    }

    private fun finiteOptimum(vararg flags: String): String {
        val source = File.createTempFile("mps-finite-optimum-certificate", ".mps").apply {
            writeText(
                "OBJSENSE\n MAX\nROWS\n N COST\nCOLUMNS\n M0 'MARKER' 'INTORG'\n" +
                    " I COST 1\n M1 'MARKER' 'INTEND'\nBOUNDS\n LI B I 0\n UI B I 2\nENDATA\n",
            )
            deleteOnExit()
        }
        val arguments = arrayOf("-e", "cp", "-p", "1", "--param", "node-limit=100", *flags, source.absolutePath)
        val text = captureStdout { assertEquals(0, runCli(arguments)) }
        assertTrue("s OPTIMUM FOUND" in text.lines(), text)
        assertTrue("o 2" in text.lines(), text)
        return text
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
            val text = captureStdout {
                assertEquals(0, runCli(arrayOf("-s", "-p", "1", *flags, source.absolutePath)))
            }

            assertTrue("s OPTIMUM FOUND" in text.lines(), text)
            if (flags.isEmpty()) assertTrue("optimizationStatus=tolerance-optimum" in text, text)
            assertTrue("o 0" in text.lines(), text)
            assertTrue("c frontendRoutingOutcome=open" in text.lines(), text)
            assertTrue("c reportedBackend=linear-authority" in text.lines(), text)
            assertFalse("declaredTheoryRoute=" in text, text)
        }
    }

    @Test
    fun `nonzero underflow should decline MPS under either certification policy`() {
        val source = File.createTempFile("mps-underflow-numerical", ".mps").apply {
            writeText(
                "ROWS\n N COST\n G R\nCOLUMNS\n X COST 1 R 1e-400\n" +
                    "RHS\n RHS R 0.1\nBOUNDS\n UP B X 1\nENDATA\n",
            )
            deleteOnExit()
        }
        for (flags in listOf(emptyArray(), arrayOf("--exact"))) {
            val loaded = MpsMode.newSession().load(
                source.absolutePath,
                CommonOptions().apply { exact = flags.isNotEmpty() },
            )
            val text = captureStdout {
                assertEquals(0, runCli(arrayOf("-s", "-p", "1", *flags, source.absolutePath)))
            }

            val invocation = assertIs<SourceRendering.LinearSource>(loaded.rendering).invocation
            assertEquals(SourceExecutionKind.LINEAR, SourceSetup.executionKind(invocation))
            assertTrue("s UNKNOWN" in text.lines(), text)
            assertTrue("c solvePipeline=linear-source" in text.lines(), text)
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
            val text = captureStdout {
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
            val text = captureStdout {
                assertEquals(0, runCli(arrayOf("-e", "cp", "-p", "1", *flags, source.absolutePath)))
            }

            val invocation = assertIs<SourceRendering.LinearSource>(loaded.rendering).invocation
            assertEquals(SourceExecutionKind.FINITE, SourceSetup.executionKind(invocation))
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
            val text = captureStdout {
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

            val invocation = assertIs<SourceRendering.LinearSource>(loaded.rendering).invocation
            assertEquals(SourceExecutionKind.FINITE, SourceSetup.executionKind(invocation))
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
            val text = captureStdout {
                assertEquals(0, runCli(arrayOf("-s", "-e", "cp", "-p", "1", *flags, source.absolutePath)))
            }

            assertTrue("s OPTIMUM FOUND" in text.lines(), text)
            assertTrue("o 0" in text.lines(), text)
            assertTrue("v I=0 X=0" in text.lines(), text)
            assertTrue("c frontendRoutingOutcome=open" in text.lines(), text)
            assertTrue("reportedBackend=linear-authority" in text, text)
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

        val text = captureStdout {
            assertEquals(0, runCli(arrayOf("-e", "cp", "-p", "1", "--exact", source.absolutePath)))
        }

        val invocation = assertIs<SourceRendering.LinearSource>(loaded.rendering).invocation
        assertEquals(SourceExecutionKind.FINITE, SourceSetup.executionKind(invocation))
        assertEquals(SourceTolerance(), invocation.source.semantics.effective.feasibilityTolerance)
        assertTrue("s OPTIMUM FOUND" in text.lines(), text)
        assertTrue("o 1/30" in text.lines(), text)
        assertTrue("exact original MPS source witness" in text, text)
    }

    @Test
    fun `exact one-sided real optimization should retain the rational optimum`() {
        val source = File.createTempFile("mps-exact-one-sided", ".mps").apply {
            writeText("ROWS\n N COST\n G R\nCOLUMNS\n X COST 1 R 10\nRHS\n RHS R 1\nENDATA\n")
            deleteOnExit()
        }

        val text = captureStdout {
            assertEquals(0, runCli(arrayOf("-e", "cp", "-p", "1", "--exact", source.absolutePath)))
        }

        assertTrue("s OPTIMUM FOUND" in text.lines(), text)
        assertTrue("o 0.1" in text.lines(), text)
        assertTrue("v X=0.1" in text.lines(), text)
        assertTrue("c optimizationStatus=attained-optimum" in text.lines(), text)
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

        val text = captureStdout { assertEquals(0, runCli(arrayOf("-e", "cp", "-p", "1", source.absolutePath))) }

        assertTrue("s OPTIMUM FOUND" in text.lines(), text)
        assertTrue("optimizationStatus=tolerance-optimum" in text, text)
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
                val text = captureStdout {
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
                    if (engine == "cp") {
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
                    assertTrue("optimizationStatus=tolerance-optimum" in text, text)
                }
                if (engine == "ls") assertFalse("optimizationStatus=" in text, text)
                assertFalse("exact-neighborhood" in text, text)
                assertFalse("declaredTheoryRoute=" in text, text)
            }
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
            val text = captureStdout {
                assertEquals(0, runCli(arrayOf("-e", "cp", "-p", "1") + flags + source.absolutePath))
            }

            assertTrue("s UNSATISFIABLE" in text.lines(), text)
        }
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

            val text = captureStdout {
                assertEquals(0, runCli(arrayOf("--exact", "-e", "cp", "-p", "1", mps.absolutePath)))
            }

            assertTrue("o $expected" in text.lines(), text)
            assertTrue("v X=$expected" in text.lines(), text)
            assertTrue("s OPTIMUM FOUND" in text.lines(), text)
        }
    }

    @Test
    fun `unfunded numerical local search should decline before publishing a source point`() {
        val mps = File.createTempFile("mpsopenincumbent", ".mps").apply {
            writeText(
                "NAME T\nROWS\n N COST\n G R\nCOLUMNS\n" +
                    " M0 'MARKER' 'INTORG'\n I R -1\n M1 'MARKER' 'INTEND'\n" +
                    " X COST 0.5 R 1\nBOUNDS\n LI B I 3\n LO B X 3\nENDATA\n",
            )
            deleteOnExit()
        }

        val text = captureStdout {
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
                        "--param",
                        "source-lp-work-limit=0",
                        mps.absolutePath,
                    ),
                ),
            )
        }

        assertTrue("s UNKNOWN" in text.lines(), text)
        assertFalse(text.lines().any { it.startsWith("v ") }, text)
        assertFalse(text.lines().any { it.startsWith("o ") }, text)
        assertFalse("s OPTIMUM FOUND" in text.lines(), text)
    }

    @Test
    fun `complete search should report numerical decimal objective optimality`() {
        val mps = File.createTempFile("mpssourceobjective", ".mps").apply {
            writeText(
                "ROWS\n N COST\n G R\nCOLUMNS\n M0 'MARKER' 'INTORG'\n I COST 1 R -1\n" +
                    " M1 'MARKER' 'INTEND'\n X COST 1.0000000000000000001 R 1\n" +
                    "BOUNDS\n LI B I 0\n LO B X 0.1\nENDATA\n",
            )
            deleteOnExit()
        }
        val text = captureStdout {
            assertEquals(0, runCli(arrayOf("-e", "cp", "-p", "1", "--param", "node-limit=10000", mps.absolutePath)))
        }

        assertTrue("s OPTIMUM FOUND" in text.lines(), text)
        assertTrue("optimizationStatus=tolerance-optimum" in text, text)
        assertTrue("o 0.10000000000000000001" in text.lines(), text)
        assertTrue("v I=0 X=0.1" in text.lines(), text)
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

        val text = captureStdout {
            assertEquals(0, runCli(arrayOf("-e", "fixed", "-p", "1", mps.absolutePath)))
        }

        assertTrue("s SATISFIABLE" in text.lines(), text)
        assertTrue("v I=0 X=1" in text.lines(), text)
    }

    @Test
    fun `native decimal refutations should retain their selected certification scope`() {
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
            val text = captureStdout {
                assertEquals(0, runCli(arrayOf("-e", "cp", "-p", "1", *flags, mps.absolutePath)))
            }

            assertTrue("s UNSATISFIABLE" in text.lines(), text)
            if (!exact) assertTrue("MPS numerical status under configured tolerances" in text, text)
            assertFalse(text.lines().any { it.startsWith("v ") }, text)
        }
    }

    @Test
    fun `open numerical heuristics should publish decimal incumbents without optimum claims`() {
        val mps = File.createTempFile("mpssourceheuristic", ".mps").apply {
            writeText(
                "ROWS\n N COST\n E R\nCOLUMNS\n M0 'MARKER' 'INTORG'\n I R 0\n" +
                    " M1 'MARKER' 'INTEND'\n X COST 0.1 R 0.2\nRHS\n RHS R 0.2\n" +
                    "BOUNDS\n FR B I\n FR B X\nENDATA\n",
            )
            deleteOnExit()
        }
        for (engine in listOf("ls", "alns")) {
            val text = captureStdout {
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
            assertTrue(text.lines().any { it.startsWith("v ") }, text)
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

        val text = captureStdout {
            assertEquals(0, runCli(arrayOf("--exact", "-e", "cp", "-p", "1", mps.absolutePath)))
        }

        assertTrue("s OPTIMUM FOUND" in text.lines(), text)
        assertTrue("o 0.02" in text.lines(), text)
        assertTrue("v I=0 X=0.2" in text.lines(), text)
    }

    @Test
    fun `constant source objectives should retain attained optimum semantics`() {
        val mps = File.createTempFile("mpsconstantsource", ".mps").apply {
            writeText("ROWS\n N COST\n E R\nRHS\n RHS R 0 COST 0.3\nENDATA\n")
            deleteOnExit()
        }

        val text = captureStdout {
            assertEquals(0, runCli(arrayOf("-e", "cp", "-p", "1", mps.absolutePath)))
        }

        assertTrue("s OPTIMUM FOUND" in text.lines(), text)
        assertTrue("o -0.3" in text.lines(), text)
    }

    @Test
    fun `an incumbent within the optimality gap should be reported optimal with its proved bound`() {
        for (engine in listOf("cp", "mixed")) {
            val text = triangleCover("-e", engine, "--source-relative-optimality-tolerance", "1/2")

            assertTrue("s OPTIMUM FOUND" in text.lines(), text)
            assertTrue("o 2.1" in text.lines(), text)
            assertTrue("c optimizationStatus=gap-optimum" in text.lines(), text)
            assertTrue("c objectiveBoundDirection=lower" in text.lines(), text)
            assertTrue("c objectiveBound=1.1" in text.lines(), text)
        }
    }

    @Test
    fun `exact MPS loading should close the bound before claiming an optimum`() {
        val text = triangleCover("-e", "cp", "--exact")

        assertTrue("o 2" in text.lines(), text)
        assertTrue("c optimizationStatus=attained-optimum" in text.lines(), text)
        assertFalse("objectiveBound=" in text, text)
    }

    @Test
    fun `an unbounded model should stay unbounded under a loose optimality gap`() {
        val text = looseGap("ROWS\n N COST\n G R\nCOLUMNS\n M0 'MARKER' 'INTORG'\n I COST 1 R 1\n" +
            " M1 'MARKER' 'INTEND'\n X COST -1 R 1\nRHS\n RHS R 1\nBOUNDS\n UP B I 3\nENDATA\n")

        assertTrue("s UNBOUNDED" in text.lines(), text)
    }

    @Test
    fun `an infeasible model should stay infeasible under a loose optimality gap`() {
        val text = looseGap("ROWS\n N COST\n G R\nCOLUMNS\n M0 'MARKER' 'INTORG'\n I COST 1 R 1\n" +
            " M1 'MARKER' 'INTEND'\n X COST 1 R 1\nRHS\n RHS R 5\nBOUNDS\n UP B I 3\n UP B X 1\nENDATA\n")

        assertTrue("s UNSATISFIABLE" in text.lines(), text)
    }

    private fun looseGap(model: String): String {
        val source = File.createTempFile("mps-loose-gap", ".mps").apply {
            writeText(model)
            deleteOnExit()
        }
        return captureStdout {
            assertEquals(0, runCli(arrayOf("-e", "cp", "-p", "1", "--source-relative-optimality-tolerance", "1/2",
                source.absolutePath)))
        }
    }

    // A weighted triangle cover: the first incumbent costs 2.1, the optimum 2.
    private fun triangleCover(vararg flags: String): String {
        val source = File.createTempFile("mps-triangle-cover", ".mps").apply {
            writeText(
                "ROWS\n N COST\n G R1\n G R2\n G R3\nCOLUMNS\n M0 'MARKER' 'INTORG'\n" +
                    " X1 COST 1.1 R1 1\n X1 R3 1\n X2 COST 1 R1 1\n X2 R2 1\n X3 COST 1 R2 1\n X3 R3 1\n" +
                    " M1 'MARKER' 'INTEND'\nRHS\n RHS R1 1 R2 1\n RHS R3 1\n" +
                    "BOUNDS\n UP B X1 1\n UP B X2 1\n UP B X3 1\nENDATA\n",
            )
            deleteOnExit()
        }
        return captureStdout {
            assertEquals(0, runCli(arrayOf("-p", "1", *flags, source.absolutePath)))
        }
    }
}
