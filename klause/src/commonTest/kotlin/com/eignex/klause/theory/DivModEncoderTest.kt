package com.eignex.klause.theory

import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.factor.arithmetic.DivMod
import com.eignex.klause.factor.arithmetic.DivisionConvention
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.RealProduct
import com.eignex.klause.factor.arithmetic.WideDivMod
import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.PolynomialExpression
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SourceIntId
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.lowering.divModPolynomialFormula
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.solver.pipeline.ExactNeighborhoodCondition
import com.eignex.klause.solver.pipeline.ExactNeighborhoodResult
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.OpenExactNeighborhood
import com.eignex.klause.solver.pipeline.OpenTheoryPipeline
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import com.eignex.klause.solver.pipeline.TheoryParams
import com.eignex.klause.solver.pipeline.execute
import com.eignex.klause.solver.pipeline.polynomialRequest
import com.eignex.klause.solver.pipeline.prepareExecutionPlan
import com.eignex.klause.theory.qfnra.PolynomialFormula
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.parseBigInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DivModEncoderTest {
    @Test
    fun `SMT minimum word division should retain its exact over-word quotient`() {
        val parsed = SmtLib.parse(
            """
            (declare-const a Int) (assert (= a (- 9223372036854775808)))
            (assert (= (div a (- 1)) 9223372036854775808))
            (assert (= (mod a (- 1)) 0))
            """.trimIndent(),
        )
        val source = parsed.model
        val relation = source.factors.filterIsInstance<DivMod>().single()
        val queries = OpenExactNeighborhood(source, TheoryParams(), preparedSource = source.prepareExecutionPlan())

        val sample = queries.use {
            assertIs<ExactNeighborhoodResult.Witness>(
                it.check(
                    ExactNeighborhoodCondition(emptyMap(), emptyMap(), emptyMap()),
                    Cancellation.Never,
                ),
            ).sample
        }

        assertEquals(bigIntOf(Long.MIN_VALUE), sample.exactIntValue(parsed.intVarNames.getValue("a")))
        assertEquals(parseBigInt("9223372036854775808"), sample.exactIntValue(relation.quotient))
    }

    @Test
    fun `variable divisor linear installation should require and attribute its fixing`() {
        val factor = DivMod(0, 1, 2, 3)
        val source = Problem(0, 4, Array(4) { IntDomain(-8, 8) }, arrayOf<Factor>(factor))
        val prepared = FactorProjectionProviders.Default.prepare(source)

        assertEquals(EncoderSupport.CONDITIONED, prepared.encoder(0, EncodingTarget.LINEAR).support)
        assertEquals(EncoderSupport.EQUIVALENT, prepared.encoder(0, EncodingTarget.POLYNOMIAL).support)
        assertEquals(EncoderSupport.UNSUPPORTED, prepared.encoder(0, EncodingTarget.DIFFERENCE).support)
        assertNull(prepared.linearTheoryModel(EncodingTarget.LINEAR))
        val installed = assertNotNull(
            prepared.linearTheoryModel(
                EncodingTarget.LINEAR,
                fixedIntegers = mapOf(SourceIntId(1) to bigIntOf(-3)),
            ),
        )
        assertTrue(
            installed.factors.all {
                (it as EncodedTheoryFactor).attribution.integerConditions ==
                    mapOf(SourceIntId(1) to bigIntOf(-3))
            },
        )
    }

    @Test
    fun `correlated theory outputs should conflict with a joint dividend equality`() {
        val parsed = SmtLib.parse(
            """
            (declare-const a Int) (assert (= (div a 3) 2))
            (assert (= (mod a 3) 2)) (assert (= a 7))
            """.trimIndent(),
        )
        val plan = parsed.model.prepareExecutionPlan()
        val queries = OpenExactNeighborhood(parsed.model, TheoryParams(), preparedSource = plan)

        val result = queries.use {
            it.check(
                ExactNeighborhoodCondition(emptyMap(), emptyMap(), emptyMap()),
                Cancellation.Never,
            )
        }

        assertIs<ExactNeighborhoodResult.Refuted>(result)
    }

    @Test
    fun `theory operand windows should retract before checking a sibling`() {
        val parsed = SmtLib.parse("(declare-const a Int) (assert (= (div a 3) 2))")
        val source = parsed.model
        val index = parsed.intVarNames.getValue("a")
        val factor = source.factors.filterIsInstance<DivMod>().single()
        val plan = source.prepareExecutionPlan()
        val queries = OpenExactNeighborhood(source, TheoryParams(), preparedSource = plan)
        queries.use {
            val five = mapOf(index to BigFraction.ofLong(5))
            assertIs<ExactNeighborhoodResult.Refuted>(
                it.check(
                    ExactNeighborhoodCondition(emptyMap(), five, five),
                    Cancellation.Never,
                ),
            )
            val eight = mapOf(index to BigFraction.ofLong(8))
            val sample = assertIs<ExactNeighborhoodResult.Witness>(
                it.check(
                    ExactNeighborhoodCondition(emptyMap(), eight, eight),
                    Cancellation.Never,
                ),
            ).sample

            assertEquals(8L, sample.intValue(index))
            assertEquals(2L, sample.intValue(factor.remainder))
        }
    }

    @Test
    fun `wide Euclidean source terms should retain their original exact correlation`() {
        val parsed = SmtLib.parse(
            """
            (declare-const a Int) (assert (<= 0 a 2))
            (assert (= (mod (+ 1000000000000000000000000000000 a) 1000000000000000000000000000000) 2))
            """.trimIndent(),
        )
        val source = parsed.model
        val factor = source.factors.filterIsInstance<WideDivMod>().single()
        val queries = OpenExactNeighborhood(source, TheoryParams(), preparedSource = source.prepareExecutionPlan())

        val sample = queries.use {
            assertIs<ExactNeighborhoodResult.Witness>(
                it.check(
                    ExactNeighborhoodCondition(emptyMap(), emptyMap(), emptyMap()),
                    Cancellation.Never,
                ),
            ).sample
        }

        assertEquals(DivisionConvention.EUCLIDEAN, factor.convention)
        assertEquals(bigIntOf(2), sample.exactIntValue(parsed.intVarNames.getValue("a")))
    }

    @Test
    fun `interrupted formula expansion should discard the complete relation scope`() {
        val source = Problem(
            0,
            3,
            Array(3) { IntDomain(-8, 8) },
            arrayOf<Factor>(DivMod(0, bigIntOf(3), 1, 2)),
        )
        var work = 0L
        val stop = object : Cancellation {
            override fun isCancelled(): Boolean = work > 0L
            override fun workMeter(): WorkMeter = WorkMeter { work += it }
        }
        var decline: EncoderDecline? = null
        val prepared = FactorProjectionProviders.Default.prepare(source)

        val installed = prepared.linearTheoryModel(
            EncodingTarget.LINEAR,
            cancellation = stop,
            onDecline = { decline = it },
        )

        assertTrue(work > 0L)
        assertNull(installed)
        assertEquals(EncoderDecline.RESOURCE, decline)
    }

    @Test
    fun `polynomial encoding should enforce signed variable division and zero exclusion`() {
        for (convention in DivisionConvention.entries) {
            for (values in listOf(
                longArrayOf(-7, 3, -2, -1),
                longArrayOf(-7, 3, -3, 2),
                longArrayOf(-7, -3, 2, -1),
                longArrayOf(-7, -3, 3, 2),
                longArrayOf(7, -3, -2, 1),
                longArrayOf(0, 0, 0, 0),
                longArrayOf(7, 3, 1, 4),
                longArrayOf(7, 3, 2, 2),
            )) {
                val factor = DivMod(0, 1, 2, 3, convention)
                val formula = divModPolynomialFormula(factor) { PolynomialExpression.Variable(SourceRealId(it)) }
                val arithmetic = PolynomialArithmetic()
                val normalized = PolynomialFormula.normalize(
                    formula,
                    arithmetic,
                    values.indices.associateWith { BigFraction.ofLong(values[it]) },
                )
                val signs = normalized.atoms.map { atom ->
                    assertTrue(atom.polynomial.variables.isEmpty())
                    val sign = (atom.polynomial.terms.singleOrNull()?.coefficient ?: BigFraction.ZERO)
                        .compareTo(BigFraction.ZERO)
                    if (atom.holds(sign)) PolynomialFormula.TRUE else PolynomialFormula.FALSE
                }.toIntArray()
                val valid = if (values[1] == 0L) {
                    false
                } else {
                    val remainder = if (convention == DivisionConvention.TRUNCATING) {
                        values[0] % values[1]
                    } else {
                        (values[0] % values[1] + kotlin.math.abs(values[1])) % kotlin.math.abs(values[1])
                    }
                    values[3] == remainder && values[0] == values[1] * values[2] + remainder
                }

                assertEquals(
                    if (valid) PolynomialFormula.TRUE else PolynomialFormula.FALSE,
                    normalized.truth(intArrayOf(), signs, arithmetic),
                    "$convention ${values.contentToString()}",
                )
            }
        }
    }

    @Test
    fun `shared polynomial completion should correlate a variable divisor with a real constraint`() {
        val source = Problem(
            0,
            4,
            arrayOf(IntDomain(7, 7), IntDomain(2, 3), IntDomain(2, 3), IntDomain(1, 1)),
            arrayOf<Factor>(DivMod(0, 1, 2, 3), RealProduct(2, 0, 1, 1.0, 1.0)),
            numRealVars = 2,
            realLower = doubleArrayOf(1.0, 3.0),
            realUpper = doubleArrayOf(1.0, 3.0),
        )
        val plan = source.prepareExecutionPlan()

        val result = OpenTheoryPipeline.execute(plan.polynomialRequest(), engine = FiniteEngine.BACKTRACK).result

        assertTrue(assertNotNull(plan.polynomialModel).encodingAttributions.any { it.sourceFactorIndex == 0 })
        assertEquals("2", assertIs<OpenTheoryResult.Sat>(result).assignment.intValue(1))
    }

    @Test
    fun `joint polynomial constraints should refute an incompatible source divisor fixing`() {
        val source = Problem(
            0,
            4,
            arrayOf(IntDomain(7, 7), IntDomain(2, 3), IntDomain(2, 3), IntDomain(1, 1)),
            arrayOf<Factor>(
                DivMod(0, 1, 2, 3),
                RealProduct(2, 0, 1, 1.0, 1.0),
                Linear(intArrayOf(1), intArrayOf(1), LinearOp.EQ, 3),
            ),
            numRealVars = 2,
            realLower = doubleArrayOf(1.0, 3.0),
            realUpper = doubleArrayOf(1.0, 3.0),
        )
        val plan = source.prepareExecutionPlan()

        val result = OpenTheoryPipeline.execute(plan.polynomialRequest(), engine = FiniteEngine.BACKTRACK).result

        assertIs<OpenTheoryResult.Unsat>(result)
    }

    @Test
    fun `noninjective remapping should retain every polynomial role`() {
        val mapping = VarRemap(intArrayOf(), intArrayOf(0, 1, 0, 1))
        val factor = DivMod(0, 1, 2, 3).remap(mapping) as DivMod
        val formula = divModPolynomialFormula(factor) { PolynomialExpression.Variable(SourceRealId(it)) }
        for (a in -1L..1L) {
            for (b in -1L..1L) {
                val arithmetic = PolynomialArithmetic()
                val normalized = PolynomialFormula.normalize(
                    formula,
                    arithmetic,
                    mapOf(0 to BigFraction.ofLong(a), 1 to BigFraction.ofLong(b)),
                )
                val signs = normalized.atoms.map { atom ->
                    val sign = (atom.polynomial.terms.singleOrNull()?.coefficient ?: BigFraction.ZERO)
                        .compareTo(BigFraction.ZERO)
                    if (atom.holds(sign)) PolynomialFormula.TRUE else PolynomialFormula.FALSE
                }.toIntArray()
                val valid = b != 0L && a / b == a && a % b == b

                assertEquals(
                    if (valid) PolynomialFormula.TRUE else PolynomialFormula.FALSE,
                    normalized.truth(intArrayOf(), signs, arithmetic),
                    "$a $b",
                )
            }
        }
    }
}
