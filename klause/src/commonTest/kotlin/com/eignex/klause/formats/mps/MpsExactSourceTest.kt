package com.eignex.klause.formats.mps

import com.eignex.klause.ir.IntegralConstants
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.SemanticOverrides
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.ir.Term
import com.eignex.klause.ir.linearRows
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.objective.toLinearObjective
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.OpenTheoryExecution
import com.eignex.klause.solver.pipeline.OpenTheoryOptimum
import com.eignex.klause.solver.pipeline.OpenTheoryPipeline
import com.eignex.klause.solver.pipeline.OpenTheoryRequest
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import com.eignex.klause.solver.pipeline.TheoryParams
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.bigIntOf
import com.eignex.util.numbers.parseBigInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MpsExactSourceTest {
    @Test
    fun `decimal cancellation should admit the original source witness`() {
        val source = Mps.parse(
            "ROWS\n N COST\n E ROW\nCOLUMNS\n X ROW 0.1\n X ROW 0.2\n" +
                " Y ROW -0.3\nRHS\n R ROW 0\nBOUNDS\n FR B X\n FR B Y\nENDATA",
        )
        val exact = SemanticOverrides(feasibilityTolerance = SourceTolerance())
        val compiled = source.toProblem(semanticOverrides = exact)

        val witness = assertNotNull(assertNotNull(source.toLinearSourceProblem(exact))
            .checkMpsWitness(listOf(BigFraction.ONE, BigFraction.ONE)))

        assertEquals(listOf(BigFraction.ONE, BigFraction.ONE), witness.values)
        assertTrue(compiled.sourceExact)
        assertTrue(
            compiled.model.factors.all { factor ->
                factor.linearRows.all { row -> row.constants is IntegralConstants }
            },
        )
    }

    @Test
    fun `exact source checking should reject a tolerance only witness`() {
        val source = Mps.parse(
            "ROWS\n N COST\n E ROW\nCOLUMNS\n X ROW 0.1\nRHS\n R ROW 0.3\n" +
                "BOUNDS\n LO B X 0.1\nENDATA",
        )
        val value = BigFraction.of(bigIntOf(30000001), bigIntOf(10000000))
        val tolerance = assertNotNull(source.toLinearSourceProblem())
        val exact = assertNotNull(source.toLinearSourceProblem(
            SemanticOverrides(feasibilityTolerance = SourceTolerance()),
        ))

        assertTrue(source.toProblem().sourceExact)
        assertFalse(assertNotNull(tolerance.checkMpsWitness(listOf(value))).exact)
        assertNull(exact.checkMpsWitness(listOf(value)))
    }

    @Test
    fun `decimal real bounds should remain exact source rows`() {
        val source = Mps.parse(
            "ROWS\n N COST\nCOLUMNS\n X COST 0\nBOUNDS\n LO B X 0.1\n UP B X 0.2\nENDATA",
        )
        val exact = SemanticOverrides(feasibilityTolerance = SourceTolerance())
        val compiled = source.toProblem(semanticOverrides = exact)
        val linear = assertNotNull(source.toLinearSourceProblem(exact))
        val tenth = BigFraction.of(BIG_ONE, bigIntOf(10))

        assertNotNull(linear.checkMpsWitness(listOf(tenth)))

        assertEquals(Double.NEGATIVE_INFINITY, compiled.model.realLower[0])
        assertEquals(Double.POSITIVE_INFINITY, compiled.model.realUpper[0])
        assertNull(linear.checkMpsWitness(listOf(tenth - BigFraction.of(BIG_ONE, bigIntOf(100000000)))))
    }

    @Test
    fun `wide integral bounds should retain original source values`() {
        val source = Mps.parse(
            "ROWS\n N COST\nCOLUMNS\n M0 'MARKER' 'INTORG'\n X COST 0\n" +
                " M1 'MARKER' 'INTEND'\nBOUNDS\n LO B X 9223372036854775809\nENDATA",
        )
        val exact = SemanticOverrides(feasibilityTolerance = SourceTolerance())
        val compiled = source.toProblem(semanticOverrides = exact)
        val linear = assertNotNull(source.toLinearSourceProblem(exact))
        val value = BigFraction.of(parseBigInt("9223372036854775809"), BIG_ONE)

        assertNotNull(linear.checkMpsWitness(listOf(value)))

        assertTrue(compiled.model.intBounds.isOpenLower(0))
        assertNull(linear.checkMpsWitness(listOf(value - BigFraction.ONE)))
    }

    @Test
    fun `objective equality should preserve original decimal units and constant`() {
        val source = Mps.parse(
            "ROWS\n N COST\nCOLUMNS\n X COST 0.1\n Y COST 0.2\n" +
                "RHS\n R COST -0.3\nBOUNDS\n FR B X\n FR B Y\nENDATA",
        )
        val exact = SemanticOverrides(feasibilityTolerance = SourceTolerance())
        val compiled = source.toProblem(semanticOverrides = exact)
        val values = listOf(BigFraction.ONE, BigFraction.ofLong(2))

        val witness = assertNotNull(assertNotNull(source.toLinearSourceProblem(exact)).checkMpsWitness(values))

        val objective = assertNotNull(witness.objective)
        assertEquals(BigFraction.of(bigIntOf(8), bigIntOf(10)), objective)
        val row = compiled.model.factors.last().linearRows.single()
        val constants = row.constants as IntegralConstants
        val coordinates = values + objective
        val activity = (0 until row.size).fold(BigFraction.ZERO) { sum, index ->
            sum + BigFraction.of(constants.exactCoeff(index), BIG_ONE) * coordinates[Term.realVar(row.ref(index))]
        }
        assertEquals(LinearOp.EQ, row.relation)
        assertEquals(BigFraction.of(constants.exactBound, BIG_ONE), activity)
    }

    @Test
    fun `retained integer objectives should map back to the original decimal value`() {
        for ((coefficient, denominator) in listOf("0.5" to 2L, "0.2" to 5L)) {
            val source = Mps.parse(
                "ROWS\n N COST\nCOLUMNS\n M0 'MARKER' 'INTORG'\n I COST $coefficient\n" +
                    " M1 'MARKER' 'INTEND'\nBOUNDS\n FX B I 1\nENDATA",
            )
            val exact = SemanticOverrides(feasibilityTolerance = SourceTolerance())
            val compiled = source.toProblem(semanticOverrides = exact)

            val witness = assertNotNull(assertNotNull(source.toLinearSourceProblem(exact))
                .checkMpsWitness(listOf(BigFraction.ONE)))
            val retained = checkNotNull(compiled.objective)
            val workingValue = BigFraction.ofLong(retained.intCoefficients.single() + retained.constant)
            val originalValue = workingValue * BigFraction.ofLong(compiled.objectiveScale).reciprocal()

            assertEquals(BigFraction.ofLong(denominator).reciprocal(), witness.objective)
            assertEquals(witness.objective, originalValue)
        }
    }

    @Test
    fun `exact source capture should survive mutations of parser arrays`() {
        val source = Mps.parse("ROWS\n N COST\n E ROW\nCOLUMNS\n X ROW 0.1\nRHS\n R ROW 0.3\nENDATA")
        val linear = assertNotNull(source.toLinearSourceProblem(
            SemanticOverrides(feasibilityTolerance = SourceTolerance()),
        ))
        source.constraints.single().coeffs[0] = 1.0

        val witness = assertNotNull(linear.checkMpsWitness(listOf(BigFraction.ofLong(3))))

        assertEquals(listOf(BigFraction.ofLong(3)), witness.values)
    }

    @Test
    fun `shared decimal denominators should admit an ordinary dense source row`() {
        val text = "ROWS\n E R\nCOLUMNS\n" +
            (0 until 100).joinToString("\n") { " X$it R 0.0000000000000000001" } +
            "\nRHS\n RHS R 0.00000000000000001\nENDATA"
        val source = Mps.parse(text)
        val exact = SemanticOverrides(feasibilityTolerance = SourceTolerance())
        val compiled = source.toProblem(semanticOverrides = exact)

        val execution = OpenTheoryPipeline.execute(
            OpenTheoryRequest(compiled.model),
            TheoryParams(openWorkLimit = 10000),
        )

        val sat = assertIs<OpenTheoryResult.Sat>(
            assertIs<OpenTheoryExecution.Satisfy>(execution).result,
        )
        assertNotNull(assertNotNull(source.toLinearSourceProblem(exact)).checkMpsWitness(
            compiled.columns.map { column ->
                val parts = sat.assignment.realValue(column.id).split('/')
                BigFraction.of(parseBigInt(parts[0]), parts.getOrNull(1)?.let(::parseBigInt) ?: BIG_ONE)
            },
        ))
    }

    @Test
    fun `complete strategies should refute an original decimal integrality conflict`() {
        for (engine in listOf(FiniteEngine.BACKTRACK, FiniteEngine.FIXED, FiniteEngine.MIXED)) {
            val source = Mps.parse(
                "ROWS\n E R\nCOLUMNS\n M0 'MARKER' 'INTORG'\n I R 0.1\n" +
                    " M1 'MARKER' 'INTEND'\nRHS\n RHS R 0.15\nBOUNDS\n FR B I\nENDATA",
            ).toProblem(semanticOverrides = SemanticOverrides(feasibilityTolerance = SourceTolerance()))

            val execution = OpenTheoryPipeline.execute(
                OpenTheoryRequest(source.model),
                TheoryParams(openWorkLimit = 1000, maxFlips = 4),
                engine,
            )

            assertIs<OpenTheoryResult.Unsat>(assertIs<OpenTheoryExecution.Satisfy>(execution).result)
        }
    }

    @Test
    fun `heuristic strategies should not refute the original decimal source`() {
        for (engine in listOf(FiniteEngine.LOCAL_SEARCH, FiniteEngine.ALNS)) {
            val source = Mps.parse(
                "ROWS\n E R\nCOLUMNS\n M0 'MARKER' 'INTORG'\n I R 0.1\n" +
                    " M1 'MARKER' 'INTEND'\nRHS\n RHS R 0.15\nBOUNDS\n FR B I\nENDATA",
            ).toProblem(semanticOverrides = SemanticOverrides(feasibilityTolerance = SourceTolerance()))

            val execution = OpenTheoryPipeline.execute(
                OpenTheoryRequest(source.model),
                TheoryParams(openWorkLimit = 1000, maxFlips = 2),
                engine,
            )

            val unknown = assertIs<OpenTheoryResult.Unknown>(assertIs<OpenTheoryExecution.Satisfy>(execution).result)
            assertTrue(unknown.stats.ls.moves.sum > 0)
        }
    }

    @Test
    fun `exact arithmetic strategies should publish original decimal mixed witnesses`() {
        for (engine in FiniteEngine.entries) {
            val parsed = Mps.parse(
                "ROWS\n E R\nCOLUMNS\n M0 'MARKER' 'INTORG'\n I R 0.1\n" +
                    " M1 'MARKER' 'INTEND'\n X R 0.2\nRHS\n RHS R 0.3\n" +
                    "BOUNDS\n FR B I\n FR B X\nENDATA",
            )
            val exact = SemanticOverrides(feasibilityTolerance = SourceTolerance())
            val source = parsed.toProblem(semanticOverrides = exact)

            val execution = OpenTheoryPipeline.execute(
                OpenTheoryRequest(source.model),
                TheoryParams(openWorkLimit = 10000, maxFlips = 8),
                engine,
            )

            val sat = assertIs<OpenTheoryResult.Sat>(assertIs<OpenTheoryExecution.Satisfy>(execution).result)
            val values = source.columns.map { column ->
                val text = if (column.real) sat.assignment.realValue(column.id) else sat.assignment.intValue(column.id)
                val parts = text.split('/')
                BigFraction.of(parseBigInt(parts[0]), parts.getOrNull(1)?.let(::parseBigInt) ?: BIG_ONE)
            }
            assertNotNull(assertNotNull(parsed.toLinearSourceProblem(exact)).checkMpsWitness(values), "$engine")
        }
    }

    @Test
    fun `cancelled exact source searches should publish no terminal proof`() {
        for (engine in FiniteEngine.entries) {
            val source = Mps.parse("ROWS\n E R\nCOLUMNS\n X R 0.1\nRHS\n RHS R 0.3\nENDATA")
                .toProblem(semanticOverrides = SemanticOverrides(feasibilityTolerance = SourceTolerance()))

            val execution = OpenTheoryPipeline.execute(
                OpenTheoryRequest(source.model),
                TheoryParams(cancellation = { true }),
                engine,
            )

            val unknown = assertIs<OpenTheoryResult.Unknown>(assertIs<OpenTheoryExecution.Satisfy>(execution).result)
            assertEquals(TerminationReason.Cancelled, unknown.reason)
        }
    }

    @Test
    fun `integral objectives should retain exact source recession checks`() {
        val parsed = Mps.parse(
            "ROWS\n N COST\n G R\nCOLUMNS\n M0 'MARKER' 'INTORG'\n I COST -1 R -0.1\n" +
                " M1 'MARKER' 'INTEND'\n X R 1\nBOUNDS\n LI B I 0\nENDATA",
        )
        val exact = SemanticOverrides(feasibilityTolerance = SourceTolerance())
        val source = parsed.toProblem(semanticOverrides = exact)

        val execution = OpenTheoryPipeline.execute(
            OpenTheoryRequest(
                source.model,
                source.objective?.toLinearObjective(),
            ),
            TheoryParams(openWorkLimit = 10000),
        )

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
        assertEquals(
            assertNotNull(assertNotNull(parsed.toLinearSourceProblem(exact)).checkMpsWitness(values)).objective,
            unbounded.value,
        )
    }

    @Test
    fun `a declared constant source objective should retain its optimum`() {
        val parsed = Mps.parse(
            "ROWS\n N COST\n E R\nCOLUMNS\n X R 0.1\nRHS\n RHS R 0.3 COST 3\nENDATA",
        )
        val exact = SemanticOverrides(feasibilityTolerance = SourceTolerance())
        val source = parsed.toProblem(semanticOverrides = exact)

        val execution = OpenTheoryPipeline.execute(
            OpenTheoryRequest(
                source.model,
                source.objective?.toLinearObjective(),
            ),
            TheoryParams(openWorkLimit = 10000),
        )

        val optimal = assertIs<OpenTheoryOptimum.Optimal>(assertIs<OpenTheoryExecution.Optimize>(execution).result)
        assertEquals(
            assertNotNull(assertNotNull(parsed.toLinearSourceProblem(exact))
                .checkMpsWitness(listOf(BigFraction.ofLong(3)))).objective,
            optimal.value,
        )
    }
}
