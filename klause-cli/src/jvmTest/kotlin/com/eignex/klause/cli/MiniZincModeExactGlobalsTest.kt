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
        fun cases(): List<Array<Any>> {
            val globals = flatZincGlobalCases + flatZincSetGlobalCases
            val completionNames = setOf(
                "all_different_int", "all_equal_int", "symmetric_all_different", "member_int", "sort",
                "global_cardinality-variable", "nvalue", "inverse_offsets", "array_int_maximum",
                "strictly_decreasing_int", "lex_less_int", "value_precede_chain_int", "diffn-variable-zero",
                "table_int", "regular", "mdd", "subcircuit", "disjunctive_strict-variable-zero",
                "array_var_set_element", "set_union", "set_in_reif-false",
            )
            val completionCases = globals.filter { it.first in completionNames }
            check(completionCases.map { it.third }.toSet() == globals.map { it.third }.toSet()) {
                "Real completion cases must cover every finite factor kind"
            }

            return globals.flatMap { global ->
                listOf("witness", "refutation", "optimum").map { arrayOf<Any>(global, "finite", it) }
            } + completionCases.flatMap { global ->
                listOf("linear", "polynomial", "algebraic").flatMap { coupling ->
                    listOf("witness", "refutation", "real-refutation", "optimum").map { verdict ->
                        arrayOf<Any>(global, coupling, verdict)
                    }
                }
            } + listOf("finite", "linear", "polynomial", "algebraic").map { coupling ->
                arrayOf<Any>(Triple("count_eq-empty-invalid", "klause_count_eq([],1,1)", null), coupling, "refutation")
            }
        }
    }

    @Test
    fun `exact execution should enforce the advertised global through source completion`() {
        val bridge = if (coupling == "algebraic") "v" else "x"
        val realConstraints = if (coupling == "finite") "" else "constraint int2float($bridge,r); " + when (coupling) {
            "linear" -> "constraint float_lin_eq([3.0,-1.0],[y,r],0.0);"
            "polynomial", "algebraic" -> "constraint float_times(y,y,r);"
            else -> ""
        }
        val realUpper = if (verdict == "real-refutation") "0.25" else "2.0"
        val realDeclarations = if (coupling == "finite") "" else
            "var 0.0..3.0: r :: output_var; var 0.0..$realUpper: y :: output_var; "
        val pin = if (verdict == "optimum") "" else {
            val value = (if (verdict == "refutation") 0 else 1) + if (coupling == "algebraic") 1 else 0
            if (coupling == "finite") "constraint int_eq(x,$value);" else "constraint float_eq(r,$value.0);"
        }
        val objective = if (coupling == "linear") "y" else "x"
        val solve = if (verdict == "optimum") "minimize $objective" else "satisfy"
        val source = File.createTempFile("exact-global", ".fzn").apply {
            val roles = listOf("v", "d", "two", "z", "s", "t", "b", "c")
                .filter {
                    val usedByGlobal = Regex("\\b$it\\b").containsMatchIn(global.second)
                    usedByGlobal || (it == bridge && coupling == "algebraic")
                }
            val declarations = roles.joinToString(" ") {
                when (it) {
                    "v" -> "var 1..3: v;"
                    "d", "two" -> "var 1..2: $it;"
                    "z" -> "var 0..1: z;"
                    "s", "t" -> "var set of 1..1: $it;"
                    else -> "var bool: $it;"
                }
            }
            val definitions = (if ("v" in roles) "constraint int_plus(x,1,v); " else "") +
                if ("d" in roles) "constraint int_eq(d,1); " else ""
            writeText("var 0..2: x :: output_var; $declarations $realDeclarations $definitions " +
                "constraint ${global.second}; $realConstraints $pin solve $solve;")
        }
        val common = CommonOptions().apply {
            engine = "cp"
            exact = true
            parallel = 1
            randomSeed = 17
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
        if (verdict.endsWith("refutation")) {
            assertTrue("=====UNSATISFIABLE=====" in output.lines(), output)
            assertFalse("----------" in output.lines(), output)
        } else {
            assertTrue("----------" in output.lines(), output)
            assertEquals("x = 1;", output.lineSequence().lastOrNull { it.startsWith("x = ") }, output)
            if (coupling != "finite") {
                val exact = output.lineSequence().lastOrNull { it.startsWith("% klause-exact: y = ") }
                if (coupling == "algebraic" || (coupling == "polynomial" && exact != "% klause-exact: y = 1;")) {
                    assertTrue(assertNotNull(exact, output).startsWith("% klause-exact: y = root("), output)
                    val image = if (coupling == "algebraic") 2 else 1
                    assertEquals("% klause-exact: r = $image;",
                        output.lineSequence().lastOrNull { it.startsWith("% klause-exact: r = ") }, output)
                } else {
                    val value = if (coupling == "linear") "1/3" else "1"
                    assertEquals("% klause-exact: y = $value;", exact, output)
                }
            }
            if (verdict == "optimum") assertTrue("==========" in output.lines(), output)
        }
    }
}
