package com.eignex.klause.solver.pipeline

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.RationalPolynomial
import com.eignex.klause.arithmetic.real.ExactRealExpression
import com.eignex.klause.arithmetic.real.ExactRealValue
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SourceIntId
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.presolve.RebuildStep
import com.eignex.klause.presolve.SourceMapping
import com.eignex.klause.presolve.SourceRebuilds
import com.eignex.klause.presolve.TransformationGuarantees
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.search.AssembledSearchModel
import com.eignex.klause.solver.search.SearchBoolValue
import com.eignex.klause.solver.search.SearchComponent
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchExactAlgebraicRealValue
import com.eignex.klause.solver.search.SearchExactIntValue
import com.eignex.klause.solver.search.SearchExactRealExpressionValue
import com.eignex.klause.solver.search.SearchExactRealValue
import com.eignex.klause.solver.search.SearchModel
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.solver.search.contribute
import com.eignex.klause.util.Bits
import com.eignex.klause.util.parseBigInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SourceWitnessAssemblerTest {
    @Test
    fun `missing source coordinates withhold a witness`() {
        val source = Problem(
            numBoolVars = 1,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(1), null, null),
            factors = emptyArray(),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(1.0),
        )
        val values: Map<Any, Any> = mapOf(
            SearchBoolValue(0) to true,
            SearchExactIntValue(SourceIntId(0)) to parseBigInt("1"),
            SearchExactRealValue(SourceRealId(0)) to BigFraction.ONE,
        )
        val assembler = SourceWitnessAssembler(source)
        val mapping = SourceMapping(source, source, TransformationGuarantees.IDENTITY)

        for (missing in values.keys) {
            assertNull(assembler.assemble(AssembledSearchModel(values - missing), mapping))
        }
    }

    @Test
    fun `two exact representations of one real coordinate withhold a witness`() {
        val source = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(), longArrayOf(), null, null),
            factors = emptyArray(),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(1.0),
        )
        val values = AssembledSearchModel(mapOf(
            SearchExactRealValue(SourceRealId(0)) to BigFraction.ONE,
            SearchExactRealExpressionValue(SourceRealId(0)) to ExactRealValue(ExactRealExpression.Pi),
        ))

        val result = SourceWitnessAssembler(source).assemble(
            values, SourceMapping(source, source, TransformationGuarantees.IDENTITY),
        )

        assertNull(result)
    }

    @Test
    fun `foreign reconstruction mappings withhold a witness`() {
        val source = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(), longArrayOf(), null, null),
            factors = emptyArray(),
        )
        val foreign = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(), longArrayOf(), null, null),
            factors = emptyArray(),
        )

        val result = SourceWitnessAssembler(source).assemble(
            AssembledSearchModel(emptyMap()), SourceMapping(foreign, foreign, TransformationGuarantees.IDENTITY),
        )

        assertNull(result)
    }

    @Test
    fun `foreign original sources cannot relabel a reduced witness`() {
        val source = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(), longArrayOf(), null, null),
            factors = emptyArray(),
        )
        val foreign = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(), longArrayOf(), null, null),
            factors = emptyArray(),
        )

        val result = SourceWitnessAssembler(source).assemble(
            AssembledSearchModel(emptyMap()), SourceMapping(foreign, source, TransformationGuarantees.IDENTITY),
        )

        assertNull(result)
    }

    @Test
    fun `unmapped exact coordinates withhold a witness`() {
        val source = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(), longArrayOf(), null, null),
            factors = emptyArray(),
        )
        val model = AssembledSearchModel(mapOf(SearchExactIntValue(SourceIntId(0)) to parseBigInt("1")))

        val result = SourceWitnessAssembler(source).assemble(
            model, SourceMapping(source, source, TransformationGuarantees.IDENTITY),
        )

        assertNull(result)
    }

    @Test
    fun `neutral witness snapshots own their exact integer coordinates`() {
        val bools = booleanArrayOf(true)
        val ints = arrayOf(parseBigInt("9223372036854775808"))
        val witness = SourceCoordinateWitness(bools, ints, emptyList())

        bools[0] = false
        ints[0] = parseBigInt("0")

        assertEquals(true, witness.boolValue(0))
        assertEquals("9223372036854775808", witness.intValue(0))
    }

    @Test
    fun `typed contributions survive producer closure without a native assignment key`() {
        val source = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(
                longArrayOf(0), longArrayOf(0), null, Bits(1).also { it.set(0) },
            ),
            factors = emptyArray(),
        )
        var value = parseBigInt("9223372036854775808")
        val component = object : SearchComponent, AutoCloseable {
            override fun contributeModel(model: SearchModel, context: SearchContext) {
                model.contribute(SearchExactIntValue(SourceIntId(0)), value)
            }

            override fun close() {
                value = parseBigInt("0")
            }
        }
        val session = SearchSession(listOf(component))
        val result = assertNotNull(SourceWitnessAssembler(source).assemble(
            session.model(), SourceMapping(source, source, TransformationGuarantees.IDENTITY),
        ))

        component.close()

        assertEquals("9223372036854775808", result.intValue(0))
    }

    @Test
    fun `rational coordinates retain their exact fraction`() {
        val source = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(), longArrayOf(), null, null),
            factors = emptyArray(),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(1.0),
        )
        val third = BigFraction.ofLong(3).reciprocal()
        val model = AssembledSearchModel(mapOf(SearchExactRealValue(SourceRealId(0)) to third))

        val result = assertNotNull(SourceWitnessAssembler(source).assemble(
            model, SourceMapping(source, source, TransformationGuarantees.IDENTITY),
        ))

        assertEquals("1/3", result.realValue(0))
    }

    @Test
    fun `algebraic contributions retain the selected conjugate root`() {
        val arithmetic = AlgebraicArithmetic(PolynomialArithmetic())
        val root = arithmetic.roots(RationalPolynomial.of(-2, 0, 1)).last()
        val source = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(), longArrayOf(), null, null),
            factors = emptyArray(),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(2.0),
        )
        val model = AssembledSearchModel(mapOf(SearchExactAlgebraicRealValue(SourceRealId(0)) to root))

        val witness = assertNotNull(SourceWitnessAssembler(source).assemble(
            model, SourceMapping(source, source, TransformationGuarantees.IDENTITY),
        ))

        assertEquals("root(2;[-2,0,1])", witness.realValue(0))
        assertEquals(0, arithmetic.compare(root, assertNotNull(witness.algebraicRealValue(0))))
    }

    @Test
    fun `symbolic contributions retain authoritative expression text`() {
        val witness = SourceCoordinateWitness(booleanArrayOf(), emptyArray(), listOf(
            SourceRealCoordinate.Expression(ExactRealValue(ExactRealExpression.Pi)),
        ))

        assertEquals("pi", witness.realValue(0))
        assertEquals(ExactRealExpression.Pi, assertNotNull(witness.symbolicRealValue(0)).expression)
    }

    @Test
    fun `symbolic real contributions decline rational ray conversion`() {
        val witness = SourceCoordinateWitness(booleanArrayOf(), emptyArray(), listOf(
            SourceRealCoordinate.Expression(ExactRealValue(ExactRealExpression.Pi)),
        ))

        val point = witness.exactWitness(1)

        assertNull(point)
    }

    @Test
    fun `approximate compatibility coordinates decline rational ray conversion`() {
        val sample = Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(1.0))
        val assignments = listOf(OpenTheoryAssignment.Difference(sample), OpenTheoryAssignment.Sampled(sample))

        for (assignment in assignments) {
            assertNull(assignment.exactWitness(1))
        }
    }

    @Test
    fun `integer-only snapshots need no real payload for ray conversion`() {
        val assignment = OpenTheoryAssignment.Sampled(Sample(booleanArrayOf(), longArrayOf(3)))

        val point = assertNotNull(assignment.exactWitness(0))

        assertEquals(BigFraction.ofLong(3), point.at(0))
    }

    @Test
    fun `rational ray conversion preserves exact wide integer coordinates`() {
        val witness = SourceCoordinateWitness(
            booleanArrayOf(), arrayOf(parseBigInt("9223372036854775808")),
            listOf(SourceRealCoordinate.Rational(BigFraction.ofLong(3).reciprocal())),
        )

        val point = assertNotNull(witness.exactWitness(1))

        assertEquals(BigFraction.ofLong(3).reciprocal(), point.at(0))
        assertEquals("9223372036854775808", point.at(1).toString())
    }

    @Test
    fun `integer reconstruction retains arbitrary precision through affine steps`() {
        val open = Bits(2).also { it.set(0); it.set(1) }
        val source = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0, 0), longArrayOf(0, 0), null, open),
            factors = emptyArray(),
        )
        val rebuild = SourceRebuilds(listOf(RebuildStep.AffineValue(0, 1, intArrayOf(1), longArrayOf(2), 1)))
        val model = AssembledSearchModel(mapOf(
            SearchExactIntValue(SourceIntId(0)) to parseBigInt("0"),
            SearchExactIntValue(SourceIntId(1)) to parseBigInt("9223372036854775808"),
        ))

        val result = assertNotNull(SourceWitnessAssembler(source).assemble(
            model, SourceMapping(source, source, TransformationGuarantees.IDENTITY, rebuild),
        ))

        assertEquals("18446744073709551617", result.intValue(0))
    }

    @Test
    fun `source reconstruction uses the existing Boolean rebuild steps`() {
        val source = Problem(
            numBoolVars = 2,
            intBounds = IntBounds.fromModelBounds(longArrayOf(), longArrayOf(), null, null),
            factors = emptyArray(),
        )
        val rebuild = SourceRebuilds(listOf(RebuildStep.CopyLiteral(0, Lit.make(1, true))))
        val model = AssembledSearchModel(mapOf(SearchBoolValue(0) to false, SearchBoolValue(1) to true))

        val result = assertNotNull(SourceWitnessAssembler(source).assemble(
            model, SourceMapping(source, source, TransformationGuarantees.IDENTITY, rebuild),
        ))

        assertEquals(true, result.boolValue(0))
    }
}
