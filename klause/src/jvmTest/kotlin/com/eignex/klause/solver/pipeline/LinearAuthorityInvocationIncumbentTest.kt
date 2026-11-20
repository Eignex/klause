package com.eignex.klause.solver.pipeline

import com.eignex.klause.RuntimeInitializedTest
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceInequality
import com.eignex.klause.solver.evidence.SourceObjective
import com.eignex.klause.solver.evidence.SourcePremise
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BigFraction
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(Parameterized::class)
class LinearAuthorityInvocationIncumbentTest(private val completion: Boolean) : RuntimeInitializedTest() {
    private val real = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
    private val scope = SourceScope(SourceRevision("conditioned-linear", "exact-affine"),
        objective = SourceObjective("x", ObjectiveSense.MINIMIZE))

    @Test
    fun `private traversal should improve checked centers within its allowance`() {
        val columns = (0..3).map { SourceCoordinate(SourceCoordinate.Kind.Integer, it) }
        val rows = columns.flatMapIndexed { index, column ->
            listOf(
                "lower$index" to SourcePremise(SourceInequality(
                    mapOf(column to BigFraction.MINUS_ONE), BigFraction.ZERO)),
                "upper$index" to SourcePremise(SourceInequality(mapOf(column to BigFraction.ONE), BigFraction.ONE)),
            )
        }.toMap() + mapOf(
            "real-lower" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "real-upper" to SourcePremise(SourceInequality(mapOf(real to BigFraction.ONE), BigFraction.ZERO)),
        )
        val source = SourceProofContext(scope, columns.toSet() + real, rows,
            columns.associateWith { BigFraction.MINUS_ONE } + (real to BigFraction.ONE))
        val seed = Sample(booleanArrayOf(), longArrayOf(0, 0, 0, 0), doubleArrayOf(0.0),
            exactReals = listOf(BigFraction.ZERO))

        val result = LinearAuthorityInvocation(source,
            TheoryParams(sourceIncumbentCompletion = completion, sourceCuts = false, maxLeaves = 2,
                maxDecisions = 1), external = { seed }, boundOrdered = true,
            incumbentCompletionCheck = { _, _, _ -> true }, incumbentProximity = true)
            .use { it.run { _, _ -> } }

        assertEquals(BigFraction.ofLong(-4), result.value)
        assertEquals(1L, result.stats.smt.completeSearchStages?.get("numericalProximityImprovements"))
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "completion={0}")
        fun cases() = listOf(false, true).map { arrayOf(it) }
    }
}
