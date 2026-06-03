package com.eignex.klause.formats.dimacs

import com.eignex.klause.cnf.CnfProblem
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
        val text = """
            p cnf 2 1
            1 -2 0
            %
            0
        """.trimIndent()
        val problem = Dimacs.parse(text)
        assertTrue(problem.factors.size == 1)
    }

    @Test
    fun `parses old wcnf format with top weight`() {
        // 3 vars, 3 clauses. top=10 → weight 10 = hard, weights 1/2 = soft.
        val text = """
            c sample wcnf
            p wcnf 3 3 10
            10 1 2 0
            1 -1 0
            2 -2 0
        """.trimIndent()
        val w = Dimacs.parseWcnf(text)
        assertEquals(3, w.numOriginalBoolVars)
        // 3 original + 2 relaxation bools.
        assertEquals(5, w.problem.numBoolVars)
        // 1 hard clause + 2 relaxed soft clauses.
        assertEquals(3, w.problem.factors.size)
        assertEquals(0.0, w.objective.boolWeights[0])
        assertEquals(1.0, w.objective.boolWeights[3])
        assertEquals(2.0, w.objective.boolWeights[4])
    }

    @Test
    fun `old wcnf without top treats max-weight clause as hard`() {
        // #86: header omits `top`. The Long.MAX_VALUE-weighted clause must be hard (not demoted to
        // soft); the normal-weight clause stays soft.
        val text = """
            p wcnf 3 2
            9223372036854775807 1 2 0
            1 -1 0
        """.trimIndent()
        val w = Dimacs.parseWcnf(text)
        assertEquals(3, w.numOriginalBoolVars)
        // Only the single soft clause allocates a relaxation bool → 3 original + 1.
        assertEquals(4, w.problem.numBoolVars)
        // 1 hard clause + 1 relaxed soft clause.
        assertEquals(2, w.problem.factors.size)
        // The relaxation bool for the soft clause carries weight 1; no relaxation bool for the hard.
        assertEquals(1.0, w.objective.boolWeights[3])
    }

    @Test
    fun `old wcnf without top keeps normal-weight clauses soft`() {
        // Sanity: a non-sentinel weight with no `top` stays soft (plain MaxSAT, no hard clauses).
        val w = Dimacs.parseWcnf("p wcnf 2 1\n5 -1 0\n")
        assertEquals(2, w.numOriginalBoolVars)
        assertEquals(3, w.problem.numBoolVars) // 2 original + 1 relaxation bool
        assertEquals(5.0, w.objective.boolWeights[2])
    }

    @Test
    fun `wcnf rejects trailing tokens after the 0 terminator`() {
        assertFails {
            Dimacs.parseWcnf("p wcnf 2 1\n1 -1 0 99\n")
        }
    }

    @Test
    fun `wcnf rejects clause not terminated by 0`() {
        assertFails {
            Dimacs.parseWcnf("p wcnf 2 1\n1 -1 -2\n")
        }
    }

    @Test
    fun `parses new maxsat format with h prefix`() {
        val text = """
            h 1 2 0
            5 -1 0
            3 -2 0
        """.trimIndent()
        val w = Dimacs.parseWcnf(text)
        assertEquals(2, w.numOriginalBoolVars)
        assertEquals(4, w.problem.numBoolVars)
        assertEquals(3, w.problem.factors.size)
        assertEquals(5.0, w.objective.boolWeights[2])
        assertEquals(3.0, w.objective.boolWeights[3])
    }
}
