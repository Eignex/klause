package com.eignex.klause.formats.flatzinc

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.bake
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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

    @Test
    fun `optional unary exclusion should reach the original choice channel`() {
        val program = parseFlatZinc("""
            var 0..2: a; var 0..2: b; var 0..2: c;
            var bool: p; var bool: choice;
            constraint klause_disjunctive_opt([true,true,p],[a,b,c],[2,2,2]);
            constraint bool_clause([p,choice],[]);
            solve satisfy;
        """.trimIndent())

        val session = PropagationSession(program.sourceProgram.problem)

        assertEquals(true, session.boolValue(program.boolVarsByName.getValue("choice")))
    }

    @Test
    fun `shared optional guards should preserve polarity and repeated starts`() {
        for (negative in listOf(false, true)) {
            val guard = if (negative) "q" else "p"
            val program = parseFlatZinc("""
                var 0..1: start;
                var bool: p; var bool: q;
                constraint bool_not(p,q);
                constraint klause_disjunctive_opt([$guard,$guard],[start,start],[2,2]);
                solve satisfy;
            """.trimIndent())

            val session = PropagationSession(program.sourceProgram.problem)

            assertEquals(negative, session.boolValue(program.boolVarsByName.getValue("p")))
        }
    }

    @Test
    fun `an absent native task should preserve all values of its start`() {
        val program = parseFlatZinc("""
            var -2..2: start;
            array[1..2] of bool: present = [false,true];
            constraint klause_disjunctive_opt(present,[start,0],[2,2]);
            solve satisfy;
        """.trimIndent())

        val samples = BacktrackSolver(program.sourceProgram.problem.bake()).enumerate().toList()

        assertEquals((-2L..2L).toSet(), samples.map { it.ints[program.intVarsByName.getValue("start")] }.toSet())
    }

    @Test
    fun `the optional bridge should decline nonpositive durations`() {
        for (duration in listOf(0, -1)) {
            assertFailsWith<UnsupportedFlatZincException> {
                parseFlatZinc("constraint klause_disjunctive_opt([true],[0],[$duration]); solve satisfy;")
            }
        }
    }

    @Test
    fun `the optional bridge should decline mismatched task arrays`() {
        assertFailsWith<UnsupportedFlatZincException> {
            parseFlatZinc("constraint klause_disjunctive_opt([true],[0,1],[2,2]); solve satisfy;")
        }
    }

    @Test
    fun `the optional bridge should decline variable durations`() {
        for (domain in listOf("0..1", "1..2")) {
            assertFailsWith<FlatZincParseException> {
                parseFlatZinc("var $domain: d; constraint klause_disjunctive_opt([true,true],[0,1],[1,d]); solve satisfy;")
            }
        }
    }
}
