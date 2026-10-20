package com.eignex.klause.formats.flatzinc

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.propagation.bake
import kotlin.test.Test
import kotlin.test.assertEquals

class FlatZincGlobalConstraintsTest {
    @Test
    fun `an empty counted array should admit a zero count`() {
        val program = parseFlatZinc("constraint count_eq([],1,0); solve satisfy;", exactFloats = true)

        val samples = BacktrackSolver(program.sourceProgram.problem.bake()).enumerate().toList()

        assertEquals(1, samples.size)
    }

    @Test
    fun `an empty counted array should reject a nonzero count`() {
        val program = parseFlatZinc("constraint count_eq([],1,1); solve satisfy;", exactFloats = true)

        val samples = BacktrackSolver(program.sourceProgram.problem.bake()).enumerate().toList()

        assertEquals(0, samples.size)
    }

    @Test
    fun `ordinary disjunctive should allow a zero duration task inside another task`() {
        val program = parseFlatZinc("constraint disjunctive([0,1],[2,0]); solve satisfy;", exactFloats = true)

        val samples = BacktrackSolver(program.sourceProgram.problem.bake()).enumerate().toList()

        assertEquals(1, samples.size)
    }

    @Test
    fun `strict disjunctive should allow a zero duration task at a task boundary`() {
        val program = parseFlatZinc("constraint disjunctive_strict([0,2],[2,0]); solve satisfy;", exactFloats = true)

        val samples = BacktrackSolver(program.sourceProgram.problem.bake()).enumerate().toList()

        assertEquals(1, samples.size)
    }
}
