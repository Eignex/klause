package com.eignex.klause.cli

import com.eignex.klause.formats.dimacs.Dimacs
import com.eignex.klause.formats.dimacs.toProblem
import com.eignex.klause.localsearch.LocalSearchModel
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WcnfModeTest {

    @Test
    fun `weighted unit moves update source literals and exact penalties together`() {
        val decoded = Dimacs.parseWcnf("3 1 0\n7 -2 0\n").toProblem()
        val solvable = linearSolvable(decoded.problem, decoded.objective, false, { "" }, boolFolds = decoded.boolFolds)
        val sweep = assertNotNull(solvable.definitionalSweep)
        val objective = assertNotNull(solvable.linearObjective)
        val state = LocalSearchState(LocalSearchModel.open(decoded.problem), Random(3))
        state.invariants = sweep.network(0, decoded.problem.numBoolVars)
        state.assignment.setBool(1, true)
        sweep.sweep(state.assignment, state.rootDomains, decoded.problem.factors)
        state.recompute()
        val move = Move.BoolFlip(0)
        assertEquals(-3.0, state.objectiveDelta(assertNotNull(solvable.lsObjective), move))

        state.apply(move)

        assertEquals(true, state.assignment.boolValue(0))
        assertEquals(false, state.assignment.boolValue(decoded.boolFolds.first().out))
        assertEquals(0L, state.cost)
        assertEquals(7L, objective.evaluateLong(state.assignment))
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

    private fun wcnf(text: String): String = File.createTempFile("wcnf", ".wcnf").apply {
        writeText(text)
        deleteOnExit()
    }.absolutePath

    @Test
    fun `a new-format weighted instance proves the minimum-cost solution`() {
        val out = capture { main(arrayOf("-e", "bt", wcnf("h 1 2 0\n3 -1 0\n1 -2 0\n"))) }
        assertTrue("s OPTIMUM FOUND" in out, out)
        assertTrue("o 1" in out.lines().map { it.trim() }, "expected optimum cost 1: $out")
        val model = out.lines().first { it.startsWith("v ") }.removePrefix("v ").trim()
        assertEquals("01", model, out)
    }

    @Test
    fun `an old-format p wcnf instance routes and proves its optimum`() {
        val out = capture { main(arrayOf("-e", "bt", wcnf("p wcnf 3 4 10\n10 1 2 3 0\n1 -1 0\n2 -2 0\n3 -3 0\n"))) }
        assertTrue("s OPTIMUM FOUND" in out, out)
        assertTrue("o 1" in out.lines().map { it.trim() }, "expected optimum cost 1: $out")
    }

    @Test
    fun `infeasible hard clauses print s UNSATISFIABLE`() {
        val out = capture { main(arrayOf(wcnf("h 1 0\nh -1 0\n2 -2 0\n"))) }
        assertTrue("s UNSATISFIABLE" in out, out)
    }

    @Test
    fun `the wcnf extension and the explicit format both route to the WCNF front-end`() {
        val path = wcnf("h 1 0\n1 -1 0\n")
        val byExt = capture { main(arrayOf("-e", "bt", path)) }
        val byFormat = capture { main(arrayOf("-e", "bt", "--format", "wcnf", path)) }
        assertTrue("s OPTIMUM FOUND" in byExt && "s OPTIMUM FOUND" in byFormat, "$byExt\n---\n$byFormat")
    }
}
