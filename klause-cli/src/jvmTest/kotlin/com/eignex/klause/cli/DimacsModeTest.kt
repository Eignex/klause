package com.eignex.klause.cli

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertTrue

class DimacsModeTest {

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

    private fun cnf(text: String): String = File.createTempFile("dimacs", ".cnf").apply {
        writeText(text)
        deleteOnExit()
    }.absolutePath

    @Test
    fun `a satisfiable cnf prints s SATISFIABLE with a full signed model line`() {
        // (x1 or x2) and (not x1) — forces x2 true; the model line must assign every variable.
        val out = capture { main(arrayOf(cnf("p cnf 2 2\n1 2 0\n-1 0\n"))) }
        assertTrue("s SATISFIABLE" in out, out)
        val model = out.lines().first { it.startsWith("v ") }.removePrefix("v ").trim().split(" ")
        assertTrue(model.last() == "0", "model line must terminate with 0: $out")
        assertTrue(model.dropLast(1).map { it.toInt() }.toSet() == setOf(-1, 2), "expected x1=false x2=true: $out")
    }

    @Test
    fun `statistics use the shared search block including the solutions count`() {
        val out = capture { main(arrayOf("-s", cnf("p cnf 2 2\n1 2 0\n-1 0\n"))) }
        // The shared searchStatPairs block replaces the former hand-rolled three lines, so the CDCL
        // counters and the solutions count are all present as `c` comments.
        assertTrue("c solutions=1" in out, out)
        for (key in listOf("c nodes=", "c failures=", "c restarts=", "c propagations=", "c learned=")) {
            assertTrue(key in out, "missing $key in: $out")
        }
    }

    @Test
    fun `a contradictory cnf prints s UNSATISFIABLE`() {
        val out = capture { main(arrayOf(cnf("p cnf 1 2\n1 0\n-1 0\n"))) }
        assertTrue("s UNSATISFIABLE" in out, out)
        assertTrue("SATISFIABLE" !in out.replace("UNSATISFIABLE", ""), out)
    }

    @Test
    fun `the cnf extension and the explicit format both route to the DIMACS front-end`() {
        val path = cnf("p cnf 1 1\n1 0\n")
        val byExt = capture { main(arrayOf(path)) }
        val byFormat = capture { main(arrayOf("--format", "dimacs", path)) }
        assertTrue("s SATISFIABLE" in byExt && "s SATISFIABLE" in byFormat, "$byExt\n---\n$byFormat")
    }
}
