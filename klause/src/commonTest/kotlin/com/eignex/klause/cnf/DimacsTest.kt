package com.eignex.klause.cnf

import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.factor.Clause
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class DimacsTest {

    @Test
    fun `parses simple sat instance`() {
        val text = """
            c sample
            p cnf 3 3
            1 -2 3 0
            -1 2 0
            -3 0
        """.trimIndent()
        val problem = Dimacs.parse(text)
        assertEquals(3, problem.numBoolVars)
        assertEquals(0, problem.numIntVars)
        assertEquals(3, problem.factors.size)
        val first = problem.factors[0] as Clause
        assertEquals(
            listOf(Lit.make(0, true), Lit.make(1, false), Lit.make(2, true)),
            first.literals.toList(),
        )
    }

    @Test
    fun `accepts multi line clauses and comments`() {
        val text = """
            c first comment
            % alt comment
            p cnf 2 2
            1 2
            0
            -1 -2 0
        """.trimIndent()
        val problem = Dimacs.parse(text)
        assertEquals(2, problem.numBoolVars)
        assertEquals(2, problem.factors.size)
    }

    @Test
    fun `round trips through cnf problem`() {
        // Build a tiny CnfProblem, dump to DIMACS, parse back, compare clause sets.
        val cnf = CnfProblem(
            numVars = 4,
            clauses = listOf(
                intArrayOf(Lit.make(0, true), Lit.make(1, false)),
                intArrayOf(Lit.make(1, true), Lit.make(2, true), Lit.make(3, false)),
            ),
            boolVarToCnfVar = intArrayOf(),
            intVarBits = arrayOf(),
            intVarMin = intArrayOf(),
        )
        val text = cnf.toDimacs()
        val problem = Dimacs.parse(text)
        assertEquals(4, problem.numBoolVars)
        assertEquals(2, problem.factors.size)
        val parsedClauses = problem.factors.map { (it as Clause).literals.toList() }
        assertEquals(
            listOf(
                listOf(Lit.make(0, true), Lit.make(1, false)),
                listOf(Lit.make(1, true), Lit.make(2, true), Lit.make(3, false)),
            ),
            parsedClauses,
        )
    }

    @Test
    fun `rejects missing header`() {
        assertFails {
            Dimacs.parse("1 2 3 0\n")
        }
    }

    @Test
    fun `rejects literal out of range`() {
        assertFails {
            Dimacs.parse("p cnf 2 1\n1 2 3 0\n")
        }
    }

    @Test
    fun `ignores trailing percent block`() {
        // SATLIB instances often end with `%\n0\n` sentinels — must be ignored.
        val text = """
            p cnf 2 1
            1 -2 0
            %
            0
        """.trimIndent()
        val problem = Dimacs.parse(text)
        assertTrue(problem.factors.size == 1)
    }
}
