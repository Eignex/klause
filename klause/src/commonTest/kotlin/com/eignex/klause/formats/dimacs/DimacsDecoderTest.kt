package com.eignex.klause.formats.dimacs

import com.eignex.klause.solver.Assignment
import com.eignex.klause.solver.objective.toLinearObjective
import kotlin.test.Test
import kotlin.test.assertEquals

class DimacsDecoderTest {
    @Test
    fun `unit costs preserve both polarities and repeated clauses`() {
        val decoded = Dimacs.parseWcnf("3 1 0\n2 -1 0\n2 1 0\n7 -2 0\n4 0\n").toProblem()
        val objective = decoded.objective.toLinearObjective()
        val assignment = Assignment(decoded.problem.numBoolVars, 0)
        for ((first, second, cost) in listOf(
            Triple(false, false, 9L),
            Triple(true, false, 6L),
            Triple(false, true, 16L),
            Triple(true, true, 13L),
        )) {
            assignment.setBool(0, first)
            assignment.setBool(1, second)
            assertEquals(cost, objective.evaluateLong(assignment))
        }
    }

    @Test
    fun `non unit clauses retain their violation cost`() {
        val decoded = Dimacs.parseWcnf("3 1 0\n7 -1 -2 0\n").toProblem()
        val objective = decoded.objective.toLinearObjective()
        val assignment = Assignment(decoded.problem.numBoolVars, 0)
        assignment.setBool(0, true)
        assignment.setBool(1, true)
        assignment.setBool(2, true)

        assertEquals(7L, objective.evaluateLong(assignment))
    }

    @Test
    fun `unit coefficient overflow keeps the relaxation encoding`() {
        val decoded = Dimacs.parseWcnf("9223372036854775806 -1 0\n2 -1 0\n").toProblem()
        val assignment = Assignment(decoded.problem.numBoolVars, 0)

        assertEquals(2, decoded.problem.numBoolVars)
        assertEquals(0L, decoded.objective.toLinearObjective().evaluateLong(assignment))
    }
}
