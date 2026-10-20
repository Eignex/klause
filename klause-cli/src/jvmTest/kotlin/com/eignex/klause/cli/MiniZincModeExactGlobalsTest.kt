package com.eignex.klause.cli

import com.eignex.klause.ir.FloatSemantics
import com.eignex.klause.ir.Problem
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class MiniZincModeExactGlobalsTest(
    private val global: Triple<String, String, String?>,
    private val coupling: String,
    private val verdict: String,
) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0} {1} {2}")
        fun cases(): List<Array<Any>> = (flatZincGlobalCases + flatZincSetGlobalCases).flatMap { global ->
            listOf("finite", "linear", "polynomial").flatMap { coupling ->
                listOf("witness", "refutation", "optimum").map { verdict -> arrayOf(global, coupling, verdict) }
            }
        } + listOf("finite", "linear", "polynomial").map { coupling ->
            arrayOf(Triple("count_eq-empty-invalid", "klause_count_eq([],1,1)", null), coupling, "refutation")
        }
    }

    @Test
    fun `exact execution should enforce the advertised global through source completion`() {
        val realConstraints = when (coupling) {
            "linear" -> "constraint float_lin_eq([3.0,-1.0],[y,r],0.0);"
            "polynomial" -> "constraint float_times(y,y,r);"
            else -> ""
        }
        val realDeclarations = if (coupling == "finite") "" else
            "var 0.0..2.0: r :: output_var; var 0.0..2.0: y :: output_var; constraint int2float(x,r);"
        val pin = if (verdict == "optimum") "" else {
            val value = if (verdict == "refutation") 0 else 1
            if (coupling == "finite") "constraint int_eq(x,$value);" else "constraint float_eq(r,$value.0);"
        }
        val objective = if (coupling == "finite") "x" else "y"
        val solve = if (verdict == "optimum") "minimize $objective" else "satisfy"
        val source = File.createTempFile("exact-global", ".fzn").apply {
            val sets = if (global in flatZincSetGlobalCases) "var set of 1..1: s; var set of 1..1: t; " +
                "var bool: b; var bool: c; " else ""
            writeText("var 0..2: x :: output_var; var 1..3: v; var 1..2: d; " +
                "var 1..2: two; var 0..1: z; " +
                sets +
                "constraint int_plus(x,1,v); constraint int_eq(d,1); " +
                "constraint ${global.second}; $realDeclarations $realConstraints $pin solve $solve;")
        }
        val common = CommonOptions().apply {
            engine = "cp"
            exact = true
            parallel = 1
            randomSeed = 17
            sourceOptions.floatBuckets = 2
        }
        val session = MiniZincMode.newSession()
        val previous = System.out
        val bytes = ByteArrayOutputStream()
        val stream = PrintStream(bytes)
        try {
            val solvable = session.load(source.absolutePath, common)
            val invocation = assertNotNull(solvable.sourceInvocation)
            assertEquals(FloatSemantics.Continuous, invocation.source.semantics.effective.floats)
            assertNull(invocation.floatProjection)
            val original = invocation.source.originalSource as Problem
            global.third?.let { type -> assertTrue(original.factors.any { it::class.simpleName == type }, type) }
            System.setOut(stream)
            SolveCore.solve(solvable, common, session.output(common))
        } finally {
            System.setOut(previous)
            stream.close()
            source.delete()
        }
        val output = bytes.toString(Charsets.UTF_8.name())
        assertFalse("klause-grid" in output, output)
        assertFalse("=====UNKNOWN=====" in output, output)
        if (verdict == "refutation") {
            assertTrue("=====UNSATISFIABLE=====" in output.lines(), output)
            assertFalse("----------" in output.lines(), output)
        } else {
            assertTrue("----------" in output.lines(), output)
            assertTrue("x = 1;" in output.lines(), output)
            if (coupling == "linear") assertTrue("% klause-exact: y = 1/3;" in output.lines(), output)
            if (coupling == "polynomial") assertTrue("% klause-exact: y = 1;" in output.lines(), output)
            if (verdict == "optimum") assertTrue("==========" in output.lines(), output)
        }
    }
}
