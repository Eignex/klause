package com.eignex.klause.theory

import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.factor.arithmetic.ContinuousProduct
import com.eignex.klause.factor.arithmetic.RealProduct
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntegralConstants
import com.eignex.klause.ir.IntDomain
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
}
