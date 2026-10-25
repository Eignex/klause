package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.Problem
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.ResumableSearch
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.EngineInvocation
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LinearCompanionSearchTest {
    @Test
    fun `companion witnesses should be checked and published without finite proof authority`() {
        val model = Problem(0, 0, emptyArray(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0))
        val objective = LinearObjective(realCoefficients = doubleArrayOf(1.0))
        val request = OpenTheoryRequest(model, objective)
        val context = assertNotNull(request.linearAuthorityContext(Cancellation.Never))
        val binding = EngineInvocation(request.preparedPlan, ModelIdentity.of(model, objective), objective,
            scope = context.scope)
        for (valid in listOf(false, true)) {
            val value = if (valid) BigFraction.ONE else BigFraction.ofLong(2)
            val sample = Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(value.toDouble()), listOf(value))
            val native = object : ResumableSearch {
                override val isDone: Boolean get() = true
                override val stats: SolveStats get() = SolveStats.EMPTY
                override fun runSlice(global: Cancellation, sliceMillis: Long, sliceNodes: Long,
                    onIncumbent: (MinimizeResult.WithSample) -> Unit): MinimizeResult {
                    val result = MinimizeResult.Optimal(sample, value.toDouble())
                    onIncumbent(result)
                    return result
                }
            }
            val accepted = mutableListOf<MinimizeResult.WithSample>()

            val result = LinearCompanionSearch(native, context, binding, objective).use {
                it.runSlice(Cancellation.Never, Long.MAX_VALUE, -1, accepted::add)
            }

            assertEquals(1L, result?.stats?.smt?.completeSearchStages?.get("linearCompanionNativeOptimal"))
            if (valid) {
                val point = assertIs<MinimizeResult.BestFound>(result)
                assertEquals(1, accepted.size)
                assertTrue(binding.matches(assertNotNull(point.sample.witnessCertificate?.publication).invocation))
            } else {
                assertIs<MinimizeResult.Unknown>(result)
                assertTrue(accepted.isEmpty())
            }
        }
    }
}
