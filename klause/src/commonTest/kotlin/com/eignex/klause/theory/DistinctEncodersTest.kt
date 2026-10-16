package com.eignex.klause.theory

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.global.Distinct
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.Term
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.OpenTheoryExecution
import com.eignex.klause.solver.pipeline.OpenTheoryPipeline
import com.eignex.klause.solver.pipeline.OpenTheoryRequest
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import com.eignex.klause.solver.pipeline.TheoryCompletion
import com.eignex.klause.solver.pipeline.TheoryParams
import com.eignex.klause.solver.pipeline.prepareExecutionPlan
import com.eignex.klause.solver.pipeline.preparePolynomialExecutionPlan
import com.eignex.klause.solver.validation.verifyRealCoordinates
import com.eignex.klause.theory.qfnra.PolynomialCondition
import com.eignex.klause.theory.qfnra.QfNraResult
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DistinctEncodersTest {
    @Test
    fun `selected exact distinct linear encoding should preserve whole Boolean reification`() {
        for (truth in listOf(false, true)) {
            val distinct = Distinct((0..1).map {
                Distinct.Operand(intArrayOf(Term.ofRealVar(it)), listOf(BigFraction.ONE))
            }, activator = 0)
            val source = Problem(1, 0, emptyArray(),
                arrayOf<Factor>(distinct, Clause(intArrayOf(Lit.make(0, truth)))),
                numRealVars = 2, realLower = doubleArrayOf(1.0, 1.0), realUpper = doubleArrayOf(1.0, 1.0))
            val providers = FactorProjectionProviders(encoder = EncoderProvider { factor, target ->
                if (factor === distinct && target == EncodingTarget.LINEAR) DistinctEncoders.linear
                else BuiltInEncoderProvider.select(factor, target)
            })
            val plan = source.prepareExecutionPlan(providers)
            val request = OpenTheoryRequest(source, componentPlan = assertNotNull(plan.componentPlan),
                preparedPlan = plan).withPresolve(PresolveConfig.NONE)

            val execution = OpenTheoryPipeline.execute(request, TheoryParams(), FiniteEngine.BACKTRACK)
            val result = assertIs<OpenTheoryExecution.Satisfy>(execution).result

            if (truth) assertIs<OpenTheoryResult.Unsat>(result) else {
                val witness = assertIs<OpenTheoryResult.Sat>(result).assignment
                val exact = (0..1).map { TheoryCompletion.parseRational(witness.realValue(it)) }
                val point = Sample(booleanArrayOf(witness.boolValue(0)), longArrayOf(),
                    DoubleArray(2) { exact[it].toDouble() }, exactReals = exact)
                assertIs<Verification.Accepted<*, *>>(verifyRealCoordinates(source, point))
            }
        }
    }

    @Test
    fun `selected exact distinct rows should preserve integer and real namespaces`() {
        for ((real, expectedSat) in listOf(1.0 to false, 2.0 to true)) {
            val distinct = Distinct(listOf(
                Distinct.Operand(intArrayOf(Term.ofIntVar(0)), listOf(BigFraction.ONE)),
                Distinct.Operand(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.ONE)),
            ))
            val source = Problem(0, 1, arrayOf(IntDomain(1, 1)), arrayOf(distinct), numRealVars = 1,
                realLower = doubleArrayOf(real), realUpper = doubleArrayOf(real))
            val providers = FactorProjectionProviders(encoder = EncoderProvider { factor, target ->
                if (factor === distinct && target == EncodingTarget.LINEAR) DistinctEncoders.linear
                else BuiltInEncoderProvider.select(factor, target)
            })
            val installed = assertNotNull(providers.prepare(source).linearTheoryModel(EncodingTarget.LINEAR))

            val result = BacktrackSolver.source(installed).solve()

            if (expectedSat) assertIs<OpenTheoryResult.Sat>(result) else assertIs<OpenTheoryResult.Unsat>(result)
        }
    }

    @Test
    fun `selected polynomial distinct encoding should refute a pinned affine collision`() {
        val distinct = Distinct((0..1).map {
            Distinct.Operand(intArrayOf(Term.ofRealVar(it)), listOf(BigFraction.ONE))
        })
        val source = Problem(0, 0, emptyArray(), arrayOf(distinct), numRealVars = 2,
            realLower = doubleArrayOf(1.0, 1.0), realUpper = doubleArrayOf(1.0, 1.0))
        val providers = FactorProjectionProviders(encoder = EncoderProvider { factor, target ->
            if (factor === distinct && target == EncodingTarget.POLYNOMIAL) DistinctEncoders.polynomial
            else BuiltInEncoderProvider.select(factor, target)
        })
        val shared = assertNotNull(source.preparePolynomialExecutionPlan(providers = providers).polynomialModel)

        val result = shared.service.invocation().use { it.check(PolynomialCondition()) }

        assertIs<QfNraResult.Unsatisfiable>(result)
        assertEquals(0, shared.encodingAttributions.single().sourceFactorIndex)
    }

    @Test
    fun `cancelled distinct installation should publish no partial enforcing relation`() {
        val distinct = Distinct((0..2).map {
            Distinct.Operand(intArrayOf(Term.ofRealVar(it)), listOf(BigFraction.ONE))
        })
        val source = Problem(0, 0, emptyArray(), arrayOf(distinct), numRealVars = 3,
            realLower = DoubleArray(3) { Double.NEGATIVE_INFINITY },
            realUpper = DoubleArray(3) { Double.POSITIVE_INFINITY })
        val providers = FactorProjectionProviders(encoder = EncoderProvider { factor, target ->
            if (factor === distinct && target == EncodingTarget.LINEAR) DistinctEncoders.linear
            else BuiltInEncoderProvider.select(factor, target)
        })
        var work = 0L
        val cancellation = object : Cancellation {
            override fun isCancelled(): Boolean = work > 0L
            override fun workMeter(): WorkMeter = WorkMeter { work += it }
        }
        val declines = mutableListOf<EncoderDecline>()

        val installed = providers.prepare(source).installLinearFactor(0, EncodingTarget.LINEAR,
            cancellation = cancellation, onDecline = declines::add)

        assertNull(installed)
        assertEquals(listOf(EncoderDecline.RESOURCE), declines)
    }
}
