package com.eignex.klause.solver.pipeline

import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.theory.BuiltInEncoderProvider
import com.eignex.klause.theory.Encoder
import com.eignex.klause.theory.EncoderProvider
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class ProblemPipelineFiniteTest(private val engine: FiniteEngine, private val text: String) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}: {1}")
        fun sources(): List<Array<Any>> = FiniteEngine.entries.flatMap { engine ->
            listOf(
                "(declare-const x Int) (declare-const y Int) (assert (<= 0 x 2)) " +
                    "(assert (<= 0 y 2)) (assert (distinct x y))",
                "(declare-const b Bool) (declare-const c Bool) (assert (distinct b (not c)))",
            ).map { arrayOf<Any>(engine, it) }
        }
    }

    @Test
    fun `a native finite source should not install unused arithmetic relations`() {
        val source = SmtLib.parse(text)
        var installations = 0
        val providers = FactorProjectionProviders(encoder = EncoderProvider { factor, target ->
            val selected = BuiltInEncoderProvider.select(factor, target)
            if (!selected.enforcesSource) selected else Encoder(selected.support,
                selected.requiredIntegers, selected.requiredBooleans) { view, encoding ->
                installations++
                selected.install(view, encoding)
            }
        })

        val invocation = SourceSetup.prepare(SourceSetupRequest(source.source, ExecutionOperation.SATISFY, engine,
            providers = providers, boundCancellation = Cancellation { true }))

        assertIs<SourceProblemRoute.Finite>(invocation.route)
        assertEquals(0, installations)
        assertEquals(engine, invocation.request.engine)
    }
}
