package com.eignex.klause.theory

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.Abs
import com.eignex.klause.factor.arithmetic.AbsAffineValue
import com.eignex.klause.factor.arithmetic.AffineAbs
import com.eignex.klause.factor.arithmetic.ContinuousProduct
import com.eignex.klause.factor.arithmetic.GridAbs
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.RealAbs
import com.eignex.klause.factor.arithmetic.RealProduct
import com.eignex.klause.formats.flatzinc.parseFlatZinc
import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceEvidenceCheck
import com.eignex.klause.solver.evidence.absSourceWitnessContext
import com.eignex.klause.solver.incumbent.Candidate
import com.eignex.klause.solver.incumbent.EngineInvocation
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.pipeline.ExactNeighborhoodCondition
import com.eignex.klause.solver.pipeline.ExactNeighborhoodResult
import com.eignex.klause.solver.pipeline.IntVariableOwner
import com.eignex.klause.solver.pipeline.OpenExactNeighborhood
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import com.eignex.klause.solver.pipeline.TheoryParams
import com.eignex.klause.solver.pipeline.algebraicRealValue
import com.eignex.klause.solver.pipeline.openWitnessVerifier
import com.eignex.klause.solver.pipeline.prepareExecutionPlan
import com.eignex.klause.solver.pipeline.toSample
import com.eignex.klause.theory.qfnra.PolynomialFormula
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.parseBigInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AbsEncodingTest {
    @Test
    fun `scalar absolute value source checks should preserve a wide exact output magnitude`() {
        val source = SmtLib.parse(
            "(declare-const x Int) (assert (= x (- 9223372036854775808))) " +
                "(assert (= (abs x) 9223372036854775808))",
        ).model
        val relation = source.factors.filterIsInstance<Abs>().single()
        val invocation = EngineInvocation(source.prepareExecutionPlan(), ModelIdentity.of(source))

        val result = assertIs<OpenTheoryResult.Sat>(BacktrackSolver.source(source).solve())
        val sample = result.assignment.toSample(source)

        assertEquals("-9223372036854775808", result.assignment.intValue(relation.operand))
        assertEquals("9223372036854775808", result.assignment.intValue(relation.result))
        assertEquals(SourceEvidenceCheck.Checked, invocation.checkWitness(sample, Cancellation.Never))
        assertIs<Verification.Accepted<*, *>>(openWitnessVerifier(source, null).verify(Candidate(sample, null)))
    }

    @Test
    fun `polynomial participation should agree with exact native absolute value checks`() {
        val source = Problem(
            0,
            2,
            Array(2) { IntDomain(-2, 2) },
            arrayOf<Factor>(
                Abs(0, 1),
                RealProduct(0, 0, 1, 0.0, 0.0),
            ),
            numRealVars = 2,
            realLower = doubleArrayOf(0.0, 0.0),
            realUpper = doubleArrayOf(0.0, 0.0),
        )
        val model = assertNotNull(source.prepareExecutionPlan().polynomialModel)
        for (input in -2L..2L) {
            for (output in -2L..2L) {
                val arithmetic = PolynomialArithmetic()
                val values = longArrayOf(input, output)
                val fixed = mutableMapOf(0 to BigFraction.ZERO, 1 to BigFraction.ZERO)
                for ((id, column) in model.integerColumns) fixed[column] = BigFraction.ofLong(values[id])
                val normalized = PolynomialFormula.normalize(model.service.formula, arithmetic, fixed)
                val signs = normalized.atoms.map { atom ->
                    assertTrue(atom.polynomial.variables.isEmpty())
                    val sign = (atom.polynomial.terms.singleOrNull()?.coefficient ?: BigFraction.ZERO)
                        .compareTo(BigFraction.ZERO)
                    if (atom.holds(sign)) PolynomialFormula.TRUE else PolynomialFormula.FALSE
                }.toIntArray()

                val truth = normalized.truth(intArrayOf(), signs, arithmetic)

                val expected = if (kotlin.math.abs(input) == output) PolynomialFormula.TRUE else PolynomialFormula.FALSE
                assertEquals(expected, truth)
            }
        }
    }

    @Test
    fun `absolute value should install a complete linear relation beyond its relaxation`() {
        val source = SmtLib.parse(
            "(declare-const x Int) (assert (= (abs x) 2)) " +
                "(assert (distinct x (- 2))) (assert (distinct x 2))",
        ).model
        val relation = source.factors.filterIsInstance<Abs>().single()

        val result = BacktrackSolver.source(source).solve()

        assertIs<LinearForm.Relaxation>(relation.linearForm)
        assertEquals(EncoderSupport.EQUIVALENT, BuiltInEncoderProvider.select(relation, EncodingTarget.LINEAR).support)
        assertIs<OpenTheoryResult.Unsat>(result)
    }

    @Test
    fun `refuted absolute value conditions should not contaminate their feasible sibling`() {
        val input = SmtLib.parse(
            "(declare-const b Bool) (declare-const x Int) " +
                "(assert (= (abs x) 2)) (assert (or b (= x 0)))",
        )
        val source = input.model
        val queries = OpenExactNeighborhood(source, TheoryParams(), preparedSource = source.prepareExecutionPlan())
        queries.use {
            val refuted = ExactNeighborhoodCondition(mapOf(0 to false), emptyMap(), emptyMap())
            val feasible = ExactNeighborhoodCondition(mapOf(0 to true), emptyMap(), emptyMap())
            assertIs<ExactNeighborhoodResult.Refuted>(it.check(refuted, Cancellation.Never))
            assertIs<ExactNeighborhoodResult.Undecided>(it.check(feasible, Cancellation { true }))

            val sample = assertIs<ExactNeighborhoodResult.Witness>(it.check(feasible, Cancellation.Never)).sample

            assertTrue(sample.boolValue(0))
            assertTrue(sample.exactIntValue(input.intVarNames.getValue("x")).toString() in setOf("-2", "2"))
        }
    }

    @Test
    fun `sign conditions should retract across exact absolute value queries`() {
        val input = SmtLib.parse(
            "(declare-const b Bool) (declare-const x Int) " +
                "(assert (= (abs x) 2)) (assert (= b (>= x 0)))",
        )
        val source = input.model
        val queries = OpenExactNeighborhood(source, TheoryParams(), preparedSource = source.prepareExecutionPlan())

        queries.use {
            for ((positive, expected) in listOf(true to "2", false to "-2", true to "2")) {
                val outcome = it.check(
                    ExactNeighborhoodCondition(mapOf(0 to positive), emptyMap(), emptyMap()),
                    Cancellation.Never,
                )
                val sample = assertIs<ExactNeighborhoodResult.Witness>(outcome).sample

                assertEquals(expected, sample.exactIntValue(input.intVarNames.getValue("x")).toString())
            }
        }
    }

    @Test
    fun `polynomial grid participation should preserve the complete finite image`() {
        val relation = GridAbs(0, 1, -1.0, 1.0, 5, 0.0, 0.8, 5)
        val source = Problem(
            0,
            2,
            Array(2) { IntDomain(0, 4) },
            arrayOf<Factor>(
                relation,
                RealProduct(0, 0, 1, 0.0, 0.0),
            ),
            numRealVars = 2,
            realLower = doubleArrayOf(0.0, 0.0),
            realUpper = doubleArrayOf(0.0, 0.0),
        )
        val model = assertNotNull(source.prepareExecutionPlan().polynomialModel)
        assertEquals(0, model.encodingAttributions.first().sourceFactorIndex)
        for (input in 0L..4L) {
            for (output in 0L..4L) {
                val arithmetic = PolynomialArithmetic()
                val values = longArrayOf(input, output)
                val fixed = mutableMapOf(0 to BigFraction.ZERO, 1 to BigFraction.ZERO)
                for ((id, column) in model.integerColumns) fixed[column] = BigFraction.ofLong(values[id])
                val normalized = PolynomialFormula.normalize(model.service.formula, arithmetic, fixed)
                val signs = normalized.atoms.map { atom ->
                    assertTrue(atom.polynomial.variables.isEmpty())
                    val sign = (atom.polynomial.terms.singleOrNull()?.coefficient ?: BigFraction.ZERO)
                        .compareTo(BigFraction.ZERO)
                    if (atom.holds(sign)) PolynomialFormula.TRUE else PolynomialFormula.FALSE
                }.toIntArray()

                val truth = normalized.truth(intArrayOf(), signs, arithmetic)

                assertEquals(
                    if (output == relation.image[input.toInt()].toLong()) {
                        PolynomialFormula.TRUE
                    } else {
                        PolynomialFormula.FALSE
                    },
                    truth,
                )
            }
        }
    }

    @Test
    fun `polynomial completion should preserve exact algebraic absolute values`() {
        val source = Problem(
            0,
            0,
            emptyArray(),
            arrayOf<Factor>(
                ContinuousProduct(0, 0, 2),
                RealAbs(0, 1),
                Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0), LinearOp.LE, -1.0),
            ),
            numRealVars = 3,
            realLower = doubleArrayOf(-2.0, 0.0, 2.0),
            realUpper = doubleArrayOf(0.0, 2.0, 2.0),
        )

        val result = assertIs<OpenTheoryResult.Sat>(BacktrackSolver.source(source).solve())

        val arithmetic = AlgebraicArithmetic()
        val operand = assertNotNull(result.assignment.algebraicRealValue(0))
        val magnitude = assertNotNull(result.assignment.algebraicRealValue(1))
        assertEquals(0, arithmetic.compare(arithmetic.negate(operand), magnitude))
        assertTrue(arithmetic.compare(magnitude, arithmetic.rational(BigFraction.ONE)) > 0)
    }

    @Test
    fun `real absolute value should preserve finite integer image ownership`() {
        val parsed = parseFlatZinc(
            """var -2..2: n; var float: x; var float: y;
                constraint int2float(n,x); constraint float_abs(x,y);
                constraint float_eq(y,1.0); solve satisfy;""",
            exactFloats = true,
        )
        val source = parsed.problem
        val plan = source.prepareExecutionPlan()
        val integer = parsed.intVarsByName.getValue("n")
        val relation = source.factors.filterIsInstance<RealAbs>().single()

        val result = assertIs<OpenTheoryResult.Sat>(BacktrackSolver.source(source).solve())

        assertEquals(IntVariableOwner.CP, assertNotNull(plan.componentPlan).intOwner(integer))
        assertTrue(
            result.assignment.intValue(integer) in setOf("-1", "1"),
            "n=${result.assignment.intValue(integer)}, x=${result.assignment.realValue(relation.operand)}, " +
                "y=${result.assignment.realValue(relation.result)}",
        )
        assertEquals("1", result.assignment.realValue(relation.result))
    }

    @Test
    fun `exact FlatZinc absolute value should retain the native relation at a rational point`() {
        val parsed = parseFlatZinc(
            """var -2.0..2.0: x; var 0.0..2.0: y;
                constraint float_abs(x,y); constraint float_lin_eq([3.0],[x],-1.0); solve satisfy;""",
            exactFloats = true,
        )
        val relation = parsed.problem.factors.filterIsInstance<RealAbs>().single()

        val result = assertIs<OpenTheoryResult.Sat>(BacktrackSolver.source(parsed.problem).solve())

        assertEquals("-1/3", result.assignment.realValue(relation.operand))
        assertEquals("1/3", result.assignment.realValue(relation.result))
    }

    @Test
    fun `exact real absolute value should reject a point permitted by its relaxation`() {
        val parsed = parseFlatZinc(
            """var -2.0..2.0: x; var 0.0..2.0: y; constraint float_abs(x,y);
                constraint float_eq(x,0.0); constraint float_eq(y,1.0); solve satisfy;""",
            exactFloats = true,
        )
        assertIs<LinearForm.Relaxation>(parsed.problem.factors.filterIsInstance<RealAbs>().single().linearForm)

        val result = BacktrackSolver.source(parsed.problem).solve()

        assertIs<OpenTheoryResult.Unsat>(result)
    }

    @Test
    fun `real absolute value aliases should exclude negative source coordinates`() {
        val parsed = parseFlatZinc(
            """var -2.0..2.0: x; constraint float_abs(x,x);
                constraint float_lt(x,0.0); solve satisfy;""",
            exactFloats = true,
        )

        val result = BacktrackSolver.source(parsed.problem).solve()

        assertIs<OpenTheoryResult.Unsat>(result)
    }

    @Test
    fun `real absolute value should retract a contradictory reified source condition`() {
        val parsed = parseFlatZinc(
            """var bool: zero; var -2.0..2.0: x; var 0.0..2.0: y;
                constraint float_abs(x,y); constraint float_eq(y,1.0);
                constraint float_eq_reif(x,0.0,zero); solve satisfy;""",
            exactFloats = true,
        )
        val source = parsed.problem
        val relation = source.factors.filterIsInstance<RealAbs>().single()
        val boolean = parsed.boolVarsByName.getValue("zero")
        val queries = OpenExactNeighborhood(source, TheoryParams(), preparedSource = source.prepareExecutionPlan())
        queries.use {
            val refuted = ExactNeighborhoodCondition(mapOf(boolean to true), emptyMap(), emptyMap())
            val feasible = ExactNeighborhoodCondition(mapOf(boolean to false), emptyMap(), emptyMap())
            assertIs<ExactNeighborhoodResult.Refuted>(it.check(refuted, Cancellation.Never))
            assertIs<ExactNeighborhoodResult.Undecided>(it.check(feasible, Cancellation { true }))

            val sample = assertIs<ExactNeighborhoodResult.Witness>(it.check(feasible, Cancellation.Never)).sample

            val values = assertNotNull(sample.exactReals)
            assertEquals(BigFraction.ONE, values[relation.result])
            assertTrue(values[relation.operand] in listOf(BigFraction.ONE, BigFraction.MINUS_ONE))
            assertEquals(source.numRealVars, values.size)
        }
    }

    @Test
    fun `wide absolute value should reject a joint conflict admitted by its implied inequalities`() {
        val source = SmtLib.parse(
            "(declare-const x Int) " +
                "(assert (= (abs (* 9223372036854775808 x)) 9223372036854775808)) " +
                "(assert (distinct x (- 1))) (assert (distinct x 1))",
        ).model
        val relation = source.factors.filterIsInstance<AffineAbs>().single()

        val result = BacktrackSolver.source(source).solve()

        assertIs<LinearForm.Relaxation>(relation.linearForm)
        assertIs<OpenTheoryResult.Unsat>(result)
    }

    @Test
    fun `wide absolute value conditions should retract across refutation cancellation and feasible siblings`() {
        val input = SmtLib.parse(
            "(declare-const b Bool) (declare-const x Int) " +
                "(assert (= (abs (* 9223372036854775808 x)) 9223372036854775808)) " +
                "(assert (or b (= x 0)))",
        )
        val source = input.model
        val prepared = source.prepareExecutionPlan()
        val binding = EngineInvocation(prepared, ModelIdentity.of(source))
        val context = assertNotNull(source.absSourceWitnessContext(LinearObjective(), Cancellation.Never))
        val sourceChecker = assertNotNull(context.semanticsChecker)
        val queries = OpenExactNeighborhood(source, TheoryParams(), preparedSource = prepared, invocation = { binding })
        queries.use {
            val refuted = ExactNeighborhoodCondition(mapOf(0 to false), emptyMap(), emptyMap())
            val feasible = ExactNeighborhoodCondition(mapOf(0 to true), emptyMap(), emptyMap())
            assertIs<ExactNeighborhoodResult.Refuted>(it.check(refuted, Cancellation.Never))
            assertIs<ExactNeighborhoodResult.Undecided>(it.check(feasible, Cancellation { true }))

            val sample = assertIs<ExactNeighborhoodResult.Witness>(it.check(feasible, Cancellation.Never)).sample

            assertTrue(sample.boolValue(0))
            assertTrue(sample.exactIntValue(input.intVarNames.getValue("x")).toString() in setOf("-1", "1"))
            assertTrue(source.factors.filterIsInstance<AffineAbs>().single().isSatisfied(sample::exactIntValue))
            assertEquals(SourceEvidenceCheck.Checked, binding.checkWitness(sample, Cancellation.Never))
            val rejected = sample.copy(bools = sample.bools.also { values -> values[0] = false })
            assertEquals(
                SourceEvidenceCheck.Checked,
                sourceChecker.witness(sample, context.scope, Cancellation.Never),
            )
            assertIs<SourceEvidenceCheck.Rejected>(
                sourceChecker.witness(rejected, context.scope, Cancellation.Never),
            )
        }
    }

    @Test
    fun `wide absolute value polynomial participation should preserve the complete relation`() {
        val scale = parseBigInt("9223372036854775808")
        val source = Problem(
            0,
            2,
            Array(2) { IntDomain(-2, 2) },
            arrayOf<Factor>(
                AffineAbs(AbsAffineValue(mapOf(0 to scale), BIG_ZERO), AbsAffineValue(mapOf(1 to scale), BIG_ZERO)),
                RealProduct(0, 0, 1, 0.0, 0.0),
            ),
            numRealVars = 2,
            realLower = doubleArrayOf(0.0, 0.0),
            realUpper = doubleArrayOf(0.0, 0.0),
        )
        val model = assertNotNull(source.prepareExecutionPlan().polynomialModel)
        assertEquals(0, model.encodingAttributions.first().sourceFactorIndex)
        for (input in -2L..2L) {
            for (output in -2L..2L) {
                val arithmetic = PolynomialArithmetic()
                val values = longArrayOf(input, output)
                val fixed = mutableMapOf(0 to BigFraction.ZERO, 1 to BigFraction.ZERO)
                for ((id, column) in model.integerColumns) fixed[column] = BigFraction.ofLong(values[id])
                val normalized = PolynomialFormula.normalize(model.service.formula, arithmetic, fixed)
                val signs = normalized.atoms.map { atom ->
                    assertTrue(atom.polynomial.variables.isEmpty())
                    val sign = (atom.polynomial.terms.singleOrNull()?.coefficient ?: BigFraction.ZERO)
                        .compareTo(BigFraction.ZERO)
                    if (atom.holds(sign)) PolynomialFormula.TRUE else PolynomialFormula.FALSE
                }.toIntArray()

                val truth = normalized.truth(intArrayOf(), signs, arithmetic)

                assertEquals(
                    if (kotlin.math.abs(input) == output) PolynomialFormula.TRUE else PolynomialFormula.FALSE,
                    truth,
                )
            }
        }
    }
}
