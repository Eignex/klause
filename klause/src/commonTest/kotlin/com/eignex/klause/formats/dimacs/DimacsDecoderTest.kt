package com.eignex.klause.formats.dimacs

import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.propagate
import com.eignex.klause.solver.Assignment
import com.eignex.klause.solver.objective.toLinearObjective
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class DimacsDecoderTest {
    @Test
    fun `wide unit penalty definitions retain exact feasible cost`() {
        val decoded = Dimacs.parseWcnf("9007199254740993 1 0\n7 -2 0\n").toProblem()
        val sweep = assertNotNull(DefinitionalSweep.infer(decoded.problem, boolFolds = decoded.boolFolds))
        val assignment = Assignment(decoded.problem.numBoolVars, 0)
        assignment.setBool(1, true)

        sweep.sweep(assignment, emptyArray(), decoded.problem.factors)

        assertEquals(9007199254741000L, decoded.objective.toLinearObjective().evaluateLong(assignment))
    }

    @Test
    fun `penalty definitions preserve original costs across weighted and hard clauses`() {
        for (text in listOf(
            "3 1 0\n7 -2 0\n",
            "h 1 -2 0\n3 1 -2 0\n7 -1 2 0\n5 -1 0\n2 0\n",
        )) {
            val decoded = Dimacs.parseWcnf(text).toProblem()
            val sweep = assertNotNull(DefinitionalSweep.infer(decoded.problem, boolFolds = decoded.boolFolds))
            val objective = decoded.objective.toLinearObjective()
            val assignment = Assignment(decoded.problem.numBoolVars, 0)
            for (bits in 0..3) {
                val first = bits and 1 != 0
                val second = bits and 2 != 0
                assignment.setBool(0, first)
                assignment.setBool(1, second)
                sweep.sweep(assignment, emptyArray(), decoded.problem.factors)
                val expected = if (text.startsWith("h")) {
                    (if (!first && second) 3L else 0L) +
                        (if (first && !second) 7L else 0L) + (if (first) 5L else 0L) + 2L
                } else {
                    (if (!first) 3L else 0L) + (if (second) 7L else 0L)
                }

                assertEquals(expected, objective.evaluateLong(assignment))
            }
        }
    }

    @Test
    fun `unit costs preserve both polarities and repeated clauses`() {
        val decoded = Dimacs.parseWcnf("2 1 0\n2 -1 0\n2 1 0\n2 -2 0\n4 0\n").toProblem()
        val objective = decoded.objective.toLinearObjective()
        val assignment = Assignment(decoded.problem.numBoolVars, 0)
        for ((first, second, cost) in listOf(
            Triple(false, false, 8L),
            Triple(true, false, 6L),
            Triple(false, true, 10L),
            Triple(true, true, 8L),
        )) {
            assignment.setBool(0, first)
            assignment.setBool(1, second)
            assertEquals(cost, objective.evaluateLong(assignment))
        }
    }

    @Test
    fun `non unit clauses retain their violation cost`() {
        val decoded = Dimacs.parseWcnf("7 1 0\n7 -1 -2 0\n").toProblem()
        val objective = decoded.objective.toLinearObjective()
        val assignment = Assignment(decoded.problem.numBoolVars, 0)
        assignment.setBool(0, true)
        assignment.setBool(1, true)
        assignment.setBool(2, true)

        assertEquals(7L, objective.evaluateLong(assignment))
    }

    @Test
    fun `a satisfied soft clause cannot carry a violation penalty`() {
        val decoded = Dimacs.parseWcnf("p wcnf 2 3 20\n20 1 0\n5 1 2 0\n3 -1 0\n").toProblem()
        val inflated = Assumptions(bools = mapOf(0 to true, 1 to false, 2 to true, 3 to true))

        val result = decoded.problem.propagate(inflated)

        assertIs<PropagationResult.Unsat>(result)
    }

    @Test
    fun `unit coefficient overflow keeps the relaxation encoding`() {
        val decoded = Dimacs.parseWcnf("4611686018427387904 -1 0\n4611686018427387904 -1 0\n").toProblem()
        val assignment = Assignment(decoded.problem.numBoolVars, 0)

        assertEquals(2, decoded.problem.numBoolVars)
        assertEquals(0L, decoded.objective.toLinearObjective().evaluateLong(assignment))
    }

    @Test
    fun `nonuniform soft costs retain separate violation indicators`() {
        val decoded = Dimacs.parseWcnf("3 1 0\n7 -2 0\n").toProblem()
        val assignment = Assignment(decoded.problem.numBoolVars, 0)
        assignment.setBool(2, true)

        assertEquals(4, decoded.problem.numBoolVars)
        assertEquals(3L, decoded.objective.toLinearObjective().evaluateLong(assignment))
    }
}
