package com.eignex.klause.cli

import com.eignex.klause.formats.mps.mpsSemantics
import com.eignex.klause.ir.LinearSourceObjective
import com.eignex.klause.ir.LinearSourceProblem
import com.eignex.klause.ir.LinearSourceTerm
import com.eignex.klause.ir.LinearSourceVariable
import com.eignex.klause.ir.ProblemSettings
import com.eignex.klause.ir.SemanticOverrides
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.solver.pipeline.ExecutionOperation
import com.eignex.klause.solver.pipeline.ExecutionOptions
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.LinearTraversal
import com.eignex.klause.solver.pipeline.SourceAttachment
import com.eignex.klause.solver.pipeline.SourceExecutionRequest
import com.eignex.klause.solver.pipeline.SourceExecutionResult
import com.eignex.klause.solver.pipeline.SourceSetup
import com.eignex.klause.solver.pipeline.SourceSetupRequest
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.bigIntOf
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MpsNumericalSemanticsTest {
    @Test
    fun `MPS numerical output should retain original objective units and authority labels`() {
        val source = LinearSourceProblem(
            listOf(LinearSourceVariable("x", true, SemanticRational.ZERO, SemanticRational.ONE)),
            emptyList(), LinearSourceObjective(listOf(LinearSourceTerm(0,
                SemanticRational.of(BIG_ONE, bigIntOf(3)))), SemanticRational.of(BIG_ONE, bigIntOf(10))))
        val invocation = SourceSetup.prepare(SourceSetupRequest(SourceAttachment(source, ProblemSettings()),
            ExecutionOperation.OPTIMIZE, FiniteEngine.FIXED, linearTraversal = LinearTraversal.NUMERICAL_FRONTIER))
        val solved = assertIs<SourceExecutionResult.Numerical>(SourceSetup.execute(SourceExecutionRequest(
            invocation, ExecutionOptions())))
        val qualified = assertNotNull(solved.linearSource)
        val bound = assertNotNull(qualified.numericalObjectiveBound)
        val bytes = ByteArrayOutputStream()
        val previous = System.out
        try {
            System.setOut(PrintStream(bytes))
            val output = MpsOutput()
            output.onLinearSourceResult(solved)

            output.onLinearResult(solved.result)

            val text = bytes.toString()
            assertContains(text, "c lpDeductionAuthority=numerical")
            assertContains(text, "c numericalObjectiveBound=" + bound)
            assertContains(text, "c numericalRelativeGap=")
            assertEquals(0.1, bound, 0.000000000000001)
            assertTrue(qualified.numericalClaims)
        } finally {
            System.setOut(previous)
        }
    }

    @Test
    fun `loading MPS without statistics should emit the captured invocation policy`() {
        val source = File.createTempFile("mps-policy", ".mps").apply {
            writeText("NAME T\nROWS\n N COST\nCOLUMNS\n X COST 1\nENDATA\n")
        }
        val bytes = ByteArrayOutputStream()
        val previous = System.out
        val options = CommonOptions().apply { exact = true }
        try {
            System.setOut(PrintStream(bytes))

            MpsMode.newSession().load(source.absolutePath, options)

            assertEquals(
                "c numericalSemantics=${mpsNumericalSemantics(checkNotNull(options.sourceSemantics))}\n",
                bytes.toString().replace("\r\n", "\n"),
            )
        } finally {
            System.setOut(previous)
            source.delete()
        }
    }

    @Test
    fun `default MPS policy should declare exact rational tolerance components`() {
        val exported = mpsNumericalSemantics(mpsSemantics.resolve())

        assertContains(exported, "\"schema\":\"mps-decimal-semantics-v1\"")
        assertContains(exported, "\"numericAuthority\":\"DECIMAL_LITERALS\"")
        assertEquals(2, Regex("\"absolute\":\"1/10000000\"").findAll(exported).count())
        assertEquals(2, Regex("\"relative\":\"1/10000000\"").findAll(exported).count())
        assertContains(exported, "\"undeclaredIntegers\":{\"lower\":\"0\",\"upper\":\"1\"}")
        assertContains(exported, "\"undeclaredReals\":{\"lower\":\"0/1\",\"upper\":null}")
        assertContains(exported, "\"integrality\":\"exact\",\"objective\":\"exact\"")
    }

    @Test
    fun `zero tolerance override should preserve declared defaults separately`() {
        val policy = mpsSemantics.resolve(SemanticOverrides(feasibilityTolerance = SourceTolerance()))

        val exported = mpsNumericalSemantics(policy)

        assertEquals(1, Regex("\"absolute\":\"1/10000000\"").findAll(exported).count())
        assertEquals(1, Regex("\"absolute\":\"0/1\"").findAll(exported).count())
        assertContains(exported.substringAfter("\"effective\":"), "\"relative\":\"0/1\"")
    }
}
