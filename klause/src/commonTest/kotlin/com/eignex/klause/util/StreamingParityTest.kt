package com.eignex.klause.util

import com.eignex.klause.formats.dimacs.Dimacs
import com.eignex.klause.lowering.dimacs.toProblem
import com.eignex.klause.lowering.flatzinc.parseFlatZinc
import com.eignex.klause.formats.mps.Mps
import com.eignex.klause.formats.opb.Opb
import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.lowering.xcsp3.Xcsp3
import com.eignex.klause.lowering.mps.toProblem
import com.eignex.klause.lowering.opb.toProblem
import com.eignex.klause.solver.Problem
import kotlin.test.Test
import kotlin.test.assertEquals

/** Each supported front-end must parse a whole-string source and a one-character-per-chunk source to the
 *  same model — i.e. its incremental consumption is agnostic to where the input is split. Guards the
 *  streaming refactor: a future scanner change that peeks past a chunk or mishandles a split token/line
 *  fails here rather than silently on a large streamed instance. */
class StreamingParityTest {
    private fun shape(p: Problem) = Triple(p.numBoolVars, p.numIntVars, p.factors.size)

    private fun assertParity(name: String, text: String, parse: (CharSource) -> Problem) {
        assertEquals(
            shape(parse(StringCharSource(text))),
            shape(parse(perCharSource(text))),
            "$name parses differently when the source is split one char at a time",
        )
    }

    @Test
    fun `dimacs parse is chunk-boundary agnostic`() =
        assertParity("dimacs", "c comment\r\np cnf 3 2\n1 -2 0\n2 3 0\n") { Dimacs.parse(it).toProblem() }

    @Test
    fun `opb parse is chunk-boundary agnostic`() =
        assertParity("opb", "* #variable= 3 #constraint= 1\n+1 x1 +1 x2 +1 x3 >= 2 ;\n") {
            Opb.parse(it).toProblem().problem
        }

    @Test
    fun `mps parse is chunk-boundary agnostic`() {
        val mps = """
            NAME          t
            ROWS
             N  cost
             L  c1
            COLUMNS
                x         cost           1.0   c1             1.0
                y         cost           1.0   c1             1.0
            RHS
                rhs       c1             3.0
            BOUNDS
             UP BND       x              4.0
             UP BND       y              4.0
            ENDATA
        """.trimIndent() + "\n"
        assertParity("mps", mps) { Mps.parse(it).toProblem().model.materializeFiniteBounds() }
    }

    @Test
    fun `smtlib parse is chunk-boundary agnostic`() {
        val text = """
            (declare-fun x () Int)
            (declare-fun y () Int)
            (assert (<= (+ x y) 5))
            (assert (>= x 0))
            (check-sat)
        """.trimIndent() + "\n"
        fun shape(source: CharSource) = SmtLib.parse(
            source,
        ).model.let { Triple(it.numBoolVars, it.numIntVars, it.factors.size) }
        assertEquals(shape(StringCharSource(text)), shape(perCharSource(text)))
    }

    @Test
    fun `xcsp3 parse is chunk-boundary agnostic`() = assertParity(
        "xcsp3",
        "<instance format='XCSP3' type='CSP'><variables><var id='x'> 0..4 </var>" +
            "<var id='y'> 0..4 </var></variables><constraints><intension> le(add(x,y),4) </intension>" +
            "</constraints></instance>",
    ) { Xcsp3.parse(it).problem }

    @Test
    fun `flatzinc parse is chunk-boundary agnostic`() = assertParity(
        "flatzinc",
        "var 0..4: x;\nvar 0..4: y;\nconstraint int_lin_le([1,1],[x,y],4);\nsolve satisfy;\n",
    ) { parseFlatZinc(it).problem }
}
