package com.eignex.klause.formats.mps

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.OpenTheoryExecution
import com.eignex.klause.solver.pipeline.OpenTheoryPipeline
import com.eignex.klause.solver.pipeline.OpenTheoryRequest
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import com.eignex.klause.solver.pipeline.OpenTheoryOptimum
import com.eignex.klause.solver.pipeline.TheoryParams
import com.eignex.klause.solver.objective.toLinearObjective
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.parseBigInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MpsExactSourceSearchTest {
    @Test
    fun `complete strategies refute original decimal integrality conflict`() {
        for (engine in listOf(FiniteEngine.BACKTRACK, FiniteEngine.FIXED, FiniteEngine.MIXED)) {
            val source = Mps.parse(
                "ROWS\n E R\nCOLUMNS\n M0 'MARKER' 'INTORG'\n I R 0.1\n" +
                    " M1 'MARKER' 'INTEND'\nRHS\n RHS R 0.15\nBOUNDS\n FR B I\nENDATA",
            ).toExactSourceProblem()

            val execution = OpenTheoryPipeline.execute(OpenTheoryRequest(source.model),
                TheoryParams(openWorkLimit = 1000, maxFlips = 4), engine)

            assertIs<OpenTheoryResult.Unsat>(assertIs<OpenTheoryExecution.Satisfy>(execution).result)
        }
    }

    @Test
    fun `heuristic strategies cannot refute the original decimal source`() {
        for (engine in listOf(FiniteEngine.LOCAL_SEARCH, FiniteEngine.ALNS)) {
            val source = Mps.parse(
                "ROWS\n E R\nCOLUMNS\n M0 'MARKER' 'INTORG'\n I R 0.1\n" +
                    " M1 'MARKER' 'INTEND'\nRHS\n RHS R 0.15\nBOUNDS\n FR B I\nENDATA",
            ).toExactSourceProblem()

            val execution = OpenTheoryPipeline.execute(OpenTheoryRequest(source.model),
                TheoryParams(openWorkLimit = 1000, maxFlips = 2), engine)

            val unknown = assertIs<OpenTheoryResult.Unknown>(assertIs<OpenTheoryExecution.Satisfy>(execution).result)
            assertTrue(unknown.stats.ls.moves.sum > 0)
        }
    }

    @Test
    fun `exact arithmetic strategies publish original decimal mixed witnesses`() {
        for (engine in FiniteEngine.entries) {
            val source = Mps.parse(
                "ROWS\n E R\nCOLUMNS\n M0 'MARKER' 'INTORG'\n I R 0.1\n" +
                    " M1 'MARKER' 'INTEND'\n X R 0.2\nRHS\n RHS R 0.3\n" +
                    "BOUNDS\n FR B I\n FR B X\nENDATA",
            ).toExactSourceProblem()

            val execution = OpenTheoryPipeline.execute(OpenTheoryRequest(source.model),
                TheoryParams(openWorkLimit = 10000, maxFlips = 8), engine)

            val sat = assertIs<OpenTheoryResult.Sat>(assertIs<OpenTheoryExecution.Satisfy>(execution).result)
            val values = source.columns.map { column ->
                val text = if (column.real) sat.assignment.realValue(column.id) else sat.assignment.intValue(column.id)
                val parts = text.split('/')
                BigFraction.of(parseBigInt(parts[0]), parts.getOrNull(1)?.let(::parseBigInt) ?: BIG_ONE)
            }
            source.sourceWitness(values)
        }
    }

    @Test
    fun `cancelled exact source searches publish no terminal proof`() {
        for (engine in FiniteEngine.entries) {
            val source = Mps.parse("ROWS\n E R\nCOLUMNS\n X R 0.1\nRHS\n RHS R 0.3\nENDATA")
                .toExactSourceProblem()

            val execution = OpenTheoryPipeline.execute(OpenTheoryRequest(source.model),
                TheoryParams(cancellation = { true }), engine)

            val unknown = assertIs<OpenTheoryResult.Unknown>(assertIs<OpenTheoryExecution.Satisfy>(execution).result)
            assertEquals(TerminationReason.Cancelled, unknown.reason)
        }
    }

    @Test
    fun `integral objectives retain exact source recession checks`() {
        val source = Mps.parse(
            "ROWS\n N COST\n G R\nCOLUMNS\n M0 'MARKER' 'INTORG'\n I COST -1 R -0.1\n" +
                " M1 'MARKER' 'INTEND'\n X R 1\nBOUNDS\n LI B I 0\nENDATA",
        ).toExactSourceProblem()

        val execution = OpenTheoryPipeline.execute(OpenTheoryRequest(source.model,
            source.objective?.toLinearObjective()), TheoryParams(openWorkLimit = 10000))

        val unbounded = assertIs<OpenTheoryOptimum.Unbounded>(assertIs<OpenTheoryExecution.Optimize>(execution).result)
        val values = source.columns.map { column ->
            val text = if (column.real) {
                unbounded.witness.realValue(column.id)
            } else {
                unbounded.witness.intValue(column.id)
            }
            val parts = text.split('/')
            BigFraction.of(parseBigInt(parts[0]), parts.getOrNull(1)?.let(::parseBigInt) ?: BIG_ONE)
        }
        assertEquals(source.sourceWitness(values).objective, unbounded.value)
    }

    @Test
    fun `a declared constant source objective retains its optimum`() {
        val source = Mps.parse(
            "ROWS\n N COST\n E R\nCOLUMNS\n X R 0.1\nRHS\n RHS R 0.3 COST 3\nENDATA",
        ).toExactSourceProblem()

        val execution = OpenTheoryPipeline.execute(OpenTheoryRequest(source.model,
            source.objective?.toLinearObjective()), TheoryParams(openWorkLimit = 10000))

        val optimal = assertIs<OpenTheoryOptimum.Optimal>(assertIs<OpenTheoryExecution.Optimize>(execution).result)
        assertEquals(source.sourceWitness(listOf(BigFraction.ofLong(3))).objective, optimal.value)
    }
}
