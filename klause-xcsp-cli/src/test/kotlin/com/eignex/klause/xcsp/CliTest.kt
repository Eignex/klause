package com.eignex.klause.xcsp

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertTrue

class CliTest {

    private fun capture(block: () -> Unit): String {
        val buf = ByteArrayOutputStream()
        val old = System.out
        System.setOut(PrintStream(buf))
        try { block() } finally { System.setOut(old) }
        return buf.toString()
    }

    @Test
    fun `coverage report counts parsed, solved and unsupported`() {
        val dir = File.createTempFile("xcsp-cov", "").let { it.delete(); it.mkdirs(); it }
        try {
            File(dir, "ok.smt2").writeText(
                "(declare-const x Int)(declare-const y Int)" +
                    "(assert (>= x 0))(assert (>= y 0))(assert (<= (+ x y) 5))(check-sat)"
            )
            File(dir, "sat.xml").writeText(
                """<instance type="CSP"><variables><var id="a"> 1..3 </var><var id="b"> 1..3 </var></variables>
                   <constraints><allDifferent> a b </allDifferent></constraints></instance>"""
            )
            // `pow` is outside the linear intension subset → an unsupported construct.
            File(dir, "bad.xml").writeText(
                """<instance type="CSP"><variables><var id="a"> 1..3 </var></variables>
                   <constraints><intension> eq(pow(a,2),4) </intension></constraints></instance>"""
            )

            val out = capture { main(arrayOf("--coverage", "-t", "5000", dir.absolutePath)) }
            assertTrue("total instances : 3" in out, out)
            assertTrue("parsed          : 2" in out, out)
            assertTrue("unsupported     : 1" in out, out)
            assertTrue("UNSUPPORTED" in out, out)
        } finally {
            dir.deleteRecursively()
        }
    }
}
