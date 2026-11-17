package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.theory.BuiltInEncoderProvider
import com.eignex.klause.theory.Encoder
import com.eignex.klause.theory.EncoderProvider
import com.eignex.klause.theory.EncoderSupport
import com.eignex.klause.theory.EncodingTarget
import com.eignex.util.numbers.compareTo
import com.eignex.util.numbers.parseBigInt
import org.junit.BeforeClass
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

@RunWith(Parameterized::class)
class PreparedEncoderTargetsTest(language: String, private val engine: FiniteEngine) {
    private val target = EncodingTarget(language)
    companion object {
        @JvmStatic
        @BeforeClass
        fun initializeRuntime() = PreparedCustomMechanismsTest.initializeRuntime()

        @JvmStatic
        @Parameterized.Parameters(name = "{0} {1}")
        fun cases(): List<Array<Any>> = listOf(EncodingTarget.LINEAR, EncodingTarget.DIFFERENCE,
            EncodingTarget.POLYNOMIAL).flatMap { target -> FiniteEngine.entries.map { arrayOf(target.id, it) } }
    }

    @Test
    fun `an enforcing owner should retain its captured encoding language through execution`() {
        val row = Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.LE, 0)
        val relation = object : Factor by row {
            override val linearForm: LinearForm? = null
        }
        val source = Problem(0, 2, Array(2) { IntDomain(0, 0) }, arrayOf(relation),
            openIntLo = BooleanArray(2) { true }, openIntHi = BooleanArray(2) { true })
        val providers = FactorProjectionProviders(encoder = EncoderProvider { factor, selected ->
            if (factor !== relation) BuiltInEncoderProvider.select(factor, selected)
            else if (selected != target) Encoder(EncoderSupport.UNSUPPORTED) else {
                val builtin = BuiltInEncoderProvider.select(row, selected)
                Encoder(builtin.support) { _, encoding -> builtin.install(row, encoding) }
            }
        })
        val invocation = SourceSetup.prepare(SourceSetupRequest(SourceAttachment(source),
            ExecutionOperation.SATISFY, engine, providers = providers))
        assertTrue(requireNotNull(invocation.admission).runnable)

        val execution = SourceSetup.execute(SourceExecutionRequest(invocation, ExecutionOptions(
            ExecutionOption.maxFlips(16L), ExecutionOption.maxInstructions(100000L), ExecutionOption.alnsIterations(4))))

        val result = when (execution) {
            is SourceExecutionResult.Linear -> assertIs<OpenTheoryExecution.Satisfy>(execution.result).result
            is SourceExecutionResult.Polynomial -> execution.result.result
            else -> error("$target $engine: $execution")
        }
        val point = assertIs<OpenTheoryResult.Sat>(result, "$target $engine").assignment
        assertTrue(parseBigInt(point.intValue(0)) <= parseBigInt(point.intValue(1)))
    }
}
