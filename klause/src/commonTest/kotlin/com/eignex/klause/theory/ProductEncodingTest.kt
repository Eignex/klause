package com.eignex.klause.theory

import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.factor.arithmetic.ContinuousProduct
import com.eignex.klause.factor.arithmetic.RealProduct
import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.ir.PolynomialExpression
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.factor.compressViolation
import com.eignex.klause.localsearch.LocalSearchModel
import com.eignex.klause.localsearch.LocalSearchProblem
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntegralConstants
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SourceIntId
import com.eignex.klause.ir.Term
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.solver.pipeline.prepareExecutionPlan
import com.eignex.klause.theory.qfnra.PolynomialFormula
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.bigIntOf
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProductEncodingTest {
    @Test
    fun `remapped polynomial products preserve every source role`() {
        val original = ContinuousProduct(2, 3, 4)
        for (roles in listOf(Triple(0, 0, 1), Triple(0, 1, 0), Triple(1, 0, 0), Triple(0, 0, 0))) {
            val (a, b, result) = roles
            val mapped = original.remap(VarRemap(intArrayOf(), intArrayOf(), intArrayOf(-1, -1, a, b, result)))
            val source = Problem(0, 0, emptyArray(), arrayOf(mapped), numRealVars = 2,
                realLower = doubleArrayOf(-10.0, -10.0), realUpper = doubleArrayOf(10.0, 10.0))
            val providers = FactorProjectionProviders(encoder = EncoderProvider { factor, target ->
                val selected = BuiltInEncoderProvider.select(factor, target)
                if (factor !== mapped || target != EncodingTarget.POLYNOMIAL) selected
                else Encoder(EncoderSupport.EQUIVALENT) { view, encoding -> selected.install(view, encoding) }
            })
            val model = assertNotNull(source.prepareExecutionPlan(providers).polynomialModel)
            assertEquals(0, model.encodingAttributions.single().sourceFactorIndex)
            for (x in -1L..3L) for (y in -1L..3L) {
                val values = longArrayOf(x, y)
                val arithmetic = PolynomialArithmetic()
                val normalized = PolynomialFormula.normalize(model.service.formula, arithmetic,
                    mapOf(0 to BigFraction.ofLong(x), 1 to BigFraction.ofLong(y)))
                val signs = normalized.atoms.map { atom ->
                    assertTrue(atom.polynomial.variables.isEmpty())
                    val sign = (atom.polynomial.terms.singleOrNull()?.coefficient ?: BigFraction.ZERO)
                        .compareTo(BigFraction.ZERO)
                    if (atom.holds(sign)) PolynomialFormula.TRUE else PolynomialFormula.FALSE
                }.toIntArray()

                val truth = normalized.truth(intArrayOf(), signs, arithmetic)

                assertEquals(if (values[a] * values[b] == values[result]) PolynomialFormula.TRUE
                    else PolynomialFormula.FALSE, truth, "$roles $x $y")
            }
        }
    }

    @Test
    fun `mixed product mappings keep integer and aliased real roles distinct`() {
        val source = Problem(0, 1, arrayOf(IntDomain(0, 3)), arrayOf<Factor>(RealProduct(0, 0, 0, -5.0, 5.0)),
            numRealVars = 1, realLower = doubleArrayOf(-5.0), realUpper = doubleArrayOf(5.0))
        val model = assertNotNull(source.prepareExecutionPlan().polynomialModel)
        val mapping = model.encodingAttributions.single()
        val integer = mapping.integerEmbeddings.getValue(SourceIntId(0)).index
        assertTrue(integer != 0)
        for (k in 0L..3L) for (x in -1L..2L) {
            val arithmetic = PolynomialArithmetic()
            val normalized = PolynomialFormula.normalize(model.service.formula, arithmetic,
                mapOf(0 to BigFraction.ofLong(x), integer to BigFraction.ofLong(k)))
            val signs = normalized.atoms.map { atom ->
                assertTrue(atom.polynomial.variables.isEmpty())
                val sign = (atom.polynomial.terms.singleOrNull()?.coefficient ?: BigFraction.ZERO)
                    .compareTo(BigFraction.ZERO)
                if (atom.holds(sign)) PolynomialFormula.TRUE else PolynomialFormula.FALSE
            }.toIntArray()

            val truth = normalized.truth(intArrayOf(), signs, arithmetic)

            assertEquals(if (k * x == x) PolynomialFormula.TRUE else PolynomialFormula.FALSE, truth, "$k $x")
        }
    }

    @Test
    fun `conditioned linear fallback preserves aliased operand and result terms`() {
        val source = Problem(0, 1, arrayOf(IntDomain(0, 3)), arrayOf<Factor>(RealProduct(0, 0, 0, -5.0, 5.0)),
            numRealVars = 1, realLower = doubleArrayOf(-5.0), realUpper = doubleArrayOf(5.0))
        val prepared = FactorProjectionProviders.Default.prepare(source)
        for (k in 0L..3L) {
            val fragment = assertNotNull(prepared.linearTheoryModel(EncodingTarget.LINEAR,
                fixedIntegers = mapOf(SourceIntId(0) to bigIntOf(k))))
            val row = fragment.factors.flatMap { assertNotNull(it.linearForm).rows }.single()
            val constants = row.constants as IntegralConstants
            for (x in -1L..2L) {
                var activity = BigFraction.ZERO
                for (term in 0 until row.size) {
                    assertEquals(Term.ofRealVar(0), row.ref(term))
                    activity += BigFraction.of(constants.exactCoeff(term), BIG_ONE) * BigFraction.ofLong(x)
                }

                assertEquals(BigFraction.ofLong(x - k * x), activity, "$k $x")
            }
        }
    }

    @Test
    fun `finite polynomial participation preserves aliased product source grading`() {
        for (roles in listOf(Triple(0, 0, 1), Triple(0, 1, 0), Triple(1, 0, 0), Triple(0, 0, 0))) {
            val (a, b, result) = roles
            val original = Product(2, 3, 4)
            val product = original.remap(VarRemap(intArrayOf(), intArrayOf(-1, -1, a, b, result)))
            val source = Problem(0, 2, Array(2) { IntDomain(-1, 3).excludeValue(2) }, arrayOf<Factor>(
                product, RealProduct(0, 0, 1, -5.0, 5.0),
            ), numRealVars = 2, realLower = doubleArrayOf(-5.0, -5.0), realUpper = doubleArrayOf(5.0, 5.0))
            val providers = FactorProjectionProviders(encoder = EncoderProvider { factor, target ->
                if (factor !is Product || target != EncodingTarget.POLYNOMIAL)
                    BuiltInEncoderProvider.select(factor, target)
                else Encoder(EncoderSupport.EQUIVALENT) { view, encoding ->
                    view as Product
                    encoding.polynomial(PolynomialRealFormula.Atom(
                        PolynomialExpression.Product(listOf(encoding.integer(SourceIntId(view.a)),
                            encoding.integer(SourceIntId(view.b)))), PolynomialRelation.EQ,
                        encoding.integer(SourceIntId(view.result))))
                    null
                }
            })
            val plan = source.prepareExecutionPlan(providers)
            val model = assertNotNull(plan.polynomialModel)
            val state = LocalSearchState(LocalSearchModel.open(model.finite), Random(1),
                projection = LocalSearchProblem(model.finite, assertNotNull(plan.finiteFactorProjections)))
            assertEquals(listOf(0), model.finiteFactorIndices)
            assertEquals(source.numIntVars, model.finite.numIntVars)
            for (id in 0 until source.numIntVars) {
                assertEquals(source.declaredIntDomains[id], model.finite.declaredIntDomains[id])
            }
            assertEquals(0, model.encodingAttributions.first().sourceFactorIndex)
            for (x in listOf(-1L, 0L, 1L, 3L)) for (y in listOf(-1L, 0L, 1L, 3L)) {
                val values = longArrayOf(x, y)
                val arithmetic = PolynomialArithmetic()
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
                state.assignment.setInt(0, x)
                state.assignment.setInt(1, y)
                state.recompute()

                val residual = kotlin.math.abs(values[a] * values[b] - values[result])
                assertEquals(compressViolation(residual, state.violationSoftCap).toLong(), state.cost, "$roles $x $y")
                assertEquals(if (state.cost == 0L) PolynomialFormula.TRUE
                    else PolynomialFormula.FALSE, truth, "$roles $x $y")
            }
        }
    }

    @Test
    fun `scoped linear fallback preserves product roles after remapping`() {
        val original = Product(2, 3, 4)
        val source = Problem(0, 5, Array(5) { IntDomain(-2, 3).excludeValue(-1) }, arrayOf<Factor>(original))
        val providers = FactorProjectionProviders(encoder = EncoderProvider { factor, target ->
            if (factor !== original || target != EncodingTarget.LINEAR) BuiltInEncoderProvider.select(factor, target)
            else Encoder(EncoderSupport.CONDITIONED, listOf(SourceIntId(original.b))) { view, encoding ->
                view as Product
                val scale = assertNotNull(encoding.fixedInteger(SourceIntId(view.b)))
                val product = encoding.auxiliary()
                encoding.define(product, EncodingAffineExpression(listOf(
                    EncodingLinearTerm.source(Term.ofIntVar(view.a), BigFraction.of(scale, BIG_ONE)),
                )))
                encoding.linearFormula(LinearEncodingFormula.Comparison(EncodingAffineExpression(listOf(
                    EncodingLinearTerm.source(Term.ofIntVar(view.result), BigFraction.ONE),
                    EncodingLinearTerm.auxiliary(product, BigFraction.MINUS_ONE),
                )), LinearOp.EQ, BigFraction.ZERO))
                null
            }
        })
        val selected = providers.prepare(source)
        for (roles in listOf(Triple(0, 0, 1), Triple(0, 1, 0), Triple(1, 0, 0), Triple(0, 0, 0))) {
            val (a, b, result) = roles
            val mapping = VarRemap(intArrayOf(), intArrayOf(-1, -1, a, b, result))
            val view = Problem(0, 2, Array(2) { IntDomain(-2, 3).excludeValue(-1) },
                arrayOf(original.remap(mapping)))
            val fragment = assertNotNull(selected.projected(view, intArrayOf(0), mapping)
                .linearTheoryModel(EncodingTarget.LINEAR, fixedIntegers = mapOf(SourceIntId(b) to bigIntOf(2))))
            val encoded = fragment.factors.single() as EncodedTheoryFactor
            assertEquals(0, encoded.attribution.sourceFactorIndex)
            assertEquals(1, encoded.attribution.affineAuxiliaries.size)
            for (x in listOf(-2L, 0L, 1L, 2L, 3L)) for (y in listOf(-2L, 0L, 1L, 2L, 3L)) {
                val values = longArrayOf(x, y)
                if (values[b] != 2L) continue
                val row = assertNotNull(encoded.linearForm).rows.single()
                val constants = row.constants as IntegralConstants
                var activity = BigFraction.ZERO
                for (term in 0 until row.size) {
                    assertTrue(Term.isInt(row.ref(term)))
                    activity += BigFraction.of(constants.exactCoeff(term), BIG_ONE) *
                        BigFraction.ofLong(values[Term.intVar(row.ref(term))])
                }

                assertEquals(BigFraction.ofLong(values[result] - values[a] * values[b]), activity, "$roles $x $y")
            }
        }
    }
}
