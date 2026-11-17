package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.codec.factorCodecSamples
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.ir.TranscendentalExpression
import com.eignex.klause.ir.TranscendentalFormula
import com.eignex.klause.ir.TranscendentalFunction
import com.eignex.klause.ir.TranscendentalProblem
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import com.eignex.util.numbers.BigFraction
import org.junit.BeforeClass
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@RunWith(Parameterized::class)
class FiniteTranscendentalConformanceTest(
    private val name: String,
    private val factor: Factor,
    private val engine: FiniteEngine,
    private val mask: Int,
) {
    companion object {
        @JvmStatic
        @BeforeClass
        fun initializeRuntime() = FiniteTranscendentalWorkerTest.initializeRuntime()

        @JvmStatic
        @Parameterized.Parameters(name = "{0} {2} mask={3}")
        fun cases(): List<Array<Any>> = factorCodecSamples().filter { it.second.variables.reals.isEmpty() }
            .flatMapIndexed { index, (key, factor) ->
                FiniteEngine.entries.flatMap { engine ->
                    listOf(0, 1, 2, 63).map { arrayOf("$key-$index", factor, engine, it) }
                }
            }
    }

    @Test
    fun `function execution should preserve the native decision on a pinned finite point`() {
        val native = Problem(6, 6, Array(6) { IntDomain(-10, 10) }, arrayOf(factor))
        val finite = Problem(6, 6, Array(6) { IntDomain(-10, 10) }, arrayOf(factor), numRealVars = 4,
            realLower = DoubleArray(4) { if (it == 3) 1.0 else 0.0 },
            realUpper = DoubleArray(4) { if (it == 3) 1.0 else 0.0 })
        val functions = TranscendentalProblem(TranscendentalFormula.Atom(
            TranscendentalExpression.Function(TranscendentalFunction.EXP,
                TranscendentalExpression.Variable(SourceRealId(8))),
            PolynomialRelation.EQ, TranscendentalExpression.Variable(SourceRealId(9))),
            (0 until 6).associate { "i$it" to it }, (0 until 4).associate { "r$it" to (6 + it) },
            (0 until 6).associate { "b$it" to it }).withFactors(finite)
        val pins = Assumptions(IntArray(6) { it }, BooleanArray(6) { mask and (1 shl it) != 0 },
            IntArray(6) { it }, LongArray(6) { (mask and 1).toLong() })
        val expected = BacktrackSolver(native.bake()).solve(BacktrackParams(assumptions = pins))

        val execution = OpenTheoryPipeline.execute(functions.pipelineRequest(), TranscendentalTheoryParams(
            maximumWork = 100000, maximumDepth = 0,
            realLower = (0 until 4).associateWith { if (it == 3) BigFraction.ONE else BigFraction.ZERO },
            realUpper = (0 until 4).associateWith { if (it == 3) BigFraction.ONE else BigFraction.ZERO },
            strategy = PolynomialStrategyParams(assumptions = pins,
                localSearch = LocalSearchParams(maxFlips = 1, maxInstructions = 1000, randomSeed = 0),
                alnsIterations = 1)), engine)
        val result = execution.result
        val diagnostic = "$name $engine $mask work=${execution.workSpent} " +
            "reason=${(result as? OpenTheoryResult.Unknown)?.reason} declined=${execution.representationDeclined}"

        when (expected) {
            is SolveResult.Sat -> {
                val point = assertIs<OpenTheoryResult.Sat>(result, diagnostic).assignment
                for (id in 0 until 6) {
                    assertEquals(pins.intValues[id].toString(), point.intValue(id))
                    assertEquals(pins.boolValues[id], point.boolValue(id))
                }
            }
            is SolveResult.Unsat -> if (engine in setOf(FiniteEngine.LOCAL_SEARCH, FiniteEngine.ALNS)) {
                assertIs<OpenTheoryResult.Unknown>(result, "$name $engine $mask")
            } else assertIs<OpenTheoryResult.Unsat>(result, "$name $engine $mask")
            is SolveResult.Unknown -> error("native fixture was undecided: $name $mask")
        }
    }
}
