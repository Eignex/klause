package com.eignex.klause.formats.mps

import com.eignex.klause.ir.IntegralConstants
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Term
import com.eignex.klause.ir.linearRows
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.pipeline.OpenTheoryExecution
import com.eignex.klause.solver.pipeline.OpenTheoryPipeline
import com.eignex.klause.solver.pipeline.OpenTheoryRequest
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import com.eignex.klause.solver.pipeline.TheoryParams
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.parseBigInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MpsExactSourceTest {
    @Test
    fun `decimal cancellation admits the original source witness`() {
        val source = Mps.parse(
            "ROWS\n N COST\n E ROW\nCOLUMNS\n X ROW 0.1\n X ROW 0.2\n" +
                " Y ROW -0.3\nRHS\n R ROW 0\nBOUNDS\n FR B X\n FR B Y\nENDATA",
        )
        val compiled = source.toExactSourceProblem()

        val witness = compiled.sourceWitness(listOf(BigFraction.ONE, BigFraction.ONE))

        assertEquals(listOf(BigFraction.ONE, BigFraction.ONE), witness.values)
        assertTrue(compiled.sourceExact)
        assertTrue(compiled.model.factors.all { factor ->
            factor.linearRows.all { row -> row.constants is IntegralConstants }
        })
    }

    @Test
    fun `exact source checking rejects a tolerance only witness`() {
        val source = Mps.parse(
            "ROWS\n N COST\n E ROW\nCOLUMNS\n X ROW 0.1\nRHS\n R ROW 0.3\n" +
                "BOUNDS\n LO B X 0.1\nENDATA",
        )
        val value = BigFraction.of(bigIntOf(30000001), bigIntOf(10000000))
        val tolerance = source.toProblem()
        val exact = source.toExactSourceProblem()

        assertFalse(tolerance.sourceExact)
        tolerance.sourceWitness(listOf(value))
        assertFailsWith<MpsLoweringException> { exact.sourceWitness(listOf(value)) }
    }

    @Test
    fun `decimal real bounds remain exact source rows`() {
        val compiled = Mps.parse(
            "ROWS\n N COST\nCOLUMNS\n X COST 0\nBOUNDS\n LO B X 0.1\n UP B X 0.2\nENDATA",
        ).toExactSourceProblem()
        val tenth = BigFraction.of(BIG_ONE, bigIntOf(10))

        compiled.sourceWitness(listOf(tenth))

        assertEquals(Double.NEGATIVE_INFINITY, compiled.model.realLower[0])
        assertEquals(Double.POSITIVE_INFINITY, compiled.model.realUpper[0])
        assertFailsWith<MpsLoweringException> {
            compiled.sourceWitness(listOf(tenth - BigFraction.of(BIG_ONE, bigIntOf(100000000))))
        }
    }

    @Test
    fun `wide integral bounds retain original source values`() {
        val compiled = Mps.parse(
            "ROWS\n N COST\nCOLUMNS\n M0 'MARKER' 'INTORG'\n X COST 0\n" +
                " M1 'MARKER' 'INTEND'\nBOUNDS\n LO B X 9223372036854775809\nENDATA",
        ).toExactSourceProblem()
        val value = BigFraction.of(parseBigInt("9223372036854775809"), BIG_ONE)

        compiled.sourceWitness(listOf(value))

        assertTrue(compiled.model.intBounds.isOpenLower(0))
        assertFailsWith<MpsLoweringException> { compiled.sourceWitness(listOf(value - BigFraction.ONE)) }
    }

    @Test
    fun `objective equality preserves original decimal units and constant`() {
        val source = Mps.parse(
            "ROWS\n N COST\nCOLUMNS\n X COST 0.1\n Y COST 0.2\n" +
                "RHS\n R COST -0.3\nBOUNDS\n FR B X\n FR B Y\nENDATA",
        )
        val compiled = source.toExactSourceProblem()
        val values = listOf(BigFraction.ONE, BigFraction.ofLong(2))

        val witness = compiled.sourceWitness(values)

        assertEquals(BigFraction.of(bigIntOf(8), bigIntOf(10)), witness.objective)
        val row = compiled.model.factors.last().linearRows.single()
        val constants = row.constants as IntegralConstants
        val coordinates = values + witness.objective
        val activity = (0 until row.size).fold(BigFraction.ZERO) { sum, index ->
            sum + BigFraction.of(constants.exactCoeff(index), BIG_ONE) * coordinates[Term.realVar(row.ref(index))]
        }
        assertEquals(LinearOp.EQ, row.relation)
        assertEquals(BigFraction.of(constants.exactBound, BIG_ONE), activity)
    }

    @Test
    fun `exact source capture survives mutations of parser arrays`() {
        val source = Mps.parse("ROWS\n N COST\n E ROW\nCOLUMNS\n X ROW 0.1\nRHS\n R ROW 0.3\nENDATA")
        val compiled = source.toExactSourceProblem()
        source.constraints.single().coeffs[0] = 1.0

        val witness = compiled.sourceWitness(listOf(BigFraction.ofLong(3)))

        assertEquals(listOf(BigFraction.ofLong(3)), witness.values)
    }

    @Test
    fun `shared decimal denominators admit an ordinary dense source row`() {
        val text = "ROWS\n E R\nCOLUMNS\n" +
            (0 until 100).joinToString("\n") { " X$it R 0.0000000000000000001" } +
            "\nRHS\n RHS R 0.00000000000000001\nENDATA"
        val compiled = Mps.parse(text).toExactSourceProblem()

        val execution = OpenTheoryPipeline.execute(OpenTheoryRequest(compiled.model),
            TheoryParams(openWorkLimit = 10000))

        val sat = assertIs<OpenTheoryResult.Sat>(
            assertIs<OpenTheoryExecution.Satisfy>(execution).result,
        )
        compiled.sourceWitness(compiled.columns.map { column ->
            val parts = sat.assignment.realValue(column.id).split('/')
            BigFraction.of(parseBigInt(parts[0]), parts.getOrNull(1)?.let(::parseBigInt) ?: BIG_ONE)
        })
    }
}
