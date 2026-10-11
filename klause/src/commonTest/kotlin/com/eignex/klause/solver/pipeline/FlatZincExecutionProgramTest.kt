package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.formats.flatzinc.parseFlatZinc
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.presolve.PresolvePipeline
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.result.MinimizeResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FlatZincExecutionProgramTest {
    @Test
    fun `objectives beyond exact floating point integer precision retain their integer variable`() {
        for ((directive, expectedCoefficient) in listOf("minimize" to 1L, "maximize" to -1L)) {
            val program = parseFlatZinc(
                """
                var bool: a;
                var 0..1: x;
                var ${Long.MAX_VALUE - 1L}..${Long.MAX_VALUE}: score;
                constraint int_lin_eq([1, 1], [x, score], ${Long.MAX_VALUE});
                constraint bool2int(a, x);
                solve $directive score;
                """.trimIndent(),
            )
            val objective = checkNotNull(program.linearObjective())

            assertEquals(expectedCoefficient, objective.intCoefficients[program.intVarsByName.getValue("score")])
            assertTrue(objective.boolWeights.all { it == 0L })
        }
    }

    @Test
    fun `presolve reconstructs named integer objectives after Boolean projection`() {
        for ((directive, expectedScore) in listOf("minimize" to -2L, "maximize" to 0L)) {
            val program = parseFlatZinc(
                """
                var bool: a;
                var bool: impossible;
                var bool: atZero;
                var 0..1: x;
                var -2..0: score;
                constraint int_lin_eq([2, 1], [x, score], 0);
                constraint int_lin_le_reif([1, 2], [x, x], -1, impossible);
                constraint int_le_reif(x, 0, atZero);
                constraint bool2int(a, x);
                solve $directive score;
                """.trimIndent(),
            )
            val objective = checkNotNull(program.linearObjective())
            val prepared = PresolvePipeline.run(
                program.problem, objective, PresolveConfig.parse("affine,binary-columns"), false,
            )
            val adjusted = prepared.objective ?: objective
            assertTrue(objective.intCoefficients.all { it == 0L })

            val result = assertIs<MinimizeResult.Optimal>(BacktrackSolver(prepared.problem.bake()).minimize(
                adjusted, BacktrackParams(randomSeed = 0L),
            ))
            val source = prepared.mapping.reconstructFrom(prepared.problem, result.assignment)

            prepared.mapping.requireObjectivePreserved(objective, adjusted, result.assignment)
            assertEquals(expectedScore, source.ints[program.intVarsByName.getValue("score")])
        }
    }

    @Test
    fun `a Boolean channel objective retains the source integer assignments`() {
        val program = parseFlatZinc(
            """
            var bool: a;
            var 0..1: x;
            var -2..0: score;
            constraint int_lin_eq([2, 1], [x, score], 0);
            constraint bool2int(a, x);
            solve maximize score;
            """.trimIndent(),
        )

        val objective = checkNotNull(program.linearObjective())
        val assignments = BacktrackSolver(program.problem.bake()).enumerate(BacktrackParams(randomSeed = 0L))
            .map { sample ->
                Triple(
                    sample.bools[program.boolVarsByName.getValue("a")],
                    sample.ints[program.intVarsByName.getValue("score")],
                    objective.evaluateExact(sample).toString(),
                )
            }.toSet()

        assertTrue(objective.intCoefficients.all { it == 0L })
        assertEquals(2L, objective.boolWeights[program.boolVarsByName.getValue("a")])
        assertEquals(setOf(Triple(false, 0L, "0"), Triple(true, -2L, "2")), assignments)
    }
}
